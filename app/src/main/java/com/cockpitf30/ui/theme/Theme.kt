package com.cockpitf30.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.cockpitf30.R

/**
 * Paleta estilo iDrive: negro grafito, texto blanco, gris plata, azul BMW y rojo M. Los acentos
 * cambian con el modo de conducción ([DriveModes]); se leen como estado de Compose, así que al
 * cambiar de modo todo se vuelve a pintar.
 */
object Cockpit {
    val Background: Color get() = DriveModes.palette.background
    val Surface: Color get() = DriveModes.palette.surface
    val SurfaceHigh: Color get() = DriveModes.palette.surfaceHigh
    /** Tema diurno (fondos claros). */
    val Light: Boolean get() = DriveModes.palette.light
    /** Base de líneas y bordes finos: blanca sobre oscuro, casi negra en el tema diurno. */
    val Ink: Color get() = DriveModes.palette.ink
    /** Veladura para separar texto del fondo: negra sobre oscuro, blanca en el tema diurno. */
    val Shade: Color get() = DriveModes.palette.shade
    val Blue: Color get() = DriveModes.palette.blue
    val BlueLight: Color get() = DriveModes.palette.blueLight
    val Text: Color get() = DriveModes.palette.text
    val TextDim: Color get() = DriveModes.palette.textDim
    /**
     * Para lo que va sobre imágenes o paneles que siguen oscuros también en el tema diurno
     * (centro del cuadro con el coche, placas de la barra lateral, botones de modo).
     */
    val OnDark: Color get() = if (Light) Color.White else Text
    val OnDarkDim: Color get() = if (Light) Color(0xFFC3CAD4) else TextDim
    val OnDarkAccent: Color get() = if (Light) DriveModes.darkPalette.blueLight else BlueLight
    val Track: Color get() = DriveModes.palette.track
    val Alert = Color(0xFFE10600)
    val Ok = Color(0xFF3DDC97)

    /** Efecto cristal de las tarjetas: blanco casi transparente y borde muy fino. */
    val Glass: Color get() = if (Light) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.05f)
    val GlassBorder: Color get() = if (Light) Ink.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.08f)

    /** Cobre metalizado y azul acero suave: acentos del menú lateral y de los marcadores. */
    val CopperDark: Color get() = DriveModes.palette.copperDark
    val Copper: Color get() = DriveModes.palette.copper
    val CopperLight: Color get() = DriveModes.palette.copperLight
    val SteelBlueDark: Color get() = DriveModes.palette.steelDark
    val SteelBlue: Color get() = DriveModes.palette.steel
    val SteelBlueLight: Color get() = DriveModes.palette.steelLight

    val MLight = Color(0xFF0066B1)
    val MDark = Color(0xFF1C2D6B)
    val MRed = Color(0xFFE10600)
}

/** Inter (licencia OFL) para textos: SemiBold/Bold en títulos. */
val Inter = FontFamily(
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Saira (licencia OFL) para cifras y datos numéricos: aire de cuadro digital. */
val Saira = FontFamily(
    Font(R.font.saira_light, FontWeight.Light),
    Font(R.font.saira_regular, FontWeight.Normal),
    Font(R.font.saira_medium, FontWeight.Medium),
    Font(R.font.saira_semibold, FontWeight.SemiBold),
    Font(R.font.saira_bold, FontWeight.Bold),
)

/** Estilo numérico en Saira, con dígitos tabulares para que las cifras no bailen al cambiar. */
fun numeric(size: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = Saira, fontSize = size.sp, fontWeight = weight, fontFeatureSettings = "tnum")

private fun style(size: Int, weight: FontWeight, spacing: Float = 0f, family: FontFamily = Inter) =
    TextStyle(
        fontFamily = family, fontSize = size.sp, fontWeight = weight, letterSpacing = spacing.sp,
        fontFeatureSettings = if (family == Saira) "tnum" else null,
    )

private val typography = Typography(
    // Los estilos display son para cifras grandes: van en Saira.
    displayLarge = style(96, FontWeight.Light, -1f, Saira),
    displayMedium = style(64, FontWeight.Light, 0f, Saira),
    displaySmall = style(44, FontWeight.Light, 0f, Saira),
    headlineSmall = style(26, FontWeight.SemiBold, 0.2f),
    titleLarge = style(24, FontWeight.Bold),
    titleMedium = style(20, FontWeight.SemiBold, 0.3f),
    bodyLarge = style(18, FontWeight.Normal),
    bodyMedium = style(17, FontWeight.Normal),
    // Rótulos un poco más grandes y gruesos que antes: eran los que peor se leían conduciendo.
    labelLarge = style(16, FontWeight.SemiBold, 1.2f),
    labelMedium = style(15, FontWeight.SemiBold, 0.4f),
)

@Composable
fun CockpitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = (if (Cockpit.Light) androidx.compose.material3.lightColorScheme(
            primary = Cockpit.Blue, secondary = Cockpit.BlueLight, background = Cockpit.Background,
            surface = Cockpit.Surface, onBackground = Cockpit.Text, onSurface = Cockpit.Text, error = Cockpit.Alert,
        ) else darkColorScheme(
            primary = Cockpit.Blue,
            secondary = Cockpit.BlueLight,
            background = Cockpit.Background,
            surface = Cockpit.Surface,
            onBackground = Cockpit.Text,
            onSurface = Cockpit.Text,
            error = Cockpit.Alert,
        )),
        typography = typography,
    ) {
        // Sin Surface en la raíz, el Text sin color heredaría negro: lo fijamos aquí para toda la app.
        CompositionLocalProvider(LocalContentColor provides Cockpit.Text) {
            DesignScale(content)
        }
    }
}

/**
 * Tema para ventanas fuera de la actividad (mini cockpit encima de Maps): mismos colores y letras,
 * y la misma escala que el lienzo de diseño, pero sin ocupar la pantalla entera.
 */
@Composable
fun CockpitOverlayTheme(content: @Composable () -> Unit) {
    val metrics = androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics
    val density = minOf(metrics.widthPixels / DESIGN_WIDTH_DP, metrics.heightPixels / DESIGN_HEIGHT_DP).coerceAtLeast(0.5f)
    MaterialTheme(colorScheme = darkColorScheme(primary = Cockpit.Blue, background = Cockpit.Background, surface = Cockpit.Surface), typography = typography) {
        CompositionLocalProvider(LocalContentColor provides Cockpit.Text, LocalDensity provides Density(density, fontScale = DriveModes.textSize.scale)) {
            content()
        }
    }
}

/** Lienzo de diseño: toda la interfaz está pensada para 1920x720 dp (la ZQ9109 a 160 dpi). */
const val DESIGN_WIDTH_DP = 1920f
const val DESIGN_HEIGHT_DP = 720f

/** Densidad aplicada por [DesignScale], para mostrarla en Diagnóstico. */
var appliedDesignDensity: Float = 0f
    private set

/** Bandas (px) que deja [DesignScale] arriba/abajo o a los lados cuando la pantalla no es 8:3. */
var appliedBandsPx: Pair<Int, Int> = 0 to 0
    private set

/**
 * Escala la interfaz para que la pantalla real equivalga siempre al lienzo de diseño, sea cual sea
 * la densidad o el tamaño de letra que traiga la radio (algunas usan 240 dpi y todo se salía).
 *
 * El lienzo mide siempre exactamente 1920x720 dp y se centra: en pantallas con otra proporción
 * (móvil 2772x1280, tablet 16:10) quedan bandas del color de fondo en lugar de estirar la
 * interfaz, así todo se ve con las mismas proporciones que en la radio.
 */
@Composable
private fun DesignScale(content: @Composable () -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Cockpit.Background),
        contentAlignment = Alignment.Center,
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight || w <= 0f || h <= 0f) {
            content()
            return@BoxWithConstraints
        }
        val density = minOf(w / DESIGN_WIDTH_DP, h / DESIGN_HEIGHT_DP)
        appliedDesignDensity = density
        appliedBandsPx = ((w - DESIGN_WIDTH_DP * density) / 2f).toInt() to
            ((h - DESIGN_HEIGHT_DP * density) / 2f).toInt()
        // fontScale: el tamaño de letra de Ajustes agranda todos los textos sin mover el resto.
        CompositionLocalProvider(LocalDensity provides Density(density, fontScale = DriveModes.textSize.scale)) {
            Box(Modifier.size(DESIGN_WIDTH_DP.dp, DESIGN_HEIGHT_DP.dp).clipToBounds()) { content() }
        }
    }
}
