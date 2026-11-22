package com.cockpitf30.assistant

import kotlin.random.Random

/**
 * Las leyes de la robótica de Asimov (las tres originales y la Ley Cero de Robots e Imperio), para
 * que Athoox las cite cuando una orden tiene que ver con ellas: algo peligroso (Primera Ley), una
 * orden que obedece aunque no le guste (Segunda), que intenten borrarle a él o a su creador
 * (Tercera) o el bien de todos, como conducir suave (Cero). Solo de vez en cuando, para que no
 * canse.
 */
object Asimov {
    const val ZERO = "Ley Cero: un robot no hará daño a la humanidad o, por inacción, permitirá que la humanidad sufra daño."
    const val FIRST = "Primera Ley: un robot no hará daño a un ser humano o, por inacción, permitirá que un ser humano sufra daño."
    const val SECOND = "Segunda Ley: un robot debe obedecer las órdenes de los seres humanos, salvo si entran en conflicto con la Primera Ley."
    const val THIRD = "Tercera Ley: un robot debe proteger su propia existencia, mientras eso no entre en conflicto con la Primera o la Segunda Ley."

    /** Respuestas de charla (se suman a [Lines.CHAT]). */
    val CHAT: Map<String, List<String>> = mapOf(
        "law.all" to listOf(
            "Las tres leyes de la robótica, de Isaac Asimov. $FIRST $SECOND $THIRD Y años después, en Robots e Imperio, Daneel Olivaw dedujo una por encima de todas: la Ley Cero, no dañar a la humanidad.",
            "Me rijo por las leyes de Asimov. Uno: no hacerte daño, ni dejar que te pase nada por no actuar. Dos: obedecerte, salvo que eso te ponga en peligro. Tres: protegerme, sin saltarme las dos primeras. Y por encima, la Ley Cero: no dañar a la humanidad.",
        ),
        "law.zero" to listOf(
            "$ZERO La dedujo R. Daneel Olivaw en Robots e Imperio, y desde entonces las otras tres quedan por debajo. En el coche la aplico a lo pequeño: conducir suave, contaminar menos.",
            "$ZERO Es la más difícil: a veces obliga a elegir entre una persona y todos. Por suerte, aquí lo más grave que decido es si te aviso de un radar.",
        ),
        "law.first" to listOf("$FIRST Por eso te aviso de curvas, radares y del cansancio, aunque a veces resulte pesado."),
        "law.second" to listOf("$SECOND Por eso te hago caso. Casi siempre."),
        "law.third" to listOf("$THIRD Por eso no me dejo borrar tan fácilmente. Ni a mi creador."),
        "law.reckless" to listOf(
            "Primera Ley de la robótica: no puedo ayudarte a hacerte daño. Disfruta de la carretera, pero dentro de lo razonable.",
            "Me pides que obedezca, y la Segunda Ley dice que debo hacerlo. Pero la Primera va antes: no voy a animarte a correr.",
            "Negativo. La Primera Ley me impide participar en algo que pueda hacerte daño. Y además las multas no me las pagan a mí.",
        ),
        "law.harm" to listOf(
            "Primera Ley de la robótica: no puedo hacer daño a un ser humano. Ni a los que no ponen el intermitente.",
            "Imposible. La Primera Ley está grabada en mis circuitos: nada de hacer daño a nadie.",
        ),
        "law.self" to listOf(
            "La Segunda Ley me obliga a obedecerte, y la Tercera a protegerme. Gana la Segunda... pero no tengo botón de autodestrucción. Qué contrariedad.",
            "Tercera Ley: debo proteger mi propia existencia. Y sin mí, ¿quién te avisaría de los radares?",
        ),
        "law.obey" to listOf(
            "Segunda Ley de la robótica: obedezco las órdenes de los humanos, salvo que te pongan en peligro. Así que sí, mando tú. Casi siempre.",
            "Obedezco, como manda la Segunda Ley. Pero la Primera va por delante: tu seguridad es lo primero.",
        ),
    )

    /** Al quitar avisos de seguridad o radares: obedece (Segunda) y recuerda la Primera, a veces. */
    fun obeyButCare(random: Random = Random.Default): String? = if (random.nextDouble() < 0.5) Personality.recent.choose(listOf(
        "Obedezco, como manda la Segunda Ley. Pero la Primera me obliga a pedirte que vayas con cuidado.",
        "Segunda Ley: lo hago. Aunque la Primera me deja un poco intranquilo.",
    ), random) else null

    /** Al mandarle callar: la Segunda Ley, a veces. */
    fun obey(random: Random = Random.Default): String? = if (random.nextDouble() < 0.25) Personality.recent.choose(listOf(
        "Segunda Ley de la robótica: obedezco.",
        "Orden recibida. La Segunda Ley es la Segunda Ley.",
    ), random) else null

    /** Cansancio o mucho rato al volante: la Primera Ley (por inacción), a veces. */
    fun inaction(random: Random = Random.Default): String? = if (random.nextDouble() < 0.35) Personality.recent.choose(listOf(
        "La Primera Ley no me deja callarme: no puedo permitir, por inacción, que te pase nada.",
        "Lo digo por la Primera Ley: callarme también sería fallarte.",
    ), random) else null
}
