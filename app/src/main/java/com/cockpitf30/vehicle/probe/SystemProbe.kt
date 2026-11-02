package com.cockpitf30.vehicle.probe

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.util.zip.ZipFile

/**
 * Busca en la radio las apps y servicios que probablemente reciben los datos del CAN
 * (la app "canbus" del fabricante, el servicio de la MCU…) y qué acciones de broadcast declaran.
 * En estas radios el decodificador suele hablar con la MCU, y la MCU con una app del sistema:
 * si esa app reenvía los datos por broadcast, podemos escucharlos sin root.
 */
object SystemProbe {
    private val KEYWORDS = listOf(
        "can", "mcu", "vehicle", "carinfo", "carsetting", "car.", ".car", "topway", ".tw.", "raise", "rzc",
        "decoder", "obd", "wy", "zq", "syu", "microntek", "hct", "nwd", "autochips", "mediatek.car", "backcar", "radar",
    )

    class PackageReport(
        val packageName: String,
        val label: String,
        val system: Boolean,
        val services: List<String>,
        val receivers: List<String>,
        val providers: List<String>,
        val actions: List<String>,
    )

    /** Palabras en el nombre visible de la app (p. ej. "Original car", "CANBUS"). */
    private val LABEL_WORDS = Regex("\\b(can|canbus|car|vehicle|vehículo|coche|mcu|original|obd|decoder)\\b", RegexOption.IGNORE_CASE)

    fun isCandidate(pkg: String, label: String? = null): Boolean {
        val p = pkg.lowercase()
        if (p.startsWith("com.google.") || p.startsWith("com.android.vending") || p.startsWith("com.cockpitf30")) return false
        return KEYWORDS.any { p.contains(it) } || (label != null && LABEL_WORDS.containsMatchIn(label))
    }

    /** Todas las apps del sistema, "paquete · nombre", para el informe. */
    @Suppress("DEPRECATION")
    fun systemApps(context: Context): List<String> {
        val pm = context.packageManager
        return runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
            .map { "${it.packageName} · ${runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault("?")}" }
            .sorted()
    }

    /** Servicios de sistema registrados (binder). Las ROM de radio suelen añadir uno para la MCU/CAN. */
    fun systemServices(): List<String> {
        val viaShell = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("service", "list"))
            val lines = p.inputStream.bufferedReader().readLines()
            p.waitFor()
            lines.drop(1).map { it.trim() }.filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
        if (viaShell.isNotEmpty()) return viaShell
        // Plan B: API oculta ServiceManager.listServices().
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            (Class.forName("android.os.ServiceManager").getMethod("listServices").invoke(null) as Array<String>).toList().sorted()
        }.getOrElse { listOf("no se pudo listar: ${it.message ?: it.javaClass.simpleName}") }
    }

    /** Jars del framework: un "mcu.jar" o "canbus.jar" delata la API del fabricante. */
    fun frameworkJars(): List<String> =
        listOf("/system/framework", "/system_ext/framework", "/vendor/framework", "/product/framework").flatMap { dir ->
            java.io.File(dir).listFiles()?.filter { it.name.endsWith(".jar") }?.map { "$dir/${it.name}" }.orEmpty()
        }.sorted()

    private val INTERESTING = Regex("can|mcu|car|vehicle|topway|raise|decoder|serial|uart|wy|zq|ts|hct|syu|backcar|radar", RegexOption.IGNORE_CASE)

    fun interesting(line: String) = INTERESTING.containsMatchIn(line)

    @Suppress("DEPRECATION")
    fun scan(context: Context): List<PackageReport> {
        val pm = context.packageManager
        val flags = PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
        val all: List<PackageInfo> = runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())
        return all.filter { pi ->
            val label = pi.applicationInfo?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
            isCandidate(pi.packageName, label)
        }.mapNotNull { basic ->
            val info = runCatching { pm.getPackageInfo(basic.packageName, flags) }.getOrNull() ?: basic
            val app = info.applicationInfo ?: return@mapNotNull null
            PackageReport(
                packageName = info.packageName,
                label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(info.packageName),
                system = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                services = info.services.orEmpty().map { (if (it.exported) "+" else "") + it.name.removePrefix(info.packageName) },
                receivers = info.receivers.orEmpty().map { (if (it.exported) "+" else "") + it.name.removePrefix(info.packageName) },
                providers = info.providers.orEmpty().mapNotNull { it.authority },
                actions = manifestActions(app.sourceDir),
            )
        }.sortedWith(compareByDescending<PackageReport> { it.actions.size }.thenBy { it.packageName })
    }

    /** Cadenas con pinta de acción de Intent dentro del manifiesto del APK. */
    fun manifestActions(apkPath: String?): List<String> {
        apkPath ?: return emptyList()
        return runCatching {
            ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry("AndroidManifest.xml") ?: return emptyList()
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                AxmlStrings.parse(bytes).filter(AxmlStrings::looksLikeAction).distinct()
            }
        }.getOrDefault(emptyList())
    }

    /** Propiedades del sistema relacionadas con canbus/MCU (las que una app puede leer). */
    fun properties(): List<String> = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("getprop"))
        val lines = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        val re = Regex("can|mcu|car|topway|raise|decoder|serial|tty|uart|wy|zq", RegexOption.IGNORE_CASE)
        lines.filter { re.containsMatchIn(it.substringBefore("]:")) }
    }.getOrElse { listOf("getprop: ${it.message}") }
}
