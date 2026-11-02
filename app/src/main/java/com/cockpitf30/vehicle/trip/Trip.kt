package com.cockpitf30.vehicle.trip

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Un viaje: desde que el coche empieza a moverse hasta que lleva un rato parado
 * ([TripMath.PAUSE_MS]). Todo se calcula en la app con la velocidad (o el GPS si no llega
 * la velocidad), salvo el consumo del ordenador de a bordo si el coche lo manda.
 */
data class Trip(
    val startMillis: Long,
    /** Última actualización; mientras dura el viaje es "ahora". */
    val lastMillis: Long = startMillis,
    /** Último instante en movimiento: la hora de fin cuando se cierra el viaje. */
    val lastMoveMillis: Long = startMillis,
    val finished: Boolean = false,
    val distanceM: Double = 0.0,
    /** Tiempo con el coche en movimiento (sin contar semáforos ni atascos parados). */
    val movingMillis: Long = 0,
    val maxSpeedKmh: Int = 0,
    /** Nivel de depósito (0..1) al empezar y ahora, para estimar el consumo. */
    val fuelStart: Float? = null,
    val fuelNow: Float? = null,
    /** Consumo medio que manda el ordenador de a bordo (l/100 km), si llega. */
    val carConsumption: Float? = null,
    /** Velocidad media de cada tramo de [sampleMs], para la gráfica. */
    val speedSamples: List<Int> = emptyList(),
    val sampleMs: Long = TripMath.FIRST_SAMPLE_MS,
    /** Acumulado del tramo en curso de la gráfica. */
    val bucketSum: Double = 0.0,
    val bucketMs: Long = 0,
) {
    val endMillis: Long get() = if (finished) lastMoveMillis else lastMillis
    val durationMillis: Long get() = (endMillis - startMillis).coerceAtLeast(0)
    val distanceKm: Double get() = distanceM / 1000.0

    /** Velocidad media sobre la duración total, como el ordenador de a bordo de BMW. */
    val avgSpeedKmh: Int?
        get() = if (durationMillis < 60_000) null else (distanceKm / (durationMillis / 3_600_000.0)).roundToInt()

    /** Litros gastados según el nivel del depósito, si ha bajado algo. */
    fun fuelUsedLiters(tankLiters: Float): Float? {
        val a = fuelStart ?: return null
        val b = fuelNow ?: return null
        val used = (a - b) * tankLiters
        return used.takeIf { it > 0f }
    }

    /**
     * Consumo medio en l/100 km: el del coche si lo manda; si no, estimado por lo que ha bajado
     * el depósito (el aforador es poco preciso, así que solo a partir de [TripMath.MIN_KM_FOR_FUEL] km).
     */
    fun avgConsumption(tankLiters: Float): Float? {
        carConsumption?.let { return it }
        if (distanceKm < TripMath.MIN_KM_FOR_FUEL) return null
        val used = fuelUsedLiters(tankLiters) ?: return null
        return (used / distanceKm.toFloat() * 100f).takeIf { it in 1f..40f }
    }

    val consumptionFromCar: Boolean get() = carConsumption != null

    fun toJson(): String = JSONObject().apply {
        put("start", startMillis)
        put("last", lastMillis)
        put("lastMove", lastMoveMillis)
        put("finished", finished)
        put("dist", distanceM)
        put("moving", movingMillis)
        put("max", maxSpeedKmh)
        fuelStart?.let { put("fuelStart", it.toDouble()) }
        fuelNow?.let { put("fuelNow", it.toDouble()) }
        carConsumption?.let { put("carCons", it.toDouble()) }
        put("samples", JSONArray(speedSamples))
        put("sampleMs", sampleMs)
        put("bucketSum", bucketSum)
        put("bucketMs", bucketMs)
    }.toString()

    companion object {
        fun fromJson(json: String?): Trip? = json?.let {
            runCatching {
                val o = JSONObject(it)
                val samples = o.optJSONArray("samples")
                Trip(
                    startMillis = o.getLong("start"),
                    lastMillis = o.getLong("last"),
                    lastMoveMillis = o.optLong("lastMove", o.getLong("last")),
                    finished = o.optBoolean("finished"),
                    distanceM = o.optDouble("dist", 0.0),
                    movingMillis = o.optLong("moving"),
                    maxSpeedKmh = o.optInt("max"),
                    fuelStart = if (o.has("fuelStart")) o.getDouble("fuelStart").toFloat() else null,
                    fuelNow = if (o.has("fuelNow")) o.getDouble("fuelNow").toFloat() else null,
                    carConsumption = if (o.has("carCons")) o.getDouble("carCons").toFloat() else null,
                    speedSamples = if (samples == null) emptyList() else List(samples.length()) { i -> samples.getInt(i) },
                    sampleMs = o.optLong("sampleMs", TripMath.FIRST_SAMPLE_MS),
                    bucketSum = o.optDouble("bucketSum", 0.0),
                    bucketMs = o.optLong("bucketMs"),
                )
            }.getOrNull()
        }
    }
}

/** Lo que llega del coche en cada paso del cálculo. */
data class TripInput(
    val now: Long,
    val speedKmh: Int?,
    /** Metros recorridos según el GPS desde el paso anterior (se usa si no hay velocidad). */
    val gpsMeters: Double = 0.0,
    val fuelLevel: Float? = null,
    val carConsumption: Float? = null,
)

/** Cálculo puro del viaje, sin Android, para poder probarlo. */
object TripMath {
    /** Parado más de este tiempo (o la radio apagada) = fin del viaje. */
    const val PAUSE_MS = 10 * 60_000L
    /** Un hueco entre lecturas mayor que esto no suma distancia (la radio estaba dormida). */
    const val MAX_STEP_MS = 5_000L
    const val MOVING_KMH = 3
    const val FIRST_SAMPLE_MS = 15_000L
    const val MAX_SAMPLES = 240
    const val MIN_KM_FOR_FUEL = 15.0

    fun isMoving(input: TripInput) = (input.speedKmh ?: 0) >= MOVING_KMH || (input.speedKmh == null && input.gpsMeters > 3.0)

    /** Empieza un viaje nuevo en este instante. */
    fun begin(input: TripInput) = Trip(
        startMillis = input.now,
        fuelStart = input.fuelLevel,
        fuelNow = input.fuelLevel,
        carConsumption = input.carConsumption,
    )

    /** true si el viaje ya debería darse por terminado. */
    fun isOver(trip: Trip, now: Long) = !trip.finished && now - trip.lastMoveMillis > PAUSE_MS

    fun finish(trip: Trip) = trip.copy(finished = true)

    /** Suma un paso al viaje en curso. */
    fun step(trip: Trip, input: TripInput): Trip {
        val dt = (input.now - trip.lastMillis).coerceIn(0, MAX_STEP_MS)
        val speed = input.speedKmh
        val meters = if (speed != null) speed / 3.6 * dt / 1000.0 else input.gpsMeters
        val shownSpeed = speed ?: if (dt > 0) (meters / dt * 3600.0).toInt() else 0
        val moving = isMoving(input)
        var t = trip.copy(
            lastMillis = input.now,
            lastMoveMillis = if (moving) input.now else trip.lastMoveMillis,
            distanceM = trip.distanceM + meters,
            movingMillis = trip.movingMillis + if (moving) dt else 0,
            maxSpeedKmh = maxOf(trip.maxSpeedKmh, shownSpeed),
            fuelStart = trip.fuelStart ?: input.fuelLevel,
            fuelNow = input.fuelLevel ?: trip.fuelNow,
            carConsumption = input.carConsumption ?: trip.carConsumption,
            bucketSum = trip.bucketSum + shownSpeed * dt,
            bucketMs = trip.bucketMs + dt,
        )
        // Cierra los tramos completos de la gráfica.
        while (t.bucketMs >= t.sampleMs) {
            val avg = (t.bucketSum / t.bucketMs).roundToInt()
            t = t.copy(speedSamples = t.speedSamples + avg, bucketSum = 0.0, bucketMs = 0)
            if (t.speedSamples.size >= MAX_SAMPLES) t = halve(t)
        }
        return t
    }

    /** Une los tramos de dos en dos: la gráfica sigue cubriendo todo el viaje con menos puntos. */
    private fun halve(t: Trip) = t.copy(
        speedSamples = t.speedSamples.chunked(2).map { it.average().roundToInt() },
        sampleMs = t.sampleMs * 2,
    )
}
