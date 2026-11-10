package com.cockpitf30.weather

import android.content.Context
import com.cockpitf30.nav.GeoPoint
import com.cockpitf30.nav.RouteTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Tiempo previsto en un punto de la ruta a la hora en que se pasará por él. */
data class RoutePoint(
    val km: Int,
    val minutes: Int,
    val point: GeoPoint,
    val code: Int,
    val tempC: Int?,
    val precipProb: Int?,
    val precipMm: Float?,
    val visibilityM: Float?,
    /** Nombre del destino en el último punto, si lo hay. */
    val label: String? = null,
)

data class RouteWeatherState(
    val destination: Place? = null,
    val points: List<RoutePoint> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val updatedAt: Long = 0,
    /** Resumen corto: «Lluvia en 25 km (70 %)» o «Sin lluvia en ruta». */
    val summary: String? = null,
)

/**
 * Tiempo a lo largo de la ruta con Open-Meteo. Con destino elegido, en puntos repartidos por la
 * línea recta hasta él; sin destino, a 10, 25 y 50 km en la dirección en la que se circula. Para
 * cada punto se toma la hora a la que se llegará (con la velocidad media prevista).
 */
object RouteWeather {
    private val _state = MutableStateFlow(RouteWeatherState())
    val state: StateFlow<RouteWeatherState> = _state.asStateFlow()

    private var lastAt: GeoPoint? = null
    private var lastBearing = 0f

    fun load(context: Context) {
        val p = context.getSharedPreferences("cockpit", Context.MODE_PRIVATE)
        val name = p.getString("route_dest_name", null) ?: return
        _state.value = _state.value.copy(
            destination = Place(name, p.getString("route_dest_region", null), null, p.getFloat("route_dest_lat", 0f).toDouble(), p.getFloat("route_dest_lon", 0f).toDouble()),
        )
    }

    suspend fun setDestination(context: Context, place: Place?) {
        val e = context.getSharedPreferences("cockpit", Context.MODE_PRIVATE).edit()
        if (place == null) e.remove("route_dest_name") else e.putString("route_dest_name", place.name).putString("route_dest_region", place.region)
            .putFloat("route_dest_lat", place.lat.toFloat()).putFloat("route_dest_lon", place.lon.toFloat())
        e.apply()
        _state.value = _state.value.copy(destination = place, points = emptyList())
        refresh(context, force = true)
    }

    /** Vuelve a calcular si toca: cada 10 min, al moverse 10 km o al girar mucho sin destino. */
    suspend fun refresh(context: Context, force: Boolean = false) {
        val route = RouteTracker.state.value
        val here = route.position ?: WeatherRepository.lastKnownLocation(context)?.let { GeoPoint(it.latitude, it.longitude) } ?: run {
            _state.value = _state.value.copy(error = "Sin posición")
            return
        }
        val s = _state.value
        val moved = lastAt?.let { RouteTracker.distance(it, here) } ?: Double.MAX_VALUE
        var turn = kotlin.math.abs(route.bearing - lastBearing) % 360f
        if (turn > 180f) turn = 360f - turn
        val due = System.currentTimeMillis() - s.updatedAt > 10 * 60_000 || moved > 10_000 ||
            (s.destination == null && turn > 45f && moved > 1000)
        if (!force && !due) return
        if (s.loading) return
        _state.value = s.copy(loading = true)
        val speedKmh = (route.fix?.speedKmh ?: 0f).coerceAtLeast(70f).coerceAtMost(110f)
        val plan = plan(here, route.bearing, s.destination)
        val result = withContext(Dispatchers.IO) { runCatching { fetch(plan, speedKmh, s.destination) } }
        result.onSuccess { pts ->
            lastAt = here; lastBearing = route.bearing
            _state.value = _state.value.copy(points = pts, loading = false, error = null, updatedAt = System.currentTimeMillis(), summary = summarize(pts))
        }.onFailure {
            _state.value = _state.value.copy(loading = false, error = it.message ?: it.javaClass.simpleName)
        }
    }

    /** Puntos (km, posición) a consultar. */
    fun plan(here: GeoPoint, bearing: Float, dest: Place?): List<Pair<Int, GeoPoint>> {
        if (dest == null) return listOf(10, 25, 50).map { it to project(here, bearing.toDouble(), it * 1000.0) }
        val target = GeoPoint(dest.lat, dest.lon)
        val total = RouteTracker.distance(here, target) / 1000.0
        // Por carretera suele ser un 20 % más que en línea recta.
        val roadKm = total * 1.2
        val n = when {
            total < 15 -> 1
            total < 60 -> 3
            total < 200 -> 4
            else -> 6
        }
        val heading = RouteTracker.bearingBetween(here, target).toDouble()
        return (1..n).map { i ->
            val f = i / n.toDouble()
            (roadKm * f).toInt().coerceAtLeast(1) to project(here, heading, total * 1000.0 * f)
        }
    }

    /** Punto a [meters] de [from] con rumbo [bearingDeg] (esfera). */
    fun project(from: GeoPoint, bearingDeg: Double, meters: Double): GeoPoint {
        val r = 6_371_000.0
        val d = meters / r
        val b = Math.toRadians(bearingDeg)
        val la1 = Math.toRadians(from.lat); val lo1 = Math.toRadians(from.lon)
        val la2 = asin(sin(la1) * cos(d) + cos(la1) * sin(d) * cos(b))
        val lo2 = lo1 + atan2(sin(b) * sin(d) * cos(la1), cos(d) - sin(la1) * sin(la2))
        return GeoPoint(Math.toDegrees(la2), Math.toDegrees(lo2))
    }

    private fun fetch(plan: List<Pair<Int, GeoPoint>>, speedKmh: Float, dest: Place?): List<RoutePoint> {
        val lats = plan.joinToString(",") { "%.4f".format(Locale.US, it.second.lat) }
        val lons = plan.joinToString(",") { "%.4f".format(Locale.US, it.second.lon) }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lats&longitude=$lons" +
            "&hourly=weather_code,temperature_2m,precipitation_probability,precipitation,visibility&forecast_days=3&timezone=auto"
        val body = get(url)
        val json = body.trim()
        val arr = if (json.startsWith("[")) JSONArray(json) else JSONArray().put(JSONObject(json))
        val now = LocalDateTime.now()
        return plan.mapIndexed { i, (km, p) ->
            val minutes = (km / speedKmh * 60).toInt()
            parsePoint(arr.getJSONObject(i), now.plusMinutes(minutes.toLong()), km, minutes, p, if (i == plan.lastIndex) dest?.name else null)
        }
    }

    /** Valores de la hora más cercana a [at] en una respuesta horaria de Open-Meteo. */
    fun parsePoint(o: JSONObject, at: LocalDateTime, km: Int, minutes: Int, p: GeoPoint, label: String?): RoutePoint {
        val h = o.getJSONObject("hourly")
        val times = h.getJSONArray("time")
        val target = at.withMinute(0).withSecond(0).withNano(0).plusHours(if (at.minute >= 30) 1 else 0)
        var idx = 0
        for (k in 0 until times.length()) {
            if (!LocalDateTime.parse(times.getString(k)).isBefore(target)) { idx = k; break }
            idx = k
        }
        fun num(key: String) = h.optJSONArray(key)?.let { a -> if (a.isNull(idx)) null else a.optDouble(idx) }
        return RoutePoint(
            km = km, minutes = minutes, point = p,
            code = num("weather_code")?.toInt() ?: 0,
            tempC = num("temperature_2m")?.let { Math.round(it).toInt() },
            precipProb = num("precipitation_probability")?.toInt(),
            precipMm = num("precipitation")?.toFloat(),
            visibilityM = num("visibility")?.toFloat(),
            label = label,
        )
    }

    fun summarize(pts: List<RoutePoint>): String {
        val wet = pts.firstOrNull { (it.precipProb ?: 0) >= 50 || (it.precipMm ?: 0f) >= 0.3f }
        val fog = pts.firstOrNull { (it.visibilityM ?: 99_999f) < 1000f }
        return when {
            wet != null && fog != null && fog.km < wet.km -> "Niebla en ${fog.km} km"
            wet != null -> "Lluvia en ${wet.km} km" + (wet.precipProb?.let { " ($it %)" } ?: "")
            fog != null -> "Niebla en ${fog.km} km"
            else -> "Sin lluvia en ruta"
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        return try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun preview(state: RouteWeatherState) { _state.value = state }
}
