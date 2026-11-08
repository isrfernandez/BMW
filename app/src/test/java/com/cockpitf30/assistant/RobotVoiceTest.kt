package com.cockpitf30.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class RobotVoiceTest {

    private fun tone(rate: Int, ms: Int): Pcm {
        val n = rate * ms / 1000
        return Pcm(ShortArray(n) { i -> (8000 * sin(2 * PI * 180 * i / rate) * (1 + 0.5 * sin(2 * PI * 4 * i / rate))).toInt().toShort() }, rate)
    }

    @Test
    fun wavRoundTrip() {
        val pcm = tone(22_050, 200)
        val back = RobotVoice.readWav(RobotVoice.toWav(pcm))
        assertNotNull(back)
        assertEquals(22_050, back!!.rate)
        assertEquals(pcm.samples.toList(), back.samples.toList())
    }

    @Test
    fun wavWithZeroDataSizeIsReadToTheEnd() {
        val bytes = RobotVoice.toWav(tone(16_000, 100))
        // Motores que escriben el tamaño de «data» a 0 porque la rellenan a trozos.
        bytes[40] = 0; bytes[41] = 0; bytes[42] = 0; bytes[43] = 0
        assertEquals(1600, RobotVoice.readWav(bytes)!!.samples.size)
    }

    @Test
    fun notAWav() {
        assertNull(RobotVoice.readWav(ByteArray(100)))
    }

    @Test
    fun processedVoiceIsLoudButNotClipped() {
        val out = RobotVoice.process(tone(24_000, 500))
        val peak = out.samples.maxOf { abs(it.toInt()) }
        assertTrue("pico $peak", peak in 25_000..30_000)
        assertTrue(out.samples.size >= 12_000)
        assertEquals(0, out.samples.first().toInt())
    }

    @Test
    fun humorChance() {
        assertEquals(0.0, Personality.jokeChance(0), 0.0)
        assertEquals(1.0, Personality.jokeChance(100), 0.0)
        assertEquals("Vale", Personality.spice("Vale", 0, "Broma."))
        assertEquals("Vale. Broma.", Personality.spice("Vale", 100, "Broma."))
        assertEquals("Otra cosa", Personality.spice("Vale", 100, "=Otra cosa", random = Random(1)))
    }

    @Test
    fun humorCommands() {
        assertEquals(Command.Humor(set = 60), VoiceCommands.parse("humor al sesenta por ciento"))
        assertEquals(Command.Humor(set = 75), VoiceCommands.parse("pon el humor al setenta y cinco por ciento"))
        assertEquals(Command.Humor(set = 25), VoiceCommands.parse("humor veinticinco"))
        assertEquals(Command.Humor(set = 100), VoiceCommands.parse("humor al cien por cien"))
        assertEquals(Command.Humor(set = 40), VoiceCommands.parse("Humor al 40 %"))
        assertEquals(Command.Humor(set = 0), VoiceCommands.parse("humor a cero"))
        assertEquals(Command.Humor(delta = 25), VoiceCommands.parse("sube el humor"))
        assertEquals(Command.Humor(delta = -25), VoiceCommands.parse("baja el humor"))
        assertEquals(Command.Humor(), VoiceCommands.parse("que nivel de humor tienes"))
        assertEquals(Command.Honesty, VoiceCommands.parse("cual es tu nivel de sinceridad"))
    }
}
