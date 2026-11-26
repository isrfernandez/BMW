package com.cockpitf30.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarKeyTest {
    private fun fp(k46: String, belt: String = "01") = CarKey.parse(mapOf(0x12 to "12 01 00 $belt 01 00 31 40 23 01", 0x46 to "46 01 $k46 00"))

    @Test
    fun twoKeysByTheByteThatDiffers() {
        val keys = listOf("isra" to listOf(fp("64")), "luis" to listOf(fp("65")))
        assertEquals("isra", CarKey.identify(keys, fp("64"))?.first)
        assertEquals("luis", CarKey.identify(keys, fp("65"))?.first)
    }

    @Test
    fun unstableByteIsIgnoredAfterLearning() {
        // La llave de Isra se guardó con el cinturón puesto y sin poner: ese byte no cuenta.
        val keys = listOf("isra" to listOf(fp("64", "01"), fp("64", "00")), "luis" to listOf(fp("65", "01")))
        assertEquals("isra", CarKey.identify(keys, fp("64", "01"))?.first)
        assertEquals("luis", CarKey.identify(keys, fp("65", "00"))?.first)
    }

    @Test
    fun unknownKeyIsNobody() {
        val keys = listOf("isra" to listOf(fp("64")))
        assertNull(CarKey.identify(keys, fp("70")))
        assertEquals("isra", CarKey.identify(keys, fp("64"))?.first)
    }
}
