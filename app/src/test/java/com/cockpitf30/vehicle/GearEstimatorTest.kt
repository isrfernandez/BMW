package com.cockpitf30.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GearEstimatorTest {
    @Test
    fun estimatesEachGear() {
        GearEstimator.KMH_PER_1000_RPM.forEachIndexed { i, r ->
            assertEquals("${i + 1}", GearEstimator.estimate((r * 2).toInt(), 2000))
        }
    }

    @Test
    fun stoppedIsNeutralAndClutchIsUnknown() {
        assertEquals("N", GearEstimator.estimate(0, 800))
        assertNull(GearEstimator.estimate(60, 900)) // 66 km/h por 1000 rpm: no cuadra con ninguna
        assertNull(GearEstimator.estimate(null, 2000))
    }

    @Test
    fun demoGearIsConsistent() {
        for (v in 5..200 step 5) {
            val g = GearEstimator.gearFor(v)
            val rpm = (v * 1000f / GearEstimator.KMH_PER_1000_RPM[g - 1]).toInt().coerceAtLeast(850)
            if (rpm > 850) assertEquals("$g", GearEstimator.estimate(v, rpm))
        }
    }
}
