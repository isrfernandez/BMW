package com.cockpitf30.vehicle.probe

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Segunda fase sobre el servicio del coche de la radio:
 * - [explore]: usa la librería cliente del fabricante (android.car.base.CarDataManager, que va
 *   dentro del lanzador BMW de serie y de CarCanbus) para listar sus claves y leer datos.
 * - [startCallbacks]: se registra en ICarService.registerCallback con un callback propio que
 *   apunta todo lo que el servicio nos mande (puertas, velocidad…).
 */
object CarDataProbe {
    private const val CAR_SERVICE = "android.car.server.ICarService"
    private const val CAR_CALLBACK = "android.car.server.ICarCallback"
    private const val DATA_MANAGER = "android.car.base.CarDataManager"
    private val CLIENT_PACKAGES = listOf("com.bmw1.launcher2", "android.car.app.canbus", "android.car.server")

    private val INTERESTING = Regex(
        "door|speed|rpm|temp|fuel|oil|mile|odo|gear|brake|acc|belt|trunk|hood|radar|light|steer|range|consum|trip|tyre|tire|pressure|tpms|rdc",
        RegexOption.IGNORE_CASE,
    )
    private val KNOWN_KEYS = listOf(
        "canbus.car_speed", "canbus.rotate_speed", "canbus_speed", "canbus_rpm", "state.main.brake", "out_temp",
        "canbus.info.door", "canbus.state.door", "canbus.info.speed", "canbus.state.speed",
    )

    private val _callbackLog = MutableStateFlow<List<String>>(emptyList())
    val callbackLog: StateFlow<List<String>> = _callbackLog.asStateFlow()

    // Referencias fuertes: si el recolector se lleva el Binder, el servicio deja de avisarnos.
    private var callbackBinder: Binder? = null
    private var callbackProxy: Any? = null

    private fun logCallback(line: String) = _callbackLog.update { (listOf(line) + it).take(150) }

    private fun binder(name: String): IBinder? = runCatching {
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, name) as IBinder?
    }.getOrNull()

    private fun loaderFor(context: Context, descriptor: String): Pair<String, ClassLoader>? {
        val type = "L${descriptor.replace('.', '/')};"
        val pkg = CLIENT_PACKAGES.firstOrNull { type in CarServiceProbe.dexStrings(context, it) } ?: return null
        val apk = context.packageManager.getApplicationInfo(pkg, 0).sourceDir
        return pkg to dalvik.system.PathClassLoader(apk, CarDataProbe::class.java.classLoader?.parent)
    }

    // ---------------------------------------------------------------- CarDataManager

    fun explore(context: Context): List<String> = buildList {
        val (pkg, loader) = loaderFor(context, DATA_MANAGER) ?: run { add("CarDataManager no está en ${CLIENT_PACKAGES.joinToString()}"); return@buildList }
        val cls = runCatching { loader.loadClass(DATA_MANAGER) }.getOrElse { add("No se pudo cargar $DATA_MANAGER de $pkg: $it"); return@buildList }
        add("$DATA_MANAGER (de $pkg)")
        cls.declaredConstructors.forEach { add("  constructor(${it.parameterTypes.joinToString { p -> p.simpleName }})") }
        cls.declaredMethods.sortedBy { it.name }.forEach { m ->
            add("  ${if (Modifier.isStatic(m.modifiers)) "static " else ""}${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
        }
        addAll(staticFields(cls, "  "))

        val dataType = runCatching { loader.loadClass("$DATA_MANAGER\$DataType") }.getOrNull()
        val types = dataType?.enumConstants?.toList().orEmpty()
        add("DataType: ${types.joinToString()}")
        types.forEach { t -> addAll(instanceFields(t!!, "    $t.")) }

        // Clases de constantes (CanbusDataDefine, CanbusDefine, CanbusProp…): las claves de verdad.
        val keys = KNOWN_KEYS.toMutableSet()
        val constantClasses = CarServiceProbe.dexStrings(context, pkg)
            .filter { it.startsWith("Landroid/car/") && it.endsWith(";") && Regex("Define|Prop|Keys|Const|DataManager").containsMatchIn(it) && '$' !in it }
            .map { it.substring(1, it.length - 1).replace('/', '.') }.distinct().take(40)
        constantClasses.forEach { name ->
            val c = runCatching { loader.loadClass(name) }.getOrNull() ?: return@forEach
            val lines = staticFields(c, "  ")
            if (lines.isEmpty()) return@forEach
            add("[$name]")
            lines.filter { INTERESTING.containsMatchIn(it) || it.contains("content://") || it.contains("PREFIX") }.take(150).forEach(::add)
            staticStrings(c).filter { v -> v.length in 3..80 && ' ' !in v && INTERESTING.containsMatchIn(v) }.forEach { keys += it }
        }

        add("-- Lecturas con CarDataManager (${keys.size} claves)")
        // El único constructor pide un ICarManagerInterface: primero buscamos la clase del fabricante
        // que lo implementa; si no, montamos un puente propio hacia ICarService.
        val bridge = managerInterface(context, loader, pkg, this)
        val instance = bridge?.let { b ->
            runCatching { cls.declaredConstructors.first { it.parameterTypes.size == 1 }.apply { isAccessible = true }.newInstance(b) }
                .onFailure { add("CarDataManager(ICarManagerInterface): ${(it.cause ?: it).javaClass.simpleName}: ${(it.cause ?: it).message}") }
                .getOrNull()
        } ?: instanceOf(cls, context)
        if (instance == null) add("No se pudo obtener una instancia")
        else {
            addAll(dumpPrefixes(instance, cls))
            addAll(readKeys(instance, cls, types, keys.toList().take(80), context))
        }
        if (bridgeCalls.isNotEmpty()) {
            add("-- Llamadas del CarDataManager al puente (protocolo)")
            addAll(bridgeCalls.take(60))
        }

        add("-- content://car_data por tipo")
        types.forEach { t ->
            listOf(t.toString(), t.toString().lowercase()).distinct().forEach { seg ->
                CarServiceProbe.queryProvider(context, "content://car_data/$seg").filterNot { it.contains(": null") }.forEach(::add)
            }
        }
    }


    private const val MANAGER_INTERFACE = "android.car.base.ICarManagerInterface"
    private val bridgeCalls = mutableListOf<String>()
    private val PREFIXES = listOf(
        "canbus.", "state.", "info.", "data.car_original.", "data.cusp.", "state.wy.", "data.wy.", "state.zhtd.",
        "callback.", "data.app.", "state.app.", "",
    )

    /** Consigue un ICarManagerInterface: clase del fabricante si existe; si no, un Proxy hacia ICarService. */
    private fun managerInterface(context: Context, loader: ClassLoader, pkg: String, out: MutableList<String>): Any? {
        val icm = runCatching { loader.loadClass(MANAGER_INTERFACE) }.getOrElse { out += "Sin $MANAGER_INTERFACE: $it"; return null }
        out += "$MANAGER_INTERFACE: " + icm.declaredMethods.sortedBy { it.name }
            .joinToString { "${it.returnType.simpleName} ${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }})" }

        // Clases del fabricante que lo implementan o que lo devuelven desde un método estático.
        val names = CarServiceProbe.dexStrings(context, pkg)
            .filter { it.startsWith("Landroid/car/") && it.endsWith(";") }
            .map { it.substring(1, it.length - 1).replace('/', '.') }.distinct().take(1500)
        val classes = names.mapNotNull { runCatching { Class.forName(it, false, loader) }.getOrNull() }
        val impls = classes.filter { icm.isAssignableFrom(it) && it != icm && !it.isInterface && !Modifier.isAbstract(it.modifiers) }
        val factories = classes.flatMap { c ->
            runCatching { c.declaredMethods.toList() }.getOrDefault(emptyList())
                .filter { Modifier.isStatic(it.modifiers) && icm.isAssignableFrom(it.returnType) }
        }
        impls.forEach { c ->
            out += "  implementa: ${c.name} · constructores ${c.declaredConstructors.joinToString { "(" + it.parameterTypes.joinToString { p -> p.simpleName } + ")" }}"
            c.declaredMethods.filter { Regex("regist|callback|listen|client|connect|getInstance|init", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
                .forEach { m -> out += "    ${if (Modifier.isStatic(m.modifiers)) "static " else ""}${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})" }
        }
        factories.forEach { m -> out += "  fábrica: ${m.declaringClass.name}.${m.name}(${m.parameterTypes.joinToString { it.simpleName }})" }

        fun argsFor(types: Array<Class<*>>): Array<Any?>? = types.map { t ->
            when {
                t.isAssignableFrom(Context::class.java) -> context.applicationContext
                t == String::class.java -> context.packageName
                t == Bundle::class.java -> Bundle()
                t == Int::class.javaPrimitiveType -> 0
                t == Boolean::class.javaPrimitiveType -> false
                else -> return null
            }
        }.toTypedArray()

        for (m in factories) {
            val args = argsFor(m.parameterTypes) ?: continue
            runCatching { m.isAccessible = true; m.invoke(null, *args) }.fold(
                { v -> if (v != null) { out += "  instancia con ${m.declaringClass.simpleName}.${m.name}"; return v } },
                { e -> out += "  ${m.name}: ${(e.cause ?: e).javaClass.simpleName}: ${(e.cause ?: e).message}" },
            )
        }
        for (c in impls) for (k in c.declaredConstructors) {
            val args = argsFor(k.parameterTypes) ?: continue
            runCatching { k.isAccessible = true; k.newInstance(*args) }.fold(
                { v -> out += "  instancia con new ${c.simpleName}(${k.parameterTypes.joinToString { it.simpleName }})"; return v },
                { e -> out += "  new ${c.simpleName}: ${(e.cause ?: e).javaClass.simpleName}: ${(e.cause ?: e).message}" },
            )
        }

        // Puente propio: cada método del interfaz se reenvía al de ICarService con la misma firma.
        val service = binder("car") ?: return null
        val (_, svcLoader) = loaderFor(context, CAR_SERVICE) ?: return null
        val svcIface = runCatching { Class.forName(CAR_SERVICE, false, loader) }.getOrNull()
            ?: runCatching { svcLoader.loadClass(CAR_SERVICE) }.getOrNull() ?: return null
        val api = runCatching {
            svcIface.classLoader!!.loadClass("$CAR_SERVICE\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, service)
        }.getOrNull() ?: return null
        out += "  usando puente propio ICarManagerInterface → ICarService"
        bridgeCalls.clear()
        return Proxy.newProxyInstance(loader, arrayOf(icm)) { _, m, a ->
            val args = a ?: emptyArray()
            val target = svcIface.methods.firstOrNull { it.name == m.name && it.parameterTypes.map { p -> p.name } == m.parameterTypes.map { p -> p.name } }
            val result = when {
                m.name == "toString" -> "CockpitBridge"
                m.name == "hashCode" -> 0
                m.name == "equals" -> false
                m.name == "asBinder" -> service
                target != null -> runCatching { target.invoke(api, *args) }.getOrElse { e -> "error ${(e.cause ?: e).message}" }
                else -> defaultFor(m.returnType)
            }
            if (bridgeCalls.size < 200) bridgeCalls += "${m.name}(${args.joinToString { describe(it) }}) → ${describe(result)}"
            if (result is String && result.startsWith("error ") && m.returnType != String::class.java) defaultFor(m.returnType) else result
        }
    }

    /** Vuelca todo lo que el servicio tenga guardado bajo cada prefijo (puertas, presiones, velocidad…). */
    private fun dumpPrefixes(instance: Any, cls: Class<*>): List<String> {
        val byPrefix = cls.declaredMethods.firstOrNull { it.name == "getValueByPrefix" } ?: return listOf("sin getValueByPrefix")
        val out = mutableListOf<String>()
        for (prefix in PREFIXES) {
            val items = runCatching { byPrefix.invoke(instance, prefix) as Array<*>? }.getOrElse { e ->
                out += "getValueByPrefix(\"$prefix\"): ${(e.cause ?: e).javaClass.simpleName}: ${(e.cause ?: e).message}"; null
            } ?: continue
            out += "getValueByPrefix(\"$prefix\"): ${items.size} claves"
            items.take(400).forEach { item -> out += "  " + describeItem(item) }
            if (prefix.isEmpty() || items.size > 50) break
        }
        return out
    }

    private fun describeItem(item: Any?): String {
        item ?: return "null"
        val fields = item.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
            .mapNotNull { f -> runCatching { f.isAccessible = true; "${f.name}=${describe(f.get(item))}" }.getOrNull() }
        return fields.joinToString(", ").ifEmpty { item.toString() }
    }

    private fun staticStrings(c: Class<*>): List<String> = c.declaredFields.filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .mapNotNull { f -> runCatching { f.isAccessible = true; f.get(null) as String? }.getOrNull() }

    private fun staticFields(c: Class<*>, indent: String): List<String> = c.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && (it.type == String::class.java || it.type == Int::class.javaPrimitiveType) }
        .mapNotNull { f -> runCatching { f.isAccessible = true; "$indent${f.name} = ${f.get(null)}" }.getOrNull() }
        .take(400)

    private fun instanceFields(o: Any, indent: String): List<String> = o.javaClass.declaredFields
        .filter { !Modifier.isStatic(it.modifiers) }
        .mapNotNull { f -> runCatching { f.isAccessible = true; "$indent${f.name} = ${f.get(o)}" }.getOrNull() }

    private fun instanceOf(cls: Class<*>, context: Context): Any? {
        cls.declaredMethods.filter { Modifier.isStatic(it.modifiers) && cls.isAssignableFrom(it.returnType) }.forEach { m ->
            val args: Array<Any?>? = when {
                m.parameterTypes.isEmpty() -> emptyArray()
                m.parameterTypes.size == 1 && m.parameterTypes[0].isAssignableFrom(Context::class.java) -> arrayOf(context.applicationContext)
                else -> null
            }
            if (args != null) runCatching { m.isAccessible = true; m.invoke(null, *args) }.getOrNull()?.let { return it }
        }
        return cls.declaredConstructors.firstNotNullOfOrNull { k ->
            val args: Array<Any?>? = when {
                k.parameterTypes.isEmpty() -> emptyArray()
                k.parameterTypes.size == 1 && k.parameterTypes[0].isAssignableFrom(Context::class.java) -> arrayOf(context.applicationContext)
                else -> null
            }
            args?.let { runCatching { k.isAccessible = true; k.newInstance(*it) }.getOrNull() }
        }
    }

    /** Llama a los métodos de lectura (get/is/has/query/read) con cada clave y cada DataType. */
    private fun readKeys(instance: Any, cls: Class<*>, types: List<Any?>, keys: List<String>, context: Context): List<String> {
        val readers = cls.declaredMethods.filter { m ->
            !Modifier.isStatic(m.modifiers) && m.returnType != Void.TYPE &&
                Regex("^(get|is|has|query|read)").containsMatchIn(m.name) && String::class.java in m.parameterTypes &&
                m.parameterTypes.none { it.name.startsWith("kotlin.jvm.functions") }
        }
        val out = mutableListOf("lectores: ${readers.joinToString { it.name + "(" + it.parameterTypes.joinToString { p -> p.simpleName } + ")" }}")
        var errors = 0
        var lastError = ""
        for (m in readers) for (key in keys) {
            val typeOptions: List<Any?> = if (m.parameterTypes.any { it.isEnum }) types else listOf(null)
            for (t in typeOptions) {
                val args = m.parameterTypes.map { p ->
                    when {
                        p == String::class.java -> key
                        p.isEnum -> t
                        p == Class::class.java -> Any::class.java
                        p == Int::class.javaPrimitiveType -> 0
                        p == Long::class.javaPrimitiveType -> 0L
                        p == Boolean::class.javaPrimitiveType -> false
                        p == Float::class.javaPrimitiveType -> 0f
                        p.isAssignableFrom(Context::class.java) -> context
                        else -> null
                    }
                }.toTypedArray()
                runCatching { m.isAccessible = true; m.invoke(instance, *args) }.fold(
                    { v -> if (v != null && v != false && v != 0 && v != "") out += "  ${m.name}($key${t?.let { ", $it" } ?: ""}) = ${describe(v)}" },
                    { e -> errors++; lastError = "${(e.cause ?: e).javaClass.simpleName}: ${(e.cause ?: e).message}" },
                )
            }
        }
        out += "  (${errors} llamadas con error${if (errors > 0) "; última: $lastError" else ""}; vacías no se muestran)"
        return out
    }

    // ---------------------------------------------------------------- Callbacks del servicio

    /** Se registra en el servicio "car" con un callback que apunta cada aviso. Devuelve el resultado legible. */
    fun startCallbacks(context: Context): String {
        if (callbackBinder != null) return "Ya registrado"
        val service = binder("car") ?: return "Servicio car no accesible"
        val (pkg, loader) = loaderFor(context, CAR_SERVICE) ?: return "ICarService no encontrado"
        return runCatching {
            val iface = loader.loadClass(CAR_SERVICE)
            val stub = loader.loadClass("$CAR_SERVICE\$Stub")
            val callbackIface = loader.loadClass(CAR_CALLBACK)
            val callbackStub = runCatching { loader.loadClass("$CAR_CALLBACK\$Stub") }.getOrNull()
            // Código de transacción → método, a partir de los campos TRANSACTION_x del Stub.
            val methods = callbackIface.declaredMethods.associateBy { it.name }
            val codes = callbackStub?.declaredFields.orEmpty().filter { it.name.startsWith("TRANSACTION_") }.mapNotNull { f ->
                runCatching { f.isAccessible = true; f.getInt(null) to f.name.removePrefix("TRANSACTION_") }.getOrNull()
            }.toMap()
            val signatures = codes.mapValues { (_, name) -> methods[name]?.parameterTypes?.toList().orEmpty() }
            val binder = SniffBinder(CAR_CALLBACK, codes, signatures, loader) { logCallback(it) }
            val proxy = Proxy.newProxyInstance(loader, arrayOf(callbackIface)) { _, m, _ ->
                when (m.name) {
                    "asBinder" -> binder
                    "hashCode" -> binder.hashCode()
                    "equals" -> false
                    "toString" -> "CockpitCallback"
                    else -> defaultFor(m.returnType)
                }
            }
            callbackBinder = binder
            callbackProxy = proxy
            val api = stub.getMethod("asInterface", IBinder::class.java).invoke(null, service)
            val register = iface.getMethod("registerCallback", Bundle::class.java, callbackIface)
            var ok = register.invoke(api, Bundle(), proxy)
            if (ok != true) {
                // Segundo intento: el servicio quizá quiere saber quién es el cliente.
                val me = context.packageName
                val who = Bundle().apply {
                    listOf("pkg", "package", "package_name", "packageName", "pkg_name", "app_id", "client", "name", "caller").forEach { putString(it, me) }
                    putString("APP_ID_$me", me)
                    putInt("pid", android.os.Process.myPid())
                    putInt("uid", android.os.Process.myUid())
                }
                ok = "$ok; con datos del cliente = ${register.invoke(api, who, proxy)}"
            }
            val desc = "registerCallback (de $pkg) = $ok · métodos del callback: " +
                codes.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}(${signatures[it.key].orEmpty().joinToString { p -> p.simpleName }})" }
            logCallback(desc)
            desc
        }.getOrElse { e ->
            callbackBinder = null
            callbackProxy = null
            "Error al registrar: ${(e.cause ?: e).javaClass.simpleName}: ${(e.cause ?: e).message}"
        }
    }

    private fun defaultFor(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        else -> null
    }

    private fun describe(v: Any?): String = when (v) {
        null -> "null"
        is Bundle -> BroadcastSniffer.describe(v)
        is ByteArray -> PortProbe.hex(v, minOf(v.size, 64))
        is IntArray -> v.joinToString(",", "[", "]")
        is Array<*> -> v.joinToString(",", "[", "]")
        else -> v.toString().take(300)
    }

    /** Binder que hace de ICarCallback y decodifica lo que recibe según la firma del método. */
    private class SniffBinder(
        private val descriptor: String,
        private val names: Map<Int, String>,
        private val signatures: Map<Int, List<Class<*>>>,
        private val loader: ClassLoader,
        private val log: (String) -> Unit,
    ) : Binder() {
        init { attachInterface(null, descriptor) }

        override fun getInterfaceDescriptor(): String = descriptor

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(descriptor)
                return true
            }
            val name = names[code] ?: "código $code"
            val args = runCatching {
                data.enforceInterface(descriptor)
                val sig = signatures[code]
                if (sig == null) "${data.dataAvail()} bytes" else sig.joinToString(", ") { readArg(data, it) }
            }.getOrElse { "no decodificado (${it.javaClass.simpleName})" }
            log("$name($args)")
            reply?.writeNoException()
            return true
        }

        private fun readArg(p: Parcel, type: Class<*>): String = when (type) {
            Int::class.javaPrimitiveType -> p.readInt().toString()
            Long::class.javaPrimitiveType -> p.readLong().toString()
            Boolean::class.javaPrimitiveType -> (p.readInt() != 0).toString()
            Float::class.javaPrimitiveType -> p.readFloat().toString()
            String::class.java -> p.readString() ?: "null"
            ByteArray::class.java -> p.createByteArray()?.let { PortProbe.hex(it, minOf(it.size, 64)) } ?: "null"
            IntArray::class.java -> p.createIntArray()?.joinToString(",", "[", "]") ?: "null"
            Bundle::class.java -> if (p.readInt() != 0) {
                Bundle.CREATOR.createFromParcel(p).apply { classLoader = loader }.let { BroadcastSniffer.describe(it) }
            } else "null"
            else -> "<${type.simpleName}>"
        }
    }
}
