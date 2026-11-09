package com.cockpitf30.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Modos de conducción virtuales. La caja CAN no manda el modo del coche (Comfort/Sport/Eco Pro),
 * así que se eligen a mano; cambian toda la paleta del lanzador al instante.
 * Normal es el aspecto de siempre (azul, cobre y acero).
 */
enum class DriveMode(val label: String, val subtitle: String) {
    NORMAL("Normal", "Azul eléctrico"),
    SPORT("Sport", "Rojo infierno"),
    NIGHT("Night", "Ámbar clásico"),
}

/** Colores que cambian con el modo. El resto (verde de OK, rojo de alerta) es igual en todos. */
data class ModePalette(
    val blue: Color,
    val blueLight: Color,
    val copperDark: Color,
    val copper: Color,
    val copperLight: Color,
    val steelDark: Color,
    val steel: Color,
    val steelLight: Color,
    val text: Color,
    val textDim: Color,
    /** Matiz con el que se tiñen las fotos de fondo (null = sin teñir). */
    val tint: Color?,
    /** Oscurecimiento extra de los fondos (de noche deslumbra menos). */
    val dim: Float,
)

object DriveModes {
    val NORMAL_PALETTE = ModePalette(
        blue = Color(0xFF1C69D8), blueLight = Color(0xFF4FA3FF),
        copperDark = Color(0xFF4A2412), copper = Color(0xFFC0764A), copperLight = Color(0xFFF2BC96),
        steelDark = Color(0xFF16263A), steel = Color(0xFF6D90B5), steelLight = Color(0xFFC3D7EC),
        text = Color(0xFFFFFFFF), textDim = Color(0xFFA7AFBA), tint = null, dim = 0f,
    )

    /** Rojo intenso con titanio en lugar del cobre, como un M. */
    val SPORT_PALETTE = ModePalette(
        blue = Color(0xFFD0160C), blueLight = Color(0xFFFF5A46),
        copperDark = Color(0xFF2A2E34), copper = Color(0xFFA4ACB6), copperLight = Color(0xFFE6EAF0),
        steelDark = Color(0xFF3A0C0A), steel = Color(0xFFD03A2E), steelLight = Color(0xFFFFB6AA),
        text = Color(0xFFFFFFFF), textDim = Color(0xFFB9AFAE), tint = Color(0xFFE0140A), dim = 0.04f,
    )

    /** Ámbar de los cuadros clásicos de BMW, con bronce, y todo un poco más apagado. */
    val NIGHT_PALETTE = ModePalette(
        blue = Color(0xFFD9820A), blueLight = Color(0xFFFFB238),
        copperDark = Color(0xFF33200C), copper = Color(0xFF9C6A34), copperLight = Color(0xFFE8C08C),
        steelDark = Color(0xFF3A2608), steel = Color(0xFFD08A2A), steelLight = Color(0xFFFFD9A0),
        text = Color(0xFFFFF1DC), textDim = Color(0xFFC2B096), tint = Color(0xFFFF9A10), dim = 0.18f,
    )

    /** El modo que eligió el conductor. */
    var selected by mutableStateOf(DriveMode.NORMAL)
        private set

    /** Está oscuro (anochecer, túnel o faros encendidos): con Normal elegido se pasa a Night solo. */
    var dark: Boolean
        get() = darkState
        set(value) {
            // Al hacerse de día se olvida que el conductor quitó Night: el próximo anochecer vuelve solo.
            if (!value) nightOverridden = false
            darkState = value
        }
    private var darkState by mutableStateOf(false)

    /** El conductor eligió Normal estando oscuro: manda él hasta que vuelva a haber luz y oscuridad. */
    private var nightOverridden by mutableStateOf(false)

    /** Si Night se pone solo cuando está oscuro. */
    var autoNight by mutableStateOf(true)
        private set

    /** Si los faros encendidos (túneles) también cuentan como oscuro. */
    var nightWithHeadlights by mutableStateOf(true)
        private set

    /** El modo que se ve. */
    val current: DriveMode
        get() = if (selected == DriveMode.NORMAL && autoNight && dark && !nightOverridden) DriveMode.NIGHT else selected

    /** true si el modo Night lo ha puesto la oscuridad y no el conductor. */
    val nightIsAuto: Boolean get() = current == DriveMode.NIGHT && selected == DriveMode.NORMAL

    val palette: ModePalette
        get() = when (current) {
            DriveMode.NORMAL -> NORMAL_PALETTE
            DriveMode.SPORT -> SPORT_PALETTE
            DriveMode.NIGHT -> NIGHT_PALETTE
        }

    fun load(context: Context) {
        val prefs = prefs(context)
        // Siempre se arranca en Normal (aunque se apagara en Sport o sea de noche). El Night
        // automático vuelve a ponerse solo con el siguiente paso de luz a oscuridad.
        selected = DriveMode.NORMAL
        nightOverridden = true
        autoNight = prefs.getBoolean(KEY_AUTO_NIGHT, true)
        nightWithHeadlights = prefs.getBoolean(KEY_NIGHT_LIGHTS, true)
    }

    fun select(context: Context?, mode: DriveMode) {
        // Elegir Normal (o pasar de Night a Normal) a oscuras quita el Night automático por ahora.
        nightOverridden = mode == DriveMode.NORMAL && darkState
        selected = mode
        context?.let { prefs(it).edit().putString(KEY_MODE, mode.name).apply() }
    }

    /** Siguiente modo (tarjeta del Inicio y voz): Normal → Sport → Night → Normal. */
    fun cycle(context: Context) = select(context, DriveMode.entries[(current.ordinal + 1) % DriveMode.entries.size])

    fun setAutoNight(context: Context, on: Boolean) {
        autoNight = on
        prefs(context).edit().putBoolean(KEY_AUTO_NIGHT, on).apply()
    }

    fun setNightWithHeadlights(context: Context, on: Boolean) {
        nightWithHeadlights = on
        prefs(context).edit().putBoolean(KEY_NIGHT_LIGHTS, on).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("cockpit", Context.MODE_PRIVATE)
    private const val KEY_MODE = "drive_mode"
    private const val KEY_AUTO_NIGHT = "auto_night"
    private const val KEY_NIGHT_LIGHTS = "night_headlights"
}
