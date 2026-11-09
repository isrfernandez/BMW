package com.cockpitf30.nav

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs

/** Radar fijo de OpenStreetMap. */
data class SpeedCamera(
    val id: Long,
    val lat: Double,
    val lon: Double,
    /** Límite en km/h si el mapa lo dice. */
    val maxspeed: Int? = null,
    /** "Tramo" (velocidad media), "Semáforo" o null para el radar normal. */
    val kind: String? = null,
    /** Rumbo que vigila en grados, si el mapa lo dice. */
    val direction: Float? = null,
) {
    val point get() = GeoPoint(lat, lon)
}

/** Radar cercano visto desde el coche. */
data class CameraView(
    val camera: SpeedCamera,
    val meters: Double,
    /** Ángulo respecto al rumbo del coche: 0 = de frente, + a la derecha. */
    val relBearing: Float,
)

data class CameraState(
    val cameras: List<SpeedCamera> = emptyList(),
    /** Radares a menos de 3 km, del más cercano al más lejano. */
    val near: List<CameraView> = emptyList(),
    /** Radar que tenemos delante y al que nos acercamos (aviso). */
    val ahead: CameraView? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val updatedAt: Long = 0,
)

/**
 * Radares fijos de OpenStreetMap (Overpass): se descargan los de 40 km alrededor, se guardan en
 * disco y se vuelven a pedir al alejarse 25 km o pasada una semana. Cada segundo se mira cuál
 * queda delante (dentro de ±35° del rumbo) y a qué distancia, para avisar sin llevar Maps abierto.
 */
object SpeedCameras {
    private const val RADIUS_M = 40_000
    private const val REFETCH_M = 25_000.0
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
    private const val NEAR_M = 3000.0
    private const val AHEAD_M = 1200.0
    private const val AHEAD_DEG = 35f
    private val ENDPOINTS = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")

    private val _state = MutableStateFlow(CameraState())
    val state: StateFlow<CameraState> = _state.asStateFlow()

    /** Se llama con el radar que acaba de entrar en zona de aviso (para decirlo en voz alta). */
    @Volatile var onAlert: ((CameraView) -> Unit)? = null

    private var job: Job? = null
    private var center: GeoPoint? = null
    private var lastFail = 0L
    private val alerted = HashSet<Long>()

    fun start(context: Context, scope: CoroutineScope) {
        if (job != null) return
        val ctx = context.applicationContext
        loadCache(ctx)
        job = scope.launch {
            while (isActive) {
                val route = RouteTracker.state.value
                val pos = route.position
                if (pos != null) {
                    maybeFetch(ctx, pos)
                    update(pos, route.bearing)
                }
                delay(1000)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    private suspend fun maybeFetch(ctx: Context, pos: GeoPoint) {
        val s = _state.value
        val c = center
        val stale = System.currentTimeMillis() - s.updatedAt > MAX_AGE_MS
        val far = c == null || RouteTracker.distance(c, pos) > REFETCH_M
        if (!(stale || far) || s.loading) return
        if (System.currentTimeMillis() - lastFail < 120_000) return
        _state.value = s.copy(loading = true)
        val result = withContext(Dispatchers.IO) { runCatching { fetch(pos) } }
        result.onSuccess { cams ->
            center = pos
            _state.value = _state.value.copy(cameras = cams, loading = false, error = null, updatedAt = System.currentTimeMillis())
            saveCache(ctx, pos, cams)
        }.onFailure {
            lastFail = System.currentTimeMillis()
            _state.value = _state.value.copy(loading = false, error = it.message ?: it.javaClass.simpleName)
        }
    }

    /** Distancias y aviso con la posición y el rumbo actuales. */
    fun update(pos: GeoPoint, bearing: Float) {
        val s = _state.value
        val near = s.cameras.asSequence()
            .map { CameraView(it, RouteTracker.distance(pos, it.point), relative(RouteTracker.bearingBetween(pos, it.point), bearing)) }
            .filter { it.meters < NEAR_M }
            .sortedBy { it.meters }
            .toList()
        val ahead = near.firstOrNull { v ->
            v.meters < AHEAD_M && abs(v.relBearing) < AHEAD_DEG &&
                // Si el mapa dice hacia dónde mira, solo cuenta si vamos en ese sentido.
                (v.camera.direction?.let { abs(relative(it, bearing)) < 60f } ?: true)
        }
        if (ahead != null && ahead.meters < 600 && alerted.add(ahead.camera.id)) onAlert?.invoke(ahead)
        // Olvida los avisos de los radares que ya quedan lejos, para volver a avisar otro día.
        if (alerted.size > 0) alerted.retainAll(near.filter { it.meters < 1500 }.map { it.camera.id }.toSet())
        _state.value = s.copy(near = near, ahead = ahead)
    }

    private fun relative(target: Float, heading: Float): Float {
        var d = target - heading
        while (d > 180) d -= 360
        while (d < -180) d += 360
        return d
    }

    private fun fetch(pos: GeoPoint): List<SpeedCamera> {
        val lat = "%.5f".format(Locale.US, pos.lat)
        val lon = "%.5f".format(Locale.US, pos.lon)
        val q = """
            [out:json][timeout:30];
            rel["enforcement"](around:$RADIUS_M,$lat,$lon)->.r;
            (node["highway"="speed_camera"](around:$RADIUS_M,$lat,$lon); node(r.r:"device"););
            out body;
            .r out body;
        """.trimIndent()
        var last: Exception? = null
        for (endpoint in ENDPOINTS) {
            try {
                return parse(post(endpoint, "data=" + URLEncoder.encode(q, "UTF-8")))
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("sin respuesta")
    }

    private fun post(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 40_000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.setRequestProperty("User-Agent", "CockpitF30/1.0")
        return try {
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Respuesta de Overpass: nodos radar y relaciones «enforcement» que los nombran como device. */
    fun parse(json: String): List<SpeedCamera> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val nodes = LinkedHashMap<Long, JSONObject>()
        val relInfo = HashMap<Long, JSONObject>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            when (e.optString("type")) {
                "node" -> nodes[e.getLong("id")] = e
                "relation" -> {
                    val tags = e.optJSONObject("tags") ?: continue
                    val members = e.optJSONArray("members") ?: continue
                    for (k in 0 until members.length()) {
                        val m = members.getJSONObject(k)
                        if (m.optString("type") == "node" && m.optString("role") == "device") relInfo[m.getLong("ref")] = tags
                    }
                }
            }
        }
        return nodes.values.mapNotNull { n ->
            val id = n.getLong("id")
            val tags = n.optJSONObject("tags") ?: JSONObject()
            val rel = relInfo[id]
            val enforcement = rel?.optString("enforcement").orEmpty()
            if (rel != null && enforcement !in setOf("maxspeed", "average_speed", "traffic_signals", "mindistance")) {
                if (tags.optString("highway") != "speed_camera") return@mapNotNull null
            }
            SpeedCamera(
                id = id, lat = n.getDouble("lat"), lon = n.getDouble("lon"),
                maxspeed = speedOf(tags.optString("maxspeed")) ?: speedOf(rel?.optString("maxspeed").orEmpty()),
                kind = when (enforcement) {
                    "average_speed" -> "Tramo"
                    "traffic_signals" -> "Semáforo"
                    else -> null
                },
                direction = tags.optString("direction").toFloatOrNull() ?: tags.optString("camera:direction").toFloatOrNull(),
            )
        }
    }

    private fun speedOf(s: String): Int? = Regex("\\d+").find(s)?.value?.toIntOrNull()?.takeIf { it in 10..200 }

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "radares.json")

    private fun saveCache(ctx: Context, at: GeoPoint, cams: List<SpeedCamera>) = runCatching {
        val arr = JSONArray()
        cams.forEach { c ->
            arr.put(JSONObject().put("id", c.id).put("lat", c.lat).put("lon", c.lon).putOpt("max", c.maxspeed).putOpt("kind", c.kind).putOpt("dir", c.direction?.toDouble()))
        }
        cacheFile(ctx).writeText(JSONObject().put("lat", at.lat).put("lon", at.lon).put("time", System.currentTimeMillis()).put("cams", arr).toString())
    }

    private fun loadCache(ctx: Context) = runCatching {
        val o = JSONObject(cacheFile(ctx).readText())
        val arr = o.getJSONArray("cams")
        val cams = (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            SpeedCamera(
                c.getLong("id"), c.getDouble("lat"), c.getDouble("lon"),
                if (c.has("max")) c.getInt("max") else null, c.optString("kind").ifEmpty { null },
                if (c.has("dir")) c.getDouble("dir").toFloat() else null,
            )
        }
        center = GeoPoint(o.getDouble("lat"), o.getDouble("lon"))
        _state.value = _state.value.copy(cameras = cams, updatedAt = o.getLong("time"))
    }

    /** Para capturas y pruebas. */
    fun preview(state: CameraState) { _state.value = state }
}
