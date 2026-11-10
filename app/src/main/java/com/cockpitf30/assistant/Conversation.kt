package com.cockpitf30.assistant

import com.cockpitf30.ui.screens.Screen

/** Algo que el asistente ha preguntado y espera que se le conteste. */
sealed interface Pending {
    /** «¿Te llevo?», «¿Te cuento un chiste?»: con un sí se hace [yes]. */
    data class Confirm(val yes: Command) : Pending
    /** Hay varios contactos con ese nombre: la siguiente frase es el nombre completo o «el segundo». */
    data class ChooseContact(val names: List<String>) : Pending
}

/** Una vuelta de la conversación: lo oído, cómo se entendió y lo respondido. */
data class Turn(val at: Long, val heard: String?, val command: Command?, val reply: String)

/**
 * Memoria de la conversación: las últimas frases, lo último de lo que se habló (para entender
 * «¿y mañana?», «otra», «llévame allí») y lo que el asistente ha preguntado. Texto puro, sin Android.
 *
 * El contexto para las preguntas que siguen dura [CONTEXT_MS]; el historial para «¿de qué
 * hablábamos?», [HISTORY_MS].
 */
class Conversation {
    private val turns = ArrayDeque<Turn>()
    var pending: Pending? = null
        private set
    private var pendingAt = 0L

    /** Gasolineras de las que se ha hablado (las más cercanas) y cuál fue la última. */
    var stationCount = 0
        private set
    var stationIndex = 0
        private set

    fun history(): List<Turn> = turns.toList()

    /** Lo último que se ha dicho en voz alta (para «repite»). */
    val lastReply: String? get() = turns.lastOrNull()?.reply

    fun ask(p: Pending?, now: Long) {
        pending = p
        pendingAt = now
    }

    fun setStations(count: Int, index: Int = 0) {
        stationCount = count
        stationIndex = index
    }

    fun forget() {
        turns.clear()
        pending = null
        stationCount = 0
        stationIndex = 0
    }

    /** La última orden de la conversación (sin contar repeticiones), si fue hace poco. */
    fun lastCommand(now: Long): Command? =
        turns.lastOrNull { it.command != null && it.command !is Command.Repeat }
            ?.takeIf { now - it.at < CONTEXT_MS }?.command

    /** ¿Se ha preguntado lo mismo hace poco? (para «te lo acabo de decir»). */
    fun askedRecently(c: Command, now: Long): Boolean =
        turns.any { it.command == c && now - it.at < AGAIN_MS }

    fun record(heard: String?, command: Command?, reply: String, now: Long) {
        turns.addLast(Turn(now, heard, command, reply))
        while (turns.size > MAX_TURNS) turns.removeFirst()
    }

    /**
     * Interpreta [text] teniendo en cuenta la conversación: respuestas a preguntas del asistente,
     * «otra», «¿y mañana?», «¿cuánto cuesta?», «llévame»…
     */
    fun resolve(text: String, now: Long): Command {
        val t = VoiceCommands.normalize(text)
        val parsed = VoiceCommands.parse(text)
        val p = pending?.takeIf { now - pendingAt < CONTEXT_MS }
        pending = null
        val last = lastCommand(now)

        when (p) {
            is Pending.Confirm -> when (parsed) {
                Command.Yes, Command.GoThere -> return p.yes
                Command.Cancel -> return Command.No
                else -> Unit
            }
            is Pending.ChooseContact -> {
                ordinal(t)?.let { i -> p.names.getOrNull(i)?.let { return Command.Call(VoiceCommands.normalize(it)) } }
                if (parsed is Command.Unknown) return Command.Call(t)
            }
            null -> Unit
        }

        val stations = stationCount > 0 && last != null && last.aboutStations()
        when (parsed) {
            Command.Another -> return when {
                last == Command.Joke -> Command.Joke
                stations -> Command.Station(stationIndex + 1, StationAction.DESCRIBE)
                last is Command.WeatherDay -> Command.WeatherDay((last.offset + 1).coerceAtMost(6))
                last is Command.Info && last.topic == InfoTopic.WEATHER -> Command.WeatherDay(1)
                last is Command.Chat && last.key == "bored" -> Command.Joke
                else -> Command.Another
            }
            Command.Price -> return if (stations) Command.Station(stationIndex, StationAction.PRICE) else Command.Info(InfoTopic.FUEL_NEAR)
            Command.GoThere -> if (stations) return Command.Station(stationIndex, StationAction.GO)
            else -> Unit
        }

        // «¿Y …?» tras una pregunta: lo mismo sobre otra cosa.
        val weatherBefore = last is Command.WeatherDay || (last is Command.Info && last.topic == InfoTopic.WEATHER)
        if (weatherBefore && t.matches(Regex("^(?:y )?(?:para )?(?:hoy|ahora)$"))) return Command.Info(InfoTopic.WEATHER)
        if (last != null && t.startsWith("y ") && parsed is Command.Open) {
            asInfo(parsed.screen)?.let { return it }
        }
        if (stations && t.matches(Regex("^(?:y )?(?:la |el )?(?:primera|segunda|tercera|cuarta|quinta|primero|segundo|tercero)$"))) {
            ordinal(t)?.let { return Command.Station(it, StationAction.DESCRIBE) }
        }
        return parsed
    }

    /** Resumen de lo hablado, para «¿de qué hablábamos?»; null si no hay nada. */
    fun recap(now: Long): String? {
        val topics = turns.filter { now - it.at < HISTORY_MS }
            .mapNotNull { it.command?.let(::describe) }
            .distinct().takeLast(4)
        if (topics.isEmpty()) return null
        val list = if (topics.size == 1) topics[0] else topics.dropLast(1).joinToString(", ") + " y " + topics.last()
        return "Hemos hablado de $list"
    }

    private fun Command.aboutStations() = this is Command.Station || (this is Command.Info && topic == InfoTopic.FUEL_NEAR)

    companion object {
        const val CONTEXT_MS = 2 * 60_000L
        const val AGAIN_MS = 90_000L
        const val HISTORY_MS = 20 * 60_000L
        const val MAX_TURNS = 30

        private val ORDINALS = listOf(
            listOf("primero", "primera", "uno", "1"), listOf("segundo", "segunda", "dos", "2"),
            listOf("tercero", "tercera", "tres", "3"), listOf("cuarto", "cuarta", "cuatro", "4"), listOf("quinto", "quinta", "cinco", "5"),
        )

        /** «el segundo» → 1. */
        fun ordinal(t: String): Int? {
            val words = t.split(' ')
            return ORDINALS.indexOfFirst { forms -> words.any { it in forms } }.takeIf { it >= 0 }
        }

        private fun asInfo(screen: Screen): Command? = when (screen) {
            Screen.FUEL -> Command.Info(InfoTopic.FUEL_NEAR)
            Screen.TRIP -> Command.Info(InfoTopic.TRIP)
            Screen.WEATHER -> Command.Info(InfoTopic.WEATHER)
            else -> null
        }

        /** Cómo se nombra una orden en el resumen. */
        fun describe(c: Command): String? = when (c) {
            is Command.Info -> when (c.topic) {
                InfoTopic.RANGE -> "la autonomía"
                InfoTopic.FUEL_NEAR -> "las gasolineras cercanas"
                InfoTopic.TYRES -> "las ruedas"
                InfoTopic.WEATHER -> "el tiempo"
                InfoTopic.TIME -> "la hora"
                InfoTopic.DOORS -> "las puertas"
                InfoTopic.TRIP -> "el viaje"
                InfoTopic.SPEED -> "la velocidad"
            }
            is Command.Station -> "las gasolineras cercanas"
            is Command.WeatherDay -> if (c.offset == 1) "el tiempo de mañana" else "el tiempo de los próximos días"
            is Command.Navigate -> "ir a ${c.destination}"
            is Command.Call -> "llamar a ${c.who}"
            is Command.Joke -> "chistes"
            is Command.Mode -> "el modo de conducción"
            is Command.Humor -> "mi humor"
            is Command.SetName, Command.AskName -> "tu nombre"
            is Command.Chat -> when (c.key) {
                "tired" -> "tu cansancio"
                "howareyou", "fine" -> "qué tal estamos"
                "whoareyou", "age" -> "quién soy"
                else -> null
            }
            is Command.Open -> "la pantalla de ${c.screen.name.lowercase().let(::screenName)}"
            is Command.Copilot -> "el modo copiloto"
            else -> null
        }

        private fun screenName(s: String) = when (s) {
            "fuel" -> "gasolineras"; "vehicle" -> "vehículo"; "trip" -> "viaje"; "weather" -> "tiempo"
            "phone" -> "teléfono"; "apps" -> "aplicaciones"; "diagnostics" -> "ajustes"; "home" -> "inicio"
            "route" -> "ruta"; "performance" -> "rendimiento"; else -> s
        }
    }
}
