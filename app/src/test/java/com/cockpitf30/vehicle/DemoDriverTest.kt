package com.cockpitf30.vehicle

import com.cockpitf30.vehicle.source.DemoDriver
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.math.sin

class DemoDriverTest {
    /** Recorre dos ciclos de la demo y mira lo que pintaría el cuadro (otro ShiftAdvisor). */
    private fun run(limits: ShiftAdvisor.Limits): Map<Shift, Int> {
        val driver = DemoDriver(limits)
        val cluster = ShiftAdvisor()
        val shown = mutableMapOf(Shift.UP to 0, Shift.DOWN to 0)
        var t = 0.0
        var now = 0L
        var maxRpm = 0
        repeat(2 * 2300) {
            val speed = (70 + 60 * sin(t / 18)).roundToInt()
            val gear = driver.update(now, speed)
            val rpm = DemoDriver.rpmFor(speed, gear)
            if (gear > 0) maxRpm = maxOf(maxRpm, rpm)
            cluster.update(now, gear.takeIf { it > 0 }, rpm, speed, limits)?.let { shown[it] = shown.getValue(it) + 1 }
            t += 0.1; now += 50
        }
        assertTrue("rpm máx $maxRpm", maxRpm < 4_500)
        return shown
    }

    @Test fun ecoShowsBothArrows() {
        val shown = run(ShiftAdvisor.ECO)
        // Al menos ~1 s de flecha de subir y de bajar por ciclo.
        assertTrue("$shown", shown.getValue(Shift.UP) >= 40)
        assertTrue("$shown", shown.getValue(Shift.DOWN) >= 40)
    }

    @Test fun sportShowsUpArrow() {
        assertTrue(run(ShiftAdvisor.SPORT).getValue(Shift.UP) >= 40)
    }
}
