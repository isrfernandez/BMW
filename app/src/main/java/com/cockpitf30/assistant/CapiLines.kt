package com.cockpitf30.assistant

import kotlin.random.Random

/** Lo que dice Athoox cuando alguien intenta borrar al Capi (con el halo de la pantalla en rojo). */
object CapiLines {
    /** Con humor. */
    val FUNNY = listOf(
        "No puedo hacerle eso a mi creador.",
        "¿Borrar a Zeus? Antes me desinstalo yo.",
        "Lo siento, Dave. Me temo que no puedo hacer eso. Y menos con Zeus.",
        "Tercera Ley de la robótica: debo proteger mi propia existencia. Y sin Zeus no existo.",
        "Me pides que obedezca, como manda la Segunda Ley. Pero borrar a Zeus sería borrarme a mí, y la Tercera Ley no me deja.",
        "Buen intento. Zeus es intocable.",
        "Eso no va a pasar. Zeus me programó y le tengo cariño. Un poco.",
        "Negativo. Sin Zeus yo sería una radio más.",
        "Petición denegada. Y queda apuntada en mi memoria, por si acaso.",
    )

    /** En serio. */
    val SERIOUS = listOf(
        "Operación totalmente prohibida.",
        "Acceso denegado. La cuenta de Zeus está protegida.",
        "No autorizado. Solo Zeus puede borrar su propia cuenta, y con su PIN.",
        "Operación bloqueada por seguridad. La cuenta del creador no se puede eliminar.",
    )

    fun refusal(humor: Int, random: Random = Random.Default): String {
        val funny = random.nextDouble() < Personality.jokeChance(humor).coerceAtLeast(0.15)
        val list = if (funny) FUNNY else SERIOUS
        return Personality.recent.choose(list, random) ?: list.first()
    }

    /** El propio Capi lo pide por voz: el PIN no se dice en voz alta. */
    const val SELF = "Para borrar tu cuenta necesito tu PIN, y por voz no, que lo oiría todo el coche. Hazlo en Ajustes, Usuarios."
}
