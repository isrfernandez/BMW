package com.cockpitf30.input

import org.junit.Assert.assertEquals
import org.junit.Test

class KnobMapTest {
    @Test
    fun roundTripsLearnedKeys() {
        val map = mapOf(300 to 20, 301 to 23)
        assertEquals(map, IDriveInput.decode(IDriveInput.encode(map)))
    }

    @Test
    fun decodeSkipsBrokenPairs() {
        assertEquals(mapOf(5 to 6), IDriveInput.decode("x:1, 5:6 ,7,:"))
        assertEquals(emptyMap<Int, Int>(), IDriveInput.decode(null))
    }
}
