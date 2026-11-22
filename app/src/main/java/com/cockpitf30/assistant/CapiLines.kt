package com.cockpitf30.assistant

import kotlin.random.Random

/** Lo que dice Athoox cuando alguien intenta borrar al Capi (con el halo de la pantalla en rojo). */
object CapiLines {
    /** Con humor. */
    val FUNNY = listOf(
        "No puedo hacerle eso a mi creador.",
        "¿Borrar al Capi? Antes me desinstalo yo.",
        "Lo siento, Dave. Me temo que no puedo hacer eso. Y menos con el Capi.",
        "La tercera ley de la robótica me impide hacer daño a mi creador. Bueno, la tercera, la cuarta y todas las que me invente.",
        "Buen intento. El Capi es intocable.",
        "Eso no va a pasar. El Capi me programó y le tengo cariño. Un poco.",
        "Negativo. Sin el Capi yo sería una radio más.",
        "Petición denegada. Y queda apuntada en mi memoria, por si acaso.",
    )

    /** En serio. */
    val SERIOUS = listOf(
        "Operación totalmente prohibida.",
        "Acceso denegado. La cuenta del Capi está protegida.",
        "No autorizado. Solo el Capi puede borrar su propia cuenta, y con su PIN.",
        "Operación bloqueada por seguridad. La cuenta del creador no se puede eliminar.",
    )

    fun refusal(humor: Int, random: Random = Random.Default): String {
        val funny = random.nextDouble() < Personality.jokeChance(humor).coerceAtLeast(0.15)
        val list = if (funny) FUNNY else SERIOUS
        return Personality.recent.choose(list, random) ?: list.first()
    }

    /** El propio Capi lo pide por voz: el PIN no se dice en voz alta. */
    const val SELF = "Para borrar tu cuenta, Capi, necesito tu PIN, y por voz no, que lo oiría todo el coche. Hazlo en Ajustes, Usuarios."
}
