package com.cockpitf30.vehicle.probe

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DexStringsTest {
    private fun dex(strings: List<String>): ByteArray {
        val header = 0x70
        val ids = header
        var dataOff = ids + strings.size * 4
        val data = strings.map { it.toByteArray(Charsets.UTF_8) }
        val size = dataOff + data.sumOf { it.size + 2 }
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("dex\n035\u0000".toByteArray(Charsets.ISO_8859_1))
        buf.putInt(0x38, strings.size).putInt(0x3C, ids)
        data.forEachIndexed { i, b ->
            buf.putInt(ids + i * 4, dataOff)
            buf.put(dataOff, b.size.toByte()) // ULEB128 de un byte
            for (j in b.indices) buf.put(dataOff + 1 + j, b[j])
            buf.put(dataOff + 1 + b.size, 0)
            dataOff += b.size + 2
        }
        return buf.array()
    }

    @Test
    fun readsStringTable() {
        val s = listOf("Landroid/car/server/ICarService;", "content://car_data/door", "speed")
        assertEquals(s, DexStrings.parse(dex(s)))
    }

    @Test
    fun ignoresNonDex() {
        assertEquals(emptyList<String>(), DexStrings.parse(ByteArray(200)))
    }
}
