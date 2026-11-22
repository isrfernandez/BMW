package com.cockpitf30.drive

import com.cockpitf30.nav.CurveAhead
import com.cockpitf30.nav.GeoPoint
import com.cockpitf30.nav.GradeAhead
import com.cockpitf30.nav.RouteTracker
import kotlin.math.abs
import kotlin.math.roundToInt

/** Lo que hay que avisar de la carretera por delante. */
sealed interface AheadEvent {
    data class Curve(val c: CurveAhead, val inM: Int) : AheadEvent
    data class Grade(val g: GradeAhead, val inM: Int) : AheadEvent
}

/**
 * Decide cuándo avisar de una curva cerrada o una pendiente fuerte que viene: con tiempo (unos
 * 12 s antes, nunca a menos de 80 m), solo si vamos más rápido de lo cómodo para esa curva, y
 * una vez por sitio.
 */
class AheadWatch(private val repeatMs: Long = 10 * 60_000L) {
    companion object {
        const val MIN_CURVE_KMH = 40
        const val LEAD_S = 12f
        const val MIN_LEAD_M = 200f
        const val TOO_CLOSE_M = 80f
        const val SAID_RADIUS_M = 120.0
    }

    private val said = ArrayList<Pair<GeoPoint, Long>>()

    fun reset() = said.clear()

    /** [path] es el camino por delante (de donde salen las distancias de [curves] y [grades]). */
    fun tick(now: Long, speedKmh: Float, path: List<GeoPoint>, curves: List<CurveAhead>, grades: List<GradeAhead>): AheadEvent? {
        said.removeAll { now - it.second > repeatMs }
        if (path.isEmpty()) return null
        val lead = maxOf(MIN_LEAD_M, speedKmh / 3.6f * LEAD_S)
        fun at(m: Float) = path[(m / 10f).roundToInt().coerceIn(0, path.lastIndex)]
        fun fresh(p: GeoPoint) = said.none { RouteTracker.distance(it.first, p) < SAID_RADIUS_M }
        if (speedKmh >= MIN_CURVE_KMH) {
            curves.firstOrNull { it.startM in TOO_CLOSE_M..(lead + 50f) }?.let { c ->
                val p = at(c.startM)
                val fast = speedKmh > c.adviceKmh + 10 || c.minRadiusM < 40f
                if (fast && fresh(p)) { said += p to now; return AheadEvent.Curve(c, round50(c.startM)) }
            }
        }
        if (speedKmh >= 30f) {
            grades.firstOrNull { it.startM in 100f..800f && abs(it.pct) >= 7f }?.let { g ->
                val p = at(g.startM)
                if (fresh(p)) { said += p to now; return AheadEvent.Grade(g, round50(g.startM)) }
            }
        }
        return null
    }

    private fun round50(m: Float) = maxOf(50, (m / 50f).roundToInt() * 50)
}
