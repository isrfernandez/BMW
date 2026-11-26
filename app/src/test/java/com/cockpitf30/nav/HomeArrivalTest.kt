package com.cockpitf30.nav

import com.cockpitf30.assistant.Command
import com.cockpitf30.assistant.Lines
import com.cockpitf30.assistant.VoiceCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeArrivalTest {
    private val home = GeoPoint(40.4300, -3.7000)
    private fun near(dLat: Double) = GeoPoint(home.lat + dLat, home.lon)

    @Test fun learnsHomeAfterThreeNightsInTheSamePlace() {
        assertNull(HomeArrival.homeFromNights(listOf(home, near(0.0005))))
        val h = HomeArrival.homeFromNights(listOf(home, GeoPoint(41.0, -4.0), near(0.0005), near(-0.0004)))
        assertNotNull(h)
        assertTrue(RouteTracker.distance(h!!, home) < 60)
    }

    @Test fun voiceSetsHome() {
        assertEquals(Command.SetHome, VoiceCommands.parse("esta es mi casa"))
        assertEquals(Command.SetHome, VoiceCommands.parse("aquí vivo"))
        assertEquals(Command.SetHome, VoiceCommands.parse("guarda mi casa"))
        assertEquals(Command.SetHome, VoiceCommands.parse("guarda esta ubicación como casa"))
        assertNotEquals(Command.SetHome, VoiceCommands.parse("llévame a casa"))
    }

    @Test fun hasArrivalLines() {
        assertTrue(Lines.PLAIN["home.arrived"].orEmpty().contains("Ya estamos en casa."))
    }
}
