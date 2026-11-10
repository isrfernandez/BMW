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
    /** «Llama a Manuel»: [who] es lo dicho tras «llama a» (nombre o número). */
    data class Call(val who: String) : Command
    data class Mode(val mode: com.cockpitf30.ui.theme.DriveMode) : Command
    data class Unknown(val text: String) : Command

    // ---- conversación
    /** «Cuéntame un chiste». */
    data object Joke : Command
    /** «Repite», «¿qué has dicho?». */
    data object Repeat : Command
    data object Thanks : Command
    /** «Sí», «vale», «venga»: contesta a lo último que preguntó el asistente. */
    data object Yes : Command
    /** «No» a una pregunta del asistente (sale de [Conversation], no del texto). */
    data object No : Command
    /** «Otro», «la siguiente»: otra vez lo último (otro chiste, la siguiente gasolinera…). */
    data object Another : Command
    /** «Llévame», «vamos allí»: al último sitio del que se ha hablado. */
    data object GoThere : Command
    /** «¿Cuánto cuesta?», «¿a qué precio?»: de la última gasolinera. */
    data object Price : Command
    /** El tiempo de otro día: 1 = mañana, 2 = pasado mañana. */
    data class WeatherDay(val offset: Int) : Command
    /** «Me llamo Israel». */
    data class SetName(val name: String) : Command
    /** «¿Cómo me llamo?». */
    data object AskName : Command
    /** «¿De qué hablábamos?», «¿qué te he preguntado?». */
    data object Recap : Command
    /** Charla: [key] de [Lines.CHAT] («hello», «howareyou», «tired»…). */
    data class Chat(val key: String) : Command
    /** «Activa / desactiva el copiloto»; null = activar. */
    data class Copilot(val on: Boolean?) : Command
    /** «Pausa»: se calla, pero recuerda dónde iba. */
    data object Pause : Command
    /** «Continúa», «sigue»: retoma lo que estaba diciendo. */
    data object Resume : Command
    /** «Cállate un rato»: el copiloto no habla en media hora. */
    data object Quiet : Command
    /** Activar o desactivar los avisos en ruta, todos ([category] null) o de un tipo. */
    data class Alerts(val category: com.cockpitf30.nav.AlertCategory?, val on: Boolean) : Command
    /** «¿Qué hay por aquí?»: sitios de interés cercanos. */
    data object Nearby : Command
    /** «Háblame de eso»: más del último sitio del que se avisó. */
    data object AboutThat : Command
    /** Una de las gasolineras de las que se acaba de hablar (lo crea [Conversation]). */
    data class Station(val index: Int, val action: StationAction) : Command
}

enum class StationAction { DESCRIBE, PRICE, GO }

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

    /** Palabras para cortar al asistente mientras habla. */
    /** Cortar y callarse. */
    private val STOP_WORDS = listOf("para", "para ya", "detente", "cancela", "silencio", "calla", "callate", "cállate", "basta", "vale ya", "stop")
    /** Pausar para luego seguir («continúa»). */
    private val PAUSE_WORDS = listOf("pausa", "haz una pausa")
    /** Retomar lo que estaba diciendo. */
    private val RESUME_WORDS = listOf("continua", "continúa", "sigue", "sigue hablando", "continua hablando", "continúa hablando", "sigue por favor")
    /** Cortar sin perder el hilo y escuchar lo que viene («espera, ¿y mañana?»). */
    private val HOLD_WORDS = listOf("espera", "un momento", "a ver", "aclarame", "aclárame", "aclarame esto", "aclárame esto", "perdona", "oye")

    /** Gramática mientras habla la radio: «Hola BMW» o una palabra para cortarla. */
    val BARGE_GRAMMAR: String = (Regex("\"([^\"]+)\"").findAll(WAKE_GRAMMAR).map { it.groupValues[1] }.filter { it != "[unk]" }.toList() +
        STOP_WORDS + PAUSE_WORDS + HOLD_WORDS + RESUME_WORDS + "[unk]").distinct().joinToString(",", "[", "]") { "\"$it\"" }

    private val BARGE_PHRASES: List<String> by lazy {
        Regex("\"([^\"]+)\"").findAll(BARGE_GRAMMAR).map { normalize(it.groupValues[1]) }.filter { it.isNotBlank() && it != "unk" }
            .distinct().sortedByDescending { it.length }.toList()
    }

    /** La orden de interrupción con la que termina [text] (resultado de la gramática), o null. */
    fun bargePhraseAtEnd(text: String): String? {
        val t = normalize(text.replace("[unk]", " "))
        if (t.isEmpty()) return null
        return BARGE_PHRASES.firstOrNull { t == it || t.endsWith(" $it") }
    }

    /** Lo que pide una palabra dicha mientras habla el asistente. */
    enum class Barge { STOP, PAUSE, HOLD, RESUME }

    fun bargeAction(text: String): Barge? {
        val t = normalize(text)
        fun inList(l: List<String>) = l.any { normalize(it) == t }
        return when {
            inList(STOP_WORDS) -> Barge.STOP
            inList(PAUSE_WORDS) -> Barge.PAUSE
            inList(HOLD_WORDS) -> Barge.HOLD
            inList(RESUME_WORDS) -> Barge.RESUME
            else -> null
        }
    }

    fun isStopWord(text: String): Boolean = bargeAction(text) == Barge.STOP

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

        if (t in setOf("pausa", "haz una pausa")) return Command.Pause
        if (t in setOf("continua", "sigue", "sigue hablando", "continua hablando", "sigue por favor", "continua por favor", "por donde ibas", "donde te habias quedado")) return Command.Resume
        if (t in setOf("para", "para ya", "detente", "parate", "stop", "basta", "vale ya")) return Command.Cancel
        if (t in setOf("nada", "no", "no gracias", "cancelar", "cancela", "olvidalo", "salir", "adios", "hasta luego", "nada mas") || has("cancela", "olvidalo")) return Command.Cancel
        if (has("gracias") && words.size <= 4) return Command.Thanks
        if (t in YES || (words.size <= 3 && t.startsWith("si "))) return Command.Yes
        if (t in ANOTHER) return Command.Another
        if (has("ayuda", "que puedes", "que sabes")) return Command.Help

        // Conversación: repetir, resumir, nombre del conductor, chistes, copiloto.
        if (has("repite", "repitelo", "que has dicho", "que dices", "no te he oido", "como dices", "como has dicho")) return Command.Repeat
        if (has("de que hablabamos", "que te he preguntado", "que te he pedido", "de que hemos hablado", "que hemos hablado")) return Command.Recap
        Regex("^(?:yo )?(?:me llamo|mi nombre es|llamame|soy) ([a-zñ ]{2,30})$").find(t)?.let { m ->
            val name = m.groupValues[1].trim()
            if (name !in setOf("tonto", "yo", "tu conductor", "el conductor")) {
                return Command.SetName(name.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } })
            }
        }
        if (has("como me llamo", "sabes mi nombre", "sabes como me llamo", "quien soy", "cual es mi nombre")) return Command.AskName
        if (has("chiste", "broma", "hazme reir", "algo gracioso", "hazme gracia")) return Command.Joke
        if (has("copiloto")) {
            return Command.Copilot(if (has("desactiva", "quita", "apaga", "para ", "deja", "fuera", "sin copiloto")) false else true)
        }
        // Avisos en ruta: «desactiva los avisos», «quita los avisos turísticos», «activa avisos de radares».
        val alertWords = has("aviso", "avisos", "puntos de interes", "informacion turistica", "turistic", "turismo")
        val toggle = has("activa", "desactiva", "quita", "apaga", "enciende", "pon ", "quiero", "sin ", "no quiero", "deja de", "para de", "vuelve a")
        if (alertWords && toggle) {
            val off = has("desactiva", "quita", "apaga", "sin ", "no quiero", "deja de", "para de", "no me avises")
            val cat = when {
                has("radar") -> com.cockpitf30.nav.AlertCategory.RADAR
                has("turistic", "turismo", "monumento", "puntos de interes", "lugares", "sitios") -> com.cockpitf30.nav.AlertCategory.TOURISM
                has("seguridad", "peligro", "paso a nivel", "pasos a nivel") -> com.cockpitf30.nav.AlertCategory.SAFETY
                has("gasolin", "combustible", "gasoil", "repostar", "autonomia") -> com.cockpitf30.nav.AlertCategory.FUEL
                has("descanso", "area", "areas", "parar") -> com.cockpitf30.nav.AlertCategory.REST
                else -> null
            }
            return Command.Alerts(cat, !off)
        }
        if (has("que hay por aqui", "que hay cerca", "que hay por la zona", "que ver por aqui", "que se puede ver", "algo que ver", "sitios de interes", "lugares de interes", "puntos de interes", "que hay interesante")) return Command.Nearby
        if (has("hablame de eso", "hablame de ese", "hablame de esa", "cuentame mas", "que es eso", "que es ese", "que es esa", "mas informacion", "cuentame de eso", "dime mas")) return Command.AboutThat
        if (has("callate", "silencio", "no hables", "deja de hablar")) return Command.Quiet
        if (Regex("^(?:llevame|llevanos|vamos|ve|navega|ir|guiame)(?: (?:alli|ahi|alla|hasta alli|hacia alli|para alla|a ella|a esa))?$").matches(t)) return Command.GoThere
        if (!has("google") && has("cuanto cuesta", "precio", "a cuanto esta", "cuanto vale", "a como esta")) return Command.Price
        if (has("manana") && !has("google")) {
            if (has(*WEATHER_WORDS) || t.matches(Regex("^(?:y )?(?:para )?(?:pasado )?manana$"))) return Command.WeatherDay(if (has("pasado manana")) 2 else 1)
        }

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

        // «Llama a Manuel», «llamar a mamá», «telefonea a Pedro móvil».
        Regex("^(?:(?:puedes|podrias) )?(?:llama|llamar|telefonea|marca)(?: a| al| a la)? (.+)").find(t)?.let { m ->
            val who = m.groupValues[1].trim()
            if (who.isNotEmpty() && who !in setOf("telefono", "por telefono")) return Command.Call(who)
        }

        // «Llévame a …», «navega a …», «cómo llego a …».
        Regex("\\b(?:llevame|llevanos|navega|navegar|ruta|como llego|ir|vamos|guiame)(?: hasta| hacia| a| al| a la)+ (.+)").find(t)?.let { m ->
            val dest = m.groupValues[1].replace(Regex("^(la |el |los |las )"), "").trim()
            if (dest.startsWith("gasolinera") || dest == "repostar") return Command.Info(InfoTopic.FUEL_NEAR)
            if (dest in setOf("inicio", "pantalla principal")) return Command.Open(Screen.HOME)
            if (dest.isNotEmpty()) return Command.Navigate(dest)
        }

        // «Busca la gasolinera más cercana», «búscame un restaurante».
        Regex("^(?:busca|buscame|encuentra|encuentrame)(?: (?:la|el|los|las|una|un|unos|unas|algun|alguna))? (.+)").find(t)?.let { m ->
            val what = m.groupValues[1].trim()
            if (what.startsWith("gasolinera") || what == "donde repostar") return Command.Info(InfoTopic.FUEL_NEAR)
            if (what.length >= 4 && what !in setOf("algo", "eso", "esto")) return Command.Navigate(what)
        }

        // Preguntas: se contestan en voz alta (y se abre la pantalla que corresponda).
        if (question || has("autonomia", "cuanto queda")) {
            when {
                has("gasolinera") -> return Command.Info(InfoTopic.FUEL_NEAR)
                has("autonomia", "combustible", "gasoil", "gasoleo", "diesel", "deposito", "gasolina", "kilometros me quedan", "queda") -> return Command.Info(InfoTopic.RANGE)
                has("presion", "neumatico", "rueda", "ruedas") -> return Command.Info(InfoTopic.TYRES)
                has("puerta", "maletero", "capo") -> return Command.Info(InfoTopic.DOORS)
                has("viaje", "consumo", "llevo", "recorrido") -> return Command.Info(InfoTopic.TRIP)
                has(*WEATHER_WORDS) -> return Command.Info(InfoTopic.WEATHER)
                has("hora") -> return Command.Info(InfoTopic.TIME)
                has("velocidad", "rapido") -> return Command.Info(InfoTopic.SPEED)
            }
        }

        chatKey(t, ::has)?.let { return Command.Chat(it) }

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
            has("gasolinera") && has("cercana", "cerca", "proxima") -> Command.Info(InfoTopic.FUEL_NEAR)
            has("radar", "radares", "multa", "en ruta", "en el camino", "por el camino", "destino") -> Command.Open(Screen.ROUTE)
            has("rendimiento", "potencia", "fuerzas g", "fuerza g", "altitud", "pendiente", "telemetria") -> Command.Open(Screen.PERFORMANCE)
            has("gasolinera", "repostar", "echar gasoil", "echar gasolina") -> Command.Open(Screen.FUEL)
            has("mantenimiento", "aceite", "itv", "revision", "cambio de ruedas") -> Command.Open(Screen.MAINTENANCE)
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

    private val WEATHER_WORDS = arrayOf("tiempo", "temperatura", "llover", "llueve", "lluvia", "clima", "frio", "calor", "sol", "nieve", "nevar", "grados")
    private val YES = setOf("si", "vale", "venga", "claro", "dale", "por favor", "adelante", "de acuerdo", "ok", "okey", "bueno", "vale vale", "si si", "claro que si", "vamos alla", "por supuesto")
    private val ANOTHER = setOf("otro", "otra", "otra mas", "otro mas", "siguiente", "la siguiente", "el siguiente", "y otra", "y otro", "uno mas", "una mas", "mas", "cuentame otro", "dime otro", "dime otra")

    /** Charla: devuelve la clave de [Lines.CHAT] o null. */
    private fun chatKey(t: String, has: (Array<out String>) -> Boolean): String? {
        fun h(vararg k: String) = has(k)
        return when {
            t in setOf("hola", "buenas", "buenos dias", "buenas tardes", "buenas noches", "hey", "hola hola") -> "hello"
            h("que tal", "como estas", "como te va", "como andas", "como te encuentras") -> "howareyou"
            h("quien eres", "como te llamas", "que eres", "presentate") -> "whoareyou"
            h("cuantos anos tienes", "que edad tienes", "cuando naciste") -> "age"
            h("te quiero", "te amo", "me gustas", "te adoro") -> "love"
            h("tonto", "idiota", "inutil", "estupido", "torpe", "imbecil", "eres malo") -> "insult"
            h("eres genial", "eres el mejor", "buen trabajo", "bien hecho", "que listo", "eres muy listo", "me encantas") -> "praise"
            t in setOf("bien", "muy bien", "todo bien", "genial", "perfecto", "estupendo", "fenomenal", "bien gracias", "voy bien", "todo perfecto", "de maravilla") -> "fine"
            h("aburrido", "me aburro", "aburrida") -> "bored"
            h("cansado", "cansada", "sueno", "fatiga", "agotado", "agotada") -> "tired"
            h("sentido de la vida") -> "life"
            h("cantame", "canta algo", "canta una", "cantas") -> "sing"
            h("hambre") -> "hungry"
            else -> null
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
