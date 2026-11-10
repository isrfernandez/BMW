package com.cockpitf30.vehicle.trip

import android.content.Context
import android.os.SystemClock
import com.cockpitf30.nav.GeoPoint
import com.cockpitf30.nav.RouteTracker
import com.cockpitf30.system.AppSettings
import com.cockpitf30.vehicle.VehicleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sin

/** Lo que muestra la pantalla de viaje. */
data class TripsState(
    /** Viaje en curso; null si el coche aún no se ha movido desde el último. */
    val current: Trip? = null,
    val last: Trip? = null,
    val tankLiters: Float = 57f,
    /** true con la fuente Demo: viajes de ejemplo que no se guardan. */
    val demo: Boolean = false,
    /** Trayectos terminados, el más antiguo primero. */
    val history: List<TripRecord> = emptyList(),
)

/** Resumen que se enseña al parar el motor (o al pedirlo en Datos de viaje). */
data class TripSummaryInfo(val trip: Trip, val score: TripScore?, val week: WeekSummary, val tankLiters: Float, val at: Long)

/**
 * Ordenador de viaje propio. La radio (ZQ9109 + caja Raise que emula un NBT) no expone en
 * Android los datos de viaje del coche, así que se calculan aquí cada segundo con la velocidad
 * que llega del CAN (o el GPS si no llega). El viaje se guarda para sobrevivir a que la radio
 * se apague con el contacto: al volver a encenderla tras más de [TripMath.PAUSE_MS] parado,
 * el viaje anterior pasa a ser el "último viaje".
 */
object TripComputer {
    private const val TICK_MS = 1_000L
    private const val SAVE_EVERY = 15
    /** Precisión mínima (m) de una posición del GPS para sumar distancia con ella. */
    private const val MAX_ACCURACY_M = 30f

    private val _state = MutableStateFlow(TripsState())
    val state: StateFlow<TripsState> = _state

    private val _summary = MutableStateFlow<TripSummaryInfo?>(null)
    /** Resumen a la vista, o null. Lo quita la ventana al cerrarse ([dismissSummary]). */
    val summary: StateFlow<TripSummaryInfo?> = _summary

    private var started = false
    private lateinit var settings: AppSettings
    private var lastGps: GeoPoint? = null

    /** Motor visto en marcha en este viaje y desde cuándo parece parado (para el resumen). */
    private var engineSeen = false
    private var engineOffSince = 0L
    private var summarizedStart = 0L
    /** Metros del cuentakilómetros aún sin guardar. */
    private var odoPendingM = 0.0

    fun start(context: Context, scope: CoroutineScope) {
        if (started) return
        started = true
        settings = AppSettings(context.applicationContext)
        loadSaved()
        scope.launch {
            var n = 0
            while (isActive) {
                tick(System.currentTimeMillis(), save = ++n % SAVE_EVERY == 0)
                delay(TICK_MS)
            }
        }
    }

    /** Guarda el viaje al pararse el servicio; [start] vuelve a arrancarlo. */
    fun stop() {
        persist()
        started = false
    }

    /** Cierra el viaje en curso y deja la pantalla lista para uno nuevo. */
    fun resetTrip() {
        val s = _state.value
        val cur = s.current ?: return
        _state.value = finishing(s, cur, System.currentTimeMillis())
        persist()
    }

    /** Cierra [cur]: pasa a «último viaje» y entra en el histórico. */
    private fun finishing(s: TripsState, cur: Trip, now: Long): TripsState {
        val done = TripMath.finish(cur)
        val history = if (s.demo) s.history else TripHistory.add(s.history, TripRecord.from(done, s.tankLiters), now)
        return s.copy(current = null, last = done, history = history)
    }

    /** Enseña el resumen del viaje en curso (o del último). */
    fun showSummary(now: Long = System.currentTimeMillis()) {
        val s = _state.value
        val trip = s.current ?: s.last ?: return
        _summary.value = TripSummaryInfo(trip, TripScore.of(trip, s.tankLiters), TripHistory.week(withCurrent(s, trip, now), now), s.tankLiters, now)
    }

    fun dismissSummary() { _summary.value = null }

    /** El histórico contando también el viaje en curso (para la semana del resumen). */
    private fun withCurrent(s: TripsState, trip: Trip, now: Long) =
        if (trip.finished || s.demo && s.history.any { it.startMillis == trip.startMillis }) s.history
        else TripHistory.add(s.history, TripRecord.from(trip, s.tankLiters), now)

    /** Cuentakilómetros propio en km (null si el conductor aún no lo ha puesto). */
    fun odometerKm(): Long? {
        if (!::settings.isInitialized) return null
        val m = settings.odometerM ?: return null
        return ((m + odoPendingM) / 1000).toLong()
    }

    /** Pone el cuentakilómetros a [km] (lo que marca el coche). */
    fun setOdometerKm(km: Long?) {
        if (!::settings.isInitialized) return
        odoPendingM = 0.0
        settings.odometerM = km?.let { it.coerceAtLeast(0) * 1000 }
    }

    private fun loadSaved() {
        val now = System.currentTimeMillis()
        var current = Trip.fromJson(settings.tripCurrent)
        var last = Trip.fromJson(settings.tripLast)
        var history = TripHistory.parse(settings.tripHistory)
        // La radio estuvo apagada (o parada) demasiado tiempo: aquel viaje terminó.
        if (current != null && TripMath.isOver(current, now)) {
            last = TripMath.finish(current)
            history = TripHistory.add(history, TripRecord.from(last, settings.tankLiters), now)
            current = null
        }
        _state.value = TripsState(current, last, settings.tankLiters, history = history)
        persist()
    }

    private fun tick(now: Long, save: Boolean) {
        val demo = VehicleRepository.status.value.sourceName == "Demo"
        if (demo != _state.value.demo) {
            if (demo) _state.value = demoTrips(now) else loadSaved()
        }
        val vs = VehicleRepository.state.value
        val gps = gpsReading()
        // Sin velocidad del CAN, la del propio GPS; si tampoco la da, la distancia entre posiciones.
        val speed = vs.speedKmh ?: gps.speedKmh
        val input = TripInput(now, speed, if (speed == null) gps.meters else 0.0, vs.fuelLevel, vs.avgConsumption)
        val s = _state.value
        val cur = s.current
        val here = RouteTracker.state.value.let { r -> r.position?.takeIf { r.live || demo } }
        val next = when {
            cur == null && TripMath.isMoving(input) -> {
                engineSeen = false; engineOffSince = 0L
                s.copy(current = TripMath.begin(input).copy(startLat = here?.lat, startLon = here?.lon, endLat = here?.lat, endLon = here?.lon))
            }
            cur == null -> s
            TripMath.isOver(cur, now) -> finishing(s, cur, now)
            else -> s.copy(current = TripMath.step(cur, input).let { t ->
                if (here == null) t else t.copy(startLat = t.startLat ?: here.lat, startLon = t.startLon ?: here.lon, endLat = here.lat, endLon = here.lon)
            })
        }
        _state.value = next
        // Cuentakilómetros propio: lo recorrido en este segundo.
        val nc = next.current
        if (!demo && nc != null && cur != null && nc.startMillis == cur.startMillis) odoPendingM += (nc.distanceM - cur.distanceM).coerceAtLeast(0.0)
        checkEngineOff(now, vs.rpm, speed, nc)
        if (!demo && (save || next.current == null && cur != null || cur == null && next.current != null)) persist()
    }

    private class GpsReading(val speedKmh: Int?, val meters: Double)

    /**
     * Lo que aporta el GPS real en este tick (la ruta simulada del Demo no cuenta): su velocidad
     * Doppler si la posición es reciente, y los metros desde la posición anterior. Las posiciones
     * de red o con poca precisión no suman distancia: saltan decenas o cientos de metros de un
     * segundo a otro y fueron las que dieron una media de 615 km/h.
     */
    private fun gpsReading(): GpsReading {
        val r = RouteTracker.state.value
        val fix = r.fix?.takeIf { r.live && it.fromGps }
        val fresh = fix != null && SystemClock.elapsedRealtime() - fix.elapsedMs < 3_000
        val speed = fix?.speedKmh?.takeIf { fresh }?.roundToInt()
        val good = fix != null && fresh && (fix.accuracyM ?: 0f) <= MAX_ACCURACY_M
        val p = r.position?.takeIf { good }
        val prev = lastGps
        lastGps = p
        val meters = if (p != null && prev != null) RouteTracker.distance(prev, p) else 0.0
        return GpsReading(speed, meters)
    }

    /**
     * Motor parado: había rpm en este viaje y ahora llevan [ENGINE_OFF_MS] a cero (o sin llegar)
     * con el coche quieto. Se enseña el resumen una vez por viaje.
     */
    private fun checkEngineOff(now: Long, rpm: Int?, speed: Int?, trip: Trip?) {
        if (trip == null) return
        if ((rpm ?: 0) >= 400) { engineSeen = true; engineOffSince = 0L; return }
        if (!engineSeen || (speed ?: 0) > 2) { engineOffSince = 0L; return }
        if (engineOffSince == 0L) engineOffSince = now
        if (now - engineOffSince < ENGINE_OFF_MS || summarizedStart == trip.startMillis) return
        summarizedStart = trip.startMillis
        engineSeen = false
        if (trip.distanceKm >= TripHistory.MIN_KM * 2 && ::settings.isInitialized && settings.tripSummary) showSummary(now)
    }

    private const val ENGINE_OFF_MS = 4_000L

    private fun persist() {
        val s = _state.value
        if (s.demo || !::settings.isInitialized) return
        settings.tripCurrent = s.current?.toJson()
        settings.tripLast = s.last?.toJson()
        settings.tripHistory = TripHistory.toJson(s.history)
        if (odoPendingM > 0) settings.odometerM?.let { settings.odometerM = it + odoPendingM.toLong(); odoPendingM -= odoPendingM.toLong() }
    }

    /** Viajes de ejemplo para el modo Demo: uno en curso desde hace 47 min y otro de ayer. */
    private fun demoTrips(now: Long): TripsState {
        fun samples(n: Int, seed: Double) = List(n) { i ->
            (68 + 40 * sin(i / 9.0 + seed) + 12 * sin(i / 4.1 + seed * 2)).roundToInt().coerceIn(0, 150)
        }
        val curStart = now - 47 * 60_000L
        val current = Trip(
            startMillis = curStart, lastMillis = now, lastMoveMillis = now,
            distanceM = 41_300.0, movingMillis = 41 * 60_000L, maxSpeedKmh = 128,
            fuelStart = 0.66f, fuelNow = 0.62f, carConsumption = 6.8f,
            speedSamples = samples(47 * 4, 0.0), sampleMs = 15_000L,
        )
        val lastStart = now - 26 * 3_600_000L
        val last = Trip(
            startMillis = lastStart, lastMillis = lastStart + 39 * 60_000L, lastMoveMillis = lastStart + 39 * 60_000L,
            finished = true, distanceM = 32_600.0, movingMillis = 33 * 60_000L, maxSpeedKmh = 112,
            carConsumption = 6.4f, speedSamples = samples(39 * 4, 1.7), sampleMs = 15_000L,
        )
        // Una semana de ejemplo: ida y vuelta al trabajo y algo más largo el fin de semana.
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val history = (13 downTo 1).flatMap { back ->
            val d = today.minusDays(back.toLong())
            val weekend = d.dayOfWeek.value >= 6
            val base = d.atTime(8, 10).atZone(zone).toInstant().toEpochMilli()
            if (weekend) listOf(TripRecord(base + 3 * 3_600_000L, base + 4 * 3_600_000L + 600_000L, 86.0 + back, 62 * 60_000L, 5.9f, 78 + back % 9, 131))
            else listOf(
                TripRecord(base, base + 26 * 60_000L, 18.4, 22 * 60_000L, 5.4f, 84 - back % 7, 102),
                TripRecord(base + 9 * 3_600_000L, base + 9 * 3_600_000L + 31 * 60_000L, 18.9, 24 * 60_000L, 5.8f, 72 + back % 11, 98),
            )
        } + TripRecord.from(last, _state.value.tankLiters)
        return TripsState(current, last, _state.value.tankLiters, demo = true, history = history)
    }
}
