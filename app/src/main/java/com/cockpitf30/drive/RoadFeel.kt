package com.cockpitf30.drive

import com.cockpitf30.vehicle.telemetry.TelemetryMath
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Lo que «siente» el coche en la carretera: cuánta resistencia lleva ahora frente a lo que le
 * costaría ir a la misma velocidad en llano y sin viento.
 *
 * Ojo con lo que es real: la caja CAN no da par, acelerador ni consumo instantáneo, así que el
 * esfuerzo del motor no se mide. Lo que sí es real es la pendiente (altitud del GPS corregida con
 * el mapa del terreno) y el viento (previsión de Open-Meteo contra el rumbo). Con eso y la física
 * del coche ([TelemetryMath]) sale la resistencia extra. La «resistencia de más» (carga, baca,
 * racha) solo se detecta rodando en punto muerto, ver [CoastMeter].
 */
data class RoadLoad(
    val speedKmh: Float,
    val slopePct: Float,
    /** Componente del viento de cara a la altura del coche, km/h (+ de cara, − de cola). */
    val headwindKmh: Float,
    /** Resistencia total frente a llano y sin viento, en % (40 = un 40 % más). */
    val extraPct: Int,
    /** Parte de ese extra que es la cuesta y la que es el viento, en puntos. */
    val slopePts: Int,
    val windPts: Int,
)

/** Lo que se ve en el cuadro: una línea corta, solo si se nota. */
data class RoadReading(val load: RoadLoad, val label: String?, val dragExcessPct: Int? = null)

object RoadFeel {
    /** El viento de la previsión es a 10 m de altura; a la del coche sopla bastante menos. */
    const val GROUND_WIND = 0.7f
    /** Desde aquí se enseña la línea en el cuadro. */
    const val SHOW_SLOPE_PCT = 4f
    const val SHOW_EXTRA_PCT = 25
    const val SHOW_HEADWIND_KMH = 15f
    /** Por debajo de esto (ciudad, maniobras) no se enseña nada. */
    const val MIN_SPEED_KMH = 40f

    /** Viento de cara (km/h, + de cara) a la altura del coche, con el viento que viene de [fromDeg]. */
    fun headwind(fromDeg: Int, kmh: Int, headingDeg: Float): Float {
        val rel = Math.toRadians((fromDeg - headingDeg).toDouble())
        return (kmh * GROUND_WIND * cos(rel)).toFloat()
    }

    /** Fuerza que frena al coche (N) a [vKmh] con esa pendiente (%) y ese viento de cara (km/h). */
    fun resistance(vKmh: Float, slopePct: Float, headwindKmh: Float): Float {
        val m = TelemetryMath.MASS_KG
        val g = TelemetryMath.G
        val s = slopePct / 100f
        val sinT = s / sqrt(1 + s * s)
        val cosT = 1 / sqrt(1 + s * s)
        val air = (vKmh + headwindKmh) / 3.6f
        val aero = 0.5f * TelemetryMath.AIR_DENSITY * TelemetryMath.CDA_M2 * air * abs(air)
        return TelemetryMath.ROLLING * m * g * cosT + m * g * sinT + aero
    }

    fun load(vKmh: Float, slopePct: Float?, windFromDeg: Int?, windKmh: Int?, headingDeg: Float): RoadLoad {
        val slope = slopePct ?: 0f
        val hw = if (windFromDeg != null && windKmh != null) headwind(windFromDeg, windKmh, headingDeg) else 0f
        val base = resistance(vKmh, 0f, 0f).coerceAtLeast(1f)
        val withSlope = resistance(vKmh, slope, 0f)
        val total = resistance(vKmh, slope, hw)
        val pct = { f: Float -> ((f / base - 1f) * 100f).roundToInt() }
        return RoadLoad(
            speedKmh = vKmh, slopePct = slope, headwindKmh = hw,
            extraPct = pct(total),
            slopePts = pct(withSlope),
            windPts = ((total - withSlope) / base * 100f).roundToInt(),
        )
    }

    /** 192 % más → «2,9» (veces el esfuerzo de ir en llano). */
    fun times(extraPct: Int): String = "%.1f".format(java.util.Locale("es", "ES"), 1 + extraPct / 100f).replace(",0", "")

    /** La velocidad a la que «se siente» el aire: la del coche más el viento de cara. */
    fun airSpeedKmh(l: RoadLoad) = (l.speedKmh + l.headwindKmh).roundToInt()

    /**
     * La línea del cuadro, o null si no hay nada que contar: «Subida 6 % · +45 % esfuerzo»,
     * «Viento de cara 25 km/h · +30 % esfuerzo», «Bajada 5 % · el motor retiene gratis»…
     */
    fun label(l: RoadLoad, dragExcessPct: Int? = null): String? {
        if (l.speedKmh < MIN_SPEED_KMH) return null
        val s = l.slopePct.roundToInt()
        val hw = l.headwindKmh.roundToInt()
        val windy = abs(l.headwindKmh) >= SHOW_HEADWIND_KMH
        val parts = mutableListOf<String>()
        val sloped = abs(l.slopePct) >= SHOW_SLOPE_PCT
        when {
            l.slopePct >= SHOW_SLOPE_PCT -> parts += "Subida $s %"
            l.slopePct <= -SHOW_SLOPE_PCT -> parts += "Bajada ${-s} %"
        }
        // Con cuesta y viento a la vez, el viento sin cifra: la línea tiene que leerse de un vistazo.
        if (windy) parts += when {
            hw > 0 -> if (sloped) "viento de cara" else "viento de cara $hw km/h"
            else -> if (sloped) "viento de cola" else "viento de cola ${-hw} km/h"
        }
        if (parts.isEmpty() && (dragExcessPct ?: 0) < SHOW_EXTRA_PCT) return null
        if (parts.isEmpty()) return "Resistencia extra +$dragExcessPct %"
        val word = " esfuerzo"
        val effort = when {
            l.extraPct >= 100 -> "×${times(l.extraPct)}$word"
            l.extraPct >= SHOW_EXTRA_PCT -> "+${l.extraPct} %$word"
            l.extraPct <= -40 -> "retiene gratis"
            l.extraPct <= -SHOW_EXTRA_PCT -> "${l.extraPct} %$word"
            else -> null
        }
        // Solo con algo que contar del esfuerzo, para no llenar el cuadro de datos sin importancia.
        if (effort == null && abs(l.slopePct) < SHOW_SLOPE_PCT) return null
        return (parts + listOfNotNull(effort)).joinToString(" · ").replaceFirstChar { it.uppercase() }
    }
}

/**
 * Prueba de rodadura: en punto muerto (o con el embrague pisado) el motor no empuja ni retiene,
 * así que lo que frena el coche es solo el aire, la rodadura y la cuesta. Si frena bastante más de
 * lo que dice la física, hay resistencia de más: viento racheado, carga, una baca… Es lo único
 * que se mide de verdad sin datos del motor.
 */
class CoastMeter {
    companion object {
        const val MIN_KMH = 50f
        /** Rpm de ralentí: el motor va suelto. */
        val IDLE_RPM = 600..1050
        const val MIN_SEGMENT_S = 5f
        /** Solo en casi llano: el error de la pendiente del GPS pesaría demasiado. */
        const val MAX_SLOPE_PCT = 2f
        const val MIN_SEGMENTS = 3
        /** Masa equivalente en punto muerto (ruedas y transmisión girando). */
        const val ROT_FACTOR = 1.03f
    }

    private val t = ArrayList<Float>()
    private val v = ArrayList<Float>()
    private val expected = ArrayList<Float>()
    private var ratio = 1f
    var segments = 0
        private set

    /** Cuánto más frena de lo esperado, en % (null hasta tener bastantes pruebas). */
    val excessPct: Int? get() = if (segments >= MIN_SEGMENTS) ((ratio - 1f) * 100f).roundToInt() else null

    fun reset() { t.clear(); v.clear(); expected.clear(); ratio = 1f; segments = 0 }

    /** Cada segundo, con el tiempo en s. */
    fun feed(timeS: Float, speedKmh: Float?, rpm: Int?, slopePct: Float?, headwindKmh: Float) {
        val coasting = speedKmh != null && rpm != null && speedKmh >= MIN_KMH && rpm in IDLE_RPM &&
            abs(slopePct ?: 99f) <= MAX_SLOPE_PCT && (v.isEmpty() || speedKmh / 3.6f <= v.last() + 0.15f)
        if (!coasting) { close(); return }
        t += timeS; v += speedKmh!! / 3.6f
        expected += RoadFeel.resistance(speedKmh, slopePct ?: 0f, headwindKmh) / (TelemetryMath.MASS_KG * CoastMeter.ROT_FACTOR)
    }

    private fun close() {
        if (t.size >= MIN_SEGMENT_S && t.last() - t.first() >= MIN_SEGMENT_S - 1) {
            val a = TelemetryMath.slope(t.toFloatArray(), v.toFloatArray(), t.size)
            val exp = expected.average().toFloat()
            if (a != null && a < 0 && exp > 0.05f) {
                val r = (-a / exp).coerceIn(0.5f, 2.5f)
                ratio = if (segments == 0) r else ratio + (r - ratio) * 0.3f
                segments++
            }
        }
        t.clear(); v.clear(); expected.clear()
    }
}
