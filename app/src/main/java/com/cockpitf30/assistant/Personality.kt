package com.cockpitf30.assistant

import java.time.LocalTime
import kotlin.random.Random

/**
 * Lo último que ha dicho el asistente, para no repetirse: al elegir una frase se descartan las
 * usadas hace poco y, si ya se han dicho todas, se toma la que lleva más tiempo sin decirse.
 * Se guarda en Ajustes ([save]/[load]) para que tampoco se repita al reiniciar la app.
 */
class RecentLines(private val capacity: Int = 150) {
    private val used = ArrayDeque<String>()

    fun choose(candidates: List<String>, random: Random = Random.Default): String? {
        if (candidates.isEmpty()) return null
        val fresh = candidates.filter { it !in used }
        val pick = if (fresh.isNotEmpty()) fresh[random.nextInt(fresh.size)]
        else candidates.minBy { used.indexOf(it) } // la más antigua de las ya dichas
        mark(pick)
        return pick
    }

    fun mark(line: String) {
        used.remove(line)
        used.addLast(line)
        while (used.size > capacity) used.removeFirst()
    }

    fun contains(line: String) = line in used

    fun save(): String = used.joinToString(SEP)

    fun load(saved: String?) {
        used.clear()
        saved?.split(SEP)?.filter { it.isNotBlank() }?.takeLast(capacity)?.forEach { used.addLast(it) }
    }

    fun clear() = used.clear()

    private companion object { const val SEP = "\u0001" }
}

/**
 * Carácter del asistente, al estilo del robot TARS: respuestas secas, con un nivel de humor
 * ajustable de 0 a 100 % («Hola BMW, humor al 60 por ciento»). Las frases están en [Lines].
 */
object Personality {
    const val DEFAULT_HUMOR = 75

    /** Frases dichas hace poco (compartido por todas las claves). */
    val recent = RecentLines()

    /** Nombre del conductor, si lo ha dicho («me llamo Israel»). */
    var userName: String? = null

    /** Probabilidad de que una respuesta lleve broma: 0 % nunca, 100 % siempre (75 % → ~2 de cada 3). */
    fun jokeChance(humor: Int): Double {
        val h = humor.coerceIn(0, 100) / 100.0
        return h * kotlin.math.sqrt(h)
    }

    /** Rellena {name}, {greet} y {humor}; null si la frase pide un nombre que no sabemos. */
    fun fill(line: String, humor: Int = DEFAULT_HUMOR, now: LocalTime = LocalTime.now()): String? {
        if ("{name}" in line && userName.isNullOrBlank()) return null
        return line.replace("{name}", userName.orEmpty())
            .replace("{greet}", greeting(now))
            .replace("{humor}", humor.toString())
    }

    fun greeting(now: LocalTime) = when (now.hour) {
        in 6..13 -> "Buenos días"
        in 14..20 -> "Buenas tardes"
        else -> "Buenas noches"
    }

    /** Una frase de [candidates] que no se haya dicho hace poco (ya rellenada). */
    fun choose(candidates: List<String>, humor: Int = DEFAULT_HUMOR, random: Random = Random.Default): String? {
        val usable = candidates.filter { "{name}" !in it || !userName.isNullOrBlank() }
        val raw = recent.choose(usable, random) ?: return null
        return fill(raw, humor)
    }

    /** Respuesta sin broma de [Lines.PLAIN] para [key] (o [key] tal cual si no existe). */
    fun line(key: String, random: Random = Random.Default): String =
        Lines.PLAIN[key]?.let { choose(it, random = random) } ?: key

    /** Frase de charla de [Lines.CHAT]; con poco humor, la versión «.serio» si la hay. */
    fun chat(key: String, humor: Int, random: Random = Random.Default): String {
        val list = (if (humor < 30) Lines.CHAT["$key.serio"] else null) ?: Lines.CHAT[key] ?: return key
        return choose(list, humor, random) ?: key
    }

    /** Un chiste que no se haya contado hace poco. */
    fun joke(random: Random = Random.Default): String = choose(Lines.JOKES, random = random)!!

    /**
     * Con humor, añade a [plain] una broma de la clave [key] de [Lines.QUIPS] (más [extra]); a veces,
     * una genérica. Una broma que empiece por «=» sustituye a [plain] en vez de añadirse.
     */
    fun spice(plain: String, humor: Int, key: String?, vararg extra: String, random: Random = Random.Default): String {
        if (random.nextDouble() >= jokeChance(humor)) return plain
        val specific = (key?.let { Lines.QUIPS[it] }.orEmpty()) + extra
        val pool = if (key != null && (specific.isEmpty() || random.nextDouble() < GENERIC_SHARE)) specific + Lines.GENERIC else specific
        val q = choose(pool, humor, random) ?: return plain
        return if (q.startsWith("=")) q.drop(1) else "${plain.trimEnd('.')}. $q"
    }

    /** Parte de las bromas que salen del montón genérico en vez del de cada tipo de respuesta. */
    private const val GENERIC_SHARE = 0.2

    fun humorReply(level: Int, random: Random = Random.Default): String = when {
        level <= 0 -> choose(listOf(
            "Humor al cero por ciento. Seré tan divertido como un manual de instrucciones.",
            "Humor al cero por ciento. A partir de ahora, solo datos.",
        ), random = random)!!
        level < 40 -> "Humor al $level por ciento. " + choose(listOf("Modo serio.", "Poca broma.", "Seré breve."), random = random)
        level < 90 -> "Humor al $level por ciento. " + choose(listOf("Confirmado.", "Ajustado.", "Me parece razonable."), random = random)
        else -> "Humor al $level por ciento. " + choose(listOf("Abróchate el cinturón.", "Tú lo has querido.", "Esto va a ser largo."), random = random)
    }
}
