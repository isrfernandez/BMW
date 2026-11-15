package com.cockpitf30.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MaintenanceTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private val today = LocalDate.of(2026, 11, 10)
    private val now = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private fun daysAgo(d: Long) = today.minusDays(d).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun oilOkThenSoonThenDue() {
        val r = ServiceRecord(100_000, daysAgo(30))
        assertEquals(ServiceLevel.OK, Maintenance.status(ServiceItem.OIL, r, 110_000, null, now, zone).level)
        val soon = Maintenance.status(ServiceItem.OIL, r, 124_000, null, now, zone)
        assertEquals(ServiceLevel.SOON, soon.level)
        assertEquals(1_000, soon.kmLeft)
        assertEquals(ServiceLevel.DUE, Maintenance.status(ServiceItem.OIL, r, 125_500, null, now, zone).level)
    }

    @Test fun tyresDueByDate() {
        val r = ServiceRecord(100_000, daysAgo(200))
        val st = Maintenance.status(ServiceItem.TYRES, r, 101_000, null, now, zone)
        assertEquals(ServiceLevel.DUE, st.level)
    }

    @Test fun itvByDate() {
        assertEquals(ServiceLevel.UNKNOWN, Maintenance.status(ServiceItem.ITV, null, null, null, now, zone).level)
        assertEquals(ServiceLevel.SOON, Maintenance.status(ServiceItem.ITV, null, null, today.plusDays(10), now, zone).level)
        assertEquals(ServiceLevel.DUE, Maintenance.status(ServiceItem.ITV, null, null, today.minusDays(1), now, zone).level)
        assertEquals(ServiceLevel.OK, Maintenance.status(ServiceItem.ITV, null, null, today.plusMonths(6), now, zone).level)
    }

    @Test fun spokenListsDueAndSoon() {
        val list = listOf(
            Maintenance.status(ServiceItem.OIL, ServiceRecord(100_000, daysAgo(30)), 124_000, null, now, zone),
            Maintenance.status(ServiceItem.ITV, null, null, today.minusDays(2), now, zone),
        )
        val s = Maintenance.spoken(list)!!
        assertTrue(s, s.startsWith("Recuerda que"))
        assertTrue(s, s.contains("ITV"))
        assertTrue(s, s.contains("1000 kilómetros"))
        assertEquals(ServiceItem.ITV, Maintenance.mostUrgent(list)!!.item)
    }

    @Test fun spokenNullWhenAllOk() {
        val ok = Maintenance.status(ServiceItem.OIL, ServiceRecord(100_000, daysAgo(10)), 101_000, null, now, zone)
        assertNull(Maintenance.spoken(listOf(ok)))
    }
}
