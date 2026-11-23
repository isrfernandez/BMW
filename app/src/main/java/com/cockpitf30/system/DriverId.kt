package com.cockpitf30.system

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import com.cockpitf30.vehicle.source.CanBoxData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Quién conduce, al arrancar y sin tocar nada. De más a menos seguro:
 *  1. La red Wi-Fi del móvil del conductor (su punto de acceso): si la radio la ve o está conectada
 *     a ella, es él. No hace falta emparejar nada por Bluetooth.
 *  2. Su voz, la primera vez que dice «Hola Athoox» en el viaje ([VoicePrint]).
 *  3. Si no, el último que condujo, con un aviso en pantalla y «No soy yo» para cambiar.
 *
 * La llave del coche sería lo ideal, pero la caja Raise no manda ninguna trama con el número de
 * llave (solo 0x12, 0x16, 0x30, 0x43, 0x46 y 0x50). Por si acaso, se guarda una «huella de
 * arranque» con las tramas de cada encendido: arrancando con cada llave se ve en Diagnóstico si
 * algún byte cambia.
 */
object DriverId {
    enum class How(val text: String) { KEY("por tu llave"), WIFI("por tu móvil"), VOICE("por tu voz"), SAID("por tu nombre"), LAST("último conductor"), GUEST("modo invitado") }
    data class Greeting(val profileId: String, val name: String, val capi: Boolean, val how: How, val at: Long = System.currentTimeMillis())

    /** Aviso en pantalla de quién conduce (unos segundos tras arrancar o al reconocerlo). */
    val greeting = MutableStateFlow<Greeting?>(null)

    /** Ya se ha mirado la voz en este viaje: los pasajeros no cambian de conductor. */
    @Volatile var voiceChecked = false

    /** Alguien ha dicho quién es en este arranque: la red del móvil ya no cambia de conductor. */
    @Volatile var answered = false

    /** Pedir en pantalla el PIN de Zeus para entrar en su perfil (nombre del perfil). */
    val askPin = MutableStateFlow<String?>(null)

    /** Se ha preguntado «¿quién conduce?» y se espera respuesta. */
    @Volatile var asked = false

    private const val KEY_WIFI = "driver_wifi_"
    private const val KEY_STARTS = "start_frames"
    private var started = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cockpit", Context.MODE_PRIVATE)

    // ── Red Wi-Fi del móvil ─────────────────────────────────────────────

    fun wifi(ctx: Context, profileId: String): String? = prefs(ctx).getString(KEY_WIFI + profileId, null)
    fun setWifi(ctx: Context, profileId: String, ssid: String?) {
        prefs(ctx).edit().apply { if (ssid.isNullOrBlank()) remove(KEY_WIFI + profileId) else putString(KEY_WIFI + profileId, ssid) }.apply()
    }

    fun clean(ssid: String?): String? = ssid?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" && it != "0x" }

    /** La red a la que está conectada la radio ahora. */
    @SuppressLint("MissingPermission")
    fun connectedSsid(ctx: Context): String? = runCatching {
        @Suppress("DEPRECATION")
        clean((ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo?.ssid)
    }.getOrNull()

    /** Redes a la vista (la conectada primero). Con la ubicación encendida, como el GPS. */
    @SuppressLint("MissingPermission")
    fun visibleSsids(ctx: Context, scan: Boolean = false): List<String> {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return emptyList()
        @Suppress("DEPRECATION")
        if (scan) runCatching { wm.startScan() }
        val seen = runCatching { wm.scanResults.mapNotNull { clean(it.SSID) } }.getOrDefault(emptyList())
        return (listOfNotNull(connectedSsid(ctx)) + seen).distinct()
    }

    /** El perfil cuya red está a la vista. */
    fun byWifi(ctx: Context, visible: List<String>): Profile? {
        val set = visible.toSet()
        return Profiles.list(ctx).firstOrNull { p -> wifi(ctx, p.id)?.let { it in set } == true }
    }

    // ── Al arrancar ─────────────────────────────────────────────────────

    /**
     * Una vez por arranque de la app: saluda al último conductor y, en cuanto llegan las tramas de
     * la caja, mira la llave ([CarKey]). Si es la de otro conductor, pasa a su perfil y lo dice.
     * Ya no se pregunta ni se escucha la voz (en la radio cortaba y creaba usuarios por error).
     */
    fun onStart(context: Context, scope: CoroutineScope, ask: () -> Unit, identified: (Profile, How) -> Unit) {
        if (started) return
        started = true
        val ctx = context.applicationContext
        scope.launch {
            delay(3_000)
            Profiles.current(ctx).takeIf { it.name.isNotBlank() }?.let { greeting.value = Greeting(it.id, it.name, it.capi, How.LAST) }
            // Las tramas de ajustes llegan al encender; se espera hasta 25 s y un poco más para tenerlas todas.
            var w = 0L
            while (!CarKey.available() && w < 25_000) { delay(500); w += 500 }
            delay(2_000)
            keyNote = CarKey.describe()
            val hit = CarKey.identify(ctx)
            keyMatch = hit?.let { (p, s) -> "${p.name.ifBlank { "sin nombre" }} (${"%.0f".format(s * 100)} %)" } ?: "ningún conductor"
            if (hit != null && !answered) {
                val (p, score) = hit
                if (score >= 0.999) CarKey.reinforce(ctx, p.id, CarKey.current())
                if (p.id != Profiles.current(ctx).id) identified(p, How.KEY)
                else greeting.value = Greeting(p.id, p.name, p.capi, How.KEY)
            }
            delay(14_000)
            recordStart(ctx)
        }
    }

    /** Diagnóstico: tramas de la llave de este arranque y a quién se parecen. */
    @Volatile var keyNote: String? = null
    @Volatile var keyMatch: String? = null

    // ── Huella de arranque (para buscar la llave) ──────────────────────

    fun recordStart(ctx: Context) {
        val frames = listOf(0x12, 0x30, 0x46).mapNotNull { t -> CanBoxData.last(t) }
        if (frames.isEmpty()) return
        val o = JSONObject().put("at", System.currentTimeMillis()).put("who", Profiles.current(ctx).name).put("frames", JSONArray(frames))
        val old = runCatching { JSONArray(prefs(ctx).getString(KEY_STARTS, "[]")) }.getOrDefault(JSONArray())
        val out = JSONArray().put(o)
        for (i in 0 until minOf(old.length(), 9)) out.put(old.get(i))
        prefs(ctx).edit().putString(KEY_STARTS, out.toString()).apply()
    }

    /** Para Diagnóstico: «22/11 18:05 · Capi · 0x12 …». */
    fun starts(ctx: Context): List<String> = runCatching {
        val a = JSONArray(prefs(ctx).getString(KEY_STARTS, "[]"))
        val f = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("es", "ES"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val fr = o.getJSONArray("frames").let { j -> (0 until j.length()).joinToString("  ") { j.getString(it) } }
            "${f.format(java.util.Date(o.getLong("at")))} · ${o.optString("who").ifBlank { "?" }} · $fr"
        }
    }.getOrDefault(emptyList())
}
