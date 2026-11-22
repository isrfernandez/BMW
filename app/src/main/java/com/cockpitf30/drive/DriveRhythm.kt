package com.cockpitf30.drive

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Cómo se está conduciendo hoy, para que el copiloto ajuste el carácter: de paseo charla más y
 * bromea, en autopista va al grano y en ciudad solo dice lo práctico.
 */
enum class Rhythm(val label: String) {
    PASEO("Paseo"),
    AUTOPISTA("Autopista"),
    CIUDAD("Ciudad"),
    ;

    /** Humor que usa el asistente con este ritmo, partiendo del elegido en Ajustes. */
    fun humor(base: Int): Int = when (this) {
        PASEO -> (base + 15).coerceAtMost(100)
        AUTOPISTA -> (base * 0.6f).roundToInt()
        CIUDAD -> (base * 0.25f).roundToInt()
    }

    /** Cuánto se alarga el rato entre dos frases del copiloto. */
    val intervalFactor: Float get() = when (this) {
        PASEO -> 0.75f
        AUTOPISTA -> 1f
        CIUDAD -> 2.5f
    }

    /** Parte de las veces que el copiloto prefiere charla (preguntas, adivinanzas) a datos del viaje. */
    val idleShare: Double get() = when (this) {
        PASEO -> 0.6
        AUTOPISTA -> 0.15
        CIUDAD -> 0.0
    }
}

/** Lo medido en el último rato, para Diagnóstico y para decidir el ritmo. */
data class RhythmStats(
    /** Minutos en movimiento dentro de la ventana. */
    val minutes: Float,
    val km: Float,
    /** Media en movimiento. */
    val avgKmh: Float,
    /** Parte del tiempo en movimiento a 80 km/h o más. */
    val fastShare: Float,
    val stopsPerKm: Float,
    /** Acelerones y frenazos (más de ~0,18 g) por km. */
    val eventsPerKm: Float,
    val shiftsPerKm: Float,
) {
    fun summary(): String = "%.0f min · %.1f km · media %.0f km/h · rápido %.0f %% · paradas %.2f/km · acel/fren %.2f/km · cambios %.2f/km"
        .format(java.util.Locale.US, minutes, km, avgKmh, fastShare * 100, stopsPerKm, eventsPerKm, shiftsPerKm)
}

/**
 * Mide el ritmo con lo que da el coche cada segundo: velocidad CAN, fuerza G longitudinal (que
 * sale de esa misma velocidad), marcha deducida y la hora. Se queda con los últimos [windowMs]
 * y solo cambia de ritmo si el nuevo se mantiene [holdMs], para no saltar en cada semáforo.
 */
class RhythmMeter(
    private val windowMs: Long = WINDOW_MS,
    private val holdMs: Long = HOLD_MS,
) {
    companion object {
        const val WINDOW_MS = 8 * 60_000L
        const val HOLD_MS = 90_000L
        /** Hace falta este rato en movimiento antes de decidir nada. */
        const val MIN_MOVING_MS = 3 * 60_000L
        const val MIN_KM = 1f
        private const val EVENT_ON_G = 0.18f
        private const val EVENT_OFF_G = 0.10f

        /**
         * El ritmo que corresponde a lo medido. [weekend] incluye festivos si se sabe; [hour] es la
         * hora local.
         */
        fun classify(s: RhythmStats, weekend: Boolean, hour: Int): Rhythm {
            val rush = !weekend && (hour in 7..9 || hour in 17..19)
            return when {
                s.avgKmh < 35f || s.stopsPerKm > 0.8f -> Rhythm.CIUDAD
                s.avgKmh < 50f && s.eventsPerKm > 3f -> Rhythm.CIUDAD
                rush && s.avgKmh < 60f && s.eventsPerKm > 2f -> Rhythm.CIUDAD
                // Un domingo a ritmo tranquilo, aunque sea por autovía, es un paseo.
                weekend && s.avgKmh < 95f && s.eventsPerKm < 1f -> Rhythm.PASEO
                s.fastShare > 0.6f && s.avgKmh >= 75f && s.eventsPerKm < 1.5f -> Rhythm.AUTOPISTA
                else -> Rhythm.PASEO
            }
        }
    }

    private class Sample(val t: Long, val kmh: Float, val meters: Float, val event: Boolean, val stop: Boolean, val shift: Boolean)

    private val samples = ArrayDeque<Sample>()
    private var lastT = 0L
    private var inEvent = false
    private var wasMoving = false
    private var lastGear: String? = null
    private var candidate: Rhythm? = null
    private var candidateSince = 0L

    var rhythm: Rhythm? = null
        private set
    var stats: RhythmStats? = null
        private set

    fun reset() {
        samples.clear(); lastT = 0L; inEvent = false; wasMoving = false; lastGear = null
        candidate = null; candidateSince = 0L; rhythm = null; stats = null
    }

    /** Cada segundo. Devuelve el ritmo nuevo si acaba de cambiar, si no null. */
    fun feed(now: Long, speedKmh: Float?, longG: Float, gear: String?, weekend: Boolean, hour: Int): Rhythm? {
        val v = speedKmh ?: 0f
        val dt = if (lastT == 0L) 0f else ((now - lastT) / 1000f).coerceIn(0f, 5f)
        lastT = now
        val moving = v >= 3f
        val stop = wasMoving && v < 3f
        if (v >= 10f) wasMoving = true else if (v < 3f) wasMoving = false
        val g = abs(longG)
        val event = moving && !inEvent && g >= EVENT_ON_G
        if (g >= EVENT_ON_G) inEvent = true else if (g <= EVENT_OFF_G) inEvent = false
        val digit = gear?.takeIf { it.length == 1 && it[0].isDigit() }
        val shift = digit != null && lastGear != null && digit != lastGear
        if (digit != null) lastGear = digit
        samples.addLast(Sample(now, v, v / 3.6f * dt, event, stop, shift))
        while (samples.isNotEmpty() && now - samples.first().t > windowMs) samples.removeFirst()

        val movingS = samples.filter { it.kmh >= 3f }
        val movingMs = movingS.size * 1000L
        val km = samples.sumOf { it.meters.toDouble() }.toFloat() / 1000f
        if (movingMs < MIN_MOVING_MS || km < MIN_KM) { stats = null; return null }
        val st = RhythmStats(
            minutes = movingMs / 60_000f,
            km = km,
            avgKmh = movingS.map { it.kmh }.average().toFloat(),
            fastShare = movingS.count { it.kmh >= 80f }.toFloat() / movingS.size,
            stopsPerKm = samples.count { it.stop } / km,
            eventsPerKm = samples.count { it.event } / km,
            shiftsPerKm = samples.count { it.shift } / km,
        )
        stats = st
        val now0 = classify(st, weekend, hour)
        if (rhythm == null) { rhythm = now0; candidate = now0; return now0 }
        if (now0 == rhythm) { candidate = now0; return null }
        if (now0 != candidate) { candidate = now0; candidateSince = now; return null }
        if (now - candidateSince < holdMs) return null
        rhythm = now0
        return now0
    }
}
