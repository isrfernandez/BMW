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
    /** «Humor al 60 por ciento» ([set]), «sube/baja el humor» ([delta]) o «¿qué humor tienes?» (nada). */
    data class Humor(val set: Int? = null, val delta: Int = 0) : Command
    /** «¿Cuál es tu nivel de sinceridad?» */
    data object Honesty : Command
    data object Cancel : Command
    data class Mode(val mode: com.cockpitf30.ui.theme.DriveMode) : Command
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
    /**
     * Frases que acepta el reconocedor de la palabra de activación (gramática de Vosk). "[unk]" recoge
     * todo lo demás para que cualquier frase no acabe forzada a «hola BMW».
     */
    val WAKE_GRAMMAR: String = listOf(
        "hola be eme uve doble", "hola be eme uve", "hola be eme doble uve", "hola be eme doble ve",
        "hola be eme", "hola uve doble", "ola be eme uve doble", "oye be eme uve doble", "[unk]",
    ).joinToString(",", "[", "]") { "\"$it\"" }

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

    private val BRAND_WORDS = setOf("be", "eme", "me", "uve", "doble", "ve", "bmw", "hola", "ola", "oye")

    /**
     * Quita del principio los restos de «hola BMW»: la activación salta en cuanto se oye «hola be eme»,
     * así que el reconocedor de la orden puede empezar oyendo «uve doble …».
     */
    fun stripWakeWords(text: String): String =
        normalize(text).split(' ').dropWhile { it in BRAND_WORDS }.joinToString(" ")

    private val QUESTION = setOf("cuanto", "cuanta", "cuantos", "cuantas", "que", "como", "cual", "hay", "dime", "donde", "va", "voy", "tengo", "esta", "estan", "hace")

    fun parse(text: String): Command {
        val t = normalize(text)
        if (t.isEmpty()) return Command.Cancel
        val words = t.split(' ').toSet()
        fun has(vararg keys: String) = keys.any { k -> if (' ' in k) t.contains(k) else words.any { it.startsWith(k) } }
        val question = words.any { it in QUESTION }

        if (t in setOf("nada", "no", "gracias", "cancelar", "cancela", "olvidalo", "salir", "adios") || has("cancela", "olvidalo")) return Command.Cancel
        if (has("ayuda", "que puedes", "que sabes")) return Command.Help

        // Ajustes de personalidad, como el robot de la película: «humor al setenta y cinco por ciento».
        if (has("humor")) {
            val n = number(t.substringAfter("humor"))
            return when {
                n != null -> Command.Humor(set = n.coerceIn(0, 100))
                has("sube", "subir", "mas humor", "aumenta") -> Command.Humor(delta = 25)
                has("baja", "bajar", "menos humor", "reduce", "quita") -> Command.Humor(delta = -25)
                else -> Command.Humor()
            }
        }
        if (has("sinceridad", "honestidad")) return Command.Honesty

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

        // Modos de conducción: «modo sport», «pon el modo noche», «modo normal».
        if (has("modo")) {
            when {
                has("sport", "deportivo", "sporty", "espor") -> return Command.Mode(com.cockpitf30.ui.theme.DriveMode.SPORT)
                has("noche", "night", "nocturno", "naight") -> return Command.Mode(com.cockpitf30.ui.theme.DriveMode.NIGHT)
                has("normal", "confort", "comfort") -> return Command.Mode(com.cockpitf30.ui.theme.DriveMode.NORMAL)
            }
        }

        // Abrir pantallas (con o sin «abre», «muestra», «pon», «ver»…).
        return when {
            has("rendimiento", "potencia", "fuerzas g", "fuerza g", "altitud", "pendiente", "telemetria") -> Command.Open(Screen.PERFORMANCE)
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

    private val UNITS = listOf("cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve")
    private val TEENS = listOf("diez", "once", "doce", "trece", "catorce", "quince", "dieciseis", "diecisiete", "dieciocho", "diecinueve")
    private val TENS = mapOf("veinte" to 20, "treinta" to 30, "cuarenta" to 40, "cincuenta" to 50, "sesenta" to 60, "setenta" to 70, "ochenta" to 80, "noventa" to 90)

    /**
     * Primer número de 0 a 100 en [text], en cifras («60») o en palabras, como lo escribe Vosk
     * («setenta y cinco», «veinticinco», «cien»). null si no hay.
     */
    fun number(text: String): Int? {
        val w = normalize(text).split(' ').filter { it.isNotEmpty() }.map { if (it == "un") "uno" else it }
        for (i in w.indices) {
            val word = w[i]
            word.toIntOrNull()?.let { return it }
            if (word == "cien" || word == "ciento") return 100
            UNITS.indexOf(word).takeIf { it >= 0 }?.let { return it }
            TEENS.indexOf(word).takeIf { it >= 0 }?.let { return 10 + it }
            if (word.startsWith("veinti")) UNITS.indexOf(word.removePrefix("veinti")).takeIf { it > 0 }?.let { return 20 + it }
            TENS[word]?.let { tens ->
                val unit = if (w.getOrNull(i + 1) == "y") UNITS.indexOf(w.getOrNull(i + 2) ?: "") else -1
                return tens + unit.coerceAtLeast(0)
            }
        }
        return null
    }
}
