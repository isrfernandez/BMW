package com.cockpitf30.vehicle.telemetry

import kotlin.math.PI
import kotlin.math.sqrt

/** Cálculos puros de la telemetría (sin Android), separados para poder probarlos. */
object TelemetryMath {
    const val G = 9.80665f

    /** BMW 320d F30 con conductor: masa, aerodinámica y rodadura aproximadas. */
    const val MASS_KG = 1600f
    const val CDA_M2 = 0.27f * 2.22f
    const val AIR_DENSITY = 1.2f
    const val ROLLING = 0.012f
    /** Pérdidas de la transmisión (caja manual y diferencial). */
    const val DRIVETRAIN_EFF = 0.88f
    const val W_PER_CV = 735.5f

    /**
     * Potencia que está dando el motor, en W, a partir de la velocidad [v] (m/s), la aceleración
     * [a] (m/s²) y la pendiente [slope] (tanto por uno, 0.05 = 5 %). Negativa = el coche frena o
     * retiene (el motor no empuja).
     */
    fun enginePowerW(v: Float, a: Float, slope: Float = 0f): Float {
        val sinT = slope / sqrt(1 + slope * slope)
        val cosT = 1 / sqrt(1 + slope * slope)
        val force = MASS_KG * a + MASS_KG * G * sinT + 0.5f * AIR_DENSITY * CDA_M2 * v * v + ROLLING * MASS_KG * G * cosT
        val wheel = force * v
        return if (wheel > 0) wheel / DRIVETRAIN_EFF else wheel
    }

    /** Par del motor en Nm con esa potencia (W) y esas rpm, o null si el motor va casi parado. */
    fun torqueNm(powerW: Float, rpm: Int?): Float? {
        if (rpm == null || rpm < 900) return null
        return powerW / (rpm * 2 * PI.toFloat() / 60f)
    }

    /**
     * Pendiente de la recta que mejor ajusta las muestras (t en segundos, y): con la velocidad da la
     * aceleración y con datos enteros de 1 km/h sale mucho más suave que restar dos valores.
     */
    fun slope(t: FloatArray, y: FloatArray, n: Int = t.size): Float? {
        if (n < 3) return null
        var mt = 0f; var my = 0f
        for (i in 0 until n) { mt += t[i]; my += y[i] }
        mt /= n; my /= n
        var num = 0f; var den = 0f
        for (i in 0 until n) { val dt = t[i] - mt; num += dt * (y[i] - my); den += dt * dt }
        return if (den < 1e-6f) null else num / den
    }

    /** Vector de 3 componentes. */
    data class V3(val x: Float, val y: Float, val z: Float) {
        operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
        operator fun times(k: Float) = V3(x * k, y * k, z * k)
        infix fun dot(o: V3) = x * o.x + y * o.y + z * o.z
        infix fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
        fun norm() = sqrt(this dot this)
        fun unit(): V3 = norm().let { if (it < 1e-6f) this else this * (1 / it) }
    }

    /**
     * Ejes del coche vistos desde la pantalla. [gravity] es lo que marca el acelerómetro parado
     * (apunta hacia arriba). La pantalla mira al conductor, así que su eje x es la derecha del coche;
     * se endereza con la gravedad y el eje hacia delante sale del producto vectorial.
     * Devuelve (derecha, delante).
     */
    fun carAxes(gravity: V3): Pair<V3, V3> {
        val up = gravity.unit()
        val x = V3(1f, 0f, 0f)
        val right = (x - up * (x dot up)).unit()
        val forward = (up cross right).unit()
        return right to forward
    }

    /** Fuerzas G (longitudinal, lateral) de una lectura del acelerómetro con la gravedad en reposo. */
    fun gForces(reading: V3, gravity: V3): Pair<Float, Float> {
        val (right, forward) = carAxes(gravity)
        val d = reading - gravity
        return (d dot forward) / G to (d dot right) / G
    }
}
