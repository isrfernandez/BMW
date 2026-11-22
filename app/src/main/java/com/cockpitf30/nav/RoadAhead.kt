package com.cockpitf30.nav

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Un tramo de carretera de OpenStreetMap con sus nodos y su geometría. */
class RoadWay(
    val id: Long,
    val nodes: LongArray,
    val pts: List<GeoPoint>,
    /** 0 doble sentido, 1 solo hacia delante, −1 solo al revés. */
    val oneway: Int,
    val highway: String,
    val roundabout: Boolean = false,
    /** Pendiente señalizada en OSM (tanto por ciento, con signo en el sentido de la vía), si la hay. */
    val inclinePct: Float? = null,
)

/** Una curva por delante: dónde empieza, lo cerrada que es y hacia dónde. */
data class CurveAhead(
    val startM: Float,
    val lengthM: Float,
    val minRadiusM: Float,
    /** Giro total en grados: + derecha, − izquierda. */
    val turnDeg: Float,
    /** Hay otra curva justo detrás (curvas enlazadas). */
    val chained: Boolean = false,
) {
    val right get() = turnDeg > 0
    /** Velocidad cómoda para tomarla (aceleración lateral de ~3 m/s²), redondeada a 10. */
    val adviceKmh: Int get() = ((sqrt(RoadAhead.LAT_ACC * minRadiusM) * 3.6f) / 10f).roundToInt() * 10
}

/** Pendiente fuerte por delante. */
data class GradeAhead(val startM: Float, val pct: Float, val lengthM: Float)

/**
 * Lo que viene por la carretera que llevamos: curvas cerradas y pendientes fuertes, aunque no
 * haya ruta en Google Maps. Se descargan de OpenStreetMap las carreteras de alrededor, se busca
 * la que pisamos (la más cercana con el mismo rumbo) y se sigue hacia delante por la
 * continuación más recta en cada cruce. Las curvas salen de la propia geometría; las pendientes,
 * de la altitud del terreno (Open-Meteo) a lo largo de ese camino.
 *
 * Los peligros señalizados en OSM (hazard=curve…) ya los avisa [RoutePois] como «Seguridad».
 */
object RoadAhead {
    const val LAT_ACC = 3.0f
    private const val FETCH_RADIUS_M = 2500
    private const val REFETCH_M = 1500.0
    private const val AHEAD_M = 2000f
    private const val STEP_M = 10f
    private const val CURVE_WIN = 3 // puntos a cada lado (±30 m)
    private const val CURVE_RADIUS_M = 150f
    private const val MIN_TURN_DEG = 35f
    private const val STEEP_PCT = 7f
    private const val GRADE_WIN_M = 300f
    private const val MATCH_M = 30.0
    private const val MATCH_DEG = 40f
    private val ENDPOINTS = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")

    @Volatile private var ways: List<RoadWay> = emptyList()
    @Volatile private var center: GeoPoint? = null
    private var fetchJob: Job? = null
    private var lastFail = 0L
    private var gradeJob: Job? = null
    private var lastGradeAt = 0L
    @Volatile var grades: List<GradeAhead> = emptyList()
        private set
    @Volatile private var gradesFrom: GeoPoint? = null
    @Volatile var lastPath: List<GeoPoint> = emptyList()
        private set

    fun reset() { ways = emptyList(); center = null; grades = emptyList(); gradesFrom = null; lastPath = emptyList() }

    /** Para las pruebas. */
    internal fun setWays(w: List<RoadWay>) { ways = w }

    /**
     * Cada segundo con el coche en marcha: descarga lo que haga falta y devuelve el camino por
     * delante (puntos cada 10 m) con sus curvas.
     */
    fun update(scope: CoroutineScope, pos: GeoPoint, heading: Float, netOk: Boolean): Pair<List<GeoPoint>, List<CurveAhead>> {
        val now = System.currentTimeMillis()
        val c = center
        if (netOk && fetchJob?.isActive != true && now - lastFail > 120_000 && (c == null || RouteTracker.distance(c, pos) > REFETCH_M)) {
            fetchJob = scope.launch(Dispatchers.IO) {
                runCatching { fetch(pos) }.onSuccess { ways = it; center = pos }.onFailure { lastFail = System.currentTimeMillis() }
            }
        }
        val path = path(ways, pos, heading, AHEAD_M) ?: return emptyList<GeoPoint>() to emptyList()
        lastPath = path
        // Altitud a lo largo del camino cada 20 s (una sola petición con todos los puntos).
        if (netOk && gradeJob?.isActive != true && now - lastGradeAt > 20_000 && path.size > 30) {
            lastGradeAt = now
            val samples = path.filterIndexed { i, _ -> i % 10 == 0 } // cada 100 m
            gradeJob = scope.launch(Dispatchers.IO) {
                runCatching { elevations(samples) }.onSuccess { alt ->
                    grades = steepGrades(alt, 100f)
                    gradesFrom = pos
                }
            }
        }
        return path to curves(path)
    }

    /** Las pendientes fuertes, con la distancia corregida por lo que hemos avanzado desde que se midieron. */
    fun gradesNow(pos: GeoPoint): List<GradeAhead> {
        val from = gradesFrom ?: return emptyList()
        val moved = RouteTracker.distance(from, pos).toFloat()
        return grades.map { it.copy(startM = it.startM - moved) }.filter { it.startM + it.lengthM > 0 }
    }

    // ── Geometría (puro) ─────────────────────────────────────────────────

    private data class Dir(val way: RoadWay, val forward: Boolean)

    /**
     * El camino por delante desde [pos] con rumbo [heading]: puntos cada [STEP_M] metros, o null
     * si no estamos sobre ninguna carretera conocida.
     */
    fun path(ways: List<RoadWay>, pos: GeoPoint, heading: Float, lengthM: Float): List<GeoPoint>? {
        if (ways.isEmpty()) return null
        // 1) El tramo que pisamos.
        var best: Triple<Dir, Int, Double>? = null
        var bestPoint: GeoPoint? = null
        for (w in ways) {
            for (i in 0 until w.pts.size - 1) {
                val a = w.pts[i]; val b = w.pts[i + 1]
                val (q, d) = project(pos, a, b)
                if (d > MATCH_M || (best != null && d >= best.third)) continue
                val brg = RouteTracker.bearingBetween(a, b)
                val fwdOk = w.oneway != -1 && angle(brg, heading) <= MATCH_DEG
                val revOk = w.oneway != 1 && angle((brg + 180f) % 360f, heading) <= MATCH_DEG
                if (fwdOk) { best = Triple(Dir(w, true), i, d); bestPoint = q }
                else if (revOk) { best = Triple(Dir(w, false), i + 1, d); bestPoint = q }
            }
        }
        val (dir0, seg, _) = best ?: return null
        if (dir0.way.roundabout) return null
        val raw = ArrayList<GeoPoint>()
        raw += bestPoint!!
        // 2) Hacia delante, por la continuación más recta en cada cruce.
        val byNode = HashMap<Long, MutableList<Pair<RoadWay, Int>>>()
        for (w in ways) w.nodes.forEachIndexed { i, n -> byNode.getOrPut(n) { ArrayList() } += w to i }
        var dir = dir0
        var idx = if (dir0.forward) seg + 1 else seg - 1 // siguiente nodo
        var total = 0.0
        val visited = HashSet<Long>()
        while (total < lengthM) {
            val w = dir.way
            if (idx !in w.pts.indices) break
            val p = w.pts[idx]
            total += RouteTracker.distance(raw.last(), p)
            raw += p
            val node = w.nodes[idx]
            if (!visited.add(node)) break
            val atEnd = if (dir.forward) idx == w.pts.lastIndex else idx == 0
            if (!atEnd) { idx += if (dir.forward) 1 else -1; continue }
            val inBrg = RouteTracker.bearingBetween(raw[raw.size - 2], p)
            var next: Pair<Dir, Int>? = null
            var nextTurn = 999f
            for ((cw, ci) in byNode[node].orEmpty()) {
                if (cw === w) continue
                if (cw.roundabout) continue
                if (ci < cw.pts.lastIndex && cw.oneway != -1) {
                    val t = angle(RouteTracker.bearingBetween(p, cw.pts[ci + 1]), inBrg)
                    if (t < nextTurn) { nextTurn = t; next = Dir(cw, true) to ci + 1 }
                }
                if (ci > 0 && cw.oneway != 1) {
                    val t = angle(RouteTracker.bearingBetween(p, cw.pts[ci - 1]), inBrg)
                    if (t < nextTurn) { nextTurn = t; next = Dir(cw, false) to ci - 1 }
                }
            }
            // Un cruce en T o una rotonda: no sabemos por dónde seguirá, el camino acaba aquí.
            if (next == null || nextTurn > 60f) break
            dir = next.first; idx = next.second
        }
        return resample(raw, STEP_M)
    }

    /** Las curvas del camino (puntos cada [STEP_M] m). */
    fun curves(path: List<GeoPoint>): List<CurveAhead> {
        if (path.size < CURVE_WIN * 2 + 3) return emptyList()
        val n = path.size
        val brg = FloatArray(n - 1) { RouteTracker.bearingBetween(path[it], path[it + 1]) }
        // Radio en cada punto con el cambio de rumbo en ±30 m.
        val radius = FloatArray(n) { Float.MAX_VALUE }
        val turn = FloatArray(n)
        for (i in CURVE_WIN until n - 1 - CURVE_WIN) {
            val d = signed(brg[i - CURVE_WIN], brg[i + CURVE_WIN - 1])
            turn[i] = d
            val rad = abs(d) * PI.toFloat() / 180f
            if (rad > 1e-3f) radius[i] = (2 * CURVE_WIN * STEP_M) / rad
        }
        val out = ArrayList<CurveAhead>()
        var i = 0
        while (i < n) {
            if (radius[i] >= CURVE_RADIUS_M) { i++; continue }
            val sign = turn[i] > 0
            val start = i
            var minR = radius[i]
            while (i < n && radius[i] < CURVE_RADIUS_M && (turn[i] > 0) == sign) { minR = minOf(minR, radius[i]); i++ }
            val end = i
            // Giro total: rumbo antes y después de la curva.
            val a = brg[(start - 1).coerceIn(0, brg.lastIndex)]
            val b = brg[end.coerceIn(0, brg.lastIndex)]
            val total = signed(a, b)
            if (abs(total) >= MIN_TURN_DEG) out += CurveAhead(start * STEP_M, (end - start) * STEP_M, minR, total)
        }
        return out.mapIndexed { k, c -> c.copy(chained = out.getOrNull(k + 1)?.let { it.startM - (c.startM + c.lengthM) < 150f } == true) }
    }

    /** Tramos de al menos [GRADE_WIN_M] con pendiente de [STEEP_PCT] % o más, con altitudes cada [stepM]. */
    fun steepGrades(alt: List<Float>, stepM: Float): List<GradeAhead> {
        val win = (GRADE_WIN_M / stepM).roundToInt().coerceAtLeast(1)
        val out = ArrayList<GradeAhead>()
        var i = 0
        while (i + win < alt.size) {
            val pct = (alt[i + win] - alt[i]) / (win * stepM) * 100f
            if (abs(pct) >= STEEP_PCT) {
                // Se alarga mientras siga igual de fuerte y en el mismo sentido.
                var j = i + win
                while (j + 1 < alt.size && abs((alt[j + 1] - alt[j]) / stepM * 100f) >= STEEP_PCT * 0.7f && (alt[j + 1] - alt[j] > 0) == (pct > 0)) j++
                val p = (alt[j] - alt[i]) / ((j - i) * stepM) * 100f
                out += GradeAhead(i * stepM, p, (j - i) * stepM)
                i = j
            } else i++
        }
        return out
    }

    private fun resample(pts: List<GeoPoint>, step: Float): List<GeoPoint> {
        if (pts.size < 2) return pts
        val out = arrayListOf(pts[0])
        var carry = 0.0
        for (k in 0 until pts.size - 1) {
            val a = pts[k]; val b = pts[k + 1]
            val d = RouteTracker.distance(a, b)
            if (d < 1e-6) continue
            var t = step - carry
            while (t <= d) {
                val f = t / d
                out += GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
                t += step
            }
            carry = d - (t - step)
        }
        return out
    }

    /** Punto de [a]-[b] más cercano a [p] y su distancia en metros. */
    private fun project(p: GeoPoint, a: GeoPoint, b: GeoPoint): Pair<GeoPoint, Double> {
        val k = cos(Math.toRadians(p.lat))
        val ax = (a.lon - p.lon) * k; val ay = a.lat - p.lat
        val bx = (b.lon - p.lon) * k; val by = b.lat - p.lat
        val dx = bx - ax; val dy = by - ay
        val len = dx * dx + dy * dy
        val t = if (len < 1e-18) 0.0 else (-(ax * dx + ay * dy) / len).coerceIn(0.0, 1.0)
        val q = GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
        return q to RouteTracker.distance(p, q)
    }

    private fun angle(a: Float, b: Float) = abs(signed(b, a))
    /** De [from] a [to], −180..180 (+ a la derecha). */
    private fun signed(from: Float, to: Float): Float { var d = (to - from) % 360f; if (d > 180) d -= 360; if (d < -180) d += 360; return d }

    // ── Red ──────────────────────────────────────────────────────────────

    private fun fetch(pos: GeoPoint): List<RoadWay> {
        val q = """
            [out:json][timeout:60];
            way["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$"](around:$FETCH_RADIUS_M,${"%.5f".format(Locale.US, pos.lat)},${"%.5f".format(Locale.US, pos.lon)});
            out body geom;
        """.trimIndent()
        var last: Exception? = null
        for (e in ENDPOINTS) {
            try { return parse(post(e, "data=" + URLEncoder.encode(q, "UTF-8"))) } catch (x: Exception) { last = x }
        }
        throw last ?: IllegalStateException("sin respuesta")
    }

    internal fun parse(json: String): List<RoadWay> {
        val arr = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<RoadWay>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val nodes = o.optJSONArray("nodes") ?: continue
            val geom = o.optJSONArray("geometry") ?: continue
            if (nodes.length() != geom.length() || nodes.length() < 2) continue
            val tags = o.optJSONObject("tags")
            val hw = tags?.optString("highway").orEmpty()
            val ow = tags?.optString("oneway").orEmpty()
            val roundabout = tags?.optString("junction") in setOf("roundabout", "circular")
            val oneway = when {
                ow == "-1" -> -1
                ow == "yes" || ow == "true" || ow == "1" || hw == "motorway" || hw == "motorway_link" || roundabout -> 1
                else -> 0
            }
            val incline = tags?.optString("incline")?.removeSuffix("%")?.trim()?.toFloatOrNull()
            out += RoadWay(
                o.getLong("id"),
                LongArray(nodes.length()) { nodes.getLong(it) },
                List(geom.length()) { geom.getJSONObject(it).let { g -> GeoPoint(g.getDouble("lat"), g.getDouble("lon")) } },
                oneway, hw, roundabout, incline,
            )
        }
        return out
    }

    private fun post(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 70_000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.setRequestProperty("User-Agent", "CockpitF30/1.0")
        return try {
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** Altitud del terreno en cada punto (Open-Meteo, hasta 100 por petición). */
    private fun elevations(pts: List<GeoPoint>): List<Float> {
        val list = pts.take(100)
        val lat = list.joinToString(",") { "%.5f".format(Locale.US, it.lat) }
        val lon = list.joinToString(",") { "%.5f".format(Locale.US, it.lon) }
        val c = URL("https://api.open-meteo.com/v1/elevation?latitude=$lat&longitude=$lon").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        return try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            val a = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("elevation")
            List(a.length()) { a.getDouble(it).toFloat() }
        } finally { c.disconnect() }
    }
}
