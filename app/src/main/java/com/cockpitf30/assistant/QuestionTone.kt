package com.cockpitf30.assistant

/**
 * Entonación de pregunta. El motor de voz de la radio dice «¿Te llevo?» igual que «Te llevo.»: aquí
 * se sube el tono al final de la frase, como hace una persona. Se lee el audio que genera el motor
 * (WAV) y su última parte con voz se reproduce cada vez un poco más deprisa (remuestreo variable):
 * el tono sube suavemente hasta [YES_NO_RISE] (~4 semitonos) y la cola queda unos 50 ms más corta.
 *
 * En español las preguntas de sí o no suben al final; las que empiezan por qué, cómo, dónde…
 * suben mucho menos ([WH_RISE]).
 *
 * Kotlin puro y sin conexión.
 */
object QuestionTone {
    const val YES_NO_RISE = 0.26f
    const val WH_RISE = 0.10f
    /** Lo que dura la subida, al final de lo hablado. */
    const val RISE_MS = 420
    /** Lo que se considera silencio al final (el motor deja un hueco tras la frase). */
    private const val SILENCE = 450

    private val WH = Regex("^(?:qu[eé]|c[oó]mo|d[oó]nde|ad[oó]nde|cu[aá]ndo|cu[aá]l(?:es)?|qui[eé]n(?:es)?|cu[aá]nt[oa]s?|por qu[eé]|para qu[eé])\\b")

    /** ¿Termina [chunk] en pregunta? Cuánto subir (0 = no es pregunta). */
    fun riseFor(chunk: String): Float {
        val t = chunk.trim().trimEnd('"', '»', ')', ' ')
        if (!t.endsWith("?")) return 0f
        // La última pregunta del trozo: desde su «¿» (o desde el último punto).
        val start = maxOf(t.lastIndexOf('¿'), t.dropLast(1).lastIndexOfAny(charArrayOf('.', '!', '?')) + 1)
        val q = t.substring(start.coerceAtLeast(0)).trimStart('¿', ' ').lowercase()
        return if (WH.containsMatchIn(q)) WH_RISE else YES_NO_RISE
    }

    /** El audio con el final subido [rise] (0,26 = un 26 % más agudo al acabar). */
    fun apply(pcm: Pcm, rise: Float): Pcm {
        val x = pcm.samples
        if (rise <= 0f || x.size < pcm.rate / 4) return pcm
        // Último trozo con voz: se busca desde el final por ventanas de 10 ms.
        val win = (pcm.rate / 100).coerceAtLeast(1)
        var end = x.size
        while (end - win > 0) {
            var peak = 0
            for (i in end - win until end) { val a = kotlin.math.abs(x[i].toInt()); if (a > peak) peak = a }
            if (peak > SILENCE) break
            end -= win
        }
        val len = (pcm.rate * RISE_MS / 1000).coerceAtMost(end)
        val start = end - len
        if (len < win * 5) return pcm
        val out = ShortArray(x.size)
        System.arraycopy(x, 0, out, 0, start)
        // Remuestreo con paso creciente (curva suave: empieza plano y sube al final).
        var pos = start.toDouble()
        var o = start
        while (pos < end - 1 && o < out.size) {
            val f = ((pos - start) / len).coerceIn(0.0, 1.0)
            val step = 1.0 + rise * (f * f * (3 - 2 * f))
            val i = pos.toInt()
            val frac = pos - i
            out[o++] = (x[i] * (1 - frac) + x[i + 1] * frac).toInt().toShort()
            pos += step
        }
        // El silencio de después, tal cual.
        val tail = x.size - end
        val n = minOf(tail, out.size - o)
        if (n > 0) System.arraycopy(x, end, out, o, n)
        return Pcm(out.copyOf(o + n.coerceAtLeast(0)), pcm.rate)
    }
}
