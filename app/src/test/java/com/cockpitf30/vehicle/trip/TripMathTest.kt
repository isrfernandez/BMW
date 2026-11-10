package com.cockpitf30.vehicle.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripMathTest {
    private fun drive(start: Long, seconds: Int, kmh: Int?, gps: Double = 0.0, fuel: Float? = null): Trip {
        var t = TripMath.begin(TripInput(start, kmh, fuelLevel = fuel))
        for (i in 1..seconds) t = TripMath.step(t, TripInput(start + i * 1000L, kmh, gps, fuel))
        return t
    }

    @Test
    fun distanceAndAverageFromSpeed() {
        val t = drive(0, 3600, 60)
        assertEquals(60.0, t.distanceKm, 0.01)
        assertEquals(60, t.avgSpeedKmh)
        assertEquals(60, t.maxSpeedKmh)
        assertEquals(3_600_000L, t.movingMillis)
    }

    @Test
    fun gpsUsedWhenNoSpeed() {
        val t = drive(0, 100, null, gps = 10.0)
        assertEquals(1.0, t.distanceKm, 0.001)
        assertEquals(36, t.maxSpeedKmh)
    }

    @Test
    fun gpsJumpsAreCappedToRealisticSpeed() {
        // Posiciones que saltan 170 m cada segundo (612 km/h): no pueden contar como distancia.
        val t = drive(0, 600, null, gps = 170.0)
        assertTrue(t.maxSpeedKmh <= TripMath.MAX_KMH)
        assertTrue(t.avgSpeedKmh!! <= TripMath.MAX_KMH)
    }

    @Test
    fun absurdSavedAverageIsHidden() {
        val t = Trip(startMillis = 0, lastMillis = 600_000, distanceM = 102_500.0, maxSpeedKmh = 700)
        assertNull(t.avgSpeedKmh)
    }

    @Test
    fun averageNeverAboveMax() {
        val t = Trip(startMillis = 0, lastMillis = 3_600_000, distanceM = 80_000.0, maxSpeedKmh = 70)
        assertEquals(70, t.avgSpeedKmh)
    }

    @Test
    fun gapsDoNotAddDistance() {
        var t = TripMath.begin(TripInput(0, 50))
        t = TripMath.step(t, TripInput(60_000, 50))
        assertEquals(50 / 3.6 * 5, t.distanceM, 0.01)
    }

    @Test
    fun endsAfterLongStopAtLastMovement() {
        var t = drive(0, 60, 50)
        for (i in 1..(TripMath.PAUSE_MS / 1000 + 5).toInt()) t = TripMath.step(t, TripInput(60_000L + i * 1000L, 0))
        assertTrue(TripMath.isOver(t, t.lastMillis))
        val done = TripMath.finish(t)
        assertEquals(60_000L, done.endMillis)
        assertEquals(60_000L, done.durationMillis)
    }

    @Test
    fun consumptionFromFuelLevelOnlyAfterEnoughKm() {
        val short = drive(0, 60, 60, fuel = 0.5f).copy(fuelNow = 0.45f)
        assertNull(short.avgConsumption(60f))
        val long = drive(0, 1800, 100, fuel = 0.5f).copy(fuelNow = 0.45f) // 50 km, 3 l
        assertEquals(6.0f, long.avgConsumption(60f)!!, 0.01f)
    }

    @Test
    fun carConsumptionWins() {
        val t = TripMath.step(TripMath.begin(TripInput(0, 10, carConsumption = 7.2f)), TripInput(1000, 10))
        assertEquals(7.2f, t.avgConsumption(60f)!!, 0.001f)
        assertTrue(t.consumptionFromCar)
    }

    @Test
    fun samplesStayBounded() {
        val t = drive(0, 6 * 3600, 80)
        assertTrue(t.speedSamples.size < TripMath.MAX_SAMPLES)
        assertTrue(t.speedSamples.size > TripMath.MAX_SAMPLES / 4)
        assertTrue(t.speedSamples.all { it == 80 })
    }

    @Test
    fun jsonRoundTrip() {
        val t = drive(1_000, 120, 70, fuel = 0.6f)
        val back = Trip.fromJson(t.toJson())
        assertNotNull(back)
        assertEquals(t, back)
        assertFalse(back!!.finished)
    }
}
