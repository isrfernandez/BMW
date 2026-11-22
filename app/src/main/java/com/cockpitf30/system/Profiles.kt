package com.cockpitf30.system

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.UUID

/** Un conductor con sus ajustes guardados. */
data class Profile(
    val id: String,
    val name: String,
    /** El Capi: el primer usuario, el creador. Nadie puede borrarlo salvo él mismo con su PIN. */
    val capi: Boolean = false,
)

/** Resultado de intentar borrar la cuenta del Capi. */
enum class CapiDelete { OK, NOT_CAPI, NO_PIN, WRONG_PIN, LOCKED }

/** Qué pasó al decir «soy Luis». */
enum class ProfileSwitch {
    /** Ya era ese usuario. */
    SAME,
    /** Existía: se han cargado sus ajustes. */
    SWITCHED,
    /** No existía: perfil nuevo con los ajustes de fábrica. */
    CREATED,
    /** El perfil de ahora no tenía nombre: se le pone este (y se queda con sus ajustes). */
    NAMED,
}

/**
 * Usuarios del coche: cada conductor tiene sus propios ajustes (humor, voz, copiloto, avisos,
 * emisoras favoritas, tema, orden de tarjetas…) y Athoox recuerda quién condujo el último.
 *
 * Los ajustes siguen en el mismo sitio de siempre (SharedPreferences «cockpit»), así el resto de
 * la app no cambia: al cambiar de usuario, los ajustes personales del que se va se guardan en su
 * perfil y se ponen los del que llega. Lo del coche (viajes, kilómetros, mantenimiento, ITV,
 * fuente de datos) es común a todos.
 */
object Profiles {
    private const val KEY_LIST = "profiles"
    private const val KEY_CURRENT = "profile_current"
    private const val KEY_DATA = "profile_data_"
    const val KEY_NAME = "assistant_user_name"

    /** Ajustes de cada conductor. */
    private val PERSONAL = setOf(
        KEY_NAME, "parking_email", "assistant_humor", "assistant_voice_name", "assistant_robot_voice", "assistant_voice_style",
        "assistant_tts", "assistant_volume", "assistant_follow_up", "assistant_follow_seconds", "assistant_recent_lines",
        "commands_with_google", "wake_sensitivity", "copilot_enabled", "copilot_minutes", "briefing",
        "route_alerts", "radar_voice", "radio_favorites", "radio_fm_favorites", "radio_volume", "radio_last_station",
        "radio_auto_play", "card_order", "sidebar_order", "home_map", "drive_mode", "day_theme", "text_size",
        "auto_night", "night_headlights", "shift_indicator", "trip_summary", "start_sound", "athos_view_sound",
        "weather_recent_places",
    )
    private val PERSONAL_PREFIX = listOf("alert_", "weather_place_")

    fun isPersonal(key: String) = key in PERSONAL || PERSONAL_PREFIX.any { key.startsWith(it) }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cockpit", Context.MODE_PRIVATE)

    /** Todos los perfiles; crea el primero (con el nombre que ya hubiera) si aún no hay ninguno. */
    fun list(ctx: Context): List<Profile> = ensure(prefs(ctx)).first

    fun current(ctx: Context): Profile = ensure(prefs(ctx)).let { (all, cur) -> all.first { it.id == cur } }

    /** Para comparar nombres: sin tildes ni mayúsculas. */
    fun norm(name: String): String =
        Normalizer.normalize(name.trim().lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("\\s+"), " ")

    /** «Soy [name]»: pasa a su perfil, o lo crea si no existe. */
    fun switchTo(ctx: Context, name: String): ProfileSwitch {
        val p = prefs(ctx)
        val (all, curId) = ensure(p)
        val cur = all.first { it.id == curId }
        val wanted = norm(name)
        if (cur.name.isNotBlank() && norm(cur.name) == wanted) return ProfileSwitch.SAME
        if (cur.name.isBlank() && all.none { norm(it.name) == wanted }) {
            save(p, all.map { if (it.id == curId) it.copy(name = name) else it }, curId)
            p.edit().putString(KEY_NAME, name).apply()
            return ProfileSwitch.NAMED
        }
        // Se guarda lo del que se va.
        p.edit().putString(KEY_DATA + curId, snapshot(p).toString()).apply()
        val target = all.firstOrNull { norm(it.name) == wanted }
        return if (target != null) {
            restore(p, p.getString(KEY_DATA + target.id, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject())
            p.edit().putString(KEY_NAME, target.name).apply()
            save(p, all, target.id)
            ProfileSwitch.SWITCHED
        } else {
            val np = Profile(UUID.randomUUID().toString().take(8), name)
            restore(p, JSONObject()) // ajustes de fábrica
            p.edit().putString(KEY_NAME, name).apply()
            save(p, all + np, np.id)
            ProfileSwitch.CREATED
        }
    }

    /** Cómo le llama Athoox: el Capi es «Capi» (se llame como se llame su perfil); los demás, por su nombre. */
    fun callName(p: Profile): String? = if (p.capi) CAPI_CALL else p.name.ifBlank { null }
    const val CAPI_CALL = "Capi"

    /** Cambia el nombre del perfil actual (false si ya hay otro con ese nombre). */
    fun rename(ctx: Context, name: String): Boolean {
        val p = prefs(ctx)
        val (all, curId) = ensure(p)
        if (all.any { it.id != curId && norm(it.name) == norm(name) }) return false
        save(p, all.map { if (it.id == curId) it.copy(name = name) else it }, curId)
        p.edit().putString(KEY_NAME, name).apply()
        return true
    }

    /** Borra un perfil que no sea el actual ni el del Capi. */
    fun delete(ctx: Context, id: String): Boolean {
        val p = prefs(ctx)
        val (all, cur) = ensure(p)
        if (id == cur || all.none { it.id == id } || all.first { it.id == id }.capi) return false
        p.edit().remove(KEY_DATA + id).apply()
        VoicePrint.forget(ctx, id); DriverId.setWifi(ctx, id, null)
        save(p, all.filter { it.id != id }, cur)
        return true
    }

    fun capi(ctx: Context): Profile = list(ctx).first { it.capi }

    // ── PIN del Capi ─────────────────────────────────────────────────────
    // Se teclea en pantalla (nunca en voz alta). Se guarda con sal y PBKDF2, no el número. Tras
    // [MAX_FAILS] fallos se bloquea un rato, cada vez más largo.

    private const val KEY_PIN = "capi_pin"
    private const val KEY_FAILS = "capi_pin_fails"
    private const val KEY_LOCK = "capi_pin_lock_until"
    const val MAX_FAILS = 5
    private const val LOCK_MS = 5 * 60_000L

    fun hasPin(ctx: Context) = prefs(ctx).getString(KEY_PIN, null) != null

    fun validPin(pin: String) = pin.length in 4..8 && pin.all { it.isDigit() }

    /** Pone o cambia el PIN; solo el Capi y, si ya tenía uno, sabiendo el anterior. */
    fun setPin(ctx: Context, pin: String, oldPin: String? = null): Boolean {
        if (!current(ctx).capi || !validPin(pin)) return false
        if (hasPin(ctx) && (oldPin == null || checkPin(ctx, oldPin) != null)) return false
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        prefs(ctx).edit().putString(KEY_PIN, b64(salt) + ":" + b64(hash(pin, salt))).putInt(KEY_FAILS, 0).apply()
        return true
    }

    /** Milisegundos de bloqueo que quedan (0 si se puede probar). */
    fun lockLeftMs(ctx: Context) = (prefs(ctx).getLong(KEY_LOCK, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    /** null si el PIN es bueno; si no, por qué no. */
    fun checkPin(ctx: Context, pin: String): CapiDelete? {
        val p = prefs(ctx)
        val stored = p.getString(KEY_PIN, null) ?: return CapiDelete.NO_PIN
        if (lockLeftMs(ctx) > 0) return CapiDelete.LOCKED
        val (salt, h) = stored.split(':').let { b64d(it[0]) to b64d(it[1]) }
        if (java.security.MessageDigest.isEqual(hash(pin, salt), h)) { p.edit().putInt(KEY_FAILS, 0).apply(); return null }
        val fails = p.getInt(KEY_FAILS, 0) + 1
        val e = p.edit().putInt(KEY_FAILS, fails)
        if (fails % MAX_FAILS == 0) e.putLong(KEY_LOCK, System.currentTimeMillis() + LOCK_MS * (fails / MAX_FAILS))
        e.apply()
        return if (fails % MAX_FAILS == 0) CapiDelete.LOCKED else CapiDelete.WRONG_PIN
    }

    /**
     * El Capi borra su propia cuenta con su PIN. Si quedan otros conductores, el rol pasa al más
     * antiguo y se carga su perfil; si no queda nadie, se empieza de cero y el próximo que diga su
     * nombre será el Capi.
     */
    fun deleteCapi(ctx: Context, pin: String): CapiDelete {
        val p = prefs(ctx)
        val (all, cur) = ensure(p)
        val me = all.first { it.id == cur }
        if (!me.capi) return CapiDelete.NOT_CAPI
        checkPin(ctx, pin)?.let { return it }
        val rest = all.filter { it.id != cur }
        p.edit().remove(KEY_DATA + cur).remove(KEY_PIN).remove(KEY_FAILS).remove(KEY_LOCK).apply()
        PhoneKey.forget(ctx)
        VoicePrint.forget(ctx, cur); DriverId.setWifi(ctx, cur, null)
        if (rest.isEmpty()) {
            restore(p, JSONObject())
            save(p, listOf(Profile("p" + UUID.randomUUID().toString().take(6), "", capi = true)), "")
            ensure(p)
        } else {
            val heir = rest.first()
            restore(p, p.getString(KEY_DATA + heir.id, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject())
            p.edit().putString(KEY_NAME, heir.name).remove(KEY_DATA + heir.id).apply()
            save(p, rest.map { it.copy(capi = it.id == heir.id) }, heir.id)
        }
        return CapiDelete.OK
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, 20_000, 256)
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
    private fun b64(b: ByteArray) = java.util.Base64.getEncoder().encodeToString(b)
    private fun b64d(s: String) = java.util.Base64.getDecoder().decode(s)

    /** Ajustes personales guardados de un perfil (los del actual, en vivo). */
    fun values(ctx: Context, id: String): Map<String, Any?> {
        val p = prefs(ctx)
        val (_, cur) = ensure(p)
        if (id == cur) return p.all.filterKeys { isPersonal(it) }
        val o = p.getString(KEY_DATA + id, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
        return o.keys().asSequence().associateWith { k -> o.optJSONObject(k)?.opt("v") }
    }

    /** Busca un perfil por nombre (sin tildes ni mayúsculas). */
    fun find(ctx: Context, name: String): Profile? = list(ctx).firstOrNull { norm(it.name) == norm(name) }

    /**
     * Las preferencias de un perfil en palabras, para la pantalla de usuarios: (qué, valor). Lo que
     * no esté guardado vale lo de fábrica (los mismos valores por defecto que [AppSettings]).
     */
    fun summary(v: Map<String, Any?>): List<Pair<String, String>> {
        fun b(k: String, def: Boolean) = (v[k] as? Boolean) ?: def
        fun i(k: String, def: Int) = (v[k] as? Number)?.toInt() ?: def
        fun s(k: String) = v[k] as? String
        fun count(k: String) = s(k)?.let { runCatching { JSONArray(it).length() }.getOrNull() } ?: 0
        val alertsOff = com.cockpitf30.nav.AlertCategory.entries.filter { c ->
            if (c == com.cockpitf30.nav.AlertCategory.RADAR) !b("radar_voice", true) else !b("alert_" + c.name.lowercase(), true)
        }
        val alerts = when {
            !b("route_alerts", true) -> "Desactivados"
            alertsOff.isEmpty() -> "Todos"
            else -> "Todos menos " + alertsOff.joinToString(", ") { it.label.lowercase() }
        }
        val theme = when (s("day_theme")) { "ON" -> "Diurno"; "OFF" -> "Oscuro"; else -> "Automático" }
        val text = when (s("text_size")) { "NORMAL" -> "Normal"; "XLARGE" -> "Muy grande"; else -> "Grande" }
        val copilot = if (b("copilot_enabled", true)) "Cada ${i("copilot_minutes", com.cockpitf30.assistant.Copilot.DEFAULT_MINUTES)} min" else "Desactivado"
        val voice = if (b("assistant_robot_voice", true)) "Robot" else "Natural"
        val radio = count("radio_favorites") + count("radio_fm_favorites")
        return listOf(
            "Humor" to "${i("assistant_humor", com.cockpitf30.assistant.Personality.DEFAULT_HUMOR)} %",
            "Copiloto" to copilot,
            "Voz" to "$voice · volumen ${i("assistant_volume", 100)} %",
            "Briefing al arrancar" to if (b("briefing", true)) "Sí" else "No",
            "Avisos en ruta" to alerts,
            "Pantalla" to "$theme · letra $text",
            "Emisoras favoritas" to if (radio == 0) "Ninguna" else "$radio",
            "Correo del coche aparcado" to (s("parking_email")?.takeIf { it.isNotBlank() } ?: "Ninguno"),
        )
    }

    private fun ensure(p: SharedPreferences): Pair<List<Profile>, String> {
        val raw = p.getString(KEY_LIST, null)
        val all = raw?.let { s ->
            runCatching {
                val a = JSONArray(s)
                (0 until a.length()).map { a.getJSONObject(it).let { o -> Profile(o.getString("id"), o.optString("name"), o.optBoolean("capi")) } }
            }.getOrNull()
        }.orEmpty().let { list ->
            // Siempre hay un Capi: el primer usuario (también en los perfiles de antes de existir el rol).
            if (list.isNotEmpty() && list.none { it.capi }) list.mapIndexed { i, pr -> pr.copy(capi = i == 0) }.also { save(p, it, p.getString(KEY_CURRENT, null) ?: it.first().id) } else list
        }
        val cur = p.getString(KEY_CURRENT, null)
        if (all.isNotEmpty() && all.any { it.id == cur }) return all to cur!!
        if (all.isNotEmpty()) { save(p, all, all.first().id); return all to all.first().id }
        // Primera vez: el perfil de quien ya usaba la app, con el nombre que hubiera dicho. Es el Capi.
        val first = Profile("p1", p.getString(KEY_NAME, null).orEmpty(), capi = true)
        save(p, listOf(first), first.id)
        return listOf(first) to first.id
    }

    private fun save(p: SharedPreferences, all: List<Profile>, current: String) {
        val a = JSONArray()
        all.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("capi", it.capi)) }
        p.edit().putString(KEY_LIST, a.toString()).putString(KEY_CURRENT, current).apply()
    }

    /** Los ajustes personales de ahora, con su tipo. */
    internal fun snapshot(p: SharedPreferences): JSONObject {
        val o = JSONObject()
        p.all.forEach { (k, v) ->
            if (!isPersonal(k) || v == null) return@forEach
            val t = when (v) {
                is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"
                else -> return@forEach
            }
            o.put(k, JSONObject().put("t", t).put("v", v))
        }
        return o
    }

    /** Quita todos los ajustes personales y pone los de [data]. */
    internal fun restore(p: SharedPreferences, data: JSONObject) {
        val e = p.edit()
        p.all.keys.filter { isPersonal(it) }.forEach { e.remove(it) }
        data.keys().forEach { k ->
            val o = data.optJSONObject(k) ?: return@forEach
            when (o.optString("t")) {
                "b" -> e.putBoolean(k, o.getBoolean("v"))
                "i" -> e.putInt(k, o.getInt("v"))
                "l" -> e.putLong(k, o.getLong("v"))
                "f" -> e.putFloat(k, o.getDouble("v").toFloat())
                "s" -> e.putString(k, o.getString("v"))
            }
        }
        e.commit()
    }
}
