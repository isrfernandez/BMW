package com.cockpitf30.vehicle

import com.cockpitf30.vehicle.source.CanBoxFrames
import com.cockpitf30.vehicle.source.CarKeyMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanBoxFramesTest {
    private fun hex(s: String) = s.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    @Test fun realBaseFrameFromTheCar() {
        // Trama real del informe de la prueba completa (motor parado, puertas cerradas, freno puesto).
        val frames = CanBoxFrames.split(hex("5A A5 09 12 01 00 00 01 00 12 00 28 00 56"))
        assertEquals(1, frames.size)
        val v = CanBoxFrames.decode(frames[0])
        assertEquals(true, v["can.acc"])
        assertEquals(true, v["can.handbrake"])
        assertEquals(false, v["can.door_fl"])
        assertEquals(18, v["can.fuel_liters"])
        assertEquals(-40, v["can.exterior_temp_raw"])
        assertTrue("la fórmula de la radio no vale para el F30", "can.out_temp" !in v)
    }

    @Test fun badChecksumIsRejected() {
        var bad = 0
        val frames = CanBoxFrames.split(hex("5A A5 09 12 01 00 00 01 00 12 00 28 00 57")) { bad++ }
        assertTrue(frames.isEmpty())
        assertEquals(1, bad)
    }

    @Test fun doorsAndDynamicInfoGoIntoTheState() {
        val doors = CanBoxFrames.make(0x12, hex("01 00 00 00 A8 20 40 95"))  // conductor, tras. izq., maletero
        val dyn = CanBoxFrames.make(0x16, hex("00 32 03 20"))               // 50 km/h, 800 rpm
        val travel = CanBoxFrames.make(0x27, hex("01 E2 40 01 F4"))          // 123456 km, 500 km
        val values = (CanBoxFrames.split(doors + dyn + travel)).flatMap { CanBoxFrames.decode(it).entries }.associate { it.key to it.value }
        val s = CarKeyMapper.apply(VehicleState(), values)
        assertEquals(50, s.speedKmh)
        assertEquals(800, s.rpm)
        assertEquals(500, s.rangeKm)
        assertEquals(Doors(driver = true, passenger = false, rearLeft = true, rearRight = false, trunk = true, hood = false), s.doors)
        assertEquals(123456, values["can.mileage"])
        assertEquals(true, values["can.belt_driver"])
    }

    @Test fun speedQueryIsTheSameTheRadioSends() {
        assertEquals("5A A5 01 90 16 A6", CanBoxFrames.query(0x16).joinToString(" ") { "%02X".format(it) })
    }
}
