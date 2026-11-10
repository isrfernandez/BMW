package com.cockpitf30.vehicle.telemetry

import com.cockpitf30.ui.theme.NightDetector
import com.cockpitf30.vehicle.telemetry.TelemetryMath.V3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TelemetryMathTest {
    @Test fun cruisingAt120NeedsAboutThirtyCv() {
        val cv = TelemetryMath.enginePowerW(120 / 3.6f, 0f) / TelemetryMath.W_PER_CV
        assertTrue("cv=$cv", cv in 20f..45f)
    }

    @Test fun fullAccelerationIsNearRatedPower() {
        // 320d: 0-100 en ~7,5 s → unos 3 m/s² a 60 km/h.
        val cv = TelemetryMath.enginePowerW(60 / 3.6f, 3f) / TelemetryMath.W_PER_CV
        assertTrue("cv=$cv", cv in 120f..230f)
    }

    @Test fun torqueFromPowerAndRpm() {
        val nm = TelemetryMath.torqueNm(100_000f, 2500)!!
        assertEquals(382f, nm, 2f)
        assertNull(TelemetryMath.torqueNm(100_000f, 700))
    }

    @Test fun regressionSlope() {
        val t = floatArrayOf(0f, 0.1f, 0.2f, 0.3f, 0.4f)
        val v = FloatArray(5) { 10f + 2f * t[it] }
        assertEquals(2f, TelemetryMath.slope(t, v)!!, 1e-4f)
    }

    @Test fun screenFacingDriverGivesForwardAndLateral() {
        // Pantalla vertical mirando al conductor: parado, el acelerómetro marca +g en y.
        val g = V3(0f, 9.8f, 0f)
        // Acelerar hacia delante = hacia -z de la pantalla.
        val (long, lat) = TelemetryMath.gForces(V3(0f, 9.8f, -4.9f), g)
        assertEquals(0.5f, long, 0.01f)
        assertEquals(0f, lat, 0.01f)
        val (l2, lat2) = TelemetryMath.gForces(V3(3f, 9.8f, 0f), g)
        assertEquals(0f, l2, 0.01f)
        assertEquals(0.306f, lat2, 0.01f)
    }

    @Test fun tiltedScreenStillWorks() {
        // Pantalla inclinada 30° hacia atrás.
        val a = Math.toRadians(30.0)
        val g = V3(0f, (9.8 * Math.cos(a)).toFloat(), (9.8 * Math.sin(a)).toFloat())
        val (_, forward) = TelemetryMath.carAxes(g)
        val reading = V3(g.x + forward.x * 4.9f, g.y + forward.y * 4.9f, g.z + forward.z * 4.9f)
        assertEquals(0.5f, TelemetryMath.gForces(reading, g).first, 0.01f)
    }

    @Test fun sunElevationMadrid() {
        val noon = ZonedDateTime.of(2026, 6, 21, 14, 15, 0, 0, ZoneId.of("Europe/Madrid"))
        assertEquals(73.0, NightDetector.sunElevation(40.4, -3.7, noon), 1.5)
        val night = ZonedDateTime.of(2026, 11, 9, 22, 0, 0, 0, ZoneId.of("Europe/Madrid"))
        assertTrue(NightDetector.sunElevation(40.4, -3.7, night) < -20)
        // 9 de noviembre en Madrid: el sol se pone hacia las 18:05.
        val dusk = ZonedDateTime.of(2026, 11, 9, 18, 5, 0, 0, ZoneId.of("Europe/Madrid"))
        assertEquals(0.0, NightDetector.sunElevation(40.4, -3.7, dusk), 1.5)
    }
}
