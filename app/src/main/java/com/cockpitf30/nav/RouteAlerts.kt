package com.cockpitf30.nav

import android.content.Context
import com.cockpitf30.fuel.FuelRepository
import com.cockpitf30.fuel.FuelStation
import com.cockpitf30.fuel.FuelStations
import com.cockpitf30.system.AppSettings
import com.cockpitf30.vehicle.VehicleRepository
import com.cockpitf30.vehicle.trip.TripComputer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Un aviso en ruta: lo que se dice en voz alta y lo que sale en la ventanita. */
data class RouteAlert(
    val id: String,
    val category: AlertCategory,
    val title: String,
    /** «Mirador · 1,5 km a la derecha». */
    val subtitle: String,
    val speech: String,
    /** Resumen para la ventanita (Wikipedia en los turísticos). */
    val text: String? = null,
    val image: ByteArray? = null,
    val at: Long = System.currentTimeMillis(),
    val poi: Poi? = null,
)

/** Gasolinera vista desde el coche. */
data class StationView(val station: FuelStation, val meters: Double, val relBearing: Float)

/**
 * Decide cuándo avisar de lo que hay en el camino (peligros, áreas de descanso, sitios turísticos y
 * combustible bajo) con lo que calcula [RoutePois]. Lo que avisa lo dice el asistente (lo conecta
 * MainActivity con [deliver]) y lo muestra la ventanita del aviso. Los radares van aparte
 * ([SpeedCameras]) pero respetan el mismo interruptor general.
 */
object RouteAlerts {
    /** Distancia de aviso y ángulo máximo respecto al rumbo, por tipo. */
    private const val SAFETY_M = 500.0
    private const val CROSSING_M = 400.0
    private const val REST_M = 2500.0
    private const val TOURISM_M = 2000.0
    private const val PLACE_M = 1500.0
    private const val PREFETCH_M = 5000.0
    /** Descanso: solo tras hora y media al volante. */
    const val REST_AFTER_MS = 90 * 60_000L
    /** Combustible: avisar por debajo de esta autonomía. */
    const val LOW_RANGE_KM = 60
    /** Entre dos avisos turísticos (en demo, la cuarta parte). */
    private const val TOURISM_GAP_MS = 3 * 60_000L
    private const val REST_GAP_MS = 20 * 60_000L
    private const val FUEL_GAP_MS = 15 * 60_000L
    /** No repetir el mismo punto en este tiempo. */
    private const val REPEAT_MS = 2 * 3_600_000L
    private const val VISIBLE_MS = 20_000L

    private val _current = MutableStateFlow<RouteAlert?>(null)
    /** El último aviso, para la ventanita (se oculta sola al cabo de unos segundos). */
    val current: StateFlow<RouteAlert?> = _current.asStateFlow()

    /** Último aviso turístico, para «háblame de eso». */
    @Volatile var lastTourism: RouteAlert? = null
        private set

    /**
     * Lo conecta la pantalla: dice el aviso en voz alta. Devuelve false si el asistente está ocupado
     * (escuchando o hablando) y hay que esperar.
     */
    @Volatile var deliver: ((RouteAlert) -> Boolean)? = null

    /** Memoria de lo ya avisado. */
    class Memory {
        val said = HashMap<String, Long>()
        val lastByCat = HashMap<AlertCategory, Long>()
        var fuelRange: Int? = null
        fun mark(a: RouteAlert, now: Long) { said[a.id] = now; lastByCat[a.category] = now }
        fun recent(id: String, now: Long) = said[id]?.let { now - it < REPEAT_MS } ?: false
        fun since(c: AlertCategory, now: Long) = lastByCat[c]?.let { now - it } ?: Long.MAX_VALUE
    }

    private val memory = Memory()
    private var pending: RouteAlert? = null
    private var prefetch: Job? = null

    fun hide() { _current.value = null }
    fun show(a: RouteAlert) { _current.value = a }
    fun visible(a: RouteAlert?, now: Long) = a != null && now - a.at < VISIBLE_MS

    /** Cada segundo, desde [RoutePois]. */
    fun tick(ctx: Context, scope: CoroutineScope, pos: GeoPoint, bearing: Float, live: Boolean) {
        val st = AppSettings(ctx)
        val on = AlertCategory.entries.filter { st.alertOn(it) && it != AlertCategory.RADAR }.toSet()
        val now = System.currentTimeMillis()
        if (on.isEmpty()) { pending = null; return }
        val demo = VehicleRepository.status.value.sourceName == "Demo"
        val near = RoutePois.state.value.near

        // Se adelanta la descarga del resumen de Wikipedia del próximo sitio turístico.
        if (AlertCategory.TOURISM in on && prefetch?.isActive != true) {
            near.firstOrNull { it.poi.category == AlertCategory.TOURISM && it.meters < PREFETCH_M && abs(it.relBearing) < 70 && !Wiki.isCached(it.poi) }
                ?.let { v -> prefetch = scope.launch(Dispatchers.IO) { Wiki.load(v.poi) } }
        }

        pending?.let { p ->
            if (now - p.at > 30_000) pending = null
            else { if (send(p, now)) pending = null; return }
        }

        val movingMs = TripComputer.state.value.current?.movingMillis
        val pick = choose(near, on, movingMs, now, demo, memory)
        val alert = when {
            pick != null -> {
                // Turismo: esperar a tener el resumen, salvo que ya esté muy cerca.
                if (pick.poi.category == AlertCategory.TOURISM && !Wiki.isCached(pick.poi) && pick.meters > 800) {
                    if (prefetch?.isActive != true) prefetch = scope.launch(Dispatchers.IO) { Wiki.load(pick.poi) }
                    null
                } else build(pick, movingMs, Wiki.cached(pick.poi))
            }
            AlertCategory.FUEL in on -> {
                val range = VehicleRepository.state.value.rangeKm
                if (fuelDue(range, memory, now)) fuelAlert(range!!, stationsAhead(pos, bearing, live)) else null
            }
            else -> null
        }
        if (alert != null) {
            if (alert.category == AlertCategory.FUEL) memory.fuelRange = VehicleRepository.state.value.rangeKm
            memory.mark(alert, now)
            if (!send(alert, now)) pending = alert
        }
    }

    private fun send(a: RouteAlert, now: Long): Boolean {
        val d = deliver ?: return true
        if (!d(a)) return false
        val shown = a.copy(at = now)
        _current.value = shown
        if (a.category == AlertCategory.TOURISM) lastTourism = shown
        return true
    }

    /** El punto del que toca avisar ahora, o null. Lo urgente (seguridad) primero. */
    fun choose(near: List<PoiView>, on: Set<AlertCategory>, movingMs: Long?, now: Long, demo: Boolean, mem: Memory): PoiView? {
        fun fresh(v: PoiView) = v.poi.category in on && !mem.recent(v.poi.id, now)
        near.firstOrNull { v ->
            fresh(v) && v.poi.category == AlertCategory.SAFETY && abs(v.relBearing) < 30 &&
                v.meters < (if (v.poi.kind == "level_crossing") CROSSING_M else SAFETY_M)
        }?.let { return it }
        if ((movingMs ?: 0) >= REST_AFTER_MS || demo) {
            if (mem.since(AlertCategory.REST, now) >= (if (demo) REST_GAP_MS / 4 else REST_GAP_MS)) {
                near.firstOrNull { fresh(it) && it.poi.category == AlertCategory.REST && it.meters < REST_M && abs(it.relBearing) < 30 }?.let { return it }
            }
        }
        if (mem.since(AlertCategory.TOURISM, now) >= (if (demo) TOURISM_GAP_MS / 4 else TOURISM_GAP_MS)) {
            near.firstOrNull { v ->
                fresh(v) && v.poi.category == AlertCategory.TOURISM &&
                    if (v.poi.kind == "place") v.meters < PLACE_M && abs(v.relBearing) < 90 else v.meters < TOURISM_M && abs(v.relBearing) < 50
            }?.let { return it }
        }
        return null
    }

    /** Toca avisar del combustible: por debajo de [LOW_RANGE_KM], y otra vez al bajar de 30 o cada 15 min. */
    fun fuelDue(range: Int?, mem: Memory, now: Long): Boolean {
        if (range == null || range >= LOW_RANGE_KM) { if (range != null && range >= LOW_RANGE_KM + 10) mem.fuelRange = null; return false }
        val last = mem.fuelRange ?: return true
        if (last >= 30 && range < 30) return true
        return mem.since(AlertCategory.FUEL, now) >= FUEL_GAP_MS
    }

    private fun stationsAhead(pos: GeoPoint, bearing: Float, live: Boolean): List<StationView> {
        val list = if (live) FuelRepository.state.value.stations else FuelRepository.demoStations(com.cockpitf30.ui.screens.DEMO_CENTER)
        return FuelStations.nearest(list, pos, limit = 20).map {
            StationView(it.station, it.meters, RoutePois.relative(RouteTracker.bearingBetween(pos, it.station.point), bearing))
        }
    }

    fun fuelAlert(range: Int, stations: List<StationView>): RouteAlert {
        val s = stations.firstOrNull { abs(it.relBearing) < 45 && it.meters < 15_000 } ?: stations.firstOrNull { it.meters < 10_000 }
        val head = "Te quedan unos $range kilómetros de autonomía."
        if (s == null) {
            return RouteAlert("fuel.none", AlertCategory.FUEL, "Combustible bajo", "$range km de autonomía", "$head No veo gasolineras cerca. Mejor no apurar.")
        }
        val where = side(s.relBearing)
        val price = s.station.diesel?.let { " Gasóleo a ${"%.2f".format(Locale("es", "ES"), it)} euros." } ?: ""
        val brand = s.station.brand.lowercase().replaceFirstChar { it.uppercase() }
        return RouteAlert(
            "fuel.${s.station.id}", AlertCategory.FUEL, "Gasolinera $brand",
            "${shortDistance(s.meters)} ${where.second} · $range km de autonomía",
            "$head Hay una gasolinera $brand a ${spokenDistance(s.meters)}, ${where.first}.$price",
            text = listOf(s.station.address, s.station.town).filter { it.isNotBlank() }.joinToString(", ") +
                (s.station.diesel?.let { " · Gasóleo ${FuelStations.formatPrice(it)} €" } ?: ""),
        )
    }

    /** El aviso de un punto. */
    fun build(v: PoiView, movingMs: Long?, wiki: WikiSummary?): RouteAlert {
        val p = v.poi
        val what = p.what
        val dist = spokenDistance(v.meters)
        val where = side(v.relBearing)
        val title = p.name ?: what
        val sub = "$what · ${shortDistance(v.meters)} ${where.second}"
        return when (p.category) {
            AlertCategory.SAFETY -> RouteAlert(
                p.id, p.category, what, "${shortDistance(v.meters)} ${where.second}",
                "Atención: ${what.lowercase()} a $dist.", poi = p,
            )
            AlertCategory.REST -> {
                val tired = movingMs?.takeIf { it >= REST_AFTER_MS }?.let { " Llevas ${com.cockpitf30.assistant.Copilot.duration(it)} conduciendo; buen sitio para parar." } ?: ""
                RouteAlert(p.id, p.category, title, sub, "${what} a $dist${p.name?.let { ", $it" } ?: ""}.$tired", poi = p)
            }
            else -> {
                val about = wiki?.extract?.let { Wiki.firstSentences(it) }
                val speech = if (p.kind == "place") {
                    "Pasamos por ${p.name}." + (about?.let { " $it" } ?: "")
                } else {
                    val named = p.name?.let { n -> if (n.lowercase().contains(what.lowercase())) n else "$n, ${what.lowercase()}" } ?: what
                    "${where.first.replaceFirstChar { it.uppercase() }}, a $dist: $named." + (about?.let { " $it" } ?: "")
                }
                RouteAlert(p.id, p.category, wiki?.title?.takeIf { p.name == null } ?: title, sub, speech, wiki?.extract, wiki?.image, poi = p)
            }
        }
    }

    /** Lo que hay cerca, para «¿qué hay por aquí?». */
    fun nearbyText(near: List<PoiView>): String {
        val tourism = near.filter { it.poi.category == AlertCategory.TOURISM && it.poi.kind != "place" }.take(3)
        val rest = near.firstOrNull { it.poi.category == AlertCategory.REST && it.meters < 5000 }
        if (tourism.isEmpty() && rest == null) return "No veo nada especial por aquí cerca."
        val parts = tourism.map { v -> "${v.poi.name ?: v.poi.what} a ${spokenDistance(v.meters)} ${side(v.relBearing).second}" }
        val sb = StringBuilder()
        if (parts.isNotEmpty()) sb.append("Cerca tienes ").append(join(parts)).append('.')
        rest?.let { sb.append(if (sb.isEmpty()) "" else " ").append("${it.poi.what} a ${spokenDistance(it.meters)}.") }
        return sb.toString()
    }

    private fun join(p: List<String>) = if (p.size <= 1) p.joinToString() else p.dropLast(1).joinToString(", ") + " y " + p.last()

    /** Hacia dónde queda: (para decirlo, para la ventanita). */
    fun side(rel: Float): Pair<String, String> = when {
        rel > 25 -> "a la derecha" to "a la derecha"
        rel < -25 -> "a la izquierda" to "a la izquierda"
        else -> "delante" to "delante"
    }

    fun spokenDistance(m: Double): String = when {
        m < 1000 -> "${maxOf(50, (m / 50).roundToInt() * 50)} metros"
        m < 10_000 -> {
            val k = (m / 100).roundToInt() / 10.0
            if (k == k.toInt().toDouble()) (if (k.toInt() == 1) "un kilómetro" else "${k.toInt()} kilómetros")
            else "${"%.1f".format(Locale("es", "ES"), k)} kilómetros"
        }
        else -> "${(m / 1000).roundToInt()} kilómetros"
    }

    private fun shortDistance(m: Double) = if (m < 1000) "${maxOf(50, (m / 50).roundToInt() * 50)} m" else "%.1f km".format(Locale("es", "ES"), m / 1000)

    /** Ajustes → Probar: el sitio turístico más cercano (o el Acueducto de Segovia) con su foto. */
    fun test(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val v = RoutePois.state.value.near.firstOrNull { it.poi.category == AlertCategory.TOURISM && it.poi.kind != "place" }
                ?: PoiView(Poi("test.acueducto", 40.9481, -4.1184, AlertCategory.TOURISM, "aqueduct", "Acueducto de Segovia", "es:Acueducto de Segovia"), 1500.0, 30f)
            val a = build(v, null, Wiki.load(v.poi)).copy(id = "test." + System.currentTimeMillis())
            send(a, System.currentTimeMillis())
        }
    }

    /** «¿Qué hay por aquí?»: la lista hablada, y en la ventanita el sitio más cercano. */
    fun nearby(scope: CoroutineScope): String {
        val s = RoutePois.state.value
        val pos = s.position
        // Para la pregunta vale todo lo descargado (12 km), no solo lo de menos de 5 km.
        val bearing = RouteTracker.state.value.bearing
        val near = if (pos == null) s.near else s.pois.map {
            PoiView(it, RouteTracker.distance(pos, it.point), RoutePois.relative(RouteTracker.bearingBetween(pos, it.point), bearing))
        }.sortedBy { it.meters }
        near.firstOrNull { it.poi.category == AlertCategory.TOURISM && it.poi.kind != "place" }?.let { v ->
            scope.launch(Dispatchers.IO) {
                val a = build(v, null, Wiki.load(v.poi))
                _current.value = a.copy(at = System.currentTimeMillis())
                lastTourism = _current.value
            }
        }
        if (s.pois.isEmpty()) {
            if (!s.loading) RoutePois.requestRefresh()
            return when {
                pos == null -> "No sé dónde estamos: la radio aún no tiene posición GPS."
                s.loading -> "Estoy descargando los puntos de interés de la zona. Pregúntame en un momento."
                s.error != null -> "No he podido descargar los puntos de interés (${s.error}). Lo vuelvo a intentar ahora; pregúntame en un minuto."
                s.updatedAt > 0 -> "En el mapa no aparece nada por aquí. Lo vuelvo a mirar ahora."
                else -> "Ahora mismo descargo los puntos de interés de la zona. Pregúntame en un minuto."
            }
        }
        return nearbyText(near)
    }

    /** «Háblame de eso»: más texto del último sitio turístico. */
    fun more(): String? {
        val a = lastTourism ?: return null
        val text = a.text ?: return "De ${a.title} no tengo más información."
        _current.value = a.copy(at = System.currentTimeMillis())
        return Wiki.firstSentences(text, 480)
    }

    /** Para pruebas. */
    fun reset() { memory.said.clear(); memory.lastByCat.clear(); memory.fuelRange = null; pending = null; _current.value = null; lastTourism = null }
}
