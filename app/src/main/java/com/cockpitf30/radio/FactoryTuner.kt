package com.cockpitf30.radio

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.cockpitf30.vehicle.source.VendorCar

/**
 * Sintonizador FM de la propia radio, a través del servicio del coche del fabricante (el mismo
 * camino que usa la app de radio de serie de estas placas, android.car.app.radio, que en esta
 * unidad no viene instalada).
 *
 * Lo sacado del lanzador de serie (android.car.radio.RadioRequestImpl / RadioDefine):
 * - Peticiones "request.radio.*" con CarBaseManager2.requestSync/requestOneway. La frecuencia va en
 *   un Bundle {band, freq}; FM es la banda 0 y la frecuencia va en unidades de 10 kHz (10230).
 * - Estado en el almacén de datos: "data.radio.composite_main_freq" (banda<<24 | índice<<16 | freq),
 *   "data.radio.main_freq_0", la lista encontrada "data.radio.list_0_0" (Bundle count/freqN/nameN),
 *   "data.radio.rds_ps_text", "state.radio.seek_state".
 * - El audio del sintonizador lo conmuta la MCU al "entrar" en la fuente Radio:
 *   request.app.enter_app {app_id = 0x10000 (APP_ID_Radio), reason}.
 *
 * Nada de esto se ha podido probar en la radio: [probe] dice si el servicio contesta y si hay
 * claves de radio; si no, la app usa Internet.
 */
object FactoryTuner {
    private const val TAG = "FactoryTuner"
    const val APP_ID_RADIO = 0x10000
    const val BAND_FM = 0

    /** Resultado de la última comprobación, para enseñarlo en la pantalla de radio. */
    data class Probe(
        val available: Boolean,
        val reason: String,
        val keys: Map<String, Any?> = emptyMap(),
    )

    @Volatile var lastProbe: Probe? = null; private set

    private fun car(context: Context): VendorCar? = VendorCar.connect(context).getOrNull()

    /**
     * ¿Hay sintonizador? Se considera que sí si el servicio del coche responde y tiene alguna clave
     * de radio (data.radio.* / state.radio.* / info.radio.*), o si la app de radio de serie existe.
     */
    fun probe(context: Context): Probe {
        val radioApp = runCatching { context.packageManager.getApplicationInfo("android.car.app.radio", 0); true }.getOrDefault(false)
        val vc = VendorCar.connect(context)
        val car = vc.getOrNull()
        // También la mezcla de navegación: es lo que baja la FM cuando habla el navegador.
        val enabled = runCatching { car?.read(listOf("data.factory.app.enable_radio", "data.factory.canbus.enable_car_radio", "data.set.gps_")) }.getOrNull().orEmpty()
        val p = if (car == null) {
            Probe(false, "No se puede hablar con el servicio del coche: ${vc.exceptionOrNull()?.message ?: "sin librería"}")
        } else {
            val keys = runCatching { car.read(listOf("data.radio.", "state.radio.", "info.radio.")) }.getOrElse { emptyMap() } + enabled
            val hasRadioKeys = keys.keys.any { it.startsWith("data.radio.") || it.startsWith("state.radio.") || it.startsWith("info.radio.") }
            val factoryOff = keys["data.factory.app.enable_radio"] == 0
            when {
                factoryOff -> Probe(false, "La radio tiene el sintonizador desactivado de fábrica (enable_radio = 0)", keys)
                hasRadioKeys -> Probe(true, "Sintonizador detectado (${keys.size} datos de radio)" + if (radioApp) " · app de radio de serie presente" else "", keys)
                radioApp -> Probe(true, "App de radio de serie presente", keys)
                else -> Probe(false, "La radio no publica datos de sintonizador ni trae app de radio", keys)
            }
        }
        lastProbe = p
        Log.i(TAG, "probe: ${p.reason} ${p.keys}")
        return p
    }

    /** Frecuencia actual (10 kHz) y nombre RDS, si los da. */
    data class Status(val freq: Int?, val rds: String?, val seeking: Boolean, val found: List<FmStation>)

    fun status(context: Context): Status? {
        val car = car(context) ?: return null
        val k = runCatching { car.read(listOf("data.radio.", "state.radio.")) }.getOrNull() ?: return null
        val composite = (k["data.radio.composite_main_freq"] as? Int)?.let(RadioLogic::decodeComposite)
        val freq = (k["data.radio.main_freq_0"] as? Int)?.and(0xFFFF)?.takeIf { it in RadioLogic.FM_MIN..RadioLogic.FM_MAX }
            ?: composite?.takeIf { it.first == BAND_FM }?.second?.takeIf { it in RadioLogic.FM_MIN..RadioLogic.FM_MAX }
        val rds = (k["data.radio.rds_ps_text"] as? String ?: (k["state.radio.rds_ps_info"] as? Bundle)?.let { b -> b.keySet().firstNotNullOfOrNull { b.get(it) as? String } })
            ?.trim()?.takeIf { it.isNotBlank() }
        val seeking = ((k["state.radio.seek_state"] as? Int) ?: 0) != 0
        val list = (k["data.radio.list_0_0"] as? Bundle)?.let { b -> @Suppress("DEPRECATION") RadioLogic.parseSavedList { b.get(it) } }.orEmpty()
        return Status(freq, rds, seeking, list)
    }

    /** Pasa el audio a la radio (fuente Radio de la MCU) y pone la banda FM. */
    fun enter(context: Context): String {
        val car = car(context) ?: return "sin servicio"
        val r = runCatching { car.requestSync("request.app.enter_app", Bundle().apply { putInt("app_id", APP_ID_RADIO); putString("reason", context.packageName) }) }
        runCatching { car.requestSync("request.radio.switch_band_fm", null) }
        return "enter_app = ${r.getOrElse { "error ${it.message}" }}"
    }

    /** Deja la fuente Radio (para que suene Android: Internet, Spotify…). */
    fun exit(context: Context) {
        val car = car(context) ?: return
        runCatching { car.requestSync("request.app.exit_app", Bundle().apply { putInt("app_id", APP_ID_RADIO); putString("reason", context.packageName) }) }
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
        val car = car(context) ?: return false
        val b = args?.let { Bundle().apply(it) }
        return runCatching { if (sync) car.requestSync(key, b) else car.requestOneway(key, b); true }
            .onFailure { Log.w(TAG, key, it) }.getOrDefault(false)
    }
}
