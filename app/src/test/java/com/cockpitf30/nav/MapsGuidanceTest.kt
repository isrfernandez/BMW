package com.cockpitf30.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class MapsGuidanceTest {
    @Test
    fun spokenExpandsUnits() {
        val g = MapsGuidance("300 m", "Gira a la derecha hacia Calle Mayor", "12 min · 8,4 km · 14:32", null, 0)
        assertEquals("300 metros, Gira a la derecha hacia Calle Mayor. 12 minutos, 8,4 kilómetros, 14:32", MapsGuidanceState.spoken(g))
    }
}
