package com.cockpitf30.vehicle.raise

import com.cockpitf30.vehicle.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RaiseFramerTest {

    private fun frame(type: Int, vararg data: Int): ByteArray {
        val d = ByteArray(data.size) { data[it].toByte() }
        return byteArrayOf(0x2E, type.toByte(), d.size.toByte()) + d +
            RaiseFramer.checksum(type, d).toByte()
    }

    @Test
    fun parsesFrameSplitAcrossReadsWithGarbage() {
        val frames = mutableListOf<RaiseFrame>()
        val framer = RaiseFramer(onFrame = { frames += it })
        val bytes = byteArrayOf(0x00, 0x13) + frame(0x01, 0x00, 0x0B, 0xB8, 0x50)

        framer.feed(bytes.copyOfRange(0, 4))
        framer.feed(bytes.copyOfRange(4, bytes.size))

        assertEquals(1, frames.size)
        assertEquals(0x01, frames[0].type)
        assertEquals(4, frames[0].data.size)
    }

    @Test
    fun rejectsBadChecksum() {
        var bad = 0
        val frames = mutableListOf<RaiseFrame>()
        val framer = RaiseFramer(onFrame = { frames += it }, onBadChecksum = { bad++ })
        val bytes = frame(0x02, 0x01)
        bytes[bytes.size - 1] = (bytes.last() + 1).toByte()

        framer.feed(bytes)

        assertTrue(frames.isEmpty())
        assertEquals(1, bad)
    }

    @Test
    fun decodesSpeedAndRpm() {
        var state = VehicleState()
        val framer = RaiseFramer(onFrame = { state = RaiseBmwDecoder.apply(it, state) })

        framer.feed(frame(0x01, 0x00, 0x0B, 0xB8, 0x50) + frame(0x02, 0x00, 0b010001))

        assertEquals(3000, state.rpm)
        assertEquals(80, state.speedKmh)
    }
}
