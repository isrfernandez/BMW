package com.cockpitf30.vehicle.source

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.cockpitf30.vehicle.VehicleRepository
import com.cockpitf30.vehicle.probe.BroadcastSniffer
import com.cockpitf30.vehicle.probe.CarDataProbe
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Datos del coche tal como los guarda la propia radio.
 *
 * La caja CAN habla con el MCU, el MCU con la app CarCanbus, y esta deja los valores en el
 * almacén del servicio del coche (android.car.server). Lo leemos con la librería cliente del
 * fabricante (CarBaseManager2 / CarDataManager), que va dentro del lanzador BMW de serie y que
 * en el informe de la 0.9.19 por fin devolvió datos (freno de mano, ACC, volante…).
 *
 * Además: se suscribe a los avisos del servicio y escucha los broadcasts CANBUS_SPEED,
 * CANBUS_RPM y OUT_TEMP. Todo lo que cambie se apunta en [RadioCarData] para verlo en
 * Diagnóstico y descubrir así qué clave es cada puerta o rueda.
 */
class RadioCarSource(private val context: Context) : VehicleDataSource {
    override val name = "Radio (CAN)"

    private var receiver: BroadcastReceiver? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    override fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val car = VendorCar.connect(context).getOrElse { e ->
                VehicleRepository.updateStatus { it.copy(connected = false, lastError = "CarDataManager: ${e.message ?: e.javaClass.simpleName}") }
                return@launch
            }
            VehicleRepository.updateStatus { it.copy(connected = true, lastError = null) }
            RadioCarData.note("Conectado (${car.origin})")
            listenBroadcasts()
            runCatching { car.subscribe(context) { wake.trySend(Unit) } }
                .onSuccess { RadioCarData.note("Avisos: $it") }
                .onFailure { RadioCarData.note("Avisos: ${(it.cause ?: it).message}") }
            var errors = 0
            while (isActive) {
                val values = runCatching { car.readAll() }.getOrElse { e ->
                    if (errors++ < 3) RadioCarData.note("Lectura: ${(e.cause ?: e).javaClass.simpleName}: ${(e.cause ?: e).message}")
                    VehicleRepository.updateStatus { it.copy(lastError = "Lectura: ${(e.cause ?: e).message}") }
                    null
                }
                if (values != null) {
                    val all = values + RadioCarData.broadcastValues()
                    RadioCarData.publish(all)
                    VehicleRepository.updateState { CarKeyMapper.apply(it, all) }
                    VehicleRepository.updateStatus { it.copy(framesReceived = it.framesReceived + 1) }
                }
                // Un aviso del servicio o un broadcast adelantan la siguiente lectura.
                withTimeoutOrNull(POLL_MS) { wake.receive() }
            }
        }
    }

    override fun stop() {
        receiver?.let { r -> runCatching { context.unregisterReceiver(r) } }
        receiver = null
    }

    private fun listenBroadcasts() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val extras = intent.extras ?: return
                extras.keySet().forEach { k -> RadioCarData.putBroadcast("bc.$k", @Suppress("DEPRECATION") extras.get(k)) }
                wake.trySend(Unit)
            }
        }
        val filter = IntentFilter().apply { BROADCASTS.forEach(::addAction) }
        ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_EXPORTED)
        receiver = r
    }

    companion object {
        private const val POLL_MS = 300L
        private val BROADCASTS = listOf(
            "android.car.action.CANBUS_SPEED", "android.car.action.CANBUS_RPM", "android.car.action.OUT_TEMP",
            "android.car.action.OBD_STATE", "android.car.action.BRAKE_STATE",
        )

        /** ¿Está la librería del fabricante en esta radio? (rápido: solo mira si el paquete existe) */
        fun isAvailable(context: Context): Boolean = VendorCar.CLIENT_PACKAGES.any { pkg ->
            runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess
        }
    }
}

/** Lo último leído de la radio y el historial de cambios, para Diagnóstico. */
object RadioCarData {
    private const val LOG_SIZE = 200
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private val _values = MutableStateFlow<Map<String, String>>(emptyMap())
    val values: StateFlow<Map<String, String>> = _values.asStateFlow()

    private val _changes = MutableStateFlow<List<String>>(emptyList())
    val changes: StateFlow<List<String>> = _changes.asStateFlow()

    private val broadcasts = java.util.concurrent.ConcurrentHashMap<String, Any>()

    fun putBroadcast(key: String, value: Any?) { if (value != null) broadcasts[key] = value }
    fun broadcastValues(): Map<String, Any?> = HashMap(broadcasts)

    fun note(line: String) = _changes.update { (listOf("${LocalTime.now().format(time)} $line") + it).take(LOG_SIZE) }

    fun publish(all: Map<String, Any?>) {
        val shown = all.mapValues { describe(it.value) }
        val before = _values.value
        if (before.isNotEmpty()) {
            val now = LocalTime.now().format(time)
            val diff = buildList {
                shown.forEach { (k, v) -> val old = before[k]; if (old != v) add("$now $k: ${old ?: "∅"} → $v") }
                before.keys.filter { it !in shown }.forEach { add("$now $it: ${before[it]} → ∅") }
            }
            // El reloj de la CPU cambia cada segundo: no es un dato del coche.
            val useful = diff.filterNot { "cpu_temperature" in it || "total_run_time" in it }
            if (useful.isNotEmpty()) _changes.update { (useful + it).take(LOG_SIZE) }
        }
        _values.value = shown
    }

    fun describe(v: Any?): String = when (v) {
        null -> "null"
        is IntArray -> v.joinToString(",", "[", "]")
        is ByteArray -> v.joinToString(" ", "[", "]") { "%02X".format(it) }
        is FloatArray -> v.joinToString(",", "[", "]")
        is LongArray -> v.joinToString(",", "[", "]")
        is Array<*> -> v.joinToString(",", "[", "]")
        is Bundle -> BroadcastSniffer.describe(v)
        else -> v.toString().take(200)
    }
}

/**
 * Acceso por reflexión a CarBaseManager2 y CarDataManager del fabricante, cargados del APK del
 * lanzador de serie. Es lo mismo que hace el lanzador BMW original para enseñar la velocidad.
 */
class VendorCar private constructor(
    private val loader: ClassLoader,
    private val base: Any,
    private val data: Any,
    private val byPrefix: Method,
    val origin: String,
) {
    private var itemKey: Field? = null
    private var itemValue: Field? = null

    // Referencias fuertes al callback: si el recolector se lo lleva, el servicio deja de avisar.
    private var callback: Any? = null

    fun readAll(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (p in PREFIXES) {
            val items = byPrefix.invoke(data, p) as Array<*>? ?: continue
            for (item in items) {
                item ?: continue
                val (kf, vf) = fieldsOf(item)
                val key = kf.get(item) as? String ?: continue
                out[key] = vf.get(item)
            }
        }
        return out
    }

    private fun fieldsOf(item: Any): Pair<Field, Field> {
        val k = itemKey; val v = itemValue
        if (k != null && v != null) return k to v
        val fields = generateSequence<Class<*>>(item.javaClass) { it.superclass }.flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) }.onEach { it.isAccessible = true }.toList()
        val key = fields.first { it.name.equals("key", true) || it.name == "mKey" }
        val value = fields.first { it.name.equals("value", true) || it.name == "mValue" }
        itemKey = key; itemValue = value
        return key to value
    }

    /**
     * Se apunta a los avisos del servicio para las claves del coche. Prueba primero
     * registerCallback(String, String[], CarBaseCallback) y luego registerICarCallback.
     */
    fun subscribe(context: Context, onChange: () -> Unit): String {
        val keys = SUBSCRIBE.toTypedArray()
        val me = context.packageName
        val results = mutableListOf<String>()
        base.javaClass.methods.filter { it.name == "registerCallback" && it.parameterTypes.size == 3 && it.parameterTypes[2].isInterface }
            .forEach { m ->
                val cbType = m.parameterTypes[2]
                val cb = Proxy.newProxyInstance(loader, arrayOf(cbType)) { _, method, args ->
                    when (method.name) {
                        "hashCode" -> System.identityHashCode(this)
                        "equals" -> false
                        "toString" -> "CockpitCarBaseCallback"
                        else -> {
                            CarDataProbe.logCallback("${method.name}(${args.orEmpty().joinToString { RadioCarData.describe(it) }})")
                            onChange()
                            defaultFor(method.returnType)
                        }
                    }
                }
                val ok = runCatching { m.invoke(base, me, keys, cb) }.getOrElse { "error ${(it.cause ?: it).message}" }
                results += "registerCallback(CarBaseCallback) = $ok"
                if (ok == true) { callback = cb; return results.joinToString("; ") }
            }
        val iCb = CarDataProbe.sniffingCarCallback(loader) { onChange() }
        if (iCb != null) {
            val m = base.javaClass.methods.firstOrNull { it.name == "registerICarCallback" && it.parameterTypes.size == 3 }
            if (m != null) {
                val ok = runCatching { m.invoke(base, me, keys, iCb) }.getOrElse { "error ${(it.cause ?: it).message}" }
                results += "registerICarCallback(pkg, claves) = $ok"
                if (ok == true) callback = iCb
            }
        }
        return results.joinToString("; ").ifEmpty { "sin métodos de registro" }
    }

    companion object {
        val CLIENT_PACKAGES = listOf("com.bmw1.launcher2", "android.car.app.canbus")
        private const val BASE_MANAGER = "android.car.base.CarBaseManager2"
        private val PREFIXES = listOf("canbus.", "state.", "info.", "data.car_original.", "data.wy.", "data.cusp.", "data.zhtd.", "callback.")
        private val SUBSCRIBE = listOf("canbus.", "state.", "info.", "data.car_original.", "data.wy.", "callback.")

        @Volatile private var cached: VendorCar? = null

        fun connect(context: Context): Result<VendorCar> = runCatching {
            cached?.let { return@runCatching it }
            var lastError: Throwable? = null
            for (pkg in CLIENT_PACKAGES) {
                val apk = runCatching { context.packageManager.getApplicationInfo(pkg, 0).sourceDir }.getOrNull() ?: continue
                try {
                    val loader = dalvik.system.PathClassLoader(apk, VendorCar::class.java.classLoader?.parent)
                    val baseCls = loader.loadClass(BASE_MANAGER)
                    val base = baseCls.getConstructor(Context::class.java).newInstance(context.applicationContext)
                    val data = baseCls.getMethod("getDataManager").invoke(base) ?: error("getDataManager() devolvió null")
                    val byPrefix = data.javaClass.getDeclaredMethod("getValueByPrefix", String::class.java).apply { isAccessible = true }
                    return@runCatching VendorCar(loader, base, data, byPrefix, pkg).also { cached = it }
                } catch (e: Throwable) {
                    lastError = (e as? java.lang.reflect.InvocationTargetException)?.cause ?: e
                }
            }
            throw lastError ?: IllegalStateException("No está la librería del fabricante")
        }

        private fun defaultFor(type: Class<*>): Any? = when (type) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            else -> null
        }
    }
}
