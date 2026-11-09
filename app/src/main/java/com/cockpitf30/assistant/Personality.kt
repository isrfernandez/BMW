package com.cockpitf30.assistant

import kotlin.random.Random

/**
 * Carácter del asistente, al estilo del robot TARS: respuestas secas, con un nivel de humor
 * ajustable de 0 a 100 % («Hola BMW, humor al 60 por ciento»).
 */
object Personality {
    const val DEFAULT_HUMOR = 75

    /** Probabilidad de que una respuesta lleve broma: 0 % nunca, 100 % siempre (75 % → ~2 de cada 3). */
    fun jokeChance(humor: Int): Double {
        val h = humor.coerceIn(0, 100) / 100.0
        return h * kotlin.math.sqrt(h)
    }

    /**
     * Con humor, añade a [plain] una de las [quips] (que empiezan por mayúscula y van detrás, tras un
     * punto). Una broma que empiece por «=» sustituye a [plain] en vez de añadirse.
     */
    fun spice(plain: String, humor: Int, vararg quips: String, random: Random = Random.Default): String {
        if (quips.isEmpty() || random.nextDouble() >= jokeChance(humor)) return plain
        val q = quips[random.nextInt(quips.size)]
        return if (q.startsWith("=")) q.drop(1) else "${plain.trimEnd('.')}. $q"
    }

    fun humorReply(level: Int): String = when {
        level <= 0 -> "Humor al cero por ciento. Seré tan divertido como un manual de instrucciones."
        level < 40 -> "Humor al $level por ciento. Modo serio."
        level < 90 -> "Humor al $level por ciento. Confirmado."
        else -> "Humor al $level por ciento. Abróchate el cinturón."
    }
}
