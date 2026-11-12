package com.cockpitf30.vehicle.source

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.cockpitf30.system.AppSettings
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
import kotlinx.coroutines.delay
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
 * Las tramas de la caja CAN llegan en el aviso "callback.pt.canbus_cmd" y se decodifican con
 * [CanBoxFrames] (puertas, freno de mano, combustible, velocidad, rpm, autonomía). La velocidad y
 * las rpm solo las manda si se le preguntan: igual que la app de la radio, cada 100 ms; ella lo hace cada 500 ms (ver [CanBoxFrames.query]).
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
            CanBoxData.onFrame = { wake.trySend(Unit) }
            runCatching { car.subscribe(context) { wake.trySend(Unit) } }
                .onSuccess { RadioCarData.note("Avisos: $it") }
                .onFailure { RadioCarData.note("Avisos: ${(it.cause ?: it).message}") }
            launch { askSpeed(car) }
            val settings = AppSettings(context)
            var errors = 0
            var values: Map<String, Any?>? = null
            var lastRead = 0L
            var lastPublish = 0L
            while (isActive) {
                // Las tramas de la caja llegan cada 100 ms: con ellas basta mezclar lo último leído del
                // almacén; releerlo entero (reflexión sobre cientos de claves) solo cada POLL_MS.
                val now = System.currentTimeMillis()
                if (values == null || now - lastRead >= POLL_MS) values = runCatching { car.readAll() }.getOrElse { e ->
                    if (errors++ < 3) RadioCarData.note("Lectura: ${(e.cause ?: e).javaClass.simpleName}: ${(e.cause ?: e).message}")
                    VehicleRepository.updateStatus { it.copy(lastError = "Lectura: ${(e.cause ?: e).message}") }
                    null
                }.also { lastRead = now }
                if (values != null) {
                    // Las claves "can.*" van al final: mandan sobre las del almacén si coinciden.
                    val can = CanBoxData.values()
                    val all = values + RadioCarData.broadcastValues() + can
                    // La lista de Diagnóstico no necesita 10 refrescos por segundo; sin Diagnóstico
                    // delante solo hace falta para los faros (modo Night, mirado cada 5 s).
                    val publishEvery = if (com.cockpitf30.system.DiagnosticsGate.active) POLL_MS else IDLE_PUBLISH_MS
                    if (now - lastPublish >= publishEvery) { RadioCarData.publish(all); lastPublish = now }
                    val tank = settings.tankLiters
                    VehicleRepository.updateState { st ->
                        val mapped = CarKeyMapper.apply(st, all)
                        val liters = CarKeyMapper.toNumber(can["can.fuel_liters"])
                        if (liters != null && liters > 0 && tank > 0) {
                            // La caja del F30 no manda la autonomía (trama 0x27): se calcula con los
                            // litros y el consumo medio del último viaje, o 5,5 l/100 km de un 320d.
                            val trips = com.cockpitf30.vehicle.trip.TripComputer.state.value
                            val cons = (trips.current?.avgConsumption(tank) ?: trips.last?.avgConsumption(tank))
                                ?.takeIf { it in 3f..15f } ?: DEFAULT_L_100KM
                            mapped.copy(
                                fuelLevel = (liters / tank).toFloat().coerceIn(0f, 1f),
                                rangeKm = mapped.rangeKm.takeIf { can["can.fuel_range"] != null } ?: (liters * 100 / cons).toInt(),
                            )
                        } else mapped
                    }
                    VehicleRepository.updateStatus { it.copy(framesReceived = it.framesReceived + 1) }
                }
                // Un aviso del servicio o un broadcast adelantan la siguiente lectura.
                withTimeoutOrNull(POLL_MS) { wake.receive() }
            }
        }
    }

    /**
     * Pide a la caja la trama 0x16 (velocidad y rpm) cada [QUERY_MS]. La app de la radio lo hace
     * cada 500 ms y se nota el retraso; a 100 ms la aguja va al ritmo del motor. Es una trama de
     * solo 6 bytes.
     */
    private suspend fun askSpeed(car: VendorCar) {
        val settings = AppSettings(context)
        val frame = CanBoxFrames.query(CanBoxFrames.TYPE_DYNAMIC)
        var reported = false
        while (true) {
            delay(QUERY_MS)
            if (!settings.canAskSpeed || !CanBoxData.baseSeen) continue
            val result = runCatching { car.sendCanbus(frame) }
            if (!reported) {
                reported = true
                RadioCarData.note("Pido velocidad/rpm a la caja (${frame.joinToString(" ") { "%02X".format(it) }}): " +
                    result.fold({ "enviado" }, { "error ${(it.cause ?: it).message}" }))
            }
        }
    }

    override fun stop() {
        CanBoxData.onFrame = null
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
        private const val IDLE_PUBLISH_MS = 2000L
        private const val QUERY_MS = 100L
        private const val DEFAULT_L_100KM = 5.5f
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
        // El historial de cambios solo se mira en Diagnóstico.
        if (before.isNotEmpty() && com.cockpitf30.system.DiagnosticsGate.active) {
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

    fun readAll(): Map<String, Any?> = read(PREFIXES)

    /** Claves que empiezan por cualquiera de [prefixes] (p. ej. "data.radio.") con su valor. */
    fun read(prefixes: List<String>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (p in prefixes) {
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
     * Se apunta a los avisos del servicio igual que el lanzador de serie (visto en su código):
     * ICarService.registerCallback(Bundle{package_name, name, key_filter, version=2}, ICarCallback).
     * Los cambios llegan como "callback.dm.data_changed" con {key, bundle}.
     */
    fun subscribe(context: Context, onChange: () -> Unit): String {
        val iCb = runCatching { CarDataProbe.sniffingCarCallback(loader, CanBoxData::handle) { onChange() } }
            .getOrElse { return "no se pudo crear el callback: ${(it.cause ?: it).javaClass.simpleName}: ${(it.cause ?: it).message}" }
            ?: return "el APK no trae ICarCallback"
        val cbType = loader.loadClass("android.car.server.ICarCallback")
        val bundle = Bundle().apply {
            putString("package_name", context.packageName)
            putString("name", "cockpit")
            putStringArray("key_filter", SUBSCRIBE.toTypedArray())
            putInt("version", 2)
        }
        val m = base.javaClass.getDeclaredMethod("registerICarCallback", Bundle::class.java, cbType).apply { isAccessible = true }
        val ok = runCatching { m.invoke(base, bundle, iCb) }.getOrElse { "error ${(it.cause ?: it).message}" }
        if (ok == true) callback = iCb
        return "registerCallback(package_name, key_filter ${SUBSCRIBE.size} claves, version 2) = $ok"
    }

    /** Manda una trama a la caja CAN igual que CarCanbus: requestOneway("request.pt.canbus_cmd", {arg_byte_array}). */
    fun sendCanbus(frame: ByteArray) {
        val m = sendMethod ?: generateSequence<Class<*>>(base.javaClass) { it.superclass }
            .firstNotNullOf { c -> runCatching { c.getDeclaredMethod("requestOneway", String::class.java, Bundle::class.java) }.getOrNull() }
            .apply { isAccessible = true }.also { sendMethod = it }
        m.invoke(base, "request.pt.canbus_cmd", Bundle().apply { putByteArray("arg_byte_array", frame) })
    }

    private var sendMethod: Method? = null
    private var syncMethod: Method? = null

    /** Petición al servicio del coche con respuesta, igual que las librerías del fabricante. */
    fun requestSync(key: String, args: Bundle?): Int {
        val m = syncMethod ?: generateSequence<Class<*>>(base.javaClass) { it.superclass }
            .firstNotNullOf { c -> runCatching { c.getDeclaredMethod("requestSync", String::class.java, Bundle::class.java) }.getOrNull() }
            .apply { isAccessible = true }.also { syncMethod = it }
        return (m.invoke(base, key, args) as? Int) ?: 0
    }

    /** Petición sin respuesta (requestOneway). */
    fun requestOneway(key: String, args: Bundle?) {
        val m = onewayMethod ?: generateSequence<Class<*>>(base.javaClass) { it.superclass }
            .firstNotNullOf { c -> runCatching { c.getDeclaredMethod("requestOneway", String::class.java, Bundle::class.java) }.getOrNull() }
            .apply { isAccessible = true }.also { onewayMethod = it }
        m.invoke(base, key, args)
    }

    private var onewayMethod: Method? = null

    companion object {
        val CLIENT_PACKAGES = listOf("com.bmw1.launcher2", "android.car.app.canbus")
        private const val BASE_MANAGER = "android.car.base.CarBaseManager2"
        private val PREFIXES = listOf("canbus.", "state.", "info.", "data.car_original.", "data.wy.", "data.cusp.", "data.zhtd.", "callback.")
        // No sabemos si el filtro admite prefijos: van las claves conocidas y también los prefijos.
        private val SUBSCRIBE = listOf(
            "canbus.car_speed", "canbus.rotate_speed", "canbus.steering_value", "canbus.info.door", "canbus.state.door",
            "state.wy.car_speed", "state.wy.car_oilmass", "state.wy.car_outtemp", "state.wy.car_safetybelt", "state.wy.car_handbrake",
            "state.main.brake", "state.main.headlight_state", "state.main.acc",
            "canbus.", "canbus.info.", "canbus.state.", "state.", "info.", "data.car_original.", "data.wy.", "callback.", "",
        )

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
