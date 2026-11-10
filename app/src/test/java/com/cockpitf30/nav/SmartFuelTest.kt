package com.cockpitf30.nav

import com.cockpitf30.fuel.FuelStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartFuelTest {
    private fun st(id: String, price: Double?) = FuelStation(id, id, "", "", 40.0, -3.0, "24H", price, null)
    private fun v(id: String, price: Double?, km: Double, bearing: Float = 0f) = StationView(st(id, price), km * 1000, bearing)

    @Test fun picksCheaperAheadWithinReach() {
        val list = listOf(v("cerca", 1.60, 2.0), v("barata", 1.45, 8.0), v("detras", 1.30, 3.0, 170f), v("lejos", 1.20, 200.0))
        val p = RouteAlerts.smartFuelPick(60, list, 57f)!!
        assertEquals("barata", p.view.station.id)
        assertEquals("cerca", p.nearest.station.id)
        assertEquals(0.15 * 57, p.savingEuros!!, 0.01)
    }

    @Test fun smallSavingNotWorthDetour() {
        val list = listOf(v("cerca", 1.50, 1.0), v("algo", 1.49, 25.0))
        val p = RouteAlerts.smartFuelPick(200, list, 57f)!!
        assertEquals("cerca", p.view.station.id)
        assertNull(p.savingEuros)
    }

    @Test fun nothingNearby() {
        assertNull(RouteAlerts.smartFuelPick(30, listOf(v("lejos", 1.4, 80.0)), 57f))
    }
}
