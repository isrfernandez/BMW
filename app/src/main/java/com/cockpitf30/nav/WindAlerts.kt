package com.cockpitf30.nav

import com.cockpitf30.weather.RelativeWind
import com.cockpitf30.weather.Wind
import com.cockpitf30.weather.WindNow
import com.cockpitf30.weather.WindPoint

/**
 * Viento en ruta: con el rumbo del coche y el viento de la previsión, avisa cuando lo llevamos de
 * proa, de través, de popa… (ver [Wind.spokenRelative]). Solo cuando sopla con algo de fuerza, con
 * el rumbo estable un rato (no en cada curva) y cuando cambia por dónde entra, con un mínimo entre
 * avisos.
 */
class WindAlerts(
    private val steadyMs: Long = STEADY_MS,
    private val gapMs: Long = GAP_MS,
) {
    companion object {
        /** El rumbo tiene que mantener el mismo viento relativo este tiempo. */
        const val STEADY_MS = 60_000L
        /** Mínimo entre dos avisos de viento. */
        const val GAP_MS = 10 * 60_000L
        /** Si no cambia, se recuerda al cabo de esto (solo si es fuerte y de costado). */
        const val REMIND_MS = 40 * 60_000L
        /** Solo en marcha: por debajo, en ciudad, el viento no importa. */
        const val MIN_SPEED_KMH = 40
        const val DEMO_STEADY_MS = 5_000L
        const val DEMO_GAP_MS = 60_000L

        /** Proa y popa no tienen banda; el resto sí (estribor ≠ babor). */
        fun key(r: RelativeWind) = r.point.name + if (r.point == WindPoint.PROA || r.point == WindPoint.POPA) "" else if (r.starboard) ".E" else ".B"

        fun alert(w: WindNow, r: RelativeWind, now: Long): RouteAlert = Wind.spokenRelative(w, r).let { speech ->
            RouteAlert(
                id = "wind.${key(r)}.$now",
                category = AlertCategory.WIND,
                title = r.point.title,
                subtitle = Wind.subtitle(w, r),
                speech = speech,
                text = speech,
            )
        }
    }

    private var candidate: String? = null
    private var since = 0L
    private var lastKey: String? = null
    private var lastAt = 0L

    fun reset() { candidate = null; since = 0L; lastKey = null; lastAt = 0L }

    /**
     * Cada segundo. Devuelve el aviso si toca. [speedKmh] null = sin dato (en demo se da por bueno).
     */
    fun tick(w: WindNow?, heading: Float, speedKmh: Int?, now: Long, demo: Boolean): RouteAlert? {
        if (w == null || (w.kmh < Wind.ROUTE_MIN_KMH && (w.gustKmh ?: 0) < Wind.ROUTE_MIN_GUST_KMH)) { candidate = null; return null }
        if (!demo && (speedKmh ?: 0) < MIN_SPEED_KMH) { candidate = null; return null }
        val r = Wind.relative(w.fromDeg, heading)
        val k = key(r)
        if (k != candidate) { candidate = k; since = now; return null }
        if (now - since < (if (demo) DEMO_STEADY_MS else steadyMs)) return null
        val gap = if (demo) DEMO_GAP_MS else gapMs
        if (lastKey != null && now - lastAt < gap) return null
        val strongSide = (r.point == WindPoint.TRAVES || r.point == WindPoint.AMURA) &&
            (w.kmh >= Wind.STRONG_KMH || (w.gustKmh ?: 0) >= Wind.STRONG_GUST_KMH)
        if (k == lastKey && !(strongSide && now - lastAt >= REMIND_MS)) return null
        lastKey = k
        lastAt = now
        return alert(w, r, now)
    }
}
