package com.cockpitf30.assistant

import com.cockpitf30.weather.WeatherKind
import kotlin.math.roundToInt
import kotlin.random.Random

/** Lo que sabe el copiloto del viaje en este momento (lo rellena [VoiceAssistant]). */
data class CopilotSnapshot(
    val now: Long,
    val hour: Int,
    val minute: Int = 0,
    val demo: Boolean = false,
    val speedKmh: Int? = null,
    /** Tiempo en movimiento del viaje en curso. */
    val movingMs: Long? = null,
    val tripKm: Double? = null,
    val tripAvgKmh: Int? = null,
    val rangeKm: Int? = null,
    val avgConsumption: Float? = null,
    val destName: String? = null,
    /** Distancia en línea recta al destino elegido en Ruta. */
    val destKm: Double? = null,
    val weatherKind: WeatherKind? = null,
    val weatherText: String? = null,
    val tempC: Int? = null,
    /** Primer punto de la ruta con lluvia probable. */
    val rainAheadKm: Int? = null,
    val camerasNear: Int = 0,
)

/** Una frase del copiloto; con [yes], es una pregunta y un «sí» hace esa orden. */
data class CopilotLine(val id: String, val text: String, val yes: Command? = null)

/**
 * Modo copiloto: cada cierto tiempo dice algo del viaje (o pregunta algo) para dar conversación
 * al conductor y evitar la fatiga. Primero lo urgente (descanso, combustible, mal tiempo), luego
 * datos del viaje y, si no hay nada que contar, una pregunta o un comentario.
 */
object Copilot {
    const val DEFAULT_MINUTES = 10
    /** En modo demo habla cada 2 minutos, para probarlo. */
    const val DEMO_INTERVAL_MS = 2 * 60_000L

    fun intervalMs(minutes: Int, demo: Boolean) = if (demo) DEMO_INTERVAL_MS else minutes.coerceIn(2, 60) * 60_000L

    private class Topic(val id: String, val urgent: Boolean, val gapMin: Int, val make: (CopilotSnapshot) -> CopilotLine?)

    private val FUEL = Command.Info(InfoTopic.FUEL_NEAR)

    private val TOPICS = listOf(
        Topic("rest", true, 25) { s ->
            val ms = s.movingMs ?: return@Topic null
            if (ms < 2 * 3_600_000L) return@Topic null
            CopilotLine("rest", "Llevas ${duration(ms)} conduciendo. Es buen momento para parar a descansar. ¿Te busco una gasolinera?", FUEL)
        },
        Topic("late", true, 30) { s ->
            if (s.hour !in 0..5 && s.hour < 23) return@Topic null
            if ((s.movingMs ?: 0) < 20 * 60_000L) return@Topic null
            CopilotLine("late", "Es tarde y llevas un rato al volante. ¿Cómo vas de sueño? Dime bien o cansado.")
        },
        Topic("range.dest", true, 30) { s ->
            val range = s.rangeKm ?: return@Topic null
            val dest = s.destKm ?: return@Topic null
            if (range >= dest * 1.15 || dest < 5) return@Topic null
            CopilotLine("range.dest", "Ojo: hasta ${s.destName ?: "el destino"} quedan unos ${km(dest)} kilómetros y la autonomía es de $range. Habrá que repostar por el camino. ¿Te busco gasolinera?", FUEL)
        },
        Topic("fuel", true, 20) { s ->
            val range = s.rangeKm ?: return@Topic null
            if (range >= 80) return@Topic null
            CopilotLine("fuel", "Quedan unos $range kilómetros de autonomía. ¿Busco la gasolinera más cercana?", FUEL)
        },
        Topic("weather.bad", true, 30) { s ->
            val kind = s.weatherKind ?: return@Topic null
            if (kind !in setOf(WeatherKind.RAIN, WeatherKind.DRIZZLE, WeatherKind.STORM, WeatherKind.SNOW, WeatherKind.FOG)) return@Topic null
            val what = s.weatherText?.lowercase() ?: "mal tiempo"
            CopilotLine("weather.bad", "Hace mal tiempo por aquí: $what. Modera la velocidad y deja más distancia.")
        },
        Topic("rain.ahead", true, 30) { s ->
            val k = s.rainAheadKm ?: return@Topic null
            if (s.weatherKind in setOf(WeatherKind.RAIN, WeatherKind.DRIZZLE, WeatherKind.STORM)) return@Topic null
            CopilotLine("rain.ahead", if (k <= 5) "Parece que va a llover muy cerca. Ve con cuidado." else "Parece que llueve en unos $k kilómetros. Ve con cuidado.")
        },
        Topic("cold", true, 45) { s ->
            val t = s.tempC ?: return@Topic null
            if (t > 3) return@Topic null
            CopilotLine("cold", "Fuera hace $t grados. Cuidado con las placas de hielo, sobre todo en puentes y sombras.")
        },
        Topic("dest", false, 12) { s ->
            val d = s.destKm ?: return@Topic null
            if (d < 1) return@Topic null
            val eta = eta(s, d)?.let { ". Llegaríamos sobre las $it" } ?: ""
            CopilotLine("dest", "Quedan unos ${km(d)} kilómetros para ${s.destName ?: "el destino"}$eta.")
        },
        Topic("trip", false, 20) { s ->
            val k = s.tripKm ?: return@Topic null
            val ms = s.movingMs ?: return@Topic null
            if (k < 2) return@Topic null
            val avg = s.tripAvgKmh?.let { ". Media de $it kilómetros por hora" } ?: ""
            CopilotLine("trip", "Llevamos ${km(k)} kilómetros en ${duration(ms)}$avg.")
        },
        Topic("cameras", false, 20) { s ->
            if (s.camerasNear <= 0) return@Topic null
            val n = if (s.camerasNear == 1) "un radar" else "${s.camerasNear} radares"
            CopilotLine("cameras", "Hay $n en los próximos kilómetros. Tranquilo, te aviso al acercarnos.")
        },
        Topic("consumption", false, 30) { s ->
            val c = s.avgConsumption ?: return@Topic null
            val v = "%.1f".format(java.util.Locale("es", "ES"), c).replace(",0", "")
            CopilotLine("consumption", "Vamos con un consumo medio de $v litros a los cien.")
        },
        Topic("weather", false, 40) { s ->
            val text = s.weatherText ?: return@Topic null
            val t = s.tempC?.let { ", $it grados" } ?: ""
            CopilotLine("weather", "Por aquí: ${text.lowercase()}$t.")
        },
        Topic("time", false, 30) { s ->
            val eta = s.destKm?.let { eta(s, it) }?.let { ". Llegaríamos a ${s.destName ?: "destino"} sobre las $it" } ?: ""
            CopilotLine("time", "Son las ${s.hour}:${"%02d".format(s.minute)}$eta.")
        },
    )

    /** Preguntas y comentarios cuando no hay nada del viaje que contar. */
    val IDLE: List<CopilotLine> = listOf(
        CopilotLine("idle.ok", "¿Qué tal vas? ¿Todo bien por ahí?"),
        CopilotLine("idle.joke", "¿Te cuento un chiste? Di sí o no.", Command.Joke),
        CopilotLine("idle.sleep", "¿Cómo vas de sueño? Dime bien o cansado."),
        CopilotLine("idle.quiet", "Llevamos un rato callados. Yo no me aburro, pero tú quizá sí. ¿Todo bien?"),
        CopilotLine("idle.where", "¿A dónde vamos hoy? Si me dices llévame a, y el sitio, te pongo la ruta."),
        CopilotLine("idle.fuel", "Por si acaso: puedo buscarte la gasolinera más cercana. ¿La busco?", Command.Info(InfoTopic.FUEL_NEAR)),
        CopilotLine("idle.day", "Bonito día para conducir. O eso me dicen los sensores que no tengo."),
        CopilotLine("idle.break", "Una pausa cada dos horas reduce mucho el cansancio. Yo no me canso, pero tú sí."),
        CopilotLine("idle.distance", "Mantén la distancia de seguridad. Lo digo por el de delante, que frena sin avisar."),
        CopilotLine("idle.weather", "¿Quieres que te diga el tiempo? Di sí.", Command.Info(InfoTopic.WEATHER)),
        CopilotLine("idle.radar", "Aquí sigo. Vigilando radares y aguantando tu música."),
        CopilotLine("idle.score", "¿Me pones nota como copiloto? Del uno al diez. Bueno, mejor no contestes."),
        CopilotLine("idle.tomorrow", "¿Quieres saber qué tiempo hará mañana? Di sí.", Command.WeatherDay(1)),
        CopilotLine("idle.water", "Recuerda beber agua en los viajes largos. Yo no puedo, pero tú sí."),
        CopilotLine("idle.posture", "Relaja los hombros y suelta un poco el volante. Se conduce mejor sin tensión."),
        CopilotLine("idle.trip", "¿Quieres que te diga cuánto llevamos de viaje? Di sí.", Command.Info(InfoTopic.TRIP)),
        CopilotLine("idle.name", "Por cierto, todavía no sé cómo te llamas. Dime: me llamo, y tu nombre."),
    )

    /** Cuándo se dijo cada tema por última vez. */
    class Memory {
        val said = HashMap<String, Long>()
        fun ok(id: String, gapMs: Long, now: Long) = said[id]?.let { now - it >= gapMs } ?: true
        fun mark(id: String, now: Long) { said[id] = now }
    }

    /** Lo próximo que debería decir el copiloto, o null si no hay nada. */
    fun next(s: CopilotSnapshot, mem: Memory, knowsName: Boolean = false, random: Random = Random.Default): CopilotLine? {
        // En demo los temas se repiten antes (cada 2 min), para poder verlo todo.
        fun gap(min: Int) = if (s.demo) min * 60_000L / 4 else min * 60_000L
        val ready = TOPICS.filter { mem.ok(it.id, gap(it.gapMin), s.now) }.mapNotNull { t -> t.make(s)?.let { t to it } }
        ready.firstOrNull { it.first.urgent }?.let { (_, line) -> mem.mark(line.id, s.now); return line }

        val info = ready.map { it.second }
        val idle = IDLE.filter { mem.ok(it.id, gap(45), s.now) && !(knowsName && it.id == "idle.name") }
        val pickInfo = info.isNotEmpty() && (idle.isEmpty() || random.nextDouble() < 0.6)
        val line = when {
            pickInfo -> info.minBy { mem.said[it.id] ?: 0L } // el que más tiempo lleva sin decirse
            idle.isNotEmpty() -> idle[random.nextInt(idle.size)]
            else -> IDLE.minBy { mem.said[it.id] ?: 0L }
        }
        mem.mark(line.id, s.now)
        return line
    }

    private fun km(d: Double): String = when {
        d >= 20 -> ((d / 5).roundToInt() * 5).toString()
        d >= 2 -> d.roundToInt().toString()
        else -> "%.1f".format(java.util.Locale("es", "ES"), d)
    }

    fun duration(ms: Long): String {
        val min = (ms / 60_000).toInt()
        val h = min / 60
        val m = min % 60
        return when {
            h == 0 -> "$m minutos"
            m == 0 -> if (h == 1) "una hora" else "$h horas"
            else -> (if (h == 1) "una hora" else "$h horas") + " y $m minutos"
        }
    }

    /** Hora de llegada estimada (línea recta, a la media del viaje o a 80 km/h). */
    private fun eta(s: CopilotSnapshot, destKm: Double): String? {
        val speed = (s.tripAvgKmh ?: s.speedKmh)?.takeIf { it >= 20 } ?: 80
        // La carretera es más larga que la línea recta: +25 %.
        val minutes = (destKm * 1.25 / speed * 60).roundToInt()
        val total = s.hour * 60 + s.minute + minutes
        return "${(total / 60) % 24}:${"%02d".format(total % 60)}"
    }
}
