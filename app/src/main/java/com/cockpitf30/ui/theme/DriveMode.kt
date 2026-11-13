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
    /** Tema diurno: fondos claros y letra oscura, para leer a pleno sol. */
    val light: Boolean = false,
    val background: Color = Color(0xFF0B0C10),
    val surface: Color = Color(0xFF121212),
    val surfaceHigh: Color = Color(0xFF1C1F26),
    val track: Color = Color(0xFF262B33),
    /** Color base de líneas, rayas y bordes finos (se usa con transparencia). */
    val ink: Color = Color.White,
    /** Color de las veladuras que separan el texto del fondo (se usa con transparencia). */
    val shade: Color = Color.Black,
)

/** Tema diurno (fondos claros): apagado, automático (de día, si no está oscuro) o siempre. */
enum class DayTheme(val label: String) {
    OFF("Desactivado"),
    AUTO("Automático"),
    ON("Siempre"),
}

/** Tamaño de la letra de toda la app (multiplica todos los textos sin mover el resto). */
enum class TextSize(val label: String, val scale: Float) {
    NORMAL("Normal", 1.0f),
    LARGE("Grande", 1.15f),
    XLARGE("Muy grande", 1.3f),
}

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

    /**
     * Diurno: fondo casi blanco y letra casi negra, con acentos más oscuros para que tengan
     * contraste sobre blanco (el azul claro del modo Normal no se leería al sol).
     */
    private fun daylight(base: ModePalette, accent: Color, accentDark: Color, metal: Color, metalDark: Color) = base.copy(
        blue = accent, blueLight = accentDark,
        copperDark = Color(0xFFB8BEC8), copper = metal, copperLight = metalDark,
        steelDark = Color(0xFFD5DCE6), steel = Color(0xFF5B7393), steelLight = Color(0xFF2E4766),
        text = Color(0xFF070B12), textDim = Color(0xFF3A4352), tint = null, dim = 0f,
        light = true,
        background = Color(0xFFE9EDF2), surface = Color(0xFFFFFFFF), surfaceHigh = Color(0xFFFFFFFF),
        track = Color(0xFFC9D0DA), ink = Color(0xFF0B1320), shade = Color.White,
    )

    val NORMAL_DAY = daylight(NORMAL_PALETTE, Color(0xFF0B5CD5), Color(0xFF0A4DB3), Color(0xFF8A5634), Color(0xFF5E3519))
    val SPORT_DAY = daylight(SPORT_PALETTE, Color(0xFFD0160C), Color(0xFFB00F07), Color(0xFF59616C), Color(0xFF2E343C))

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

    /** Modo y tema diurno impuestos por el modo demo (null = los del conductor). No se guardan. */
    var demoMode by mutableStateOf<DriveMode?>(null)
    var demoDaylight by mutableStateOf<Boolean?>(null)

    /** El modo que se ve. */
    val current: DriveMode
        get() = demoMode ?: if (selected == DriveMode.NORMAL && autoNight && dark && !nightOverridden) DriveMode.NIGHT else selected

    /** true si el modo Night lo ha puesto la oscuridad y no el conductor. */
    val nightIsAuto: Boolean get() = current == DriveMode.NIGHT && selected == DriveMode.NORMAL

    /** Tema diurno elegido en Ajustes. */
    var dayTheme by mutableStateOf(DayTheme.AUTO)
        private set

    /** Tamaño de letra elegido en Ajustes. */
    var textSize by mutableStateOf(TextSize.LARGE)
        private set

    /**
     * Si se ve el tema diurno. Night (puesto a mano u oscuro) siempre manda: de noche el blanco
     * deslumbra. En automático, de día; con «Siempre», también en túneles (pero no con Night).
     */
    val daylight: Boolean
        get() = current != DriveMode.NIGHT && demoDaylight ?: when (dayTheme) {
            DayTheme.OFF -> false
            DayTheme.AUTO -> !dark
            DayTheme.ON -> true
        }

    val palette: ModePalette
        get() = when (current) {
            DriveMode.NORMAL -> if (daylight) NORMAL_DAY else NORMAL_PALETTE
            DriveMode.SPORT -> if (daylight) SPORT_DAY else SPORT_PALETTE
            DriveMode.NIGHT -> NIGHT_PALETTE
        }

    /** Paleta oscura del modo actual (para lo que sigue oscuro con el tema diurno). */
    val darkPalette: ModePalette
        get() = when (current) {
            DriveMode.NORMAL -> NORMAL_PALETTE
            DriveMode.SPORT -> SPORT_PALETTE
            DriveMode.NIGHT -> NIGHT_PALETTE
        }

    fun setDayTheme(context: Context, theme: DayTheme) {
        dayTheme = theme
        prefs(context).edit().putString(KEY_DAY_THEME, theme.name).apply()
    }

    fun setTextSize(context: Context, size: TextSize) {
        textSize = size
        prefs(context).edit().putString(KEY_TEXT_SIZE, size.name).apply()
    }

    /** Solo para las capturas de prueba (Paparazzi no tiene preferencias). */
    fun testDaylight(on: Boolean, size: TextSize = TextSize.LARGE) {
        textSize = size
        selected = DriveMode.NORMAL
        darkState = false
        dayTheme = if (on) DayTheme.ON else DayTheme.OFF
    }

    fun load(context: Context) {
        val prefs = prefs(context)
        // Se arranca en Normal aunque se apagara en Sport; si es de noche (o está oscuro) el
        // Night automático se pone en cuanto el detector lo ve, a los pocos segundos de arrancar.
        selected = DriveMode.NORMAL
        nightOverridden = false
        autoNight = prefs.getBoolean(KEY_AUTO_NIGHT, true)
        nightWithHeadlights = prefs.getBoolean(KEY_NIGHT_LIGHTS, true)
        dayTheme = runCatching { DayTheme.valueOf(prefs.getString(KEY_DAY_THEME, null) ?: "") }.getOrDefault(DayTheme.AUTO)
        textSize = runCatching { TextSize.valueOf(prefs.getString(KEY_TEXT_SIZE, null) ?: "") }.getOrDefault(TextSize.LARGE)
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
    private const val KEY_DAY_THEME = "day_theme"
    private const val KEY_TEXT_SIZE = "text_size"
}
