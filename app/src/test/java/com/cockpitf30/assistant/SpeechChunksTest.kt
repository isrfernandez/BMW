package com.cockpitf30.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChunksTest {
    @Test fun splitsLongTextIntoSentences() {
        val text = "Delante, a 2 kilómetros: Catedral de Segovia. " +
            "La catedral es un templo gótico construido entre los siglos dieciséis y dieciocho, con una torre de casi noventa metros. " +
            "Fue la última gran catedral gótica de España, y por su elegancia la llaman la dama de las catedrales. Es preciosa."
        val chunks = SpeechChunks.split(text)
        assertEquals("Delante, a 2 kilómetros:", chunks.first())
        assertTrue(chunks.all { it.length <= SpeechChunks.MAX })
        assertEquals(text.replace(" ", ""), chunks.joinToString("").replace(" ", ""))
        val off = SpeechChunks.offsets(text, chunks)
        chunks.forEachIndexed { i, c -> assertTrue(text.startsWith(c, off[i])) }
    }

    @Test fun veryLongSentenceIsCutAtCommas() {
        val text = "Un castillo " + "con torres, murallas y fosos, ".repeat(15) + "muy bonito."
        val chunks = SpeechChunks.split(text)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= SpeechChunks.MAX })
    }

    @Test fun shortText() {
        assertEquals(listOf("Vale."), SpeechChunks.split("Vale."))
        assertEquals(emptyList<String>(), SpeechChunks.split("  "))
    }
}
