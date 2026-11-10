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

/** Tipos de aviso en ruta; cada uno se puede apagar por separado en Ajustes o por voz. */
enum class AlertCategory(val label: String, val spoken: String) {
    RADAR("Radares", "de radares"),
    SAFETY("Seguridad", "de seguridad"),
    FUEL("Combustible", "de combustible"),
    REST("Descanso", "de áreas de descanso"),
    TOURISM("Turismo", "turísticos"),
}

/** Un punto de interés de OpenStreetMap. */
data class Poi(
    /** "n123", "w456"… (tipo de elemento OSM + id). */
    val id: String,
    val lat: Double,
    val lon: Double,
    val category: AlertCategory,
    /** Valor de la etiqueta OSM: "viewpoint", "castle", "level_crossing", "animal_crossing", "town"… */
    val kind: String,
    val name: String?,
    /** Etiqueta wikipedia («es:Alcázar de Segovia») si la hay. */
    val wikipedia: String? = null,
    val wikidata: String? = null,
) {
    val point get() = GeoPoint(lat, lon)
    /** Qué es, en español: «Mirador», «Castillo», «Paso a nivel»… */
    val what: String get() = RoutePois.describe(this)
}

/** Un punto visto desde el coche. */
data class PoiView(val poi: Poi, val meters: Double, val relBearing: Float)

data class PoiState(
    val pois: List<Poi> = emptyList(),
    /** Puntos a menos de 5 km, del más cercano al más lejano. */
    val near: List<PoiView> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val updatedAt: Long = 0,
)

/**
 * Puntos de interés de OpenStreetMap alrededor del coche (Overpass, como los radares): peligros
 * señalizados, pasos a nivel, áreas de descanso, monumentos, miradores, museos y pueblos con
 * artículo en Wikipedia. Se descargan los de 15 km, se guardan en disco y se renuevan al alejarse
 * 8 km. Cada segundo se recalcula qué hay cerca y [RouteAlerts] decide si avisar.
 */
object RoutePois {
    private const val RADIUS_M = 15_000
    private const val REFETCH_M = 8_000.0
    private const val MAX_AGE_MS = 3L * 24 * 3600 * 1000
    private const val NEAR_M = 5000.0
    private val ENDPOINTS = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")

    private val _state = MutableStateFlow(PoiState())
    val state: StateFlow<PoiState> = _state.asStateFlow()

    private var job: Job? = null
    private var center: GeoPoint? = null
    private var lastFail = 0L

    fun start(context: Context, scope: CoroutineScope) {
        if (job != null) return
        val ctx = context.applicationContext
        loadCache(ctx)
        job = scope.launch {
            while (isActive) {
                val route = RouteTracker.state.value
                val pos = route.position
                if (pos != null) {
                    runCatching {
                        maybeFetch(ctx, pos)
                        update(pos, route.bearing)
                        RouteAlerts.tick(ctx, scope, pos, route.bearing, route.live)
                    }
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
        result.onSuccess { pois ->
            center = pos
            _state.value = _state.value.copy(pois = pois, loading = false, error = null, updatedAt = System.currentTimeMillis())
            saveCache(ctx, pos, pois)
        }.onFailure {
            lastFail = System.currentTimeMillis()
            _state.value = _state.value.copy(loading = false, error = it.message ?: it.javaClass.simpleName)
        }
    }

    fun update(pos: GeoPoint, bearing: Float) {
        val s = _state.value
        val near = s.pois.asSequence()
            .map { PoiView(it, RouteTracker.distance(pos, it.point), relative(RouteTracker.bearingBetween(pos, it.point), bearing)) }
            .filter { it.meters < NEAR_M }
            .sortedBy { it.meters }
            .toList()
        _state.value = s.copy(near = near)
    }

    fun relative(target: Float, heading: Float): Float {
        var d = target - heading
        while (d > 180) d -= 360
        while (d < -180) d += 360
        return d
    }

    private val TOURISM = "attraction|viewpoint|museum|theme_park|zoo"
    private val HISTORIC = "castle|monument|ruins|archaeological_site|monastery|fort|city_gate|aqueduct|bridge|tower|palace|church|cathedral"

    fun query(lat: String, lon: String) = """
        [out:json][timeout:30];
        (
          node["hazard"](around:$RADIUS_M,$lat,$lon);
          node["railway"="level_crossing"](around:$RADIUS_M,$lat,$lon);
          nwr["highway"~"^(rest_area|services)$"](around:$RADIUS_M,$lat,$lon);
          nwr["tourism"~"^($TOURISM)$"]["name"]["wikidata"](around:$RADIUS_M,$lat,$lon);
          nwr["historic"~"^($HISTORIC)$"]["name"]["wikidata"](around:$RADIUS_M,$lat,$lon);
          node["place"~"^(city|town)$"]["name"]["wikidata"](around:$RADIUS_M,$lat,$lon);
        );
        out center tags;
    """.trimIndent()

    private fun fetch(pos: GeoPoint): List<Poi> {
        val q = query("%.5f".format(Locale.US, pos.lat), "%.5f".format(Locale.US, pos.lon))
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

    /** Respuesta de Overpass («out center tags»): nodos con lat/lon, vías y relaciones con center. */
    fun parse(json: String): List<Poi> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Poi>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: continue
            val center = e.optJSONObject("center")
            val lat = if (e.has("lat")) e.getDouble("lat") else center?.optDouble("lat") ?: continue
            val lon = if (e.has("lon")) e.getDouble("lon") else center?.optDouble("lon") ?: continue
            val type = e.optString("type").take(1).ifEmpty { "n" }
            fun tag(k: String) = tags.optString(k).ifBlank { null }
            val name = tag("name:es") ?: tag("name")
            val (cat, kind) = when {
                tag("hazard") != null -> AlertCategory.SAFETY to tag("hazard")!!
                tag("railway") == "level_crossing" -> AlertCategory.SAFETY to "level_crossing"
                tag("highway") == "rest_area" || tag("highway") == "services" -> AlertCategory.REST to tag("highway")!!
                tag("historic") != null -> AlertCategory.TOURISM to tag("historic")!!
                tag("tourism") != null -> AlertCategory.TOURISM to tag("tourism")!!
                tag("place") != null -> AlertCategory.TOURISM to "place"
                else -> continue
            }
            if (cat == AlertCategory.TOURISM && name == null) continue
            out += Poi("$type${e.getLong("id")}", lat, lon, cat, kind, name, tag("wikipedia"), tag("wikidata"))
        }
        return out.distinctBy { it.id }
    }

    /** Nombre del tipo de punto en español. */
    fun describe(p: Poi): String = when (p.kind) {
        "level_crossing" -> "Paso a nivel"
        "animal_crossing", "deer", "cattle", "wild_animals" -> "Paso de animales"
        "curve", "curves", "dangerous_curve" -> "Curva peligrosa"
        "dangerous_junction", "junction" -> "Cruce peligroso"
        "falling_rocks", "rockfall", "landslide" -> "Desprendimientos"
        "ice", "icy_road" -> "Peligro de hielo"
        "slippery", "slippery_road" -> "Calzada deslizante"
        "school_zone", "children" -> "Zona escolar"
        "pedestrians" -> "Peatones"
        "cyclists" -> "Ciclistas"
        "road_works" -> "Obras"
        "fog" -> "Zona de niebla"
        "flooding" -> "Zona inundable"
        "side_winds", "wind" -> "Viento lateral"
        "bump", "dip" -> "Badén"
        "queue", "traffic_jam" -> "Retenciones frecuentes"
        "accident_blackspot", "accident" -> "Tramo de concentración de accidentes"
        "rest_area" -> "Área de descanso"
        "services" -> "Área de servicio"
        "viewpoint" -> "Mirador"
        "museum" -> "Museo"
        "attraction" -> "Lugar de interés"
        "theme_park" -> "Parque temático"
        "zoo" -> "Zoo"
        "castle" -> "Castillo"
        "monument" -> "Monumento"
        "ruins" -> "Ruinas"
        "archaeological_site" -> "Yacimiento arqueológico"
        "monastery" -> "Monasterio"
        "fort" -> "Fortaleza"
        "city_gate" -> "Puerta de la ciudad"
        "aqueduct" -> "Acueducto"
        "bridge" -> "Puente histórico"
        "tower" -> "Torre"
        "palace" -> "Palacio"
        "church" -> "Iglesia"
        "cathedral" -> "Catedral"
        "place" -> "Localidad"
        else -> if (p.category == AlertCategory.SAFETY) "Peligro" else "Punto de interés"
    }

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "puntos_interes.json")

    private fun saveCache(ctx: Context, at: GeoPoint, pois: List<Poi>) = runCatching {
        val arr = JSONArray()
        pois.forEach { p ->
            arr.put(
                JSONObject().put("id", p.id).put("lat", p.lat).put("lon", p.lon).put("cat", p.category.name).put("kind", p.kind)
                    .putOpt("name", p.name).putOpt("wp", p.wikipedia).putOpt("wd", p.wikidata),
            )
        }
        cacheFile(ctx).writeText(JSONObject().put("lat", at.lat).put("lon", at.lon).put("time", System.currentTimeMillis()).put("pois", arr).toString())
    }

    private fun loadCache(ctx: Context) = runCatching {
        val o = JSONObject(cacheFile(ctx).readText())
        val arr = o.getJSONArray("pois")
        val pois = (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            Poi(
                p.getString("id"), p.getDouble("lat"), p.getDouble("lon"), AlertCategory.valueOf(p.getString("cat")), p.getString("kind"),
                p.optString("name").ifEmpty { null }, p.optString("wp").ifEmpty { null }, p.optString("wd").ifEmpty { null },
            )
        }
        center = GeoPoint(o.getDouble("lat"), o.getDouble("lon"))
        _state.value = _state.value.copy(pois = pois, updatedAt = o.getLong("time"))
    }

    /** Para pruebas y capturas. */
    fun preview(state: PoiState) { _state.value = state }
}
