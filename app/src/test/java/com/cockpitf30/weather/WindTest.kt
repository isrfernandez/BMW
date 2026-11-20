package com.cockpitf30.weather

import com.cockpitf30.assistant.Briefing
import com.cockpitf30.assistant.BriefingInput
import com.cockpitf30.nav.WindAlerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class WindTest {
    private val day = LocalDate.of(2026, 11, 20)

    private fun hours(vararg dirs: Pair<Int, Int>) = dirs.mapIndexed { i, (dir, kmh) ->
        HourForecast(day.atTime(i, 0), 15, 0, true, 0, 0f, kmh, dir)
    }

    @Test fun namesAndForce() {
        assertEquals("poniente", Wind.name(270))
        assertEquals("levante", Wind.name(95))
        assertEquals("suroeste", Wind.name(225))
        assertEquals("norte", Wind.name(350))
        assertEquals("de poniente", Wind.from(260))
        assertEquals("del suroeste", Wind.from(220))
        assertEquals("flojo", Wind.force(15))
        assertEquals("fresquito", Wind.force(32))
        assertEquals(4, Wind.beaufort(25))
    }

    @Test fun veersToSouthwestInTheAfternoon() {
        // Levante toda la mañana y suroeste desde las 16.
        val h = hours(*Array(24) { i -> (if (i < 16) 90 else 225) to 18 })
        val f = Forecast(15, 0, true, emptyList(), h, windKmh = 14, windDir = 90)
        val t = Wind.today(f, day.atTime(10, 30))!!
        assertTrue(t, t.startsWith("Viento de levante, flojo, a 14 kilómetros por hora"))
        assertTrue(t, t.endsWith("rolando a suroeste por la tarde"))
    }

    @Test fun noVeerWhenSteady() {
        val h = hours(*Array(24) { 270 to 20 })
        assertNull(Wind.veer(h, day.atTime(9, 0), 270))
        // Un solo cambio de una hora no cuenta.
        val blip = hours(*Array(24) { i -> (if (i == 14) 90 else 270) to 20 })
        assertNull(Wind.veer(blip, day.atTime(9, 0), 270))
    }

    @Test fun dayForecastSaysTurn() {
        val h = hours(*Array(24) { i -> (if (i < 14) 90 else 270) to 22 })
        val f = Forecast(15, 0, true, listOf(DayForecast(day, 0, 8, 18, windMaxKmh = 30)), h)
        val t = Wind.day(f, day)!!
        assertTrue(t, t.contains("de levante, fresquito, hasta 30"))
        assertTrue(t, t.endsWith("rolando a poniente por la tarde"))
    }

    @Test fun relativeNauticalPoints() {
        // Rumbo norte.
        assertEquals(WindPoint.PROA, Wind.relative(10, 0f).point)
        assertEquals(WindPoint.POPA, Wind.relative(180, 0f).point)
        val beam = Wind.relative(270, 0f)
        assertEquals(WindPoint.TRAVES, beam.point)
        assertEquals("babor", beam.side)
        val bow = Wind.relative(45, 0f)
        assertEquals(WindPoint.AMURA, bow.point)
        assertEquals("estribor", bow.side)
        assertEquals(WindPoint.ALETA, Wind.relative(220, 0f).point)
        // Rumbo oeste con poniente: de proa.
        assertEquals(WindPoint.PROA, Wind.relative(270, 268f).point)
        val s = Wind.spokenRelative(WindNow(270, 35, 55), beam)
        assertTrue(s, s.contains("través por babor") && s.contains("Barlovento a la izquierda") && s.contains("rachas"))
    }

    @Test fun routeAlertNeedsSteadyHeadingAndGap() {
        val a = WindAlerts(steadyMs = 60_000, gapMs = 600_000)
        val w = WindNow(270, 25)
        assertNull(a.tick(w, 0f, 90, 0, demo = false))
        assertNull(a.tick(w, 0f, 90, 30_000, demo = false))
        assertNotNull(a.tick(w, 0f, 90, 61_000, demo = false))
        // Mismo viento relativo: no se repite.
        assertNull(a.tick(w, 0f, 90, 200_000, demo = false))
        // Cambia a proa, pero no han pasado 10 min.
        assertNull(a.tick(w, 270f, 90, 300_000, demo = false))
        assertNull(a.tick(w, 270f, 90, 400_000, demo = false))
        assertNotNull(a.tick(w, 270f, 90, 700_000, demo = false))
        // En ciudad o en calma, nada.
        val b = WindAlerts(0, 0)
        assertNull(b.tick(w, 0f, 30, 0, demo = false))
        assertNull(b.tick(WindNow(270, 8), 0f, 90, 0, demo = false))
    }

    @Test fun briefingIncludesWind() {
        val t = Briefing.text(BriefingInput(hour = 9, tempC = 12, weather = "Despejado", wind = "Viento de levante, flojo, a 14 kilómetros por hora, rolando a poniente por la tarde"))
        assertTrue(t, t.contains("Despejado.".lowercase()) || t.contains("despejado."))
        assertTrue(t, t.contains("rolando a poniente por la tarde."))
    }

    @Test fun parsesCurrentWind() {
        val json = """{"current":{"temperature_2m":14.2,"weather_code":1,"is_day":1,"wind_speed_10m":17.6,"wind_direction_10m":243,"wind_gusts_10m":38.1},
            "daily":{"time":["2026-11-20"],"weather_code":[1],"temperature_2m_max":[18],"temperature_2m_min":[7]}}"""
        val f = parseForecast(json)
        assertEquals(WindNow(243, 18, 38), Wind.now(f, LocalDateTime.of(2026, 11, 20, 12, 0)))
    }
}
