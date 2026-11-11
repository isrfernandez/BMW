package com.cockpitf30.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BriefingTest {
    @Test fun morningWithEverything() {
        val t = Briefing.text(BriefingInput(hour = 8, name = "Israel", tempC = 12, weather = "Despejado", rainLater = true, rangeKm = 420, camerasOnRoute = 2, routeKm = 18.0, camerasNear = 3, service = "Recuerda que la ITV está caducada."))
        assertTrue(t, t.startsWith("Buenos días, Israel."))
        assertTrue(t, t.contains("12 grados"))
        assertTrue(t, t.contains("puede llover"))
        assertTrue(t, t.contains("420 kilómetros"))
        assertTrue(t, t.contains("ITV"))
        assertTrue(t, t.endsWith("Buen viaje."))
    }

    @Test fun lowRangeWarns() {
        val t = Briefing.text(BriefingInput(hour = 22, rangeKm = 40))
        assertTrue(t, t.startsWith("Buenas noches."))
        assertTrue(t, t.contains("repostar"))
        assertFalse(t, t.contains("grados"))
    }
}
