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

    @Test fun applyRaisesPitchKeepingLength() {
        val rate = 16000
        val voiced = ShortArray(rate * 2) { (8000 * kotlin.math.sin(2 * Math.PI * 150 * it / rate)).toInt().toShort() }
        val pcm = Pcm(voiced + ShortArray(rate / 5), rate)
        val out = QuestionTone.apply(pcm, QuestionTone.YES_NO_RISE)
        assertEquals(pcm.samples.size, out.samples.size)
        fun crossings(a: ShortArray, from: Int, to: Int) = (from + 1 until to).count { (a[it - 1] < 0) != (a[it] < 0) }
        // Al principio, igual; justo antes de acabar, bastante más agudo (~1,5×).
        val s0 = rate / 2
        assertEquals(crossings(pcm.samples, s0, s0 + 1600).toDouble(), crossings(out.samples, s0, s0 + 1600).toDouble(), 2.0)
        val e = rate * 2
        val before = crossings(pcm.samples, e - 1200, e - 80)
        val after = crossings(out.samples, e - 1200, e - 80)
        assertTrue("$before -> $after", after > before * 1.35)
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
