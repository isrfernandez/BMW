package com.cockpitf30.vehicle.trip

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.roundToInt

/** Un trayecto terminado, tal como se guarda en el histórico. */
data class TripRecord(
    val startMillis: Long,
    val endMillis: Long,
    val distanceKm: Double,
    val movingMillis: Long,
    /** l/100 km si se pudo saber. */
    val consumption: Float? = null,
    /** Nota de eficiencia 0..100 si el trayecto fue lo bastante largo. */
    val score: Int? = null,
    val maxSpeedKmh: Int = 0,
    val startLat: Double? = null,
    val startLon: Double? = null,
    val endLat: Double? = null,
    val endLon: Double? = null,
) {
    val durationMillis: Long get() = (endMillis - startMillis).coerceAtLeast(0)

    fun toJson(): JSONObject = JSONObject().apply {
        put("s", startMillis); put("e", endMillis); put("km", distanceKm); put("mv", movingMillis); put("max", maxSpeedKmh)
        consumption?.let { put("c", it.toDouble()) }
        score?.let { put("sc", it) }
        startLat?.let { put("sla", it) }; startLon?.let { put("slo", it) }
        endLat?.let { put("ela", it) }; endLon?.let { put("elo", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = TripRecord(
            startMillis = o.getLong("s"), endMillis = o.getLong("e"), distanceKm = o.optDouble("km", 0.0),
            movingMillis = o.optLong("mv"), maxSpeedKmh = o.optInt("max"),
            consumption = if (o.has("c")) o.getDouble("c").toFloat() else null,
            score = if (o.has("sc")) o.getInt("sc") else null,
            startLat = if (o.has("sla")) o.getDouble("sla") else null, startLon = if (o.has("slo")) o.getDouble("slo") else null,
            endLat = if (o.has("ela")) o.getDouble("ela") else null, endLon = if (o.has("elo")) o.getDouble("elo") else null,
        )

        fun from(trip: Trip, tankLiters: Float) = TripRecord(
            startMillis = trip.startMillis, endMillis = trip.endMillis, distanceKm = trip.distanceKm,
            movingMillis = trip.movingMillis, consumption = trip.avgConsumption(tankLiters),
            score = TripScore.of(trip, tankLiters)?.score, maxSpeedKmh = trip.maxSpeedKmh,
            startLat = trip.startLat, startLon = trip.startLon, endLat = trip.endLat, endLon = trip.endLon,
        )
    }
}

/** Totales de una semana (de lunes a domingo). */
data class WeekSummary(
    /** Kilómetros de cada día, lunes primero. */
    val kmPerDay: List<Double>,
    val trips: Int,
    val km: Double,
    val movingMillis: Long,
    val avgScore: Int?,
    val avgConsumption: Float?,
    /** Kilómetros de la semana anterior, para comparar. */
    val previousKm: Double,
    /** Día de hoy, 0 = lunes. */
    val today: Int,
)

/** Histórico de trayectos: cálculo puro (lo guarda [TripComputer] en los ajustes). */
object TripHistory {
    /** Trayectos más cortos que esto no se guardan (mover el coche en el garaje). */
    const val MIN_KM = 0.3
    const val KEEP_DAYS = 120
    const val MAX_RECORDS = 400

    fun parse(json: String?): List<TripRecord> = runCatching {
        val arr = JSONArray(json ?: return emptyList())
        List(arr.length()) { TripRecord.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun toJson(list: List<TripRecord>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

    /** Añade [r] (o lo sustituye si ya estaba, mismo inicio) y quita lo viejo. */
    fun add(list: List<TripRecord>, r: TripRecord, now: Long): List<TripRecord> {
        if (r.distanceKm < MIN_KM) return list
        val cutoff = now - KEEP_DAYS * 86_400_000L
        return (list.filter { it.startMillis != r.startMillis && it.endMillis >= cutoff } + r)
            .sortedBy { it.startMillis }.takeLast(MAX_RECORDS)
    }

    /** Resumen de la semana de [now] (lunes a domingo, hora local). */
    fun week(list: List<TripRecord>, now: Long, zone: ZoneId = ZoneId.systemDefault()): WeekSummary {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val start = monday.atStartOfDay(zone).toInstant().toEpochMilli()
        val prevStart = monday.minusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val days = DoubleArray(7)
        val inWeek = list.filter { it.startMillis >= start }
        inWeek.forEach { r ->
            val d = Instant.ofEpochMilli(r.startMillis).atZone(zone).toLocalDate()
            val i = (d.toEpochDay() - monday.toEpochDay()).toInt()
            if (i in 0..6) days[i] += r.distanceKm
        }
        val scored = inWeek.mapNotNull { r -> r.score?.let { it to r.distanceKm } }
        val cons = inWeek.mapNotNull { r -> r.consumption?.let { it to r.distanceKm } }
        fun weighted(p: List<Pair<Number, Double>>): Double? {
            val w = p.sumOf { it.second }
            return if (p.isEmpty() || w <= 0) null else p.sumOf { it.first.toDouble() * it.second } / w
        }
        return WeekSummary(
            kmPerDay = days.toList(), trips = inWeek.size, km = days.sum(),
            movingMillis = inWeek.sumOf { it.movingMillis },
            avgScore = weighted(scored)?.roundToInt(), avgConsumption = weighted(cons)?.toFloat(),
            previousKm = list.filter { it.startMillis in prevStart until start }.sumOf { it.distanceKm },
            today = (today.toEpochDay() - monday.toEpochDay()).toInt(),
        )
    }

    /**
     * Destino habitual saliendo de [lat],[lon]: el final más repetido (agrupando a menos de
     * [radiusKm]) de los trayectos que empezaron cerca de aquí, si se repite al menos [minTrips] veces.
     */
    fun usualDestination(list: List<TripRecord>, lat: Double, lon: Double, radiusKm: Double = 1.0, minTrips: Int = 2): Pair<Double, Double>? {
        val ends = list.filter { r ->
            r.startLat != null && r.startLon != null && r.endLat != null && r.endLon != null &&
                km(lat, lon, r.startLat, r.startLon) < radiusKm && km(lat, lon, r.endLat, r.endLon) > radiusKm * 2
        }.map { it.endLat!! to it.endLon!! }
        if (ends.size < minTrips) return null
        val best = ends.maxBy { e -> ends.count { km(e.first, e.second, it.first, it.second) < radiusKm } }
        val group = ends.filter { km(best.first, best.second, it.first, it.second) < radiusKm }
        if (group.size < minTrips) return null
        return group.map { it.first }.average() to group.map { it.second }.average()
    }

    /** Distancia aproximada en km (equirrectangular: de sobra para unos pocos km). */
    fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val x = (lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2)) * 111.32
        val y = (lat2 - lat1) * 110.57
        return Math.sqrt(x * x + y * y)
    }

    /** Distancia en km del punto P al segmento A–B. */
    fun kmToSegment(pLat: Double, pLon: Double, aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val k = Math.cos(Math.toRadians(aLat)) * 111.32
        val px = (pLon - aLon) * k; val py = (pLat - aLat) * 110.57
        val bx = (bLon - aLon) * k; val by = (bLat - aLat) * 110.57
        val len2 = bx * bx + by * by
        val t = if (len2 <= 0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val dx = px - bx * t; val dy = py - by * t
        return Math.sqrt(dx * dx + dy * dy)
    }
}

/** Nota de eficiencia de un trayecto y el consejo que más ayudaría. */
data class TripScore(val score: Int, val tip: String, val smooth: Int, val idle: Int, val speed: Int, val fuel: Int?) {
companion object {
    /** Por debajo de esta distancia no se puntúa (no hay datos para juzgar). */
    const val MIN_KM = 1.0
    /** Consumo de referencia de un 320d en conducción tranquila, l/100 km. */
    const val REF_L_100 = 5.0

    /**
     * 100 menos las penalizaciones: tirones (diferencia media entre tramos de 15 s de la gráfica
     * de velocidad), tiempo parado con el motor en marcha, tiempo por encima de 120 km/h y, si se
     * sabe, el consumo frente a [REF_L_100].
     */
    fun of(trip: Trip, tankLiters: Float): TripScore? {
        if (trip.distanceKm < MIN_KM) return null
        val s = trip.speedSamples
        val diffs = s.zipWithNext { a, b -> abs(b - a) }
        val meanDiff = if (diffs.isEmpty()) 0.0 else diffs.average()
        val smooth = (((meanDiff - 4.0) / 12.0).coerceIn(0.0, 1.0) * 35).roundToInt()
        val idleRatio = if (trip.durationMillis <= 0) 0.0 else 1.0 - trip.movingMillis.toDouble() / trip.durationMillis
        val idle = (((idleRatio - 0.12) / 0.38).coerceIn(0.0, 1.0) * 20).roundToInt()
        val fast = if (s.isEmpty()) 0.0 else s.count { it > 120 }.toDouble() / s.size
        val speed = ((fast / 0.5).coerceIn(0.0, 1.0) * 20).roundToInt()
        val cons = trip.avgConsumption(tankLiters)
        val fuel = cons?.let { (((it - REF_L_100) / 4.0).coerceIn(0.0, 1.0) * 25).roundToInt() }
        val score = (100 - smooth - idle - speed - (fuel ?: 0)).coerceIn(0, 100)
        val worst = listOf(smooth / 35.0 to 0, idle / 20.0 to 1, speed / 20.0 to 2, (fuel ?: 0) / 25.0 to 3).maxBy { it.first }
        val tip = if (worst.first < 0.25) "Conducción muy fina: suave y constante."
        else when (worst.second) {
            0 -> "Anticipa más: acelerones y frenadas suben el consumo."
            1 -> "Mucho tiempo parado con el motor en marcha."
            2 -> "Mucho rato por encima de 120: a 130 gasta un 20 % más."
            else -> "Consumo alto: cambia antes de marcha y mantén el ritmo."
        }
        return TripScore(score, tip, smooth, idle, speed, fuel)
    }
}
}
