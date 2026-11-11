package com.cockpitf30.vehicle

/** Consejo de cambio de marcha del cuadro. */
enum class Shift { UP, DOWN }

/**
 * Indicador de cambio para la caja manual de 6 del 320d: aconseja subir cuando el motor ya va
 * alegre y en la siguiente marcha seguiría por encima del mínimo, y bajar cuando va ahogado. En
 * Sport los umbrales suben (estirar las marchas). El consejo tiene que mantenerse [HOLD_MS] para
 * salir, y se quita en cuanto deja de cumplirse: no parpadea al pasar por el umbral.
 */
class ShiftAdvisor(private val ratios: FloatArray = GearEstimator.KMH_PER_1000_RPM) {
    /** Umbrales de un modo, en rpm. */
    data class Limits(val upAt: Int, val minAfterUp: Int, val downBelow: Int, val maxAfterDown: Int)

    companion object {
        const val HOLD_MS = 1_200L
        /** Diésel: par desde 1.500 rpm; por debajo de 1.150 va ahogado. */
        val ECO = Limits(upAt = 2_100, minAfterUp = 1_350, downBelow = 1_150, maxAfterDown = 2_300)
        val SPORT = Limits(upAt = 4_000, minAfterUp = 2_500, downBelow = 1_900, maxAfterDown = 4_200)
    }

    private var candidate: Shift? = null
    private var since = 0L
    var current: Shift? = null
        private set

    /** Lo que aconsejaría ahora mismo sin esperar (puro). */
    fun raw(gear: Int?, rpm: Int?, speedKmh: Int?, limits: Limits): Shift? {
        if (gear == null || rpm == null || speedKmh == null || speedKmh < 8 || gear !in 1..ratios.size) return null
        val i = gear - 1
        if (gear < ratios.size && rpm >= limits.upAt) {
            val after = rpm * ratios[i] / ratios[i + 1]
            if (after >= limits.minAfterUp) return Shift.UP
        }
        if (gear > 1 && rpm < limits.downBelow) {
            val after = rpm * ratios[i] / ratios[i - 1]
            if (after <= limits.maxAfterDown) return Shift.DOWN
        }
        return null
    }

    /** Actualiza con una lectura nueva y devuelve el consejo a mostrar. */
    fun update(now: Long, gear: Int?, rpm: Int?, speedKmh: Int?, limits: Limits): Shift? {
        val r = raw(gear, rpm, speedKmh, limits)
        if (r != candidate) { candidate = r; since = now }
        current = when {
            r == null -> null
            r == current -> r
            now - since >= HOLD_MS -> r
            else -> current.takeIf { it == r }
        }
        return current
    }
}
