package com.cockpitf30.radio

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.cockpitf30.vehicle.source.VendorCar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Sintonizador FM de la propia radio, a través del servicio del coche del fabricante
 * (android.car.server).
 *
 * Sacado del servicio del coche de esta unidad (android.car.server, RadioCore / AppStack / AppManager):
 * - Las peticiones "request.radio.*" las atiende el propio servicio (RadioCore) y las manda a la MCU:
 *   no hace falta la app de radio de serie (android.car.app.radio), que en esta unidad no viene.
 *   La frecuencia va en un Bundle {band, freq}; FM es la banda 0, en unidades de 10 kHz (10230).
 * - Lo que contesta la MCU queda en el almacén: "data.radio.main_freq_0",
 *   "data.radio.composite_main_freq" (banda<<24 | freq), "state.radio.curr_freq_range",
 *   la lista "data.radio.list_0_0", "data.radio.rds_ps_text", "state.radio.seek_state".
 * - El audio del sintonizador lo conmuta la MCU al pasar a la fuente Radio (APP_ID_Radio = 0x10000)
 *   con request.app.enter_app {app_id, reason}.
 *
 * Por qué no sonaba en la 0.9.68 (dos fallos):
 * 1. enter_app con reason = nuestro paquete hace que el servicio intente abrir la actividad
 *    android.car.app.radio/.RadioMain; como no existe, no deja Radio como fuente actual
 *    (state.app.cur_source se queda en 65539 = EmptySource). Con reason "simple" (lo que usa el
 *    propio servicio en enterAppSimple, p. ej. para el asistente de voz) entra en la fuente sin abrir
 *    ninguna pantalla. "self" hace lo mismo; se prueba como segunda opción.
 * 2. Pedíamos foco de audio permanente (AUDIOFOCUS_GAIN) para la FM. El servicio del coche, al
 *    perder el foco con una fuente de la MCU activa, se pasa a EmptySource (cortando la radio), y al
 *    recuperarlo nos quitaba el foco a nosotros y nuestra app paraba la FM. La FM ya no pide foco.
 *
 * [trace] guarda paso a paso lo que se pide y lo que contesta, y [runTest] hace una prueba
 * completa para el informe.
 */
object FactoryTuner {
    private const val TAG = "FactoryTuner"
    const val APP_ID_RADIO = 0x10000
    const val APP_ID_EMPTY = 65539
    const val BAND_FM = 0
    const val KEY_SOURCE = "state.app.cur_source"

    /** Formas de entrar en la fuente Radio sin la app de serie, en orden. */
    private val ENTER_REASONS = listOf("simple", "self")

    /** Resultado de la última comprobación, para enseñarlo en la pantalla de radio. */
    data class Probe(
        val available: Boolean,
        val reason: String,
        val keys: Map<String, Any?> = emptyMap(),
    )

    @Volatile var lastProbe: Probe? = null; private set

    private val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val _trace = MutableStateFlow<List<String>>(emptyList())
    /** Últimas órdenes al sintonizador y lo que contestó (lo más nuevo abajo). */
    val trace: StateFlow<List<String>> = _trace.asStateFlow()

    fun note(line: String) {
        Log.i(TAG, line)
        _trace.update { (it + "${LocalTime.now().format(time)} $line").takeLast(80) }
    }

    private fun car(context: Context): VendorCar? = VendorCar.connect(context).getOrNull()

    fun sourceName(id: Int?): String = when (id) {
        null -> "?"
        APP_ID_RADIO -> "Radio"
        APP_ID_EMPTY -> "ninguna (EmptySource)"
        65537 -> "Bluetooth"
        65555 -> "Música"
        65558 -> "Coche original"
        65549 -> "AUX"
        65562 -> "AUX virtual"
        else -> "0x" + Integer.toHexString(id)
    }

    fun currentSource(context: Context): Int? =
        runCatching { car(context)?.read(listOf(KEY_SOURCE))?.get(KEY_SOURCE) as? Int }.getOrNull()

    /**
     * ¿Hay sintonizador? El servicio del coche siempre lleva la parte de radio (RadioCore), así que
     * se da por disponible si el servicio contesta y la fábrica no lo ha desactivado. Si además la
     * MCU ya ha dado frecuencia (data.radio.main_freq_*), es que el sintonizador está vivo.
     */
    fun probe(context: Context): Probe {
        val radioApp = runCatching { context.packageManager.getApplicationInfo("android.car.app.radio", 0); true }.getOrDefault(false)
        val vc = VendorCar.connect(context)
        val car = vc.getOrNull()
        val p = if (car == null) {
            Probe(false, "No se puede hablar con el servicio del coche: ${vc.exceptionOrNull()?.message ?: "sin librería"}")
        } else {
            val keys = snapshot(car)
            val factoryOff = keys["data.factory.app.enable_radio"] == 0
            val mcuAnswered = keys.keys.any { it.startsWith("data.radio.main_freq_") || it == "data.radio.composite_main_freq" || it == "state.radio.curr_freq_range" }
            val src = sourceName(keys[KEY_SOURCE] as? Int)
            when {
                factoryOff -> Probe(false, "La radio tiene el sintonizador desactivado de fábrica (enable_radio = 0)", keys)
                mcuAnswered -> Probe(true, "Sintonizador detectado: la MCU da frecuencia · fuente $src", keys)
                else -> Probe(
                    true,
                    "Servicio de radio presente; la MCU aún no ha dado frecuencia (se sabrá al poner la FM) · fuente $src" +
                        if (radioApp) " · app de radio de serie presente" else "",
                    keys,
                )
            }
        }
        lastProbe = p
        Log.i(TAG, "probe: ${p.reason} ${p.keys}")
        return p
    }

    private fun snapshot(car: VendorCar): Map<String, Any?> {
        val keys = runCatching { car.read(listOf("data.radio.", "state.radio.", "info.radio.")) }.getOrElse { emptyMap() }
        val extra = runCatching {
            car.read(listOf(KEY_SOURCE, "data.factory.app.enable_radio", "data.factory.canbus.enable_car_radio", "data.factory.arm_drive_radio_and_audio", "data.factory.radio_area", "data.set.gps_"))
        }.getOrElse { emptyMap() }
        return keys + extra
    }

    /** Frecuencia actual (10 kHz) y nombre RDS, si los da. */
    data class Status(val freq: Int?, val rds: String?, val seeking: Boolean, val found: List<FmStation>, val source: Int?, val mcuAnswered: Boolean)

    fun status(context: Context): Status? {
        val car = car(context) ?: return null
        val k = runCatching { car.read(listOf("data.radio.", "state.radio.", KEY_SOURCE)) }.getOrNull() ?: return null
        val composite = (k["data.radio.composite_main_freq"] as? Int)?.let(RadioLogic::decodeComposite)
        val freq = (k["data.radio.main_freq_0"] as? Int)?.and(0xFFFF)?.takeIf { it in RadioLogic.FM_MIN..RadioLogic.FM_MAX }
            ?: composite?.takeIf { it.first == BAND_FM }?.second?.takeIf { it in RadioLogic.FM_MIN..RadioLogic.FM_MAX }
        val rds = (k["data.radio.rds_ps_text"] as? String ?: (k["state.radio.rds_ps_info"] as? Bundle)?.let { b -> b.keySet().firstNotNullOfOrNull { @Suppress("DEPRECATION") b.get(it) as? String } })
            ?.trim()?.takeIf { it.isNotBlank() }
        val seeking = ((k["state.radio.seek_state"] as? Int) ?: 0) and 0xFFFF != 0
        val list = (k["data.radio.list_0_0"] as? Bundle)?.let { b -> @Suppress("DEPRECATION") RadioLogic.parseSavedList { b.get(it) } }.orEmpty()
        return Status(freq, rds, seeking, list, k[KEY_SOURCE] as? Int, freq != null || composite != null)
    }

    /**
     * Pasa a la fuente Radio de la MCU (que conmuta el audio del sintonizador) y pone la banda FM.
     * Prueba cada forma de [ENTER_REASONS] hasta que state.app.cur_source diga Radio.
     * Devuelve true si la fuente quedó en Radio.
     */
    fun enter(context: Context): Boolean {
        val car = car(context) ?: run { note("enter: sin servicio del coche"); return false }
        val before = currentSource(context)
        if (before == APP_ID_RADIO) { note("enter: la fuente ya es Radio"); return true }
        for (reason in ENTER_REASONS) {
            val r = runCatching { car.requestSync("request.app.enter_app", Bundle().apply { putInt("app_id", APP_ID_RADIO); putString("reason", reason) }) }
            val now = waitSource(context, APP_ID_RADIO, 1500)
            note("enter_app(Radio, \"$reason\") = ${r.getOrElse { "error ${(it.cause ?: it).message}" }} · fuente ${sourceName(before)} → ${sourceName(now)}")
            if (now == APP_ID_RADIO) {
                runCatching { car.requestSync("request.radio.switch_band_fm", null) }
                return true
            }
        }
        runCatching { car.requestSync("request.radio.switch_band_fm", null) }
        return false
    }

    private fun waitSource(context: Context, want: Int, ms: Long): Int? {
        val end = SystemClock.uptimeMillis() + ms
        var s = currentSource(context)
        while (s != want && SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(150)
            s = currentSource(context)
        }
        return s
    }

    /** Deja la fuente Radio (para que suene Android: Internet, Spotify…). */
    fun exit(context: Context) {
        val car = car(context) ?: return
        val r = runCatching { car.requestSync("request.app.exit_app", Bundle().apply { putInt("app_id", APP_ID_RADIO); putString("reason", context.packageName) }) }
        note("exit_app(Radio) = ${r.getOrElse { "error ${it.message}" }}")
    }

    fun tune(context: Context, freq: Int) = req(context, "request.radio.set_main_freq", sync = true) {
        putInt("band", BAND_FM); putInt("freq", freq)
    }

    fun seekNext(context: Context) = req(context, "request.radio.seek_next")
    fun seekPrev(context: Context) = req(context, "request.radio.seek_prev")
    fun stepNext(context: Context) = req(context, "request.radio.step_next")
    fun stepPrev(context: Context) = req(context, "request.radio.step_prev")
    /** Barrido de toda la banda guardando lo que encuentra en "data.radio.list_0_0". */
    fun search(context: Context) = req(context, "request.radio.start_search")
    fun stopSearch(context: Context) = req(context, "request.radio.stop_search")

    private fun req(context: Context, key: String, sync: Boolean = false, args: (Bundle.() -> Unit)? = null): Boolean {
        val car = car(context) ?: run { note("$key: sin servicio del coche"); return false }
        val b = args?.let { Bundle().apply(it) }
        val r = runCatching { if (sync) car.requestSync(key, b) else { car.requestOneway(key, b); "oneway" } }
        note("${key.removePrefix("request.")}${b?.let { " " + bundleText(it) } ?: ""} = ${r.getOrElse { "error ${(it.cause ?: it).message}" }}")
        return r.isSuccess
    }

    private fun bundleText(b: Bundle) = b.keySet().joinToString(prefix = "{", postfix = "}") { "$it=${@Suppress("DEPRECATION") b.get(it)}" }

    private fun describe(v: Any?): String = when (v) {
        is Bundle -> bundleText(v)
        is IntArray -> v.joinToString(prefix = "[", postfix = "]")
        is Array<*> -> v.joinToString(prefix = "[", postfix = "]")
        else -> v.toString()
    }

    /**
     * Prueba completa para el informe: lee el estado, entra en la fuente Radio, sintoniza [freq],
     * busca la siguiente emisora y vuelve a leer, apuntando qué claves cambian. Tarda unos 10 s.
     */
    fun runTest(context: Context, freq: Int): List<String> = buildList {
        add("== Prueba FM (sintonizador de la radio)")
        val radioApp = runCatching { context.packageManager.getApplicationInfo("android.car.app.radio", 0); true }.getOrDefault(false)
        add("App de radio de serie (android.car.app.radio): ${if (radioApp) "instalada" else "no instalada"}")
        add("Propiedad car.ky_home_radio_to_originalcar = ${sysProp("car.ky_home_radio_to_originalcar")}")
        val vc = VendorCar.connect(context)
        val car = vc.getOrNull()
        if (car == null) { add("Sin servicio del coche: ${vc.exceptionOrNull()}"); return@buildList }
        fun snap(title: String): Map<String, Any?> {
            val s = snapshot(car)
            add("-- $title (${s.size} claves) · fuente ${sourceName(s[KEY_SOURCE] as? Int)}")
            s.toSortedMap().forEach { (k, v) -> add("  $k = ${describe(v)}") }
            return s
        }
        fun diff(title: String, a: Map<String, Any?>, b: Map<String, Any?>) {
            val changed = (a.keys + b.keys).sorted().filter { describe(a[it]) != describe(b[it]) }
            add("-- Cambios $title: ${if (changed.isEmpty()) "ninguno" else ""}")
            changed.forEach { add("  $it: ${describe(a[it])} → ${describe(b[it])}") }
        }
        val s0 = snap("Antes")
        val entered = enter(context)
        add("Entrar en la fuente Radio: ${if (entered) "SÍ" else "NO"}")
        val s1 = snapshot(car)
        diff("al entrar", s0, s1)
        tune(context, freq)
        SystemClock.sleep(2500)
        val s2 = snapshot(car)
        diff("al sintonizar ${RadioLogic.formatFm(freq)}", s1, s2)
        seekNext(context)
        SystemClock.sleep(5000)
        val s3 = snap("Tras buscar la siguiente emisora")
        diff("al buscar", s2, s3)
        val st = status(context)
        add("Resultado: fuente ${sourceName(st?.source)} · la MCU ${if (st?.mcuAnswered == true) "SÍ" else "NO"} da frecuencia" +
            (st?.freq?.let { " · ${RadioLogic.formatFm(it)} MHz" } ?: "") + (st?.rds?.let { " · RDS «$it»" } ?: ""))
        add("-- Órdenes y respuestas")
        addAll(trace.value)
    }

    private fun sysProp(key: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrDefault("?").ifBlank { "(vacía)" }
}
