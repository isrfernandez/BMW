package com.cockpitf30.nav

import com.cockpitf30.drive.AheadEvent
import com.cockpitf30.drive.AheadWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class RoadAheadTest {
    private val lat0 = 40.0
    private val lon0 = -3.7
    private fun pt(eastM: Double, northM: Double) = GeoPoint(lat0 + northM / 111_320.0, lon0 + eastM / (111_320.0 * cos(Math.toRadians(lat0))))

    /** Recta hacia el norte de 600 m y luego curva a la derecha de radio [r] y 90°, y otra recta al este. */
    private fun road(r: Double): List<RoadWay> {
        val a = (0..60).map { pt(0.0, it * 10.0) }
        val arc = (1..18).map { k -> val t = Math.toRadians(k * 5.0); pt(r - r * cos(t), 600 + r * sin(t)) }
        val end = arc.last()
        val b = (1..40).map { GeoPoint(end.lat, end.lon + it * 10.0 / (111_320.0 * cos(Math.toRadians(lat0)))) }
        val pts1 = a + arc
        val w1 = RoadWay(1, LongArray(pts1.size) { it.toLong() + 1 }, pts1, 0, "secondary")
        val pts2 = listOf(pts1.last()) + b
        val w2 = RoadWay(2, LongArray(pts2.size) { if (it == 0) pts1.size.toLong() else 1000L + it }, pts2, 0, "secondary")
        return listOf(w1, w2)
    }

    @Test fun findsSharpCurveAhead() {
        val path = RoadAhead.path(road(50.0), pt(2.0, 100.0), 0f, 2000f)!!
        val curves = RoadAhead.curves(path)
        assertEquals(1, curves.size)
        val c = curves[0]
        assertTrue("${c.startM}", c.startM in 440f..520f)
        assertTrue(c.right)
        assertTrue("${c.turnDeg}", c.turnDeg in 75f..105f)
        assertTrue("${c.minRadiusM}", c.minRadiusM in 35f..75f)
        assertTrue("${c.adviceKmh}", c.adviceKmh in 40..50)
    }

    @Test fun gentleCurveIsIgnored() {
        val path = RoadAhead.path(road(600.0), pt(0.0, 100.0), 0f, 2000f)!!
        assertTrue(RoadAhead.curves(path).isEmpty())
    }

    @Test fun wrongWayOrFarIsNotMatched() {
        assertNull(RoadAhead.path(road(50.0), pt(200.0, 100.0), 0f, 2000f))
        // Hacia el sur por la misma vía: el camino va al revés y no ve la curva.
        val back = RoadAhead.path(road(50.0), pt(0.0, 300.0), 180f, 2000f)!!
        assertTrue(RoadAhead.curves(back).isEmpty())
    }

    @Test fun steepGrade() {
        val alt = listOf(800f, 800f, 799f, 790f, 781f, 772f, 763f, 760f, 760f)
        val g = RoadAhead.steepGrades(alt, 100f)
        assertEquals(1, g.size)
        assertTrue("${g[0].pct}", g[0].pct < -7f)
    }

    @Test fun warnsOnceWithLead() {
        val path = RoadAhead.path(road(50.0), pt(0.0, 100.0), 0f, 2000f)!!
        val curves = RoadAhead.curves(path)
        val w = AheadWatch()
        // A 90 km/h el aviso es a unos 300 m: la curva a ~500 m aún no.
        assertNull(w.tick(0, 90f, path, curves, emptyList()))
        val near = curves.map { it.copy(startM = 280f) }
        val e = w.tick(1000, 90f, path, near, emptyList())
        assertTrue(e is AheadEvent.Curve)
        assertNull(w.tick(2000, 90f, path, near, emptyList()))
        // Despacio no hace falta avisar.
        assertNull(AheadWatch().tick(0, 45f, path, near, emptyList()))
    }
}
