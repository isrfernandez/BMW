package com.cockpitf30.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinesMoreTest {
    @Test fun moreLinesNoDuplicates() {
        assertEquals(Lines.JOKES.size, Lines.JOKES.toSet().size)
        assertEquals(Lines.FACTS.size, Lines.FACTS.toSet().size)
        assertTrue(Lines.JOKES.size >= 330)
        assertTrue(Lines.FACTS.size >= 190)
        assertTrue(LinesMore.UNKNOWN_SHORT.all { it.endsWith("?") })
    }
}
