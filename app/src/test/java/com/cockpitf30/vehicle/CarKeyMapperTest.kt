package com.cockpitf30.vehicle

import com.cockpitf30.vehicle.source.CarKeyMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarKeyMapperTest {
    @Test
    fun knownKeys() {
        val s = CarKeyMapper.apply(
            VehicleState(),
            mapOf("canbus.car_speed" to 87, "canbus.rotate_speed" to 2150, "state.wy.car_outtemp" to 12, "canbus.max_steering_value" to 780),
        )
        assertEquals(87, s.speedKmh)
        assertEquals(2150, s.rpm)
        assertEquals(12f, s.outsideTempC)
    }

    @Test
    fun broadcastBeatsGuess() {
        val s = CarKeyMapper.apply(VehicleState(), mapOf("bc.canbus_speed" to 50, "canbus.info.speed" to 10))
        assertEquals(50, s.speedKmh)
    }

    @Test
    fun doorsByName() {
        val s = CarKeyMapper.apply(
            VehicleState(),
            mapOf("canbus.info.door_fl" to 1, "canbus.info.doorRF" to 0, "canbus.state.door_back" to 1, "data.factory.wy.door_alarm" to 1, "canbus.info.doorFront" to 0),
        )
        assertEquals(true, s.doors.driver)
        assertEquals(false, s.doors.passenger)
        assertEquals(true, s.doors.trunk)
        assertEquals(false, s.doors.hood)
        assertNull(s.doors.rearLeft)
    }

    @Test
    fun tyresInKpaOrTenths() {
        val s = CarKeyMapper.apply(VehicleState(), mapOf("canbus.info.tpms_fl" to 240, "canbus.info.tpms_rr" to 23, "canbus.info.tpms_temp_fl" to 30))
        assertEquals(2.4f, s.tyres.frontLeft!!, 0.001f)
        assertEquals(2.3f, s.tyres.rearRight!!, 0.001f)
    }
}
