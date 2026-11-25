package com.cockpitf30.assistant

/**
 * Entonación de pregunta. El motor de voz de la radio dice «¿Te llevo?» igual que «Te llevo.»: aquí
 * se sube el tono al final de la frase, como hace una persona. Se lee el audio que genera el motor
 * (WAV) y en su última parte con voz el tono sube suavemente hasta [YES_NO_RISE] (~7–8 semitonos,
 * lo que sube una pregunta hablada) sin cambiar la duración.
 *
 * En español las preguntas de sí o no suben al final; las que empiezan por qué, cómo, dónde…
 * suben mucho menos ([WH_RISE]).
 *
 * Kotlin puro y sin conexión.
 */
object QuestionTone {
    const val YES_NO_RISE = 0.55f
    const val WH_RISE = 0.18f
    /** Lo que dura la subida, al final de lo hablado. */
    const val RISE_MS = 520
    private const val GRAIN_MS = 40
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

    /**
     * El audio con el final subido [rise] (0,55 = un 55 % más agudo al acabar). Cambia el tono sin
     * cambiar la duración: granos de 40 ms solapados a la mitad, cada uno leído más deprisa según
     * avanza la subida (antes se remuestreaba la cola entera y apenas se notaba).
     */
    fun apply(pcm: Pcm, rise: Float): Pcm {
        val x = pcm.samples
        if (rise <= 0f || x.size < pcm.rate / 4) return pcm
        val win = (pcm.rate / 100).coerceAtLeast(1)
        val end = voicedEnd(x, win)
        val len = (pcm.rate * RISE_MS / 1000).coerceAtMost(end)
        val start = end - len
        if (len < win * 5) return pcm
        val n = x.size
        val grain = (pcm.rate * GRAIN_MS / 1000).coerceAtLeast(16)
        val hop = grain / 2
        val w = FloatArray(grain) { (0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * it / grain)).toFloat() }
        val from = (start - grain).coerceAtLeast(0)
        val to = minOf(n, end + grain)
        val acc = FloatArray(to - from)
        val wsum = FloatArray(to - from)
        var g0 = from
        while (g0 < to) {
            val c = g0 + hop
            val r = ratio(c, start, len, rise)
            for (k in 0 until grain) {
                val o = g0 + k
                if (o >= to) break
                val pos = c + (k - hop) * r
                val ip = kotlin.math.floor(pos).toInt()
                val v = if (ip < 0 || ip + 1 >= n) 0.0 else x[ip] + (x[ip + 1] - x[ip]) * (pos - ip)
                acc[o - from] += (v * w[k]).toFloat()
                wsum[o - from] += w[k]
            }
            g0 += hop
        }
        val out = x.copyOf()
        for (o in from until to) {
            val ws = wsum[o - from]
            // Bordes de la zona: mezcla suave con el original.
            val v = if (ws > 0.05f) acc[o - from] / ws else x[o].toFloat()
            val edge = minOf(1f, (o - from).toFloat() / grain, (to - 1 - o).toFloat() / grain).coerceAtLeast(0f)
            out[o] = (x[o] * (1 - edge) + v * edge).toInt().coerceIn(-32768, 32767).toShort()
        }
        return Pcm(out, pcm.rate)
    }

    /** Cuánto más agudo en la muestra [c]: plano hasta la subida, luego curva suave hasta [rise]. */
    private fun ratio(c: Int, start: Int, len: Int, rise: Float): Double {
        val f = ((c - start).toDouble() / (len * 0.85)).coerceIn(0.0, 1.0)
        return 1.0 + rise * (f * f * (3 - 2 * f))
    }

    /** Fin de lo hablado: desde el final, por ventanas de [win], hasta que haya voz. */
    private fun voicedEnd(x: ShortArray, win: Int): Int {
        var end = x.size
        while (end - win > 0) {
            var peak = 0
            for (i in end - win until end) { val a = kotlin.math.abs(x[i].toInt()); if (a > peak) peak = a }
            if (peak > SILENCE) break
            end -= win
        }
        return end
    }
}
