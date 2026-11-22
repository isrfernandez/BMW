package com.cockpitf30.system

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.UUID

/** Un conductor con sus ajustes guardados. */
data class Profile(val id: String, val name: String)

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
        KEY_NAME, "assistant_humor", "assistant_voice_name", "assistant_robot_voice", "assistant_voice_style",
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

    /** Borra un perfil que no sea el actual. */
    fun delete(ctx: Context, id: String): Boolean {
        val p = prefs(ctx)
        val (all, cur) = ensure(p)
        if (id == cur || all.none { it.id == id }) return false
        p.edit().remove(KEY_DATA + id).apply()
        save(p, all.filter { it.id != id }, cur)
        return true
    }

    private fun ensure(p: SharedPreferences): Pair<List<Profile>, String> {
        val raw = p.getString(KEY_LIST, null)
        val all = raw?.let { s ->
            runCatching {
                val a = JSONArray(s)
                (0 until a.length()).map { a.getJSONObject(it).let { o -> Profile(o.getString("id"), o.optString("name")) } }
            }.getOrNull()
        }.orEmpty()
        val cur = p.getString(KEY_CURRENT, null)
        if (all.isNotEmpty() && all.any { it.id == cur }) return all to cur!!
        if (all.isNotEmpty()) { save(p, all, all.first().id); return all to all.first().id }
        // Primera vez: el perfil de quien ya usaba la app, con el nombre que hubiera dicho.
        val first = Profile("p1", p.getString(KEY_NAME, null).orEmpty())
        save(p, listOf(first), first.id)
        return listOf(first) to first.id
    }

    private fun save(p: SharedPreferences, all: List<Profile>, current: String) {
        val a = JSONArray()
        all.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name)) }
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
