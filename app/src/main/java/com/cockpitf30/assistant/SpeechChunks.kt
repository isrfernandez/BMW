package com.cockpitf30.assistant

/**
 * Parte lo que va a decir el asistente en trozos para el motor de voz: frases enteras (o, si una
 * frase es muy larga, hasta una coma) de como mucho [MAX] letras. Así empieza a sonar antes y una
 * explicación larga no depende de una única síntesis.
 */
object SpeechChunks {
    const val MAX = 200

    fun split(text: String, max: Int = MAX): List<String> {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return emptyList()
        val sentences = Regex("(?<=[.!?…:;])\\s+").split(clean).filter { it.isNotBlank() }
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() { if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() } }
        for (sentence in sentences) {
            for (piece in pieces(sentence, max)) {
                if (cur.isNotEmpty() && cur.length + 1 + piece.length > max) flush()
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(piece)
                // La primera frase sola, para que empiece a hablar cuanto antes.
                if (out.isEmpty()) flush()
            }
        }
        flush()
        return out
    }

    /** Una frase demasiado larga, partida por comas (o por palabras si no hay comas). */
    private fun pieces(sentence: String, max: Int): List<String> {
        if (sentence.length <= max) return listOf(sentence)
        val out = ArrayList<String>()
        var rest = sentence
        while (rest.length > max) {
            val cut = rest.lastIndexOf(", ", max).takeIf { it > max / 3 }?.plus(1)
                ?: rest.lastIndexOf(' ', max).takeIf { it > 0 } ?: max
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    /** Dónde empieza cada trozo dentro de [text] (aproximado si había espacios dobles). */
    fun offsets(text: String, chunks: List<String>): List<Int> {
        var from = 0
        return chunks.map { c ->
            val i = text.indexOf(c.take(12), from).takeIf { it >= 0 } ?: from
            from = i + c.length
            i
        }
    }
}
