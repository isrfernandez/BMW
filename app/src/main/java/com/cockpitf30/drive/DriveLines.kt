package com.cockpitf30.drive

import com.cockpitf30.assistant.Personality
import kotlin.math.roundToInt
import kotlin.random.Random

/** Lo que va diciendo Athoox de la carretera, del ritmo y de la cobertura. */
object DriveLines {
    // ── Lectura de carretera ────────────────────────────────────────────

    fun climb(l: RoadLoad, humor: Int, random: Random = Random.Default): String {
        val s = l.slopePct.roundToInt()
        val x = l.extraPct
        val how = if (x >= 100) "${RoadFeel.times(x)} veces el empuje" else "un $x por ciento más de empuje"
        val plain = "Subida del $s por ciento. El coche necesita $how que en llano."
        return spice(plain, humor, random,
            "Al motor le toca trabajar. Yo no sudo, pero él sí.",
            "Si notas que pide una marcha menos, es por esto.",
            "La cuesta no se ve tanto como se nota.",
        )
    }

    fun descent(l: RoadLoad, humor: Int, random: Random = Random.Default): String {
        val s = (-l.slopePct).roundToInt()
        val plain = "Bajada del $s por ciento. Si sueltas el acelerador con la marcha metida, el diésel corta la inyección y no gasta nada."
        return spice(plain, humor, random,
            "Cuesta abajo y gratis: mis dos cosas favoritas.",
            "Deja que el motor retenga y ahorra frenos.",
            "La gravedad invita esta vez.",
        )
    }

    fun headwind(l: RoadLoad, humor: Int, random: Random = Random.Default): String {
        val w = l.headwindKmh.roundToInt()
        val v = l.speedKmh.roundToInt()
        val air = RoadFeel.airSpeedKmh(l)
        val plain = "Viento de cara de unos $w kilómetros por hora. Para el aire es como ir a $air en vez de a $v: un ${l.extraPct} por ciento más de resistencia."
        return spice(plain, humor, random,
            "Hoy el aire juega en contra.",
            "Si el consumo sube, no es culpa tuya.",
            "Ni el mejor aerodinamista de Múnich puede con esto.",
        )
    }

    fun tailwind(l: RoadLoad, humor: Int, random: Random = Random.Default): String {
        val w = (-l.headwindKmh).roundToInt()
        val plain = "Viento de cola de unos $w kilómetros por hora. Hoy el aire empuja: buen momento para ahorrar."
        return spice(plain, humor, random,
            "Por una vez, el viento está de nuestro lado.",
            "Es como bajar una cuesta que no se ve.",
        )
    }

    fun drag(excessPct: Int, humor: Int, random: Random = Random.Default): String {
        val plain = "Rodando en punto muerto, el coche frena un $excessPct por ciento más de lo que debería. Puede ser viento racheado, carga o algo que haga resistencia, como una baca."
        return spice(plain, humor, random,
            "Si no llevas nada encima, culpa al viento.",
            "No es un reproche al maletero, es física.",
        )
    }

    // ── Ritmo de viaje ──────────────────────────────────────────────────

    fun rhythm(r: Rhythm, humor: Int, random: Random = Random.Default): String = pick(random, when (r) {
        Rhythm.PASEO -> if (humor >= 40) listOf(
            "Esto tiene pinta de paseo tranquilo. Me relajo y te doy algo más de conversación.",
            "Buen ritmo de paseo. Si te apetece charlar, aquí estoy, y con más ganas de bromas.",
            "Modo paseo. Tú disfruta del camino, que yo pongo la charla.",
        ) else listOf(
            "Ritmo de paseo. Te daré algo más de conversación.",
        )
        Rhythm.AUTOPISTA -> listOf(
            "Ritmo de autopista. Seré más breve y al grano.",
            "Vamos de viaje largo. Me quedo con lo importante y no te corto el hilo.",
            "Modo autopista: datos claros y pocas interrupciones.",
        )
        Rhythm.CIUDAD -> listOf(
            "Mucho tráfico. Me pongo en modo breve.",
            "Ciudad densa. Solo avisos prácticos.",
            "Esto es ciudad. Hablo poco y solo de lo útil.",
        )
    })

    // ── Túnel y cobertura ───────────────────────────────────────────────

    fun blindIn(noData: Boolean, humor: Int, random: Random = Random.Default): String {
        val plain = if (noData) "Perdemos satélites y datos. Sigo a ciegas con la velocidad del coche y el último rumbo."
        else "Perdemos los satélites. Sigo a ciegas con la velocidad del coche y el último rumbo."
        return spice(plain, humor, random,
            "Tranquilo, sé contar metros a oscuras.",
            "Navegación a la antigua, como los marinos sin GPS.",
            "Esto parece un túnel. Yo no le tengo miedo a la oscuridad.",
        )
    }

    fun blindOut(driftM: Int?, sunny: Boolean, dataBack: Boolean, humor: Int, random: Random = Random.Default): String {
        val back = when {
            sunny && dataBack -> "Volvemos a tener cobertura y sol."
            sunny -> "Volvemos a ver el cielo, y hay sol."
            dataBack -> "Volvemos a tener cobertura."
            else -> "Volvemos a ver los satélites."
        }
        val drift = driftM?.let {
            when {
                it < 30 -> " A ciegas no me desvié ni $it metros."
                it < 300 -> " Corrijo la posición: me había ido unos ${(it / 10.0).roundToInt() * 10} metros."
                else -> " Corrijo la posición."
            }
        } ?: ""
        val refresh = if (dataBack) " Actualizo lo que tenía pendiente." else ""
        val plain = back + drift + refresh
        return spice(plain, humor, random,
            "Bienvenidos de vuelta a la superficie.",
            "Echaba de menos los satélites. Un poco.",
            "Otros sistemas se habrían quedado mudos. Yo no.",
        )
    }

    fun noData(humor: Int, random: Random = Random.Default): String {
        val plain = "Zona sin datos. Radares, gasolineras y mapa siguen con lo guardado; el tiempo se actualizará al volver la cobertura."
        return spice(plain, humor, random,
            "Internet se ha quedado atrás. Yo no.",
            "Sin cobertura, pero con memoria.",
        )
    }

    fun dataBack(humor: Int, random: Random = Random.Default): String {
        val plain = "Vuelve la cobertura. Actualizo el tiempo y los avisos."
        return spice(plain, humor, random, "Ya estamos otra vez conectados al mundo.", "Internet ha vuelto. Lo echaba de menos.")
    }

    /** La broma va según el humor ([Personality.jokeChance]); si no, la frase sola. */
    private fun spice(plain: String, humor: Int, random: Random, vararg quips: String): String {
        if (random.nextDouble() >= Personality.jokeChance(humor)) return plain
        val q = Personality.recent.choose(quips.toList(), random) ?: return plain
        return "$plain $q"
    }

    private fun pick(random: Random, list: List<String>) = Personality.recent.choose(list, random) ?: list.first()

    /** Para el popup: «+40 % esfuerzo» y similares. */
    fun effort(l: RoadLoad): String = when {
        l.extraPct >= 100 -> "×${RoadFeel.times(l.extraPct)} el esfuerzo en llano"
        l.extraPct > 0 -> "+${l.extraPct} % de esfuerzo"
        else -> "${l.extraPct} % de esfuerzo"
    }
}
