package com.cockpitf30.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.BoxWithConstraints
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

/** Paleta estilo iDrive: negro grafito, texto blanco, gris plata, azul BMW y rojo M. */
object Cockpit {
    val Background = Color(0xFF0B0C10)
    val Surface = Color(0xFF121212)
    val SurfaceHigh = Color(0xFF1C1F26)
    val Blue = Color(0xFF1C69D8)
    val BlueLight = Color(0xFF4FA3FF)
    val Text = Color(0xFFFFFFFF)
    val TextDim = Color(0xFFA7AFBA)
    val Track = Color(0xFF262B33)
    val Alert = Color(0xFFE10600)
    val Ok = Color(0xFF3DDC97)

    /** Efecto cristal de las tarjetas: blanco casi transparente y borde muy fino. */
    val Glass = Color.White.copy(alpha = 0.05f)
    val GlassBorder = Color.White.copy(alpha = 0.08f)

    /** Cobre metalizado y azul acero suave: acentos del menú lateral y de los marcadores. */
    val CopperDark = Color(0xFF4A2412)
    val Copper = Color(0xFFC0764A)
    val CopperLight = Color(0xFFF2BC96)
    val SteelBlueDark = Color(0xFF16263A)
    val SteelBlue = Color(0xFF6D90B5)
    val SteelBlueLight = Color(0xFFC3D7EC)

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
    bodyMedium = style(16, FontWeight.Normal),
    labelLarge = style(15, FontWeight.SemiBold, 1.5f),
    labelMedium = style(13, FontWeight.Medium, 0.5f),
)

@Composable
fun CockpitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Cockpit.Blue,
            secondary = Cockpit.BlueLight,
            background = Cockpit.Background,
            surface = Cockpit.Surface,
            onBackground = Cockpit.Text,
            onSurface = Cockpit.Text,
            error = Cockpit.Alert,
        ),
        typography = typography,
    ) {
        // Sin Surface en la raíz, el Text sin color heredaría negro: lo fijamos aquí para toda la app.
        CompositionLocalProvider(LocalContentColor provides Cockpit.Text) {
            DesignScale(content)
        }
    }
}

/** Lienzo de diseño: toda la interfaz está pensada para 1920x720 dp (la ZQ9109 a 160 dpi). */
const val DESIGN_WIDTH_DP = 1920f
const val DESIGN_HEIGHT_DP = 720f

/** Densidad aplicada por [DesignScale], para mostrarla en Diagnóstico. */
var appliedDesignDensity: Float = 0f
    private set

/**
 * Escala la interfaz para que la pantalla real equivalga siempre al lienzo de diseño, sea cual sea
 * la densidad o el tamaño de letra que traiga la radio (algunas usan 240 dpi y todo se salía).
 */
@Composable
private fun DesignScale(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight || w <= 0f || h <= 0f) {
            content()
            return@BoxWithConstraints
        }
        val density = minOf(w / DESIGN_WIDTH_DP, h / DESIGN_HEIGHT_DP)
        appliedDesignDensity = density
        CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1f), content = content)
    }
}
