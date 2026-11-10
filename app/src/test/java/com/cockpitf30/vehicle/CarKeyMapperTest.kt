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


}
