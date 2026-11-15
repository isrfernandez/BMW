package com.cockpitf30.assistant

/**
 * Separa tu voz del eco de la suya mientras habla: su propia voz suena más o menos igual todo el
 * rato en el micrófono; cuando hablas tú por encima, el nivel sube claramente.
 */
object EchoGate {
    /** Últimos ~4 s de nivel mientras habla (bloques de 100 ms). */
    const val WINDOW = 40
    /** Lo que tiene que superar tu voz al eco «fuerte» de la suya para contar como orden. */
    const val OVER_ECHO = 1.3f

    /**
     * ¿El último ~0,6 s suena claramente por encima de su eco? Se compara con el percentil 80 de lo
     * anterior (sus sílabas fuertes), no con la media, que incluye las pausas entre palabras.
     */
    fun voiceOverEcho(levels: List<Float>): Pair<Boolean, Float> {
        if (levels.size < 12) return true to 0f // aún sin referencia: como antes
        val recent = levels.takeLast(6)
        val before = levels.dropLast(6).sorted()
        val loud = before[(before.size * 0.8f).toInt().coerceAtMost(before.size - 1)].coerceAtLeast(0.02f)
        val ratio = recent.average().toFloat() / loud
        return (ratio >= OVER_ECHO) to ratio
    }
}
