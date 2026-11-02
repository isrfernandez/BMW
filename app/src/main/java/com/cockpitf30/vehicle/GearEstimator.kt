package com.cockpitf30.vehicle

import kotlin.math.abs

/**
 * El F30 de este coche es diésel con cambio manual de 6 velocidades: el CAN no da la marcha
 * metida, así que se deduce de la relación velocidad / rpm. Los km/h por cada 1000 rpm son
 * aproximados para un 320d manual; si en el coche no cuadran, se ajustan aquí.
 */
object GearEstimator {
    val KMH_PER_1000_RPM = floatArrayOf(8.3f, 15.2f, 23.6f, 31.6f, 39.8f, 48.6f)

    /** Margen admitido respecto a la relación de cada marcha (embrague pisado, cambiando...). */
    private const val TOLERANCE = 0.15f

    /** "1".."6", "N" parado, o null si no se puede saber (sin datos o embrague pisado). */
    fun estimate(speedKmh: Int?, rpm: Int?): String? {
        if (speedKmh == null || rpm == null) return null
        if (speedKmh < 3) return "N"
        if (rpm < 600) return null
        val ratio = speedKmh * 1000f / rpm
        val best = KMH_PER_1000_RPM.indices.minBy { abs(KMH_PER_1000_RPM[it] - ratio) / KMH_PER_1000_RPM[it] }
        val err = abs(KMH_PER_1000_RPM[best] - ratio) / KMH_PER_1000_RPM[best]
        return if (err <= TOLERANCE) "${best + 1}" else null
    }

    /** Marcha razonable para circular a esa velocidad (usada por el modo Demo). */
    fun gearFor(speedKmh: Int): Int = when {
        speedKmh < 18 -> 1
        speedKmh < 32 -> 2
        speedKmh < 50 -> 3
        speedKmh < 68 -> 4
        speedKmh < 88 -> 5
        else -> 6
    }
}
