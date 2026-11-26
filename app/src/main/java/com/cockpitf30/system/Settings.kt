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

    /** Fuente de datos que había antes de una demo sin terminar (null si no hay demo a medias). */
    var demoRestoreSource: String?
        get() = prefs.getString("demo_restore_source", null)
        set(value) = prefs.edit().putString("demo_restore_source", value).apply()

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

    /** Asistente de voz: escuchar «Hola Athos» mientras la app está en pantalla. */
    var assistantEnabled: Boolean
        get() = prefs.getBoolean(KEY_ASSISTANT, true)
        set(value) = prefs.edit().putBoolean(KEY_ASSISTANT, value).apply()

    /**
     * Volumen de la voz del asistente en % (10..150) sobre el volumen general: para equilibrarla
     * con Maps y la radio. Por encima de 100 % solo sube la voz TARS (se amplifica la señal).
     */
    var assistantVolume: Int
        get() = prefs.getInt("assistant_volume", 100)
        set(value) = prefs.edit().putInt("assistant_volume", value.coerceIn(10, 150)).apply()

    /** Responder también en voz alta (si la radio tiene motor de voz). */
    var assistantSpeaks: Boolean
        get() = prefs.getBoolean(KEY_ASSISTANT_TTS, true)
        set(value) = prefs.edit().putBoolean(KEY_ASSISTANT_TTS, value).apply()

    /** Al llegar a casa, «Ya estamos en casa» ([com.cockpitf30.nav.HomeArrival]). */
    var assistantHomeGreeting: Boolean
        get() = prefs.getBoolean(KEY_ASSISTANT_HOME_GREETING, true)
        set(value) = prefs.edit().putBoolean(KEY_ASSISTANT_HOME_GREETING, value).apply()

    /** Con Internet, la variante «network» de la voz de Google (más natural; entona las preguntas). */
    var assistantNetVoice: Boolean
        get() = prefs.getBoolean(KEY_ASSISTANT_NET_VOICE_V2, false)
        set(value) = prefs.edit().putBoolean(KEY_ASSISTANT_NET_VOICE_V2, value).apply()

    /** Velocidad de la voz en % sobre la del estilo elegido (50..200). */
    var assistantSpeechRate: Int
        get() = prefs.getInt(KEY_ASSISTANT_SPEECH_RATE, 100)
        set(value) = prefs.edit().putInt(KEY_ASSISTANT_SPEECH_RATE, value.coerceIn(50, 200)).apply()

    /** Tono de la voz en % sobre el del estilo elegido (50..150): menos = más grave. */
    var assistantPitch: Int
        get() = prefs.getInt(KEY_ASSISTANT_PITCH, 100)
        set(value) = prefs.edit().putInt(KEY_ASSISTANT_PITCH, value.coerceIn(50, 150)).apply()

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

    /** Sensibilidad de «Hola Athos»: 0 baja (sigla entera y muy clara), 1 normal, 2 alta (como antes). */
    var wakeSensitivity: Int
        get() = prefs.getInt("wake_sensitivity", 1)
        set(value) = prefs.edit().putInt("wake_sensitivity", value.coerceIn(0, 2)).apply()

    /** Tras responder, seguir escuchando unos segundos sin «Hola Athos» (conversación seguida). */
    var assistantFollowUp: Boolean
        get() = prefs.getBoolean("assistant_follow_up", true)
        set(value) = prefs.edit().putBoolean("assistant_follow_up", value).apply()

    /**
     * Callarse en cuanto oye a alguien hablar encima de su voz (experimental). Por defecto no: en la
     * radio su propia voz por los altavoces la cortaba sola. Se corta con «Hola Athoox», «para»,
     * tocando la tarjeta o con el botón de voz del volante.
     */
    var assistantVoiceOver: Boolean
        get() = prefs.getBoolean("assistant_voice_over", false)
        set(value) = prefs.edit().putBoolean("assistant_voice_over", value).apply()

    /** Las órdenes (lo que va tras «Hola Athos») con el reconocedor de Google, si la radio lo tiene. */
    var commandsWithGoogle: Boolean
        get() = prefs.getBoolean("commands_with_google", true)
        set(value) = prefs.edit().putBoolean("commands_with_google", value).apply()

    /** Segundos que sigue escuchando sin «Hola Athos» tras responder (antes 12 s fijos: demasiado). */
    var assistantFollowSeconds: Int
        get() = prefs.getInt("assistant_follow_seconds", 5)
        set(value) = prefs.edit().putInt("assistant_follow_seconds", value.coerceIn(2, 20)).apply()

    /** Modo copiloto: el asistente habla por su cuenta cada [copilotMinutes] (cada 2 min en demo). */
    var copilotEnabled: Boolean
        get() = prefs.getBoolean("copilot_enabled", true)
        set(value) = prefs.edit().putBoolean("copilot_enabled", value).apply()

    var copilotMinutes: Int
        get() = prefs.getInt("copilot_minutes", com.cockpitf30.assistant.Copilot.DEFAULT_MINUTES)
        set(value) = prefs.edit().putInt("copilot_minutes", value.coerceIn(2, 60)).apply()

    /** Nombre del conductor («me llamo Israel»). */
    var assistantUserName: String?
        get() = prefs.getString("assistant_user_name", null)
        set(value) = prefs.edit().putString("assistant_user_name", value).apply()

    /** Últimas frases dichas por el asistente, para no repetirlas (ver RecentLines). */
    var assistantRecentLines: String?
        get() = prefs.getString("assistant_recent_lines", null)
        set(value) = prefs.edit().putString("assistant_recent_lines", value).apply()

    /** Nombre de la voz del motor de la radio elegida en Diagnóstico; null = elegir sola. */
    var assistantVoiceName: String?
        get() = prefs.getString(KEY_ASSISTANT_VOICE, null)
        set(value) = prefs.edit().putString(KEY_ASSISTANT_VOICE, value).apply()

    /** Decir en voz alta «Radar a 400 metros». */
    var radarVoice: Boolean
        get() = prefs.getBoolean("radar_voice", true)
        set(value) = prefs.edit().putBoolean("radar_voice", value).apply()

    /** Interruptor general de los avisos en ruta (radares, seguridad, combustible, descanso y turismo). */
    var routeAlerts: Boolean
        get() = prefs.getBoolean("route_alerts", true)
        set(value) = prefs.edit().putBoolean("route_alerts", value).apply()

    /** Si se avisa de [c]: interruptor general y el de la categoría (radares = [radarVoice]). */
    fun alertOn(c: com.cockpitf30.nav.AlertCategory): Boolean = routeAlerts && alertCategory(c)

    fun alertCategory(c: com.cockpitf30.nav.AlertCategory): Boolean =
        if (c == com.cockpitf30.nav.AlertCategory.RADAR) radarVoice else prefs.getBoolean("alert_" + c.name.lowercase(), true)

    fun setAlertCategory(c: com.cockpitf30.nav.AlertCategory, on: Boolean) {
        if (c == com.cockpitf30.nav.AlertCategory.RADAR) radarVoice = on
        else prefs.edit().putBoolean("alert_" + c.name.lowercase(), on).apply()
    }

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

    /** Orden de los botones de la barra lateral (ids de SideArt), como el de las tarjetas. */
    var sidebarOrder: List<String>
        get() = prefs.getString("sidebar_order", "")!!.split(',').filter { it.isNotBlank() }
        set(value) = prefs.edit().putString("sidebar_order", value.joinToString(",")).apply()

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

    /** Trayectos terminados (ver TripHistory), en JSON. */
    var tripHistory: String?
        get() = prefs.getString("trip_history", null)
        set(value) = prefs.edit().putString("trip_history", value).apply()

    /**
     * Cuentakilómetros propio, en metros: la caja CAN no manda el del coche. Lo pone el conductor
     * una vez en Mantenimiento y la app le suma lo que recorre. null = sin poner.
     */
    var odometerM: Long?
        get() = prefs.getLong("odometer_m", -1L).takeIf { it >= 0 }
        set(value) = prefs.edit().putLong("odometer_m", value ?: -1L).apply()

    /** Última vez que se hizo [item]: km del cuentakilómetros y fecha. */
    fun serviceRecord(item: com.cockpitf30.vehicle.ServiceItem): com.cockpitf30.vehicle.ServiceRecord? {
        val k = "service_" + item.name.lowercase()
        val km = prefs.getLong(k + "_km", -1L).takeIf { it >= 0 }
        val at = prefs.getLong(k + "_at", -1L).takeIf { it > 0 }
        return if (km == null && at == null) null else com.cockpitf30.vehicle.ServiceRecord(km, at)
    }

    fun setServiceRecord(item: com.cockpitf30.vehicle.ServiceItem, r: com.cockpitf30.vehicle.ServiceRecord?) {
        val k = "service_" + item.name.lowercase()
        prefs.edit().putLong(k + "_km", r?.doneAtKm ?: -1L).putLong(k + "_at", r?.doneAtMillis ?: -1L).apply()
    }

    /** Cada cuántos km se hace [item] (el usuario puede cambiar el de fábrica). */
    fun serviceEveryKm(item: com.cockpitf30.vehicle.ServiceItem): Int? =
        item.everyKm?.let { prefs.getInt("service_" + item.name.lowercase() + "_every_km", it) }

    fun setServiceEveryKm(item: com.cockpitf30.vehicle.ServiceItem, km: Int) {
        prefs.edit().putInt("service_" + item.name.lowercase() + "_every_km", km.coerceIn(1_000, 60_000)).apply()
    }

    /** Fecha de la próxima ITV (día de la época), o null. */
    var itvDate: java.time.LocalDate?
        get() = prefs.getLong("itv_epoch_day", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let { java.time.LocalDate.ofEpochDay(it) }
        set(value) = prefs.edit().putLong("itv_epoch_day", value?.toEpochDay() ?: Long.MIN_VALUE).apply()

    /** Años entre ITV (2 hasta los 10 años del coche, luego 1). */
    var itvYears: Int
        get() = prefs.getInt("itv_years", 1)
        set(value) = prefs.edit().putInt("itv_years", value.coerceIn(1, 2)).apply()

    /** Sonido de Athos al arrancar (pantalla de inicio). */
    var startSound: Boolean
        get() = prefs.getBoolean("start_sound", true)
        set(value) = prefs.edit().putBoolean("start_sound", value).apply()

    /** Sonido de Athos al pasar a la vista Athos del cuadro del Inicio. */
    var athosViewSound: Boolean
        get() = prefs.getBoolean("athos_view_sound", true)
        set(value) = prefs.edit().putBoolean("athos_view_sound", value).apply()

    /** Ventana de resumen al parar el motor. */
    var tripSummary: Boolean
        get() = prefs.getBoolean("trip_summary", true)
        set(value) = prefs.edit().putBoolean("trip_summary", value).apply()

    /** Inicio del último viaje cuyo resumen ya se enseñó (para no repetirlo ni perderlo). */
    var lastSummaryStart: Long
        get() = prefs.getLong("last_summary_start", 0L)
        set(value) = prefs.edit().putLong("last_summary_start", value).apply()

    /** Flecha de cambio de marcha en el cuadro. */
    /** Con Maps u otra app delante, seguir escuchando «Hola Athos» (servicio en primer plano con micrófono). */
    var listenOutsideApp: Boolean
        get() = prefs.getBoolean("listen_outside_app", true)
        set(value) = prefs.edit().putBoolean("listen_outside_app", value).apply()

    /** Mini cockpit flotante (velocidad, marcha, asistente…) encima de Maps y de otras apps. */
    var miniCockpit: Boolean
        get() = prefs.getBoolean("mini_cockpit", true)
        set(value) = prefs.edit().putBoolean("mini_cockpit", value).apply()

    /** Posición del mini cockpit en la pantalla (px desde arriba a la izquierda; -1 = sin mover). */
    var miniCockpitPos: Pair<Int, Int>
        get() = prefs.getInt("mini_cockpit_x", -1) to prefs.getInt("mini_cockpit_y", -1)
        set(value) = prefs.edit().putInt("mini_cockpit_x", value.first).putInt("mini_cockpit_y", value.second).apply()

    /** Abrir Maps en pantalla dividida junto al cockpit (si la radio lo permite). */
    var splitScreenMaps: Boolean
        get() = prefs.getBoolean("split_screen_maps", false)
        set(value) = prefs.edit().putBoolean("split_screen_maps", value).apply()

    /** Mini cockpit plegado: solo velocidad y micrófono. */
    var miniCockpitCompact: Boolean
        get() = prefs.getBoolean("mini_cockpit_compact", false)
        set(value) = prefs.edit().putBoolean("mini_cockpit_compact", value).apply()

    var shiftIndicator: Boolean
        get() = prefs.getBoolean("shift_indicator", true)
        set(value) = prefs.edit().putBoolean("shift_indicator", value).apply()

    /** Saludo del asistente al arrancar (tiempo, autonomía, radares y mantenimiento). */
    var briefing: Boolean
        get() = prefs.getBoolean("briefing", true)
        set(value) = prefs.edit().putBoolean("briefing", value).apply()

    var lastBriefingAt: Long
        get() = prefs.getLong("last_briefing_at", 0L)
        set(value) = prefs.edit().putLong("last_briefing_at", value).apply()

    /** Halo de luz ambiente en los bordes de la pantalla. */
    var ambientLight: Boolean
        get() = prefs.getBoolean("ambient_light", true)
        set(value) = prefs.edit().putBoolean("ambient_light", value).apply()

    // ---- Radio
    /** Última fuente elegida (FM o INTERNET). */
    var radioSource: String?
        get() = prefs.getString("radio_source", null)
        set(value) = prefs.edit().putString("radio_source", value).apply()

    /** Última frecuencia FM (10 kHz). */
    var radioFmFreq: Int
        get() = prefs.getInt("radio_fm_freq", 9800)
        set(value) = prefs.edit().putInt("radio_fm_freq", value).apply()

    var radioFmFavorites: String?
        get() = prefs.getString("radio_fm_favorites", null)
        set(value) = prefs.edit().putString("radio_fm_favorites", value).apply()

    /** Favoritas de Internet (JSON). */
    var radioFavorites: String?
        get() = prefs.getString("radio_favorites", null)
        set(value) = prefs.edit().putString("radio_favorites", value).apply()

    var radioLastStation: String?
        get() = prefs.getString("radio_last_station", null)
        set(value) = prefs.edit().putString("radio_last_station", value).apply()

    /** Fuente de la última emisora que sonó (FM o INTERNET); null = nunca sonó ninguna. */
    var radioLastPlayed: String?
        get() = prefs.getString("radio_last_played", null)
        set(value) = prefs.edit().putString("radio_last_played", value).apply()

    /** Volver a poner la última emisora al arrancar la app. */
    var radioAutoPlay: Boolean
        get() = prefs.getBoolean("radio_auto_play", true)
        set(value) = prefs.edit().putBoolean("radio_auto_play", value).apply()

    /** Volumen propio de la radio (0–100), independiente del general. */
    var radioVolume: Int
        get() = prefs.getInt("radio_volume", com.cockpitf30.radio.RadioLogic.DEFAULT_VOLUME)
        set(value) = prefs.edit().putInt("radio_volume", value.coerceIn(0, 100)).apply()

    /** Bajar la radio (y otras apps) mientras el asistente escucha o habla. */
    var radioDuckAssistant: Boolean
        get() = prefs.getBoolean("radio_duck_assistant", true)
        set(value) = prefs.edit().putBoolean("radio_duck_assistant", value).apply()

    /** Bajar la radio con las indicaciones de Google Maps (ver radio.NavDuck). */
    var radioDuckNav: Boolean
        get() = prefs.getBoolean("radio_duck_nav", true)
        set(value) = prefs.edit().putBoolean("radio_duck_nav", value).apply()

    private companion object {
        const val KEY_CAN_ASK_SPEED = "can_ask_speed"
        const val KEY_ASSISTANT = "assistant_enabled"
        const val KEY_ASSISTANT_TTS = "assistant_tts"
        const val KEY_ASSISTANT_ROBOT = "assistant_robot_voice"
        const val KEY_ASSISTANT_NET_VOICE_V2 = "assistant_net_voice_v2"
        const val KEY_ASSISTANT_SPEECH_RATE = "assistant_speech_rate"
        const val KEY_ASSISTANT_PITCH = "assistant_pitch"
        const val KEY_ASSISTANT_HOME_GREETING = "assistant_home_greeting"
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
