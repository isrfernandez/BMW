package com.cockpitf30.weather

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Viento en un momento: de dónde viene (grados, 0 = norte), fuerza y rachas en km/h. */
data class WindNow(val fromDeg: Int, val kmh: Int, val gustKmh: Int? = null)

/** Cómo entra el viento respecto al rumbo del coche, como en un barco. */
enum class WindPoint(val title: String) {
    PROA("Viento de proa"),
    AMURA("Viento por la amura"),
    TRAVES("Viento de través"),
    ALETA("Viento por la aleta"),
    POPA("Viento de popa"),
}

/** Viento relativo: por dónde entra y si viene de la derecha (estribor) o de la izquierda (babor). */
data class RelativeWind(val point: WindPoint, val starboard: Boolean, val relDeg: Float) {
    val side: String get() = if (starboard) "estribor" else "babor"
}

/**
 * Viento en palabras, para el parte, la previsión y los avisos en ruta: rumbos con los nombres de
 * siempre (levante, poniente…), fuerza con la escala Beaufort tal y como la dicen los partes
 * marítimos (flojo, bonancible, fresquito…), el «rolando a…» cuando cambia durante el día y, en
 * ruta, si entra de proa, por la amura, de través, por la aleta o de popa.
 */
object Wind {
    /** Por debajo de esto no se habla de dirección: está en calma. */
    const val CALM_KMH = 6
    /** En ruta solo se avisa a partir de aquí (o con rachas fuertes). */
    const val ROUTE_MIN_KMH = 15
    const val ROUTE_MIN_GUST_KMH = 35
    /** A partir de aquí, de costado, se recomienda cuidado. */
    const val STRONG_KMH = 30
    const val STRONG_GUST_KMH = 45
    /** Para decir que rola: el viento tiene que girar al menos esto, y durante dos horas seguidas. */
    const val VEER_DEG = 60

    private val NAMES = listOf("norte", "nordeste", "levante", "sureste", "sur", "suroeste", "poniente", "noroeste")
    private val SHORT = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")

    fun sector(deg: Int): Int = ((norm360(deg.toDouble()) + 22.5) / 45.0).toInt() % 8

    /** «poniente», «suroeste»… */
    fun name(deg: Int): String = NAMES[sector(deg)]

    /** «SO», «O»… para la pantalla. */
    fun short(deg: Int): String = SHORT[sector(deg)]

    /** «de poniente», «del suroeste»: levante y poniente van sin artículo. */
    fun from(deg: Int): String = sector(deg).let { if (it == 2 || it == 6) "de ${NAMES[it]}" else "del ${NAMES[it]}" }

    /** Nombre de la fuerza en la escala Beaufort (en minúsculas, como en los partes). */
    fun force(kmh: Int): String = when {
        kmh < 2 -> "calma"
        kmh < 6 -> "ventolina"
        kmh < 12 -> "flojito"
        kmh < 20 -> "flojo"
        kmh < 29 -> "bonancible"
        kmh < 39 -> "fresquito"
        kmh < 50 -> "fresco"
        kmh < 62 -> "frescachón"
        kmh < 75 -> "temporal"
        kmh < 89 -> "temporal fuerte"
        kmh < 103 -> "temporal duro"
        else -> "temporal muy duro"
    }

    /** Número Beaufort (0–12). */
    fun beaufort(kmh: Int): Int = listOf(2, 6, 12, 20, 29, 39, 50, 62, 75, 89, 103, 118).indexOfFirst { kmh < it }.let { if (it < 0) 12 else it }

    /** «, con rachas de 45» solo si las rachas destacan. */
    private fun gusts(w: WindNow): String =
        w.gustKmh?.takeIf { it >= w.kmh + 15 && it >= 25 }?.let { ", con rachas de $it" } ?: ""

    /** «Viento de poniente, flojo, a 15 kilómetros por hora, con rachas de 35». */
    fun spoken(w: WindNow): String =
        if (w.kmh < CALM_KMH) "Viento en calma"
        else "Viento ${from(w.fromDeg)}, ${force(w.kmh)}, a ${w.kmh} kilómetros por hora${gusts(w)}"

    /** Cuándo es una hora del día, para «rolando a poniente por la tarde». */
    fun period(hour: Int): String = when (hour) {
        in 6..11 -> "por la mañana"
        in 12..14 -> "a mediodía"
        in 15..20 -> "por la tarde"
        else -> "por la noche"
    }

    /** Hacia dónde rola el viento y cuándo. */
    data class Veer(val toDeg: Int, val at: LocalDateTime) {
        val text: String get() = "rolando a ${name(toDeg)} ${period(at.hour)}"
    }

    /**
     * Si el viento cambia de dirección lo que queda de día (hasta medianoche, como mucho 12 horas),
     * cuándo y hacia dónde: tiene que girar [VEER_DEG] o más respecto al de ahora, durante al menos
     * dos horas seguidas y con algo de viento (si no, un cambio en calma no se nota).
     */
    fun veer(hours: List<HourForecast>, now: LocalDateTime, currentDeg: Int): Veer? {
        val end = minOf(now.toLocalDate().plusDays(1).atStartOfDay(), now.plusHours(12))
        val later = hours.filter { it.time.isAfter(now) && it.time.isBefore(end) && it.windKmh >= CALM_KMH + 2 }.sortedBy { it.time }
        for (i in 0 until later.size - 1) {
            val a = later[i]
            val b = later[i + 1]
            if (b.time != a.time.plusHours(1)) continue
            if (diff(a.windDir, currentDeg) >= VEER_DEG && diff(b.windDir, currentDeg) >= VEER_DEG && diff(a.windDir, b.windDir) <= 45) {
                return Veer(mean(listOf(a, b)), a.time)
            }
        }
        return null
    }

    /** El viento de ahora: el de «current» si lo hay, si no el de la hora en curso. */
    fun now(f: Forecast, at: LocalDateTime = LocalDateTime.now()): WindNow? {
        if (f.windKmh != null && f.windDir != null) return WindNow(f.windDir, f.windKmh, f.gustKmh)
        val h = f.hours.minByOrNull { abs(java.time.Duration.between(it.time, at).toMinutes()) }
            ?.takeIf { abs(java.time.Duration.between(it.time, at).toMinutes()) <= 90 } ?: return null
        return WindNow(h.windDir, h.windKmh, h.gustKmh)
    }

    /**
     * Parte de ahora: «Viento de levante, flojo, a 14 kilómetros por hora, rolando a poniente por la
     * tarde». Si ahora está en calma pero luego entra, lo dice así.
     */
    fun today(f: Forecast, at: LocalDateTime = LocalDateTime.now()): String? {
        val w = now(f, at) ?: return null
        val v = veer(f.hours, at, w.fromDeg)
        if (w.kmh < CALM_KMH) {
            val later = f.hours.filter { it.time.isAfter(at) && it.time.toLocalDate() == at.toLocalDate() && it.windKmh >= 12 }.minByOrNull { it.time }
            return if (later != null) "Ahora en calma; ${period(later.hour())} entrará viento ${from(later.windDir)}, ${force(later.windKmh)}" else "Viento en calma"
        }
        return spoken(w) + (v?.let { ", ${it.text}" } ?: "")
    }

    private fun HourForecast.hour() = time.hour

    /**
     * Viento de un día de la previsión: dirección media de las horas de día (8 a 21), fuerza máxima
     * y, si de la mañana a la tarde gira, «rolando a…».
     */
    fun day(f: Forecast, date: LocalDate): String? {
        val hours = f.hoursOf(date).filter { it.time.hour in 8..21 }
        if (hours.isEmpty()) return null
        val max = f.days.firstOrNull { it.date == date }?.windMaxKmh ?: hours.maxOf { it.windKmh }
        if (max < CALM_KMH + 4) return "Viento en calma"
        val morning = hours.filter { it.time.hour < 14 }
        val afternoon = hours.filter { it.time.hour >= 14 }
        val first = if (morning.isNotEmpty()) mean(morning) else mean(hours)
        val turn = afternoon.takeIf { morning.isNotEmpty() && it.isNotEmpty() }?.let(::mean)?.takeIf { diff(it, first) >= VEER_DEG }
        return "Viento ${from(first)}, ${force(max)}, hasta $max kilómetros por hora" +
            (turn?.let { ", rolando a ${name(it)} por la tarde" } ?: "")
    }

    /** Viento de un tramo del día en la ficha de la previsión: «Mañana», de dónde, media y máxima. */
    data class DayPart(val label: String, val fromDeg: Int, val kmh: Int, val maxKmh: Int) {
        val calm: Boolean get() = maxKmh < CALM_KMH
    }

    private val PARTS = listOf("Madrugada" to 0..5, "Mañana" to 6..11, "Tarde" to 12..17, "Noche" to 18..23)

    /**
     * Viento de un día en cuatro tramos (madrugada, mañana, tarde y noche). Si en todo el día apenas
     * cambia (dirección parecida y fuerza parecida) o está en calma, un solo tramo para todo el día.
     */
    fun dayParts(hours: List<HourForecast>): List<DayPart> {
        if (hours.isEmpty()) return emptyList()
        val parts = PARTS.mapNotNull { (label, range) ->
            val hs = hours.filter { it.time.hour in range }
            if (hs.isEmpty()) null
            else DayPart(label, mean(hs), hs.sumOf { it.windKmh } / hs.size, hs.maxOf { it.windKmh })
        }
        val all = DayPart("Todo el día", mean(hours), hours.sumOf { it.windKmh } / hours.size, hours.maxOf { it.windKmh })
        if (parts.size <= 1 || all.calm) return listOf(all)
        val windy = parts.filter { !it.calm }
        val sameDir = windy.all { diff(it.fromDeg, all.fromDeg) < 45 }
        val sameForce = parts.maxOf { it.kmh } - parts.minOf { it.kmh } < 8
        val sameCalm = parts.all { it.calm } || windy.size == parts.size
        return if (sameDir && sameForce && sameCalm) listOf(all) else parts
    }

    /** Dirección media de varias horas, pesando por la fuerza (media de vectores, no de grados). */
    fun mean(hours: List<HourForecast>): Int {
        var x = 0.0
        var y = 0.0
        for (h in hours) {
            val r = Math.toRadians(h.windDir.toDouble())
            val wgt = maxOf(1, h.windKmh).toDouble()
            x += sin(r) * wgt; y += cos(r) * wgt
        }
        if (sqrt(x * x + y * y) < 1e-6) return hours.firstOrNull()?.windDir ?: 0
        return norm360(Math.toDegrees(atan2(x, y))).roundToInt() % 360
    }

    /** Diferencia angular (0–180). */
    fun diff(a: Int, b: Int): Int = abs(norm180((a - b).toDouble())).roundToInt()

    /**
     * Por dónde entra el viento según el rumbo: proa (de frente, ±25°), amura (hasta 70°), través
     * (de costado, hasta 110°), aleta (hasta 155°) y popa (por detrás).
     */
    fun relative(fromDeg: Int, headingDeg: Float): RelativeWind {
        val rel = norm180(fromDeg - headingDeg.toDouble()).toFloat()
        val a = abs(rel)
        val p = when {
            a <= 25 -> WindPoint.PROA
            a <= 70 -> WindPoint.AMURA
            a <= 110 -> WindPoint.TRAVES
            a <= 155 -> WindPoint.ALETA
            else -> WindPoint.POPA
        }
        return RelativeWind(p, rel > 0, rel)
    }

    /** «poniente · 32 km/h · por babor», para la ventanita del aviso. */
    fun subtitle(w: WindNow, r: RelativeWind): String =
        listOfNotNull(
            name(w.fromDeg).replaceFirstChar { it.uppercase() },
            "${w.kmh} km/h" + (w.gustKmh?.takeIf { it >= w.kmh + 15 }?.let { " (rachas $it)" } ?: ""),
            if (r.point == WindPoint.PROA || r.point == WindPoint.POPA) null else "por ${r.side}",
        ).joinToString(" · ")

    /** Lo que dice el asistente en ruta, en términos náuticos. */
    fun spokenRelative(w: WindNow, r: RelativeWind): String {
        val strong = w.kmh >= STRONG_KMH || (w.gustKmh ?: 0) >= STRONG_GUST_KMH
        val how = "${force(w.kmh)}, ${w.kmh} kilómetros por hora${gusts(w)}"
        val windward = if (r.starboard) "Barlovento a la derecha y sotavento a la izquierda" else "Barlovento a la izquierda y sotavento a la derecha"
        return when (r.point) {
            WindPoint.PROA -> "Viento de proa, ${from(w.fromDeg)}, $how: lo llevamos de frente, en contra, y se nota en el consumo."
            WindPoint.AMURA -> "Viento por la amura de ${r.side}, $how: casi de frente y algo de costado. $windward."
            WindPoint.TRAVES -> "Viento de través por ${r.side}, $how: nos entra de costado. $windward." +
                if (strong) " Ojo a las rachas en viaductos, puentes y al adelantar camiones." else ""
            WindPoint.ALETA -> "Viento por la aleta de ${r.side}, $how: a favor y un poco de lado, navegamos a un largo."
            WindPoint.POPA -> "Viento de popa, ${from(w.fromDeg)}, $how: lo llevamos a favor, empuja y se ahorra algo de gasóleo."
        }
    }

    private fun norm360(d: Double): Double = ((d % 360.0) + 360.0) % 360.0
    private fun norm180(d: Double): Double = norm360(d + 180.0) - 180.0
}
