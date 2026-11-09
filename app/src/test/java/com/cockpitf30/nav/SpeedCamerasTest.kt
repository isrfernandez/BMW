package com.cockpitf30.nav

import com.cockpitf30.weather.Place
import com.cockpitf30.weather.RoutePoint
import com.cockpitf30.weather.RouteWeather
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class SpeedCamerasTest {
    private val overpass = """
        {"elements":[
          {"type":"node","id":1,"lat":40.40,"lon":-3.70,"tags":{"highway":"speed_camera","maxspeed":"80"}},
          {"type":"node","id":2,"lat":40.41,"lon":-3.71},
          {"type":"node","id":3,"lat":40.42,"lon":-3.72,"tags":{"highway":"speed_camera","direction":"90"}},
          {"type":"relation","id":9,"members":[{"type":"node","ref":2,"role":"device"},{"type":"way","ref":5,"role":"section"}],
           "tags":{"type":"enforcement","enforcement":"average_speed","maxspeed":"100 km/h"}}
        ]}
    """.trimIndent()

    @Test fun parsesNodesAndEnforcementRelations() {
        val cams = SpeedCameras.parse(overpass).associateBy { it.id }
        assertEquals(3, cams.size)
        assertEquals(80, cams[1]!!.maxspeed)
        assertNull(cams[1]!!.kind)
        assertEquals("Tramo", cams[2]!!.kind)
        assertEquals(100, cams[2]!!.maxspeed)
        assertEquals(90f, cams[3]!!.direction)
    }

    @Test fun cameraAheadIsDetected() {
        SpeedCameras.preview(CameraState(cameras = listOf(SpeedCamera(1, 40.4045, -3.70, 80), SpeedCamera(2, 40.3955, -3.70))))
        // Hacia el norte: el radar 1 (500 m al norte) queda delante; el 2 detrás.
        SpeedCameras.update(GeoPoint(40.40, -3.70), 0f)
        val s = SpeedCameras.state.value
        assertEquals(2, s.near.size)
        assertEquals(1L, s.ahead?.camera?.id)
        assertTrue(s.ahead!!.meters in 450.0..550.0)
        // Hacia el sur, el delante es el 2.
        SpeedCameras.update(GeoPoint(40.40, -3.70), 180f)
        assertEquals(2L, SpeedCameras.state.value.ahead?.camera?.id)
    }

    @Test fun routePlanWithoutDestinationGoesAhead() {
        val pts = RouteWeather.plan(GeoPoint(40.0, -3.0), 90f, null)
        assertEquals(listOf(10, 25, 50), pts.map { it.first })
        val d = RouteTracker.distance(GeoPoint(40.0, -3.0), pts.last().second)
        assertEquals(50_000.0, d, 500.0)
        assertTrue(pts.last().second.lon > -3.0)
    }

    @Test fun routePlanToDestinationEndsThere() {
        val madrid = Place("Madrid", null, null, 40.4168, -3.7038)
        val pts = RouteWeather.plan(GeoPoint(41.6488, -0.8891), 0f, madrid) // desde Zaragoza
        assertEquals(6, pts.size)
        assertEquals(0.0, RouteTracker.distance(pts.last().second, GeoPoint(madrid.lat, madrid.lon)), 200.0)
    }

    @Test fun hourlyPointAndSummary() {
        val json = JSONObject("""{"hourly":{"time":["2026-11-09T12:00","2026-11-09T13:00","2026-11-09T14:00"],
            "weather_code":[1,61,63],"temperature_2m":[14.2,12.6,11.0],"precipitation_probability":[5,60,80],
            "precipitation":[0,0.6,2.1],"visibility":[24000,8000,3000]}}""")
        val p = RouteWeather.parsePoint(json, LocalDateTime.parse("2026-11-09T12:40"), 25, 20, GeoPoint(0.0, 0.0), null)
        assertEquals(61, p.code)
        assertEquals(13, p.tempC)
        assertEquals(60, p.precipProb)
        val dry = RoutePoint(10, 8, GeoPoint(0.0, 0.0), 1, 14, 5, 0f, 24000f)
        assertEquals("Lluvia en 25 km (60 %)", RouteWeather.summarize(listOf(dry, p)))
        assertEquals("Sin lluvia en ruta", RouteWeather.summarize(listOf(dry)))
    }
}
