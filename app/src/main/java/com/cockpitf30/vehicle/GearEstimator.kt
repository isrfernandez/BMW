package com.cockpitf30.vehicle

import kotlin.math.abs

/**
 * El F30 de este coche es diésel con cambio manual de 6 velocidades: el CAN no da la marcha
 * metida, así que se deduce de la relación velocidad / rpm.
 *
 * Tabla del 320d manual (caja GS6: 4,11 · 2,25 · 1,40 · 1,00 · 0,83 · 0,69; grupo ≈2,8; rueda
 * ≈1,98 m de desarrollo). La de antes era un 20 % más corta y daba una marcha de más.
 * Además se ajusta sola conduciendo: cada vez que la relación cae cerca de una marcha, corrige
 * poco a poco una escala común (neumáticos distintos, velocímetro que marca de más…).
 */
object GearEstimator {
    val KMH_PER_1000_RPM = floatArrayOf(10.3f, 18.8f, 30.1f, 42.3f, 50.9f, 61.2f)

    /** Escala aprendida sobre la tabla (1 = tal cual). */
    @Volatile var scale = 1f
        private set

    /** Margen admitido respecto a la relación de cada marcha (embrague pisado, cambiando...). */
    private const val TOLERANCE = 0.12f
    private const val LEARN_TOLERANCE = 0.06f
    private const val LEARN_RATE = 0.01f

    /** "1".."6", "N" parado, o null si no se puede saber (sin datos o embrague pisado). */
    fun estimate(speedKmh: Int?, rpm: Int?): String? {
        if (speedKmh == null || rpm == null) return null
        if (speedKmh < 3) return "N"
        if (rpm < 600) return null
        val ratio = speedKmh * 1000f / rpm / scale
        val best = KMH_PER_1000_RPM.indices.minBy { abs(KMH_PER_1000_RPM[it] - ratio) / KMH_PER_1000_RPM[it] }
        val err = abs(KMH_PER_1000_RPM[best] - ratio) / KMH_PER_1000_RPM[best]
        // Aprende solo con marcha clara, en movimiento y con el motor tirando.
        if (err <= LEARN_TOLERANCE && speedKmh >= 15 && rpm >= 1100) {
            scale = (scale * (1 - LEARN_RATE) + scale * (ratio / KMH_PER_1000_RPM[best]) * LEARN_RATE).coerceIn(0.85f, 1.15f)
        }
        return if (err <= TOLERANCE) "${best + 1}" else null
    }

    /** Marcha razonable para circular a esa velocidad (usada por el modo Demo). */
    fun gearFor(speedKmh: Int): Int = when {
        speedKmh < 20 -> 1
        speedKmh < 35 -> 2
        speedKmh < 55 -> 3
        speedKmh < 75 -> 4
        speedKmh < 95 -> 5
        else -> 6
    }
}
