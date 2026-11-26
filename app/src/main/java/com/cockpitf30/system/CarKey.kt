package com.cockpitf30.system

import android.content.Context
import com.cockpitf30.vehicle.source.CanBoxData
import org.json.JSONArray
import org.json.JSONObject

/**
 * Quién conduce según la llave con la que se ha arrancado (una llave = un conductor).
 *
 * La caja CAN no da el número de llave como tal, pero al encender manda unas tramas (0x12 y las
 * de ajustes 0x43–0x47) en las que cambia algún byte según la llave. No sabemos de antemano cuál:
 * se aprende. Cada conductor guarda la «huella» de su llave al pulsar «Asociar la llave de este
 * arranque» en Ajustes › Usuarios (y se refuerza en cada arranque reconocido). Al arrancar se
 * comparan solo los bytes que distinguen unas llaves de otras y que no cambian entre arranques
 * de la misma llave.
 */
object CarKey {
    /** Tramas que se miran (0x30 es la versión de la caja y 0x16/0x50 cambian sin parar). */
    val TYPES = listOf(0x12, 0x43, 0x44, 0x45, 0x46, 0x47)
    private const val KEY = "carkey_"
    private const val MAX_SAMPLES = 6

    /** Huella: posición («46:2») → valor del byte. */
    fun parse(frames: Map<Int, String>): Map<String, Int> {
        val out = HashMap<String, Int>()
        for ((t, hex) in frames) {
            hex.trim().split(' ').filter { it.isNotBlank() }.forEachIndexed { i, b ->
                if (i > 0) b.toIntOrNull(16)?.let { out["%02X:%d".format(t, i)] = it }
            }
        }
        return out
    }

    /** La huella de este arranque (vacía si aún no han llegado las tramas). */
    fun current(): Map<String, Int> = parse(TYPES.mapNotNull { t -> CanBoxData.last(t)?.let { t to it } }.toMap())

    /** Hay tramas para mirar la llave. */
    fun available(): Boolean = TYPES.any { CanBoxData.last(it) != null }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cockpit", Context.MODE_PRIVATE)

    fun samples(ctx: Context, profileId: String): List<Map<String, Int>> = runCatching {
        val a = JSONArray(prefs(ctx).getString(KEY + profileId, "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o -> o.keys().asSequence().associateWith { o.getInt(it) } } }
    }.getOrDefault(emptyList())

    fun has(ctx: Context, profileId: String) = samples(ctx, profileId).isNotEmpty()

    /** «Asociar la llave de este arranque»: empieza de cero con la huella de ahora. Quita esa llave a los demás. */
    fun associate(ctx: Context, profileId: String): Boolean {
        val fp = current()
        if (fp.isEmpty()) return false
        // La misma llave no puede ser de dos conductores.
        Profiles.list(ctx).filter { it.id != profileId }.forEach { p -> if (identify(listOf(p.id to samples(ctx, p.id)), fp)?.first == p.id) forget(ctx, p.id) }
        save(ctx, profileId, listOf(fp))
        return true
    }

    /** Un arranque reconocido sin dudas: se añade para aprender qué bytes son estables. */
    fun reinforce(ctx: Context, profileId: String, fp: Map<String, Int>) {
        if (fp.isEmpty()) return
        save(ctx, profileId, (samples(ctx, profileId) + listOf(fp)).takeLast(MAX_SAMPLES))
    }

    fun forget(ctx: Context, profileId: String) { prefs(ctx).edit().remove(KEY + profileId).apply() }

    private fun save(ctx: Context, profileId: String, list: List<Map<String, Int>>) {
        val a = JSONArray()
        list.forEach { m -> a.put(JSONObject().apply { m.forEach { (k, v) -> put(k, v) } }) }
        prefs(ctx).edit().putString(KEY + profileId, a.toString()).apply()
    }

    /** El conductor de esta llave y con qué seguridad (1 = todos los bytes que distinguen coinciden). */
    fun identify(ctx: Context): Pair<Profile, Double>? {
        val fp = current()
        if (fp.isEmpty()) return null
        val all = Profiles.list(ctx)
        val hit = identify(all.map { it.id to samples(ctx, it.id) }.filter { it.second.isNotEmpty() }, fp) ?: return null
        return all.firstOrNull { it.id == hit.first }?.let { it to hit.second }
    }

    /** Bytes que no cambian entre los arranques guardados de una llave. */
    internal fun stable(samples: List<Map<String, Int>>): Map<String, Int> {
        if (samples.isEmpty()) return emptyMap()
        return samples.first().filter { (k, v) -> samples.all { it[k] == v } }
    }

    /** Lógica pura (tests): [keys] = (perfil, huellas guardadas). */
    internal fun identify(keys: List<Pair<String, List<Map<String, Int>>>>, fp: Map<String, Int>): Pair<String, Double>? {
        val st = keys.map { (id, s) -> id to stable(s) }.filter { it.second.isNotEmpty() }
        if (st.isEmpty()) return null
        if (st.size == 1) {
            // Una sola llave guardada: tiene que coincidir casi todo lo estable.
            val (id, m) = st.first()
            val common = m.keys.filter { it in fp }
            if (common.size < 3) return null
            val ok = common.count { fp[it] == m[it] }.toDouble() / common.size
            return if (ok >= 0.95) id to ok else null
        }
        // Varias: solo cuentan los bytes estables en todas que tienen valores distintos entre llaves.
        val shared = st.map { it.second.keys }.reduce { a, b -> a intersect b }.filter { it in fp }
        val distinct = shared.filter { k -> st.map { it.second[k] }.toSet().size > 1 }
        if (distinct.isEmpty()) return null
        val scores = st.map { (id, m) -> id to distinct.count { fp[it] == m[it] }.toDouble() / distinct.size }.sortedByDescending { it.second }
        val best = scores[0]
        return if (best.second >= 0.75 && best.second > scores[1].second) best else null
    }

    /** Para Diagnóstico: las tramas de la llave de este arranque. */
    fun describe(): String = TYPES.mapNotNull { t -> CanBoxData.last(t) }.joinToString("  ").ifBlank { "sin tramas de la caja todavía" }
}
