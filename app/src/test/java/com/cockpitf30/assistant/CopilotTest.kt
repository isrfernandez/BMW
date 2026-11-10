package com.cockpitf30.assistant

import com.cockpitf30.weather.WeatherKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CopilotTest {

    @Test
    fun demoTalksEveryTwoMinutes() {
        assertEquals(120_000L, Copilot.intervalMs(10, demo = true))
        assertEquals(600_000L, Copilot.intervalMs(10, demo = false))
    }

    @Test
    fun urgentThingsFirst() {
        val mem = Copilot.Memory()
        val s = CopilotSnapshot(now = 0, hour = 12, rangeKm = 40, movingMs = 2 * 3_600_000L + 10 * 60_000L)
        val first = Copilot.next(s, mem)!!
        assertEquals("rest", first.id)
        assertTrue(first.text, first.text.contains("2 horas y 10 minutos"))
        assertEquals(Command.Info(InfoTopic.FUEL_NEAR), first.yes)
        assertEquals("fuel", Copilot.next(s.copy(now = 60_000), mem)!!.id)
    }

    @Test
    fun badWeatherAndDestination() {
        val mem = Copilot.Memory()
        val s = CopilotSnapshot(now = 0, hour = 12, weatherKind = WeatherKind.RAIN, weatherText = "Lluvia", destName = "Madrid", destKm = 52.0, speedKmh = 100)
        assertEquals("Hace mal tiempo por aquí: lluvia. Modera la velocidad y deja más distancia.", Copilot.next(s, mem)!!.text)
        val line = Copilot.next(s.copy(now = 1_000), mem, random = Random(0))!!
        assertTrue(line.text, line.id == "dest" || line.id.startsWith("idle") || line.id == "weather" || line.id == "time")
    }

    @Test
    fun doesNotRepeatTheSameLine() {
        val mem = Copilot.Memory()
        val ids = (0 until 10).map { Copilot.next(CopilotSnapshot(now = it * 120_000L, hour = 12, demo = true), mem, random = Random(it))!!.id }
        ids.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
    }
}
