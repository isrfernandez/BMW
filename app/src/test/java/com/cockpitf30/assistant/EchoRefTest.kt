package com.cockpitf30.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EchoRefTest {
    /** Envolvente de voz: sílabas de 60–200 ms con huecos de 40–160 ms. */
    private fun speech(frames: Int, rnd: Random): FloatArray {
        val out = FloatArray(frames)
        var i = 0
        while (i < frames) {
            val on = rnd.nextInt(3, 10)
            val level = rnd.nextFloat() * 3000f + 1000f
            for (k in 0 until on) if (i + k < frames) out[i + k] = level * (0.7f + 0.3f * rnd.nextFloat())
            i += on
            i += rnd.nextInt(2, 8)
        }
        return out
    }

    /** Suena [ref]; el micro oye su eco con [delay] tramas, ganancia [g], cola de reverberación y ruido. */
    private fun run(e: EchoRef, ref: FloatArray, delay: Int, g: Float, user: (Int) -> Float = { 0f }, rnd: Random = Random(3)): List<Boolean> {
        e.newSpeech()
        e.setFloor(60f)
        val base = 10_000L
        ref.forEachIndexed { i, v -> e.refFrame(base + i, v) }
        val out = ArrayList<Boolean>()
        var tail = 0f
        for (blk in 0 until ref.size / 5) {
            for (k in 0 until 5) {
                val f = blk * 5 + k
                val r = if (f - delay >= 0) ref[f - delay] else 0f
                tail = maxOf(r, tail * 0.6f)
                e.micFrame(base + f, g * tail + 50f + rnd.nextFloat() * 20f + user(f))
            }
            out += e.analyse(base + blk * 5 + 4)
        }
        return out
    }

    @Test
    fun learnsDelayAndDoesNotTriggerOnItsOwnEcho() {
        val e = EchoRef()
        val rnd = Random(7)
        val res = run(e, speech(400, rnd), delay = 14, g = 0.6f)
        assertTrue(e.ready)
        assertEquals(14, e.lag)
        assertFalse("su eco no es una voz encima", res.drop(10).any { it })
    }

    @Test
    fun detectsVoiceOverQuickly() {
        val e = EchoRef()
        val rnd = Random(11)
        // Primera frase: aprende.
        run(e, speech(300, rnd), delay = 10, g = 0.8f)
        assertTrue(e.ready)
        // Segunda frase: a los 0,4 s el conductor habla a volumen normal (como su eco) durante 1 s.
        val res = run(e, speech(200, rnd), delay = 10, g = 0.8f, user = { f -> if (f in 20..70) 2500f else 0f })
        val first = res.indexOfFirst { it }
        assertTrue("detecta la voz encima", first >= 0)
        // Bloques de 100 ms: lo detecta en menos de medio segundo desde que empiezas.
        assertTrue("tarda ${first * 100 - 400} ms", first * 5 + 4 - 20 <= 25)
    }

    @Test
    fun toleratesImperfectEcho() {
        val e = EchoRef()
        val rnd = Random(21)
        run(e, speech(300, rnd), delay = 12, g = 0.7f)
        // Eco con ±20 ms de baile y la ganancia variando ±30 % (altavoz y habitáculo reales).
        val ref = speech(400, rnd)
        e.newSpeech(); e.setFloor(60f)
        var tail = 0f
        var any = false
        for (blk in 0 until ref.size / 5) {
            for (k in 0 until 5) {
                val f = blk * 5 + k
                val d = 12 + rnd.nextInt(-1, 2)
                val r = if (f - d >= 0) ref[f - d] else 0f
                tail = maxOf(r, tail * 0.6f)
                e.refFrame(30_000L + f, ref[f])
                e.micFrame(30_000L + f, 0.7f * (0.7f + 0.6f * rnd.nextFloat()) * tail + 50f + rnd.nextFloat() * 30f)
            }
            if (blk > 2 && e.analyse(30_000L + blk * 5 + 4)) any = true
        }
        assertFalse("no se corta con su propio eco imperfecto (${e.describe()})", any)
    }

    @Test
    fun noDecisionWithoutReference() {
        val e = EchoRef()
        run(e, speech(300, Random(5)), delay = 8, g = 0.7f)
        assertTrue(e.ready)
        e.newSpeech()
        // Sin audio de referencia en esta frase: no se corta por mucho que suene el micro.
        for (f in 0 until 50L) e.micFrame(20_000 + f, 3000f)
        assertFalse(e.analyse(20_049))
    }
}
