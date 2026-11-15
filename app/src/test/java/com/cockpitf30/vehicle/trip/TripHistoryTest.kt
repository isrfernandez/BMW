package com.cockpitf30.vehicle.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TripHistoryTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private fun at(date: LocalDate, hour: Int) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    private val wed = LocalDate.of(2026, 11, 11)

    private fun rec(start: Long, km: Double, score: Int? = null, sla: Double? = null, slo: Double? = null, ela: Double? = null, elo: Double? = null) =
        TripRecord(start, start + 1_800_000, km, 1_500_000, 5f, score, 100, sla, slo, ela, elo)

    @Test fun addIgnoresShortAndReplacesSameStart() {
        val now = at(wed, 12)
        var list = TripHistory.add(emptyList(), rec(now, 0.1), now)
        assertTrue(list.isEmpty())
        list = TripHistory.add(list, rec(now, 5.0), now)
        list = TripHistory.add(list, rec(now, 7.0), now)
        assertEquals(1, list.size)
        assertEquals(7.0, list[0].distanceKm, 0.001)
    }

    @Test fun addDropsOldRecords() {
        val now = at(wed, 12)
        val old = rec(now - 200L * 86_400_000, 10.0)
        val list = TripHistory.add(listOf(old), rec(now, 5.0), now)
        assertEquals(1, list.size)
    }

    @Test fun weekGroupsMondayToSunday() {
        val mon = wed.minusDays(2)
        val list = listOf(
            rec(at(mon, 8), 20.0, 80),
            rec(at(wed, 9), 10.0, 60),
            rec(at(mon.minusDays(3), 9), 30.0), // semana anterior
        )
        val w = TripHistory.week(list, at(wed, 18), zone)
        assertEquals(2, w.today)
        assertEquals(2, w.trips)
        assertEquals(30.0, w.km, 0.001)
        assertEquals(20.0, w.kmPerDay[0], 0.001)
        assertEquals(10.0, w.kmPerDay[2], 0.001)
        assertEquals(30.0, w.previousKm, 0.001)
        // Media ponderada por km: (80·20 + 60·10) / 30 ≈ 73.
        assertEquals(73, w.avgScore)
    }

    @Test fun usualDestinationNeedsRepeats() {
        val home = 40.40 to -3.70
        val work = 40.50 to -3.60
        val one = listOf(rec(1, 15.0, sla = home.first, slo = home.second, ela = work.first, elo = work.second))
        assertNull(TripHistory.usualDestination(one, home.first, home.second))
        val two = one + rec(2, 15.0, sla = home.first + 0.001, slo = home.second, ela = work.first + 0.002, elo = work.second)
        val d = TripHistory.usualDestination(two, home.first, home.second)
        assertNotNull(d)
        assertEquals(work.first, d!!.first, 0.01)
    }

    @Test fun distanceToSegment() {
        // Punto a 1 km al norte del centro de un tramo este-oeste.
        val d = TripHistory.kmToSegment(40.009, -3.70, 40.0, -3.75, 40.0, -3.65)
        assertEquals(1.0, d, 0.05)
    }

    @Test fun jsonRoundTrip() {
        val r = rec(123, 4.5, 77, 40.0, -3.0, 41.0, -4.0)
        assertEquals(listOf(r), TripHistory.parse(TripHistory.toJson(listOf(r))))
    }
}
