package com.cockpitf30.vehicle.probe

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tercera vía hacia los datos del coche: la propia app CarCanbus (android.car.app.canbus).
 *
 * En el informe de la 0.9.19 el almacén del servicio solo tenía el volante del CAN; las puertas,
 * presiones y velocidad se quedan dentro de CarCanbus. Esa app trae interfaces AIDL para otras
 * apps (ICanBus360Service, "registerICanBusCallBack(packname)") pensadas para las cámaras 360, y
 * el lanzador BMW de serie usa com.acloud…CloudCanbusInfoManager para pintar las puertas.
 *
 * [explore] se conecta a cada servicio de CarCanbus, lista sus métodos, se registra con
 * callbacks que apuntan todo en «Avisos del servicio» y llama a los métodos de lectura.
 * [writeTestZip] guarda el informe y los APK del fabricante en un zip en Descargas.
 */
object CanbusProbe {
    private const val CANBUS = "android.car.app.canbus"
    private const val LAUNCHER = "com.bmw1.launcher2"
    private val READ = Regex("^(get|is|has|query|read)")
    private val REGISTER = Regex("^(register|add|set).*(call ?back|listener|observer)", RegexOption.IGNORE_CASE)
    private val connections = mutableListOf<ServiceConnection>()

    fun explore(context: Context): List<String> = buildList {
        val pm = context.packageManager
        val apk = runCatching { pm.getApplicationInfo(CANBUS, 0).sourceDir }.getOrNull()
        if (apk == null) { add("$CANBUS no está instalada"); return@buildList }
        val loader = dalvik.system.PathClassLoader(apk, CanbusProbe::class.java.classLoader?.parent)
        val strings = CarServiceProbe.dexStrings(context, CANBUS)

        // Interfaces AIDL que trae CarCanbus.
        val aidl = strings.filter { it.startsWith("L") && it.endsWith(";") && '$' !in it && Regex("(?i)/I[A-Z][A-Za-z0-9]*(CanBus|Canbus|Car)[A-Za-z0-9]*;$").containsMatchIn(it) }
            .map { it.substring(1, it.length - 1).replace('/', '.') }.distinct()
        add("-- Interfaces de $CANBUS (${aidl.size})")
        aidl.forEach { name ->
            val c = runCatching { loader.loadClass(name) }.getOrNull() ?: return@forEach
            if (!c.isInterface) return@forEach
            add("[$name]")
            val stub = runCatching { loader.loadClass("$name\$Stub") }.getOrNull()
            val (codes, _) = CarDataProbe.transactions(c, stub)
            c.declaredMethods.sortedBy { it.name }.forEach { m ->
                val code = codes.entries.firstOrNull { it.value == m.name }?.key
                add("  ${code ?: "?"} ${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
            }
        }

        // Servicios de la app: los conectamos uno a uno.
        val services = runCatching { pm.getPackageInfo(CANBUS, PackageManager.GET_SERVICES).services.orEmpty().toList() }.getOrDefault(emptyList())
        add("-- Servicios (${services.size})")
        for (s in services) {
            add("${s.name} · exportado=${s.exported}${s.permission?.let { " · permiso $it" } ?: ""}")
            val binder = bind(context, ComponentName(CANBUS, s.name))
            if (binder == null) { add("  no se pudo conectar"); continue }
            val descriptor = runCatching { binder.interfaceDescriptor }.getOrNull()
            add("  conectado · interfaz ${descriptor ?: "?"}")
            if (descriptor.isNullOrEmpty()) continue
            addAll(callService(context, loader, descriptor, binder).map { "  $it" })
        }

        add("-- Lanzador BMW: CloudCanbusInfoManager")
        addAll(launcherManager(context))
    }

    private fun bind(context: Context, component: ComponentName): IBinder? {
        var result: IBinder? = null
        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { result = service; latch.countDown() }
            override fun onServiceDisconnected(name: ComponentName) {}
            override fun onNullBinding(name: ComponentName) { latch.countDown() }
        }
        val ok = runCatching { context.applicationContext.bindService(Intent().setComponent(component), conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!ok) return null
        latch.await(3, TimeUnit.SECONDS)
        // Seguimos conectados para que los callbacks sigan llegando.
        if (result != null) connections += conn else runCatching { context.applicationContext.unbindService(conn) }
        return result
    }

    /** Registra callbacks y llama a los lectores del servicio cuyo AIDL es [descriptor]. */
    private fun callService(context: Context, loader: ClassLoader, descriptor: String, binder: IBinder): List<String> = buildList {
        val iface = runCatching { loader.loadClass(descriptor) }.getOrNull() ?: run { add("interfaz no encontrada en el APK"); return@buildList }
        val api = runCatching { loader.loadClass("$descriptor\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder) }
            .getOrElse { add("asInterface: ${(it.cause ?: it).message}"); return@buildList }
        val methods = iface.declaredMethods.sortedBy { it.name }
        // Primero los registros, así luego los lectores pueden disparar avisos.
        for (m in methods.filter { REGISTER.containsMatchIn(it.name) && !it.name.startsWith("un") }) {
            val args = argsFor(context, loader, m, callbacks = true)
            if (args == null) { add("${m.name}: argumentos desconocidos"); continue }
            add("${m.name}(${m.parameterTypes.joinToString { it.simpleName }}) = ${invoke(m, api, args)}")
        }
        for (m in methods.filter { READ.containsMatchIn(it.name) && it.returnType != Void.TYPE }) {
            val args = argsFor(context, loader, m, callbacks = false) ?: continue
            add("${m.name}(${args.joinToString { it?.toString()?.take(30) ?: "null" }}) = ${invoke(m, api, args)}")
        }
    }

    private fun invoke(m: Method, target: Any, args: Array<Any?>): String =
        runCatching { describe(m.invoke(target, *args)) }.getOrElse { "error ${(it.cause ?: it).javaClass.simpleName}: ${(it.cause ?: it).message}" }

    private fun argsFor(context: Context, loader: ClassLoader, m: Method, callbacks: Boolean): Array<Any?>? = m.parameterTypes.map { t ->
        when {
            t == String::class.java -> context.packageName
            t == Int::class.javaPrimitiveType -> 0
            t == Long::class.javaPrimitiveType -> 0L
            t == Boolean::class.javaPrimitiveType -> true
            t.isInterface && callbacks -> sniffer(loader, t)
            else -> return null
        }
    }.toTypedArray()

    /** Callback AIDL propio que apunta cada llamada en «Avisos del servicio». */
    private fun sniffer(loader: ClassLoader, iface: Class<*>): Any {
        val stub = runCatching { loader.loadClass("${iface.name}\$Stub") }.getOrNull()
        val (codes, signatures) = CarDataProbe.transactions(iface, stub)
        val binder = CarDataProbe.SniffBinder(iface.name, codes, signatures, loader) { CarDataProbe.logCallback("[${iface.simpleName}] $it") }
        return CarDataProbe.proxyFor(iface, loader, binder).also { keep += binder; keep += it }
    }

    private val keep = mutableListOf<Any>()

    private fun launcherManager(context: Context): List<String> = buildList {
        val apk = runCatching { context.packageManager.getApplicationInfo(LAUNCHER, 0).sourceDir }.getOrNull()
            ?: run { add("$LAUNCHER no está instalado"); return@buildList }
        val loader = dalvik.system.PathClassLoader(apk, CanbusProbe::class.java.classLoader?.parent)
        val names = CarServiceProbe.dexStrings(context, LAUNCHER)
            .filter { it.startsWith("Lcom/acloud/") && it.endsWith(";") && Regex("(?i)canbus|car|door|tpms").containsMatchIn(it) }
            .map { it.substring(1, it.length - 1).replace('/', '.') }.distinct()
        add("clases: ${names.joinToString()}")
        for (name in names.filter { '$' !in it }) {
            val c = runCatching { loader.loadClass(name) }.getOrNull() ?: continue
            add("[$name]${if (c.isInterface) " interfaz" else ""}")
            c.declaredMethods.sortedBy { it.name }.take(120).forEach { m ->
                add("  ${if (Modifier.isStatic(m.modifiers)) "static " else ""}${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
            }
            c.declaredFields.take(60).forEach { f ->
                val v = if (Modifier.isStatic(f.modifiers) && (f.type == String::class.java || f.type.isPrimitive)) runCatching { f.isAccessible = true; " = ${f.get(null)}" }.getOrDefault("") else ""
                add("  campo ${f.type.simpleName} ${f.name}$v")
            }
        }
        // Si tiene getInstance(), lo creamos y le pedimos lo que sepa leer.
        val manager = names.firstOrNull { it.endsWith("CloudCanbusInfoManager") }?.let { runCatching { loader.loadClass(it) }.getOrNull() } ?: return@buildList
        val instance = manager.declaredMethods.filter { Modifier.isStatic(it.modifiers) && manager.isAssignableFrom(it.returnType) }
            .firstNotNullOfOrNull { m ->
                val args = m.parameterTypes.map { if (it.isAssignableFrom(Context::class.java)) context.applicationContext else return@firstNotNullOfOrNull null }.toTypedArray()
                runCatching { m.isAccessible = true; m.invoke(null, *args) }.onFailure { add("${m.name}: ${(it.cause ?: it).message}") }.getOrNull()
            } ?: run { add("sin getInstance utilizable"); return@buildList }
        add("instancia: $instance")
        manager.declaredMethods.filter { !Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty() && READ.containsMatchIn(it.name) && it.returnType != Void.TYPE }
            .forEach { m -> add("  ${m.name}() = ${runCatching { m.isAccessible = true; describe(m.invoke(instance)) }.getOrElse { "error ${(it.cause ?: it).message}" }}") }
    }

    private fun describe(v: Any?): String = when (v) {
        null -> "null"
        is android.os.Bundle -> BroadcastSniffer.describe(v)
        is IntArray -> v.joinToString(",", "[", "]")
        is ByteArray -> PortProbe.hex(v, minOf(v.size, 64))
        is Array<*> -> v.joinToString(",", "[", "]")
        else -> v.toString().take(300)
    }

    /** Apps del fabricante que interesa estudiar (las que existan en esta radio). */
    private val VENDOR_PACKAGES = listOf(CANBUS, LAUNCHER, "com.ID801.launcher2", "android.car.server", "android.car.provider", "android.car.app.tpms")

    /**
     * Guarda en Descargas un único zip con el informe y los APK del fabricante. (Antes cada APK
     * iba suelto y Android recortaba el nombre en el primer punto: todos se pisaban.)
     */
    fun writeTestZip(context: Context, report: String): String {
        val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val name = "cockpit-prueba-$stamp.zip"
        val included = mutableListOf<String>()
        fun write(out: java.io.OutputStream) = java.util.zip.ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("informe.txt"))
            zip.write(report.toByteArray())
            zip.closeEntry()
            for (pkg in VENDOR_PACKAGES) {
                val src = runCatching { java.io.File(context.packageManager.getApplicationInfo(pkg, 0).sourceDir) }.getOrNull() ?: continue
                runCatching {
                    zip.putNextEntry(java.util.zip.ZipEntry("apks/$pkg.apk"))
                    src.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    included += "$pkg (${src.length() / 1024 / 1024} MB)"
                }.onFailure { included += "$pkg: ${it.message}" }
            }
        }
        val where = if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore no devolvió destino")
            context.contentResolver.openOutputStream(uri)!!.use(::write)
            "Descargas/$name"
        } else {
            val file = java.io.File(context.getExternalFilesDir(null), name)
            file.outputStream().use(::write)
            file.absolutePath
        }
        return "$where · informe + ${included.joinToString()}"
    }
}
