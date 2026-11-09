package com.cockpitf30.system

import android.content.Context
import com.cockpitf30.input.IDriveInput
import com.cockpitf30.weather.Place

enum class SourceMode(val label: String) {
    AUTO("Auto"),
    RADIO("Radio CAN"),
    SERIAL("Serie"),
    CAR_PROPERTY("CarProperty"),
    DEMO("Demo"),
}

/** Ajustes persistentes de la app. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("cockpit", Context.MODE_PRIVATE)

    var sourceMode: SourceMode
        get() {
            // 0.9.20: el puerto serie no se puede abrir en esta radio; quien lo tenía elegido pasa a Auto
            // (que ahora usa los datos de la propia radio). Solo una vez.
            if (!prefs.getBoolean(KEY_MIGRATED_RADIO, false)) {
                val e = prefs.edit().putBoolean(KEY_MIGRATED_RADIO, true)
                if (prefs.getString(KEY_SOURCE, null) == SourceMode.SERIAL.name) e.putString(KEY_SOURCE, SourceMode.AUTO.name)
                e.apply()
            }
            return runCatching { SourceMode.valueOf(prefs.getString(KEY_SOURCE, null)!!) }.getOrDefault(SourceMode.AUTO)
        }
        set(value) = prefs.edit().putString(KEY_SOURCE, value.name).apply()

    var serialPath: String
        get() = prefs.getString(KEY_PATH, "/dev/ttyMT1")!!
        set(value) = prefs.edit().putString(KEY_PATH, value).apply()

    /** 38400 es el valor habitual de Raise, pero no está confirmado para esta placa. */
    var baudRate: Int
        get() = prefs.getInt(KEY_BAUD, 38400)
        set(value) = prefs.edit().putInt(KEY_BAUD, value).apply()

    /** Teclas del iDrive aprendidas en Diagnóstico (ver IDriveInput.learned). */
    var knobKeys: Map<Int, Int>
        get() = IDriveInput.decode(prefs.getString(KEY_KNOB_KEYS, null))
        set(value) = prefs.edit().putString(KEY_KNOB_KEYS, IDriveInput.encode(value)).apply()

    /** Asistente de voz: escuchar «Hola BMW» mientras la app está en pantalla. */
    var assistantEnabled: Boolean
        get() = prefs.getBoolean(KEY_ASSISTANT, true)
        set(value) = prefs.edit().putBoolean(KEY_ASSISTANT, value).apply()

    /** Responder también en voz alta (si la radio tiene motor de voz). */
    var assistantSpeaks: Boolean
        get() = prefs.getBoolean(KEY_ASSISTANT_TTS, true)
        set(value) = prefs.edit().putBoolean(KEY_ASSISTANT_TTS, value).apply()

    /** Voz al estilo TARS: hombre adulto, grave y pausada (false = voz normal de la radio, sin cambios). */
    var assistantRobotVoice: Boolean
        get() = prefs.getBoolean(KEY_ASSISTANT_ROBOT, true)
        set(value) = prefs.edit().putBoolean(KEY_ASSISTANT_ROBOT, value).apply()

    /** Estilo de la voz TARS: natural (por defecto), con timbre de máquina o robot. */
    var assistantRobotStrength: com.cockpitf30.assistant.RobotVoice.Strength
        get() = runCatching { com.cockpitf30.assistant.RobotVoice.Strength.valueOf(prefs.getString(KEY_ASSISTANT_STRENGTH, null)!!) }
            .getOrDefault(com.cockpitf30.assistant.RobotVoice.Strength.NATURAL)
        set(value) = prefs.edit().putString(KEY_ASSISTANT_STRENGTH, value.name).apply()

    /** Nivel de humor del asistente, 0..100 %. */
    var assistantHumor: Int
        get() = prefs.getInt(KEY_ASSISTANT_HUMOR, com.cockpitf30.assistant.Personality.DEFAULT_HUMOR)
        set(value) = prefs.edit().putInt(KEY_ASSISTANT_HUMOR, value.coerceIn(0, 100)).apply()

    /** Nombre de la voz del motor de la radio elegida en Diagnóstico; null = elegir sola. */
    var assistantVoiceName: String?
        get() = prefs.getString(KEY_ASSISTANT_VOICE, null)
        set(value) = prefs.edit().putString(KEY_ASSISTANT_VOICE, value).apply()

    /** Decir en voz alta «Radar a 400 metros». */
    var radarVoice: Boolean
        get() = prefs.getBoolean("radar_voice", true)
        set(value) = prefs.edit().putBoolean("radar_voice", value).apply()

    var askedContacts: Boolean
        get() = prefs.getBoolean("asked_contacts", false)
        set(value) = prefs.edit().putBoolean("asked_contacts", value).apply()

    var askedMicrophone: Boolean
        get() = prefs.getBoolean(KEY_ASKED_MIC, false)
        set(value) = prefs.edit().putBoolean(KEY_ASKED_MIC, value).apply()

    var askedBatteryExemption: Boolean
        get() = prefs.getBoolean(KEY_ASKED_BATTERY, false)
        set(value) = prefs.edit().putBoolean(KEY_ASKED_BATTERY, value).apply()

    /** Orden de las tarjetas del Inicio (ids separados por comas); vacío = orden por defecto. */
    var cardOrder: List<String>
        get() = prefs.getString(KEY_CARD_ORDER, "")!!.split(',').filter { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_CARD_ORDER, value.joinToString(",")).apply()

    /** Lugar elegido para el tiempo; null = usar la ubicación GPS. */
    var weatherPlace: Place?
        get() {
            val name = prefs.getString(KEY_W_NAME, null) ?: return null
            return Place(
                name, prefs.getString(KEY_W_REGION, null), prefs.getString(KEY_W_COUNTRY, null),
                prefs.getFloat(KEY_W_LAT, 0f).toDouble(), prefs.getFloat(KEY_W_LON, 0f).toDouble(),
            )
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    listOf(KEY_W_NAME, KEY_W_REGION, KEY_W_COUNTRY, KEY_W_LAT, KEY_W_LON).forEach(::remove)
                } else {
                    putString(KEY_W_NAME, value.name).putString(KEY_W_REGION, value.region).putString(KEY_W_COUNTRY, value.country)
                    putFloat(KEY_W_LAT, value.lat.toFloat()).putFloat(KEY_W_LON, value.lon.toFloat())
                }
            }.apply()
        }

    /** Últimas localidades con previsión (elegidas o por GPS), la más reciente primero. */
    val recentPlaces: List<Place>
        get() = runCatching {
            val arr = org.json.JSONArray(prefs.getString(KEY_W_RECENT, null) ?: return emptyList())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Place(o.getString("n"), o.optString("r").ifBlank { null }, o.optString("c").ifBlank { null }, o.getDouble("lat"), o.getDouble("lon"))
            }
        }.getOrDefault(emptyList())

    /** Apunta [place] al principio del registro; quita duplicados (mismo nombre o a menos de 2 km). */
    fun addRecentPlace(place: Place) {
        val list = (listOf(place) + recentPlaces.filterNot { it.name.equals(place.name, true) || near(it, place) }).take(RECENT_PLACES)
        val arr = org.json.JSONArray()
        list.forEach { arr.put(org.json.JSONObject().put("n", it.name).put("r", it.region ?: "").put("c", it.country ?: "").put("lat", it.lat).put("lon", it.lon)) }
        prefs.edit().putString(KEY_W_RECENT, arr.toString()).apply()
    }

    private fun near(a: Place, b: Place): Boolean {
        val dLat = (a.lat - b.lat) * 111.0
        val dLon = (a.lon - b.lon) * 111.0 * kotlin.math.cos(Math.toRadians(a.lat))
        return dLat * dLat + dLon * dLon < 4.0
    }

    /** Última previsión descargada, para mostrarla sin conexión. */
    var weatherCache: Triple<String, String, Long>?
        get() {
            val json = prefs.getString(KEY_W_JSON, null) ?: return null
            return Triple(json, prefs.getString(KEY_W_PLACE, "")!!, prefs.getLong(KEY_W_TIME, 0))
        }
        set(value) {
            if (value == null) return
            prefs.edit().putString(KEY_W_JSON, value.first).putString(KEY_W_PLACE, value.second).putLong(KEY_W_TIME, value.third).apply()
        }

    /** Disposición de la pantalla de apps (orden y grupos) en JSON. */
    var appsLayout: List<LayoutEntry>
        get() = AppLayout.fromJson(prefs.getString(KEY_APPS_LAYOUT, null))
        set(value) = prefs.edit().putString(KEY_APPS_LAYOUT, AppLayout.toJson(value)).apply()

    /** true = apps de la A a la Z; false = orden personalizado. */
    var appsAlphabetical: Boolean
        get() = prefs.getBoolean(KEY_APPS_AZ, false)
        set(value) = prefs.edit().putBoolean(KEY_APPS_AZ, value).apply()

    var askedLocation: Boolean
        get() = prefs.getBoolean(KEY_ASKED_LOCATION, false)
        set(value) = prefs.edit().putBoolean(KEY_ASKED_LOCATION, value).apply()

    /** Zona central del Inicio: true = mapa, false = coche (con una puerta abierta siempre el coche). */
    var homeMap: Boolean
        get() = prefs.getBoolean(KEY_HOME_MAP, true)
        set(value) = prefs.edit().putBoolean(KEY_HOME_MAP, value).apply()

    /** Viaje en curso y último viaje terminado, en JSON (ver Trip). */
    var tripCurrent: String?
        get() = prefs.getString(KEY_TRIP_CURRENT, null)
        set(value) = prefs.edit().putString(KEY_TRIP_CURRENT, value).apply()

    var tripLast: String?
        get() = prefs.getString(KEY_TRIP_LAST, null)
        set(value) = prefs.edit().putString(KEY_TRIP_LAST, value).apply()

    /** Capacidad del depósito para estimar el consumo: 57 l (F30 diésel). */
    var tankLiters: Float
        get() = prefs.getFloat(KEY_TANK, 57f)
        set(value) = prefs.edit().putFloat(KEY_TANK, value).apply()

    /** Pedir a la caja CAN la velocidad y las rpm cada 100 ms (la app de la radio lo hace cada 500 ms). */
    var canAskSpeed: Boolean
        get() = prefs.getBoolean(KEY_CAN_ASK_SPEED, true)
        set(value) = prefs.edit().putBoolean(KEY_CAN_ASK_SPEED, value).apply()

    private companion object {
        const val KEY_CAN_ASK_SPEED = "can_ask_speed"
        const val KEY_ASSISTANT = "assistant_enabled"
        const val KEY_ASSISTANT_TTS = "assistant_tts"
        const val KEY_ASSISTANT_ROBOT = "assistant_robot_voice"
        const val KEY_ASSISTANT_STRENGTH = "assistant_voice_style"
        const val KEY_ASSISTANT_HUMOR = "assistant_humor"
        const val KEY_ASSISTANT_VOICE = "assistant_voice_name"
        const val KEY_ASKED_MIC = "asked_microphone"
        const val KEY_TRIP_CURRENT = "trip_current"
        const val KEY_TRIP_LAST = "trip_last"
        const val KEY_TANK = "tank_liters"
        const val KEY_HOME_MAP = "home_map"
        const val KEY_APPS_LAYOUT = "apps_layout"
        const val KEY_APPS_AZ = "apps_az"
        const val KEY_CARD_ORDER = "card_order"
        const val KEY_W_NAME = "weather_place_name"
        const val KEY_W_REGION = "weather_place_region"
        const val KEY_W_COUNTRY = "weather_place_country"
        const val KEY_W_LAT = "weather_place_lat"
        const val KEY_W_LON = "weather_place_lon"
        const val KEY_W_RECENT = "weather_recent_places"
        const val RECENT_PLACES = 6
        const val KEY_W_JSON = "weather_cache_json"
        const val KEY_W_PLACE = "weather_cache_place"
        const val KEY_W_TIME = "weather_cache_time"
        const val KEY_ASKED_LOCATION = "asked_location"
        const val KEY_SOURCE = "source"
        const val KEY_MIGRATED_RADIO = "migrated_radio_0920"
        const val KEY_PATH = "serial_path"
        const val KEY_BAUD = "baud"
        const val KEY_ASKED_BATTERY = "asked_battery"
        const val KEY_KNOB_KEYS = "knob_keys"
    }
}
