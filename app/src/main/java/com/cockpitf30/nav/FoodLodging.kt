package com.cockpitf30.nav

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/** Qué se busca: dónde comer, tomar algo, dormir o acampar. */
enum class StayKind(val label: String) {
    EAT("Restaurante"), DRINK("Bar"), SLEEP("Alojamiento"), CAMP("Camping");
}

/** Un restaurante, bar, hotel o camping de OpenStreetMap. */
data class Stay(
    val id: String,
    val lat: Double,
    val lon: Double,
    val kind: StayKind,
    /** Etiqueta OSM: restaurant, bar, cafe, hotel, camp_site… */
    val tag: String,
    val name: String,
    /** «cocina regional», «3 estrellas»… */
    val detail: String? = null,
    val phone: String? = null,
    /**
     * Popularidad aproximada (OpenStreetMap no tiene valoraciones): estrellas, artículo en
     * Wikidata y lo completa que está la ficha (web, horario, teléfono, cocina). Las cadenas de
     * comida rápida restan.
     */
    val score: Int = 0,
) {
    val point get() = GeoPoint(lat, lon)
    val what: String get() = FoodLodging.describe(tag)
}

data class StayView(val stay: Stay, val meters: Double, val relBearing: Float)

data class StayState(
    val stays: List<Stay> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val updatedAt: Long = 0,
    val center: GeoPoint? = null,
)

/**
 * Comer y dormir: restaurantes y bares (3 km alrededor) y hoteles, hostales y campings (15 km),
 * de OpenStreetMap por Overpass, como los puntos turísticos. Solo se descarga si los avisos de
 * «Comer y dormir» están encendidos o se pregunta por voz («dónde comer», «campings»). Se renueva
 * al alejarse 2,5 km, como mucho una vez cada 90 s.
 */
object FoodLodging {
    private const val FOOD_RADIUS_M = 3000
    private const val STAY_RADIUS_M = 15_000
    private const val REFETCH_M = 2500.0
    private const val MIN_GAP_MS = 90_000L
    private const val MAX_AGE_MS = 6 * 3_600_000L
    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )

    /** Distancias y huecos de los avisos. */
    private const val FOOD_ALERT_M = 1500.0
    private const val STAY_ALERT_M = 3000.0
    const val FOOD_GAP_MS = 45 * 60_000L
    const val STAY_GAP_MS = 30 * 60_000L
    /** Fuera de las horas de dormir, alojamiento solo tras tres horas al volante. */
    const val STAY_AFTER_MS = 3 * 3_600_000L

    private val _state = MutableStateFlow(StayState())
    val state: StateFlow<StayState> = _state.asStateFlow()
    @Volatile private var lastTry = 0L
    @Volatile private var loadingSince = 0L
    @Volatile private var failures = 0
    /**
     * Propio, no el de quien pregunta: con el de la pantalla, al salir de ella se cancelaba la
     * descarga a medias y se quedaba «cargando» para siempre (Comer y Dormir en «Buscando…»).
     */
    private val own = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Qué ha pasado en la última descarga (para la pantalla y Diagnóstico). */
    @Volatile var note: String = "sin descargar"
        private set

    /**
     * Cada segundo desde [RouteAlerts.tick] (con la categoría encendida), al abrir Turismo o al
     * preguntar. Si falla, se reintenta solo a los 20 s, 40 s, 80 s… (máx. 5 min).
     */
    @Suppress("UNUSED_PARAMETER")
    fun maybeFetch(scope: CoroutineScope?, pos: GeoPoint, force: Boolean = false) {
        val s = _state.value
        val now = System.currentTimeMillis()
        if (s.loading) {
            if (now - loadingSince < 100_000) return
            note = "la descarga anterior no acabó; se repite"
        }
        val far = s.center == null || RouteTracker.distance(s.center, pos) > REFETCH_M
        val stale = now - s.updatedAt > MAX_AGE_MS
        if (!(far || stale || force)) return
        val gap = when {
            force -> 10_000L
            failures > 0 -> minOf(20_000L shl (failures - 1).coerceAtMost(4), 300_000L)
            else -> MIN_GAP_MS
        }
        if (now - lastTry < gap) return
        lastTry = now
        loadingSince = now
        _state.value = s.copy(loading = true)
        own.launch {
            val r = runCatching { fetch(pos) }
            r.onSuccess { list ->
                failures = 0
                note = "${list.size} sitios · ${java.time.LocalTime.now().withNano(0)}"
                _state.value = StayState(list, false, null, System.currentTimeMillis(), pos)
            }.onFailure {
                failures++
                val msg = it.message ?: it.javaClass.simpleName
                note = "fallo $failures: $msg"
                _state.value = _state.value.copy(loading = false, error = msg)
            }
        }
    }

    /** «Reintentar» de la pantalla. */
    fun retry(pos: GeoPoint) { lastTry = 0; maybeFetch(null, pos, force = true) }

    fun views(pos: GeoPoint, bearing: Float, list: List<Stay> = _state.value.stays): List<StayView> =
        list.map { StayView(it, RouteTracker.distance(pos, it.point), RoutePois.relative(RouteTracker.bearingBetween(pos, it.point), bearing)) }
            .sortedBy { it.meters }

    fun query(lat: String, lon: String) = """
        [out:json][timeout:40];
        nwr["amenity"~"^(restaurant|bar|cafe|pub|fast_food|biergarten)$"]["name"](around:$FOOD_RADIUS_M,$lat,$lon)->.food;
        nwr["tourism"~"^(hotel|motel|hostel|guest_house|apartment|chalet|alpine_hut|camp_site|caravan_site)$"]["name"](around:$STAY_RADIUS_M,$lat,$lon)->.stay;
        .food out center tags 300;
        .stay out center tags 300;
    """.trimIndent()

    private fun fetch(pos: GeoPoint): List<Stay> {
        val q = query("%.5f".format(Locale.US, pos.lat), "%.5f".format(Locale.US, pos.lon))
        val errors = ArrayList<String>()
        for (endpoint in ENDPOINTS) {
            val host = URL(endpoint).host
            note = "preguntando a $host…"
            try {
                return parse(post(endpoint, "data=" + URLEncoder.encode(q, "UTF-8")))
            } catch (e: Exception) {
                errors += "$host: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IllegalStateException(errors.joinToString("; ").ifEmpty { "sin respuesta" })
    }

    private fun post(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 45_000
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

    fun parse(json: String): List<Stay> {
        val root = JSONObject(json)
        root.optString("remark").takeIf { it.contains("error", ignoreCase = true) }?.let { error("Overpass: " + it.take(80)) }
        val elements = root.optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Stay>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: continue
            val center = e.optJSONObject("center")
            val lat = if (e.has("lat")) e.getDouble("lat") else center?.optDouble("lat") ?: continue
            val lon = if (e.has("lon")) e.getDouble("lon") else center?.optDouble("lon") ?: continue
            fun tag(k: String) = tags.optString(k).ifBlank { null }
            val name = tag("name:es") ?: tag("name") ?: continue
            val amenity = tag("amenity")
            val tourism = tag("tourism")
            val (kind, t) = when {
                amenity == "restaurant" || amenity == "fast_food" -> StayKind.EAT to amenity
                amenity != null -> StayKind.DRINK to amenity
                tourism == "camp_site" || tourism == "caravan_site" -> StayKind.CAMP to tourism
                tourism != null -> StayKind.SLEEP to tourism
                else -> continue
            }
            val detail = when (kind) {
                StayKind.EAT, StayKind.DRINK -> tag("cuisine")?.let(::cuisine)
                StayKind.SLEEP -> tag("stars")?.toIntOrNull()?.takeIf { it in 1..5 }?.let { "$it ${if (it == 1) "estrella" else "estrellas"}" }
                StayKind.CAMP -> null
            }
            val type = e.optString("type").take(1).ifEmpty { "n" }
            var score = 0
            if (tag("wikidata") != null || tag("wikipedia") != null) score += 3
            if (tag("website") != null || tag("contact:website") != null) score += 1
            if (tag("opening_hours") != null) score += 1
            if (tag("phone") != null || tag("contact:phone") != null) score += 1
            if (tag("cuisine") != null) score += 1
            tag("stars")?.toIntOrNull()?.let { score += it.coerceIn(0, 5) }
            if (tag("brand") != null && t == "fast_food") score -= 2
            out += Stay("$type${e.getLong("id")}", lat, lon, kind, t, name, detail, tag("phone") ?: tag("contact:phone"), score)
        }
        return out.distinctBy { it.id }
    }

    fun describe(tag: String) = when (tag) {
        "restaurant" -> "Restaurante"
        "fast_food" -> "Comida rápida"
        "bar", "pub", "biergarten" -> "Bar"
        "cafe" -> "Cafetería"
        "hotel" -> "Hotel"
        "motel" -> "Motel"
        "hostel" -> "Albergue"
        "guest_house" -> "Hostal"
        "apartment" -> "Apartamentos"
        "chalet" -> "Casa rural"
        "alpine_hut" -> "Refugio"
        "camp_site" -> "Camping"
        "caravan_site" -> "Área de autocaravanas"
        else -> "Sitio"
    }

    private val CUISINE = mapOf(
        "regional" to "cocina regional", "spanish" to "cocina española", "tapas" to "tapas", "mediterranean" to "cocina mediterránea",
        "pizza" to "pizzería", "italian" to "cocina italiana", "burger" to "hamburguesas", "chinese" to "cocina china",
        "japanese" to "cocina japonesa", "sushi" to "sushi", "mexican" to "cocina mexicana", "kebab" to "kebab",
        "seafood" to "marisquería", "fish" to "pescados", "steak_house" to "asador", "grill" to "asador", "barbecue" to "asador",
        "asian" to "cocina asiática", "indian" to "cocina india", "vegetarian" to "vegetariano", "vegan" to "vegano",
        "coffee_shop" to "cafetería", "sandwich" to "bocadillos", "chicken" to "pollos asados", "international" to "cocina internacional",
        "local" to "cocina local", "basque" to "cocina vasca", "galician" to "cocina gallega", "asturian" to "cocina asturiana",
    )

    private fun cuisine(v: String): String? = v.split(';', ',').map { it.trim().lowercase() }.firstNotNullOfOrNull { CUISINE[it] }

    /** ¿Hora de comer o de cenar? (13:00–15:30 y 20:00–22:30) */
    fun mealTime(cal: Calendar = Calendar.getInstance()): Boolean {
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return m in (13 * 60)..(15 * 60 + 30) || m in (20 * 60)..(22 * 60 + 30)
    }

    /** ¿Hora de buscar dónde dormir? (de 19:30 a 2:00) */
    fun nightTime(cal: Calendar = Calendar.getInstance()): Boolean {
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return m >= 19 * 60 + 30 || m < 2 * 60
    }

    /**
     * El sitio del que toca avisar ahora, o null: un restaurante delante (a menos de 1,5 km) a la
     * hora de comer o cenar, o un hotel / camping (3 km) por la noche o tras tres horas al volante.
     * Mejor los que dicen qué cocina o estrellas tienen; nunca bares (para eso se pregunta).
     */
    fun choose(
        near: List<StayView>, meal: Boolean, night: Boolean, movingMs: Long?, now: Long,
        sinceFood: Long, sinceStay: Long, recent: (String) -> Boolean, demo: Boolean = false,
    ): StayView? {
        val gap = if (demo) 4 else 1
        if ((meal || demo) && sinceFood >= FOOD_GAP_MS / gap) {
            near.filter { it.stay.kind == StayKind.EAT && it.meters < FOOD_ALERT_M && abs(it.relBearing) < 40 && !recent(it.stay.id) }
                .sortedWith(compareBy({ if (it.stay.detail != null) 0 else 1 }, { it.meters }))
                .firstOrNull()?.let { return it }
        }
        if ((night || (movingMs ?: 0) >= STAY_AFTER_MS || demo) && sinceStay >= STAY_GAP_MS / gap) {
            near.filter { (it.stay.kind == StayKind.SLEEP || it.stay.kind == StayKind.CAMP) && it.meters < STAY_ALERT_M && abs(it.relBearing) < 45 && !recent(it.stay.id) }
                .firstOrNull()?.let { return it }
        }
        return null
    }

    /** El aviso de un sitio. */
    fun alert(v: StayView): RouteAlert {
        val s = v.stay
        val where = RouteAlerts.side(v.relBearing)
        val dist = RouteAlerts.spokenDistance(v.meters)
        val intro = when (s.kind) {
            StayKind.EAT, StayKind.DRINK -> "Para comer"
            StayKind.SLEEP -> "Para dormir"
            StayKind.CAMP -> "Para acampar"
        }
        val named = if (s.name.lowercase().contains(s.what.lowercase())) s.name else "${s.what} ${s.name}"
        val speech = "$intro, ${where.first} a $dist: $named" + (s.detail?.let { ", $it" } ?: "") + "."
        return RouteAlert(
            "stay." + s.id, AlertCategory.FOOD, s.name,
            "${s.what} · ${short(v.meters)} ${where.second}" + (s.detail?.let { " · $it" } ?: ""),
            speech, text = listOfNotNull(s.detail?.replaceFirstChar { it.uppercase() }, s.phone?.let { "Tel. $it" }).joinToString(" · ").ifBlank { null },
            poi = Poi("stay." + s.id, s.lat, s.lon, AlertCategory.FOOD, s.tag, s.name),
        )
    }

    /**
     * Orden de «Recomendados»: cuenta la popularidad, pero lo cercano gana: cada punto de
     * popularidad vale como 1 km (sitios) o 400 m (comer y dormir) de distancia.
     */
    fun rank(meters: Double, popularity: Int, metersPerPoint: Double) = meters - popularity * metersPerPoint

    private fun short(m: Double) = if (m < 1000) "${maxOf(50, (m / 50).toInt() * 50)} m" else "%.1f km".format(Locale("es", "ES"), m / 1000)

    /**
     * La respuesta a «dónde comer», «hoteles», «campings»…: los tres más cercanos de ese tipo
     * (delante primero si hay), y el primero en la ventanita. [first] recibe el más cercano para
     * poder ofrecer «¿te llevo?».
     */
    fun answer(kinds: Set<StayKind>, near: List<StayView>, st: StayState, havePos: Boolean): Pair<String, StayView?> {
        val what = when {
            kinds == setOf(StayKind.CAMP) -> "campings"
            kinds.all { it == StayKind.SLEEP || it == StayKind.CAMP } -> "alojamientos"
            kinds == setOf(StayKind.DRINK) -> "bares"
            else -> "sitios para comer"
        }
        if (st.stays.isEmpty() || near.isEmpty()) {
            return when {
                !havePos -> "No sé dónde estamos: la radio aún no tiene posición GPS." to null
                st.loading || st.updatedAt == 0L -> "Estoy buscando $what por la zona. Pregúntame en un momento." to null
                st.error != null -> "No he podido buscar $what (${st.error}). Lo vuelvo a intentar; pregúntame en un minuto." to null
                else -> "No veo $what por aquí cerca." to null
            }
        }
        val list = near.filter { it.stay.kind in kinds }
        if (list.isEmpty()) return "No veo $what por aquí cerca." to null
        val ahead = list.filter { abs(it.relBearing) < 60 }
        val pick = (if (ahead.size >= 2) ahead else list).take(3)
        val parts = pick.map { v ->
            val s = v.stay
            val named = if (s.name.lowercase().contains(s.what.lowercase())) s.name else "${s.what.lowercase()} ${s.name}"
            "$named, a ${RouteAlerts.spokenDistance(v.meters)} ${RouteAlerts.side(v.relBearing).second}" + (s.detail?.let { ", $it" } ?: "")
        }
        val list3 = if (parts.size <= 1) parts.joinToString() else parts.dropLast(1).joinToString("; ") + "; y " + parts.last()
        return "Cerca tienes $list3. ¿Te llevo al primero?" to pick.first()
    }
}
