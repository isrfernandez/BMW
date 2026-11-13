package com.cockpitf30.nav

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import com.cockpitf30.weather.WeatherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class GeoPoint(val lat: Double, val lon: Double)

/** Posición actual y el recorrido hecho desde que arrancó la app. */
data class RouteState(
    val position: GeoPoint? = null,
    /** Rumbo en grados, 0 = norte, en sentido horario. */
    val bearing: Float = 0f,
    val track: List<GeoPoint> = emptyList(),
    /** true mientras llegan posiciones reales (no la simulación del modo Demo). */
    val live: Boolean = false,
    val time: Long = 0,
    /** Datos de la última posición real, para el ordenador de viaje. */
    val fix: GpsFix? = null,
)

/**
 * Una posición real: de qué proveedor viene, su precisión, la velocidad que mide el propio GPS
 * (efecto Doppler, mucho más fiable que derivarla de dos posiciones) y cuándo llegó según el reloj
 * de arranque, que no salta cuando la radio pone en hora el reloj del sistema.
 */
data class GpsFix(
    val fromGps: Boolean,
    val accuracyM: Float?,
    val speedKmh: Float?,
    val elapsedMs: Long,
)

/**
 * Sigue la posición con el GPS de la radio (LocationManager, sin Google Play Services) y guarda
 * la ruta por la que se va circulando para dibujarla sobre el mapa del Inicio.
 */
object RouteTracker {
    private const val MAX_POINTS = 4000
    private const val MIN_STEP_M = 4.0

    private val _state = MutableStateFlow(RouteState())
    val state: StateFlow<RouteState> = _state

    private var started = false
    private val listener = LocationListener { onLocation(it) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watchdog: Job? = null
    private var lastKnownNanos = 0L

    /** Veces que se ha vuelto a pedir el GPS porque no llegaban posiciones (para Diagnóstico). */
    val retries = MutableStateFlow(0)

    /**
     * Empieza a escuchar el GPS. Se puede llamar varias veces (p. ej. tras conceder el permiso).
     *
     * Al arrancar la radio el GPS a veces aún no está listo (proveedor apagado o sin servicio) y
     * antes la petición se perdía: el mapa y las gasolineras se quedaban sin posición hasta cerrar
     * la app. Ahora se pide siempre y un vigilante la repite mientras no lleguen posiciones.
     */
    fun start(context: Context) {
        if (started || !WeatherRepository.hasLocationPermission(context)) return
        val app = context.applicationContext
        app.getSystemService(LocationManager::class.java) ?: return
        started = true
        WeatherRepository.lastKnownLocation(app)?.let { lastKnownNanos = it.elapsedRealtimeNanos; onLocation(it) }
        register(app)
        watchdog = scope.launch {
            var wait = 15_000L
            while (true) {
                delay(wait)
                val fix = _state.value.fix
                val stale = fix == null || SystemClock.elapsedRealtime() - fix.elapsedMs > 30_000
                if (!stale) { wait = 15_000L; continue }
                // Sin posiciones: lo último que sepa el sistema (si es nuevo) y volver a pedir.
                WeatherRepository.lastKnownLocation(app)?.takeIf { it.elapsedRealtimeNanos > lastKnownNanos }?.let {
                    lastKnownNanos = it.elapsedRealtimeNanos
                    onLocation(it)
                }
                register(app)
                com.cockpitf30.vehicle.telemetry.Telemetry.restartGps(app)
                retries.update { it + 1 }
                // Cada vez un poco más espaciado, hasta un intento por minuto.
                wait = (wait + 15_000L).coerceAtMost(60_000L)
            }
        }
    }

    /**
     * (Re)pide posiciones al GPS y a la red. Sin mirar si el proveedor está encendido: si aún no
     * lo está, Android las manda en cuanto se encienda.
     */
    @SuppressLint("MissingPermission")
    private fun register(context: Context) {
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        runCatching { lm.removeUpdates(listener) }
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            runCatching {
                if (lm.allProviders.contains(provider)) lm.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
            }
        }
    }

    private fun onLocation(loc: Location) {
        // El GPS manda frente a la red: si hay un GPS reciente, ignoramos las posiciones de red.
        // Se compara con el reloj de arranque: loc.time del GPS viene de los satélites y el de la red
        // del reloj de la radio, que puede ir desfasado; antes eso dejaba pasar posiciones de red
        // entre las del GPS y el recorrido iba dando saltos.
        val s = _state.value
        val now = SystemClock.elapsedRealtime()
        val fromGps = loc.provider == LocationManager.GPS_PROVIDER
        val lastGps = s.fix?.takeIf { it.fromGps }
        if (!fromGps && s.live && lastGps != null && now - lastGps.elapsedMs < 10_000) return
        val p = GeoPoint(loc.latitude, loc.longitude)
        val bearing = when {
            loc.hasBearing() && loc.hasSpeed() && loc.speed > 1.5f -> loc.bearing
            else -> s.position?.takeIf { distance(it, p) > 8 }?.let { bearingBetween(it, p) } ?: s.bearing
        }
        // Al pasar de la simulación a datos reales se empieza un recorrido nuevo.
        val fix = GpsFix(
            fromGps = fromGps,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
            speedKmh = if (fromGps && loc.hasSpeed()) loc.speed * 3.6f else null,
            elapsedMs = now,
        )
        push(p, bearing, live = true, time = loc.time, reset = !s.live, fix = fix)
    }

    /** Modo Demo: avanza por un recorrido de ejemplo mientras no haya GPS real. */
    fun simulate(speedKmh: Int, dtSeconds: Double) {
        val s = _state.value
        if (s.live && System.currentTimeMillis() - s.time < 15_000) return
        demoMeters += speedKmh / 3.6 * dtSeconds
        val (p, bearing) = DemoRoute.at(demoMeters)
        push(p, bearing, live = false, time = System.currentTimeMillis(), reset = s.live)
    }

    private var demoMeters = 0.0

    private fun push(p: GeoPoint, bearing: Float, live: Boolean, time: Long, reset: Boolean, fix: GpsFix? = null) {
        val s = _state.value
        val base = if (reset) emptyList() else s.track
        val track = if (base.isEmpty() || distance(base.last(), p) >= MIN_STEP_M) {
            (base + p).let { if (it.size > MAX_POINTS) it.drop(it.size - MAX_POINTS) else it }
        } else base
        _state.value = RouteState(p, bearing, track, live, time, fix ?: s.fix.takeIf { live })
    }

    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon) * cos(Math.toRadians((a.lat + b.lat) / 2))
        return r * kotlin.math.sqrt(dLat * dLat + dLon * dLon)
    }

    fun bearingBetween(a: GeoPoint, b: GeoPoint): Float {
        val dLon = Math.toRadians(b.lon - a.lon)
        val la = Math.toRadians(a.lat); val lb = Math.toRadians(b.lat)
        val y = sin(dLon) * cos(lb)
        val x = cos(la) * sin(lb) - sin(la) * cos(lb) * cos(dLon)
        return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
    }
}

/** Recorrido de ejemplo para el modo Demo: Ludwigstraße y Leopoldstraße (Múnich), ida y vuelta. */
private object DemoRoute {
    private val points = listOf(
        GeoPoint(48.14180, 11.57735), // Odeonsplatz
        GeoPoint(48.14600, 11.57900),
        GeoPoint(48.15050, 11.58110), // Universität
        GeoPoint(48.15226, 11.58217), // Siegestor
        GeoPoint(48.15700, 11.58430),
        GeoPoint(48.16205, 11.58660), // Münchner Freiheit
        GeoPoint(48.16800, 11.58900),
        GeoPoint(48.17300, 11.59080),
    )
    private val legs = points.zipWithNext { a, b -> RouteTracker.distance(a, b) }
    private val total = legs.sum()

    fun at(meters: Double): Pair<GeoPoint, Float> {
        val m = meters % (2 * total)
        val forward = m < total
        var d = if (forward) m else 2 * total - m
        for (i in legs.indices) {
            if (d <= legs[i] || i == legs.lastIndex) {
                val f = (d / legs[i]).coerceIn(0.0, 1.0)
                val a = points[i]; val b = points[i + 1]
                val p = GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
                val bearing = if (forward) RouteTracker.bearingBetween(a, b) else RouteTracker.bearingBetween(b, a)
                return p to bearing
            }
            d -= legs[i]
        }
        return points.first() to 0f
    }
}
