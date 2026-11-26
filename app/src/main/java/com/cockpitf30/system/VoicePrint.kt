package com.cockpitf30.system

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * Huella de voz de cada conductor, con el modelo de locutor de Vosk (sin Internet). Cada frase
 * reconocida trae un vector «spk» que resume cómo suena quien habla; la huella guardada es la
 * media de varias frases del conductor y se compara por coseno.
 *
 * Sirve para elegir conductor, no como cerradura: falla con ruido o la voz tomada y una grabación
 * la engaña. Por eso borrar la cuenta del Capi sigue pidiendo PIN y móvil.
 */
object VoicePrint {
    /** Desde aquí se da por hecho que es esa persona. */
    const val MATCH = 0.62f
    /** Para aceptar el alias del Capi con una frase corta («soy Isra»): algo menos que [MATCH]. */
    const val VERIFY = 0.55f
    /** Por debajo de esto, seguro que no es. */
    const val MISMATCH = 0.35f
    /** Ventaja mínima sobre el segundo más parecido. */
    const val MARGIN = 0.08f
    /** Frases para aprender una voz. */
    const val SAMPLES = 4
    /** Tramas de 10 ms: con menos de ~0,45 s de voz el vector no vale. */
    const val MIN_FRAMES = 45
    private const val KEY = "voice_print_"

    data class Heard(val vector: FloatArray, val frames: Int, val at: Long)

    /** Lo último que se ha oído con huella. */
    @Volatile var last: Heard? = null
        private set

    /** Aprendiendo la voz de un perfil: cuántas frases faltan. */
    data class Enroll(val profileId: String, val name: String, val got: List<FloatArray> = emptyList())
    val enrolling = MutableStateFlow<Enroll?>(null)

    /** Última comparación, para enseñarla en Ajustes y calibrar. */
    val lastScore = MutableStateFlow<Pair<String, Float>?>(null)

    fun vector(json: JSONObject): Heard? {
        val a = json.optJSONArray("spk") ?: return null
        val frames = json.optInt("spk_frames", 0)
        if (frames < MIN_FRAMES || a.length() == 0) return null
        return Heard(FloatArray(a.length()) { a.optDouble(it).toFloat() }, frames, System.currentTimeMillis())
    }

    /** Desde el bucle del micrófono, con cada frase terminada. */
    fun heard(json: JSONObject) { vector(json)?.let { last = it } }

    fun recent(maxAgeMs: Long = 8_000L, now: Long = System.currentTimeMillis()): Heard? = last?.takeIf { now - it.at <= maxAgeMs }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0f else (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    /** Media de vectores normalizados. */
    fun average(list: List<FloatArray>): FloatArray {
        val n = list.first().size
        val out = FloatArray(n)
        list.forEach { v ->
            val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-6f)
            for (i in 0 until n) out[i] += v[i] / norm
        }
        for (i in 0 until n) out[i] /= list.size
        return out
    }

    /** Quién es entre [prints]: el más parecido si pasa [MATCH] y saca [MARGIN] al siguiente. */
    fun best(v: FloatArray, prints: Map<String, FloatArray>): Pair<String, Float>? {
        val scored = prints.map { (id, p) -> id to cosine(v, p) }.sortedByDescending { it.second }
        val top = scored.firstOrNull() ?: return null
        val second = scored.getOrNull(1)?.second ?: -1f
        return top.takeIf { it.second >= MATCH && it.second - second >= MARGIN }
    }

    // ── Guardado (del coche, por id de perfil) ───────────────────────────

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cockpit", Context.MODE_PRIVATE)

    fun stored(ctx: Context, profileId: String): FloatArray? =
        prefs(ctx).getString(KEY + profileId, null)?.let { s -> runCatching { s.split(',').map { it.toFloat() }.toFloatArray() }.getOrNull() }

    fun has(ctx: Context, profileId: String) = stored(ctx, profileId) != null

    fun all(ctx: Context): Map<String, FloatArray> =
        Profiles.list(ctx).mapNotNull { p -> stored(ctx, p.id)?.let { p.id to it } }.toMap()

    fun save(ctx: Context, profileId: String, v: FloatArray) {
        prefs(ctx).edit().putString(KEY + profileId, v.joinToString(",") { "%.5f".format(java.util.Locale.US, it) }).apply()
    }

    fun forget(ctx: Context, profileId: String) { prefs(ctx).edit().remove(KEY + profileId).apply() }

    /** Compara con la huella de un perfil (null si no tiene o no hay voz reciente). */
    fun score(ctx: Context, profileId: String, heard: Heard? = recent()): Float? {
        val p = stored(ctx, profileId) ?: return null
        return cosine(heard?.vector ?: return null, p).also { lastScore.value = profileId to it }
    }

    /** Quién habla, entre los perfiles con huella. */
    fun identify(ctx: Context, heard: Heard? = recent()): Pair<Profile, Float>? {
        val v = heard?.vector ?: return null
        val (id, s) = best(v, all(ctx)) ?: return null
        lastScore.value = id to s
        return Profiles.list(ctx).firstOrNull { it.id == id }?.let { it to s }
    }

    // ── Aprender una voz ─────────────────────────────────────────────────

    fun startEnroll(profile: Profile) { enrolling.value = Enroll(profile.id, profile.name) }
    fun cancelEnroll() { enrolling.value = null }

    /** Una frase más para la huella. Devuelve las que faltan (0 = guardada). */
    fun enroll(ctx: Context, heard: Heard): Int {
        val e = enrolling.value ?: return -1
        val got = e.got + heard.vector
        if (got.size < SAMPLES) { enrolling.value = e.copy(got = got); return SAMPLES - got.size }
        save(ctx, e.profileId, average(got))
        enrolling.value = null
        return 0
    }
}
