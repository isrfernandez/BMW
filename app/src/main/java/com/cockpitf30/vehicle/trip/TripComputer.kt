package com.cockpitf30.vehicle.trip

import android.content.Context
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
)

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

    private val _state = MutableStateFlow(TripsState())
    val state: StateFlow<TripsState> = _state

    private var started = false
    private lateinit var settings: AppSettings
    private var lastGps: GeoPoint? = null

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
        _state.value = s.copy(current = null, last = TripMath.finish(cur))
        persist()
    }

    private fun loadSaved() {
        val now = System.currentTimeMillis()
        var current = Trip.fromJson(settings.tripCurrent)
        var last = Trip.fromJson(settings.tripLast)
        // La radio estuvo apagada (o parada) demasiado tiempo: aquel viaje terminó.
        if (current != null && TripMath.isOver(current, now)) {
            last = TripMath.finish(current)
            current = null
        }
        _state.value = TripsState(current, last, settings.tankLiters)
        persist()
    }

    private fun tick(now: Long, save: Boolean) {
        val demo = VehicleRepository.status.value.sourceName == "Demo"
        if (demo != _state.value.demo) {
            if (demo) _state.value = demoTrips(now) else loadSaved()
        }
        val vs = VehicleRepository.state.value
        val input = TripInput(now, vs.speedKmh, gpsMeters(), vs.fuelLevel, vs.avgConsumption)
        val s = _state.value
        val cur = s.current
        val next = when {
            cur == null && TripMath.isMoving(input) -> s.copy(current = TripMath.begin(input))
            cur == null -> s
            TripMath.isOver(cur, now) -> s.copy(current = null, last = TripMath.finish(cur))
            else -> s.copy(current = TripMath.step(cur, input))
        }
        _state.value = next
        if (!demo && (save || next.current == null && cur != null || cur == null && next.current != null)) persist()
    }

    /** Metros del GPS real desde el tick anterior (la ruta simulada del Demo no cuenta). */
    private fun gpsMeters(): Double {
        val r = RouteTracker.state.value
        val p = r.position?.takeIf { r.live } ?: return 0.0
        val prev = lastGps
        lastGps = p
        return prev?.let { RouteTracker.distance(it, p) }?.takeIf { it < 200 } ?: 0.0
    }

    private fun persist() {
        val s = _state.value
        if (s.demo || !::settings.isInitialized) return
        settings.tripCurrent = s.current?.toJson()
        settings.tripLast = s.last?.toJson()
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
        return TripsState(current, last, _state.value.tankLiters, demo = true)
    }
}
