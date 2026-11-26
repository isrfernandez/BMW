package com.cockpitf30.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveTest {
    @Test fun flatCalmIsZeroExtra() {
        val l = RoadFeel.load(100f, 0f, null, null, 0f)
        assertEquals(0, l.extraPct)
        assertNull(RoadFeel.label(l))
    }

    @Test fun climbAddsEffort() {
        val l = RoadFeel.load(100f, 6f, null, null, 0f)
        assertTrue(l.extraPct > 100)
        assertEquals("Subida 6 % · ×${RoadFeel.times(l.extraPct)} esfuerzo", RoadFeel.label(l))
        val soft = RoadFeel.load(130f, 4f, null, null, 0f)
        assertEquals("Subida 4 % · +${soft.extraPct} % esfuerzo", RoadFeel.label(soft))
    }

    @Test fun headwindFromAhead() {
        // Viento que viene del norte yendo al norte: de cara.
        val l = RoadFeel.load(120f, 0f, 0, 40, 0f)
        assertEquals(28, l.headwindKmh.toInt())
        assertTrue(l.windPts > 25)
        assertTrue(RoadFeel.label(l)!!.startsWith("Viento de cara 28 km/h"))
        val tail = RoadFeel.load(120f, 0f, 180, 40, 0f)
        assertTrue(tail.headwindKmh < -27f)
    }

    @Test fun noLabelInTown() { assertNull(RoadFeel.label(RoadFeel.load(30f, 8f, null, null, 0f))) }

    @Test fun roadWatchNeedsSteadyClimb() {
        val w = RoadWatch(steadyMs = 10_000, gapMs = 60_000, sameGapMs = 120_000)
        val l = RoadFeel.load(90f, 6f, null, null, 0f)
        assertNull(w.tick(l, null, 0))
        assertNull(w.tick(l, null, 5_000))
        assertEquals(RoadKind.SUBIDA, w.tick(l, null, 11_000))
        assertNull(w.tick(l, null, 30_000))
    }

    @Test fun coastDetectsExtraDrag() {
        val c = CoastMeter()
        var t = 0f
        repeat(3) {
            var v = 100f
            // Frena el doble de lo que dice la física.
            val a = 2f * com.cockpitf30.drive.RoadFeel.resistance(100f, 0f, 0f) / (1600f * 1.03f) * 3.6f
            repeat(8) { c.feed(t, v, 800, 0f, 0f); v -= a; t += 1f }
            c.feed(t, 100f, 2200, 0f, 0f); t += 1f
        }
        val e = c.excessPct
        assertNotNull(e)
        assertTrue("$e", e!! in 70..130)
    }

    @Test fun rhythmHighway() {
        val m = RhythmMeter(holdMs = 10_000)
        var r: Rhythm? = null
        for (s in 0..400) m.feed(s * 1000L, 115f, 0f, "6", weekend = false, hour = 11)?.let { r = it }
        assertEquals(Rhythm.AUTOPISTA, r)
    }

    @Test fun rhythmCity() {
        val m = RhythmMeter()
        var r: Rhythm? = null
        for (s in 0..600) {
            val v = if (s % 60 < 15) 0f else 30f
            m.feed(s * 1000L, v, if (s % 60 == 15) 0.25f else 0f, if (v > 0) "2" else "N", weekend = false, hour = 12)?.let { r = it }
        }
        assertEquals(Rhythm.CIUDAD, r)
        assertTrue(Rhythm.CIUDAD.humor(80) < 30)
    }

    @Test fun sundayIsPaseo() {
        val m = RhythmMeter()
        var r: Rhythm? = null
        for (s in 0..400) m.feed(s * 1000L, 85f, 0f, "5", weekend = true, hour = 11)?.let { r = it }
        assertEquals(Rhythm.PASEO, r)
    }

    @Test fun tunnelInAndOut() {
        val c = CoverageWatch()
        assertNull(c.tick(0, 500, 80f, true))
        assertNull(c.tick(4_000, 4_000, 80f, true))
        assertTrue(c.blind)
        assertEquals(CoverageEvent.BLIND_IN, c.tick(11_000, 11_000, 80f, false))
        assertEquals(CoverageEvent.BLIND_OUT, c.tick(40_000, 500, 80f, true))
    }

    @Test fun shortTunnelIsSilent() {
        val c = CoverageWatch()
        c.tick(0, 500, 80f, true)
        c.tick(5_000, 5_000, 80f, true)
        assertNull(c.tick(7_000, 500, 80f, true))
    }

    @Test fun noGpsEverIsSilent() {
        val c = CoverageWatch()
        for (s in 0..100) assertNull(c.tick(s * 1000L, null, 50f, true))
    }
}
