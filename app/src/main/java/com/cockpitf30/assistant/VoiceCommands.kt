package com.cockpitf30.assistant

import com.cockpitf30.ui.screens.Screen
import java.text.Normalizer

/** Datos que el asistente sabe contar en voz alta. */
enum class InfoTopic { RANGE, FUEL_NEAR, TYRES, WEATHER, TIME, DOORS, TRIP, SPEED }

/** Lo que el usuario ha pedido, ya interpretado. */
sealed interface Command {
    data class Open(val screen: Screen) : Command
    data class Info(val topic: InfoTopic) : Command
    data class Navigate(val destination: String) : Command
    data object Music : Command
    data object Maps : Command
    /** «Pregunta a Google …»: se le pasa la pregunta (o nada para abrir el asistente de Google). */
    data class AskGoogle(val query: String?) : Command
    data object Help : Command
    data object Cancel : Command
    data class Unknown(val text: String) : Command
}

/**
 * Palabra de activación y órdenes en español. Todo es texto puro (sin Android) para poder probarlo:
 * el reconocedor sin conexión (Vosk) devuelve minúsculas sin signos, y Google, frases con tildes y
 * mayúsculas; las dos cosas se normalizan igual.
 */
object VoiceCommands {

    /** Minúsculas, sin tildes ni signos, palabras separadas por un espacio. */
    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9ñ ]+"), " ")
            .trim().replace(Regex("\\s+"), " ")

    /**
     * Formas en que el reconocedor escribe «BMW» tras «hola», con las palabras pegadas. El modelo
     * pequeño de Vosk no conoce la sigla y la deletrea («be eme uve doble»), a menudo mal partida
     * («beme uve», «me uve doble»); Google suele escribir «BMW».
     */
    private val BMW_FORMS: Set<String> = buildSet {
        addAll(listOf("bmw", "bm", "bmv", "beemeuvedoble", "beemeuve", "beeme"))
        // Variantes de cómo se parte o se oye cada letra: «be/ve/pe/de» + «eme/me» + «uve doble/doble uve/doble ve».
        for (b in listOf("be", "ve", "pe", "de", "")) for (m in listOf("eme", "me", "em")) {
            for (w in listOf("uvedoble", "uve", "dobleuve", "dobleve", "doble", "")) {
                val f = b + m + w
                if (f.length >= 4) add(f)
            }
        }
        addAll(listOf("uvedoble", "vedoble"))
    }
    private val HELLO = setOf("hola", "ola", "hello", "oye")

    /**
     * Si [text] contiene «hola BMW», devuelve lo que viene después (quizá vacío); si no, null.
     * Se acepta un «hola» seguido, en las tres palabras siguientes, de alguna forma de la sigla.
     */
    fun afterWakeWord(text: String): String? {
        val words = normalize(text).split(' ').filter { it.isNotEmpty() }
        for (i in words.indices) {
            if (words[i] !in HELLO) continue
            // La forma más larga que encaje: «be eme uve doble» antes que «be eme».
            val n = (4 downTo 1).firstOrNull { n ->
                i + n <= words.lastIndex && words.subList(i + 1, i + 1 + n).joinToString("") in BMW_FORMS
            } ?: continue
            return words.drop(i + 1 + n).joinToString(" ")
        }
        return null
    }

    private val QUESTION = setOf("cuanto", "cuanta", "cuantos", "cuantas", "que", "como", "cual", "hay", "dime", "donde", "va", "voy", "tengo", "esta", "estan", "hace")

    fun parse(text: String): Command {
        val t = normalize(text)
        if (t.isEmpty()) return Command.Cancel
        val words = t.split(' ').toSet()
        fun has(vararg keys: String) = keys.any { k -> if (' ' in k) t.contains(k) else words.any { it.startsWith(k) } }
        val question = words.any { it in QUESTION }

        if (t in setOf("nada", "no", "gracias", "cancelar", "cancela", "olvidalo", "salir", "adios") || has("cancela", "olvidalo")) return Command.Cancel
        if (has("ayuda", "que puedes", "que sabes")) return Command.Help

        if (has("google") && !has("maps", "mapa")) {
            val q = t.substringAfter("google").trim()
            return Command.AskGoogle(q.ifEmpty { null })
        }

        // «Llévame a …», «navega a …», «cómo llego a …».
        Regex("\\b(?:llevame|llevanos|navega|navegar|ruta|como llego|ir|vamos|guiame)(?: hasta| hacia| a| al| a la)+ (.+)").find(t)?.let { m ->
            val dest = m.groupValues[1].replace(Regex("^(la |el |los |las )"), "").trim()
            if (dest.startsWith("gasolinera") || dest == "repostar") return Command.Info(InfoTopic.FUEL_NEAR)
            if (dest in setOf("inicio", "pantalla principal")) return Command.Open(Screen.HOME)
            if (dest.isNotEmpty()) return Command.Navigate(dest)
        }

        // Preguntas: se contestan en voz alta (y se abre la pantalla que corresponda).
        if (question || has("autonomia", "cuanto queda")) {
            when {
                has("gasolinera") -> return Command.Info(InfoTopic.FUEL_NEAR)
                has("autonomia", "combustible", "gasoil", "gasoleo", "diesel", "deposito", "gasolina", "kilometros me quedan", "queda") -> return Command.Info(InfoTopic.RANGE)
                has("presion", "neumatico", "rueda", "ruedas") -> return Command.Info(InfoTopic.TYRES)
                has("puerta", "maletero", "capo") -> return Command.Info(InfoTopic.DOORS)
                has("viaje", "consumo", "llevo", "recorrido") -> return Command.Info(InfoTopic.TRIP)
                has("tiempo", "temperatura", "llover", "llueve", "lluvia", "clima", "frio", "calor", "sol", "nieve", "nevar", "grados") -> return Command.Info(InfoTopic.WEATHER)
                has("hora") -> return Command.Info(InfoTopic.TIME)
                has("velocidad", "rapido") -> return Command.Info(InfoTopic.SPEED)
            }
        }

        // Abrir pantallas (con o sin «abre», «muestra», «pon», «ver»…).
        return when {
            has("gasolinera", "repostar", "echar gasoil", "echar gasolina") -> Command.Open(Screen.FUEL)
            has("viaje", "consumo", "recorrido") -> Command.Open(Screen.TRIP)
            has("vehiculo", "coche", "neumatico", "rueda", "puerta", "presion") -> Command.Open(Screen.VEHICLE)
            has("tiempo", "clima", "prevision", "meteo") -> Command.Open(Screen.WEATHER)
            has("telefono", "llama", "llamar", "contacto", "marcador", "agenda") -> Command.Open(Screen.PHONE)
            has("aplicacion", "apps", "app") -> Command.Open(Screen.APPS)
            has("diagnostico", "ajuste", "configuracion") -> Command.Open(Screen.DIAGNOSTICS)
            has("inicio", "principal", "home") -> Command.Open(Screen.HOME)
            has("musica", "spotify", "cancion", "reproductor") -> Command.Music
            has("mapa", "navegacion", "navegador", "maps", "gps") -> Command.Maps
            has("autonomia", "combustible", "gasoil", "deposito") -> Command.Info(InfoTopic.RANGE)
            has("hora") -> Command.Info(InfoTopic.TIME)
            else -> Command.Unknown(t)
        }
    }
}
