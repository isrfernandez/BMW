package com.cockpitf30.vehicle.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AxmlStringsTest {

    /** Construye un XML binario mínimo: cabecera + tabla de cadenas. */
    private fun axml(strings: List<String>, utf8: Boolean): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = strings.map { s ->
            val at = data.size()
            if (utf8) {
                val b = s.toByteArray(Charsets.UTF_8)
                data.write(s.length); data.write(b.size); data.write(b); data.write(0)
            } else {
                data.write(s.length and 0xFF); data.write(s.length shr 8)
                s.forEach { c -> data.write(c.code and 0xFF); data.write(c.code shr 8) }
                data.write(0); data.write(0)
            }
            at
        }
        while (data.size() % 4 != 0) data.write(0)
        val headerSize = 28
        val poolSize = headerSize + strings.size * 4 + data.size()
        val pool = ByteBuffer.allocate(poolSize).order(ByteOrder.LITTLE_ENDIAN)
        pool.putShort(1).putShort(headerSize.toShort()).putInt(poolSize).putInt(strings.size).putInt(0)
            .putInt(if (utf8) 0x100 else 0).putInt(headerSize + strings.size * 4).putInt(0)
        offsets.forEach { pool.putInt(it) }
        pool.put(data.toByteArray())
        val all = ByteBuffer.allocate(8 + poolSize).order(ByteOrder.LITTLE_ENDIAN)
        all.putShort(3).putShort(8).putInt(8 + poolSize).put(pool.array())
        return all.array()
    }

    @Test
    fun readsUtf8AndUtf16Pools() {
        val strings = listOf("manifest", "com.tw.canbus.ACTION_DATA", "ñandú")
        assertEquals(strings, AxmlStrings.parse(axml(strings, utf8 = true)))
        assertEquals(strings, AxmlStrings.parse(axml(strings, utf8 = false)))
    }

    @Test
    fun ignoresGarbage() {
        assertEquals(emptyList<String>(), AxmlStrings.parse(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun recognisesActionNames() {
        assertTrue(AxmlStrings.looksLikeAction("com.tw.canbus.ACTION_DATA"))
        assertTrue(AxmlStrings.looksLikeAction("com.wy.can.action.door"))
        assertTrue(AxmlStrings.looksLikeAction("android.intent.action.BOOT_COMPLETED"))
        assertFalse(AxmlStrings.looksLikeAction("com.example.MainActivity"))
        assertFalse(AxmlStrings.looksLikeAction("hello world.ACTION.X"))
    }
}
