package com.cockpitf30.vehicle.probe

import android.content.Context
import android.net.Uri
import android.os.IBinder
import java.util.zip.ZipFile

/**
 * Investiga el "Car Service" propio de esta radio (servicio binder "car", app android.car.server
 * con el provider "car_data"), que es por donde la MCU entrega los datos del CAN a Android.
 */
object CarServiceProbe {
    /** Apps del fabricante que hablan con el servicio del coche, y el lanzador BMW de serie. */
    private val TARGET_PACKAGES = listOf(
        "android.car.server", "android.car.app.canbus", "android.car.provider",
        "android.car.framework.override", "com.bmw1.launcher2",
    )
    private val BINDERS = listOf("car", "car_proxy", "car.panel_color")
    private val PROVIDERS = listOf("content://car_data", "content://carsetting")

    /** Cadenas que delatan cómo se piden los datos del coche. */
    private val INTERESTING = Regex(
        "door|speed|rpm|fuel|oil|mileage|odometer|gear|temp|canbus|car_data|content://|ICar|Canbus|" +
            "android\\.car\\.(action|extra|intent)|getCar|registerCar|CarInfo|CarData|outside|seatbelt|handbrake|trunk|hood",
        RegexOption.IGNORE_CASE,
    )

    fun run(context: Context): List<String> = buildList {
        add("-- Binders")
        BINDERS.forEach { name -> add(binderInfo(name)) }

        add("-- Content providers")
        val uris = PROVIDERS.toMutableSet()
        val strings = TARGET_PACKAGES.associateWith { dexStrings(context, it) }
        // URIs que aparecen en el código de las apps del fabricante.
        strings.values.flatten().filter { it.startsWith("content://car") }.forEach { uris += it.trimEnd('/') }
        uris.take(25).forEach { addAll(queryProvider(context, it)) }

        add("-- Interfaz del servicio")
        addAll(serviceMethods(context, strings))

        add("-- Cadenas del código de las apps del coche")
        strings.forEach { (pkg, list) ->
            val hits = list.filter { it.length in 3..160 && INTERESTING.containsMatchIn(it) }.distinct()
            add("[$pkg] ${if (list.isEmpty()) "sin dex legible" else "${list.size} cadenas, ${hits.size} interesantes"}")
            hits.take(400).forEach { add("  $it") }
        }
    }

    private fun binder(name: String): IBinder? = runCatching {
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, name) as IBinder?
    }.getOrNull()

    private fun binderInfo(name: String): String {
        val b = binder(name) ?: return "$name: no accesible"
        return "$name: ${runCatching { b.interfaceDescriptor }.getOrElse { "descriptor? ${it.message}" }} · vivo=${b.isBinderAlive}"
    }

    private fun apkOf(context: Context, pkg: String): String? =
        runCatching { context.packageManager.getApplicationInfo(pkg, 0).sourceDir }.getOrNull()

    fun dexStrings(context: Context, pkg: String): List<String> {
        val apk = apkOf(context, pkg) ?: return emptyList()
        return runCatching {
            ZipFile(apk).use { zip ->
                zip.entries().toList().filter { it.name.matches(Regex("classes\\d*\\.dex")) }.flatMap { e ->
                    DexStrings.parse(zip.getInputStream(e).use { it.readBytes() })
                }
            }
        }.getOrDefault(emptyList())
    }

    fun queryProvider(context: Context, uri: String): List<String> = runCatching {
        context.contentResolver.query(Uri.parse(uri), null, null, null, null).use { c ->
            if (c == null) return listOf("$uri: null")
            val lines = mutableListOf("$uri: ${c.count} filas · columnas ${c.columnNames.joinToString()}")
            var n = 0
            while (c.moveToNext() && n++ < 40) {
                lines += "  " + (0 until c.columnCount).joinToString(" | ") { i ->
                    runCatching { c.getString(i) }.getOrElse { "<blob>" } ?: "null"
                }
            }
            lines
        }
    }.getOrElse { listOf("$uri: ${it.javaClass.simpleName}: ${it.message}") }

    /**
     * Carga la interfaz AIDL del servicio "car" desde la app que la contiene, lista sus métodos
     * y llama a los getters sin parámetros (get/is/has), que solo leen.
     */
    private fun serviceMethods(context: Context, strings: Map<String, List<String>>): List<String> = buildList {
        val descriptor = binder("car")?.let { runCatching { it.interfaceDescriptor }.getOrNull() } ?: "android.car.server.ICarService"
        val typeName = "L${descriptor.replace('.', '/')};"
        val pkg = strings.entries.firstOrNull { (_, s) -> typeName in s }?.key ?: "android.car.server"
        val apk = apkOf(context, pkg) ?: run { add("APK de $pkg no encontrado"); return@buildList }
        val loader = dalvik.system.PathClassLoader(apk, CarServiceProbe::class.java.classLoader?.parent)
        val stub = runCatching { loader.loadClass("$descriptor\$Stub") }.getOrElse {
            add("$descriptor\$Stub no se pudo cargar desde $pkg: ${it.message}")
            return@buildList
        }
        val iface = loader.loadClass(descriptor)
        add("$descriptor (de $pkg): ${iface.declaredMethods.size} métodos")
        iface.declaredMethods.sortedBy { it.name }.forEach { m ->
            add("  ${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
        }
        val service = runCatching { stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder("car")) }.getOrNull()
            ?: run { add("asInterface: sin servicio"); return@buildList }
        iface.declaredMethods.filter { m ->
            m.parameterTypes.isEmpty() && m.returnType != Void.TYPE &&
                (m.name.startsWith("get") || m.name.startsWith("is") || m.name.startsWith("has"))
        }.sortedBy { it.name }.take(60).forEach { m ->
            val v = runCatching { m.invoke(service) }.fold({ describe(it) }, { e -> "error ${(e.cause ?: e).javaClass.simpleName}: ${(e.cause ?: e).message}" })
            add("  ${m.name}() = $v")
        }
    }

    private fun describe(v: Any?): String = when (v) {
        null -> "null"
        is ByteArray -> PortProbe.hex(v, minOf(v.size, 64))
        is IntArray -> v.joinToString(",", "[", "]")
        is Array<*> -> v.joinToString(",", "[", "]")
        is android.os.Bundle -> BroadcastSniffer.describe(v)
        else -> v.toString().take(300)
    }
}
