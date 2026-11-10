package com.cockpitf30.vehicle.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DragTimerTest {
    private fun run(accel: (Float) -> Float, seconds: Float, startStill: Float = 2f): List<DragResult> {
        val out = ArrayList<DragResult>()
        val timer = DragTimer { out += it }
        var t = 0f
        while (t < startStill) { timer.feed(t, 0f); t += 0.1f }
        val t0 = t
        while (t < t0 + seconds) { timer.feed(t, accel(t - t0)); t += 0.1f }
        return out
    }

    @Test fun zeroToHundredAndQuarter() {
        // Aceleración constante de 3,5 m/s² (12,6 km/h por segundo): 0–100 en ~7,9 s.
        val res = run({ s -> (s * 12.6f).coerceAtMost(200f) }, 20f).associateBy { it.test }
        assertEquals(100f / 12.6f, res[DragTest.ZERO_100]!!.seconds, 0.15f)
        // 400 m con a = 3,5 m/s²: t = sqrt(2·400/3,5) ≈ 15,1 s.
        assertEquals(15.1f, res[DragTest.QUARTER_400]!!.seconds, 0.25f)
        assertEquals(60f / 12.6f, res[DragTest.MID_60_120]!!.seconds, 0.15f)
    }

    @Test fun liftingOffCancels() {
        val res = run({ s -> if (s < 4f) s * 12.6f else 50f - (s - 4f) * 5f }, 10f)
        assertTrue(res.isEmpty())
    }

    @Test fun noStartWithoutStop() {
        val res = run({ s -> 20f + s * 12.6f }, 10f, startStill = 0f)
        assertTrue(res.none { it.test == DragTest.ZERO_100 })
    }
}
