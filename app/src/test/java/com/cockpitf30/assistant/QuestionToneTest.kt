package com.cockpitf30.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionToneTest {
    @Test fun yesNoRisesMoreThanWh() {
        assertEquals(QuestionTone.YES_NO_RISE, QuestionTone.riseFor("¿Te llevo a la gasolinera?"))
        assertEquals(QuestionTone.WH_RISE, QuestionTone.riseFor("Vale. ¿Dónde quieres ir?"))
        assertEquals(0f, QuestionTone.riseFor("Te llevo a la gasolinera."))
        assertEquals(QuestionTone.YES_NO_RISE, QuestionTone.riseFor("No te he entendido. ¿Puedes repetirlo?"))
    }

    @Test fun applyShortensVoicedTailAndKeepsSilence() {
        val rate = 16000
        val voiced = ShortArray(rate) { (8000 * kotlin.math.sin(it * 0.2)).toInt().toShort() }
        val pcm = Pcm(voiced + ShortArray(rate / 5), rate)
        val out = QuestionTone.apply(pcm, QuestionTone.YES_NO_RISE)
        assertTrue(out.samples.size < pcm.samples.size)
        assertTrue(out.samples.size > pcm.samples.size - rate / 5)
        assertEquals(pcm, QuestionTone.apply(pcm, 0f))
        QuestionTone.apply(Pcm(ShortArray(10), rate), 0.3f) // corto: no peta
    }

    @Test fun moreLinesNoDuplicates() {
        assertEquals(Lines.JOKES.size, Lines.JOKES.toSet().size)
        assertEquals(Lines.FACTS.size, Lines.FACTS.toSet().size)
        assertTrue(Lines.JOKES.size >= 330)
        assertTrue(Lines.FACTS.size >= 190)
        assertTrue(Personality.line("unknown.short").endsWith("?"))
    }
}
