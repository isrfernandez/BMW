package com.cockpitf30.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cockpitf30.nav.SpeedCameras
import com.cockpitf30.system.AppSettings
import com.cockpitf30.ui.theme.Cockpit
import com.cockpitf30.vehicle.telemetry.Telemetry
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/** Estado de la luz ambiente, ya cuantizado para no redibujar diez veces por segundo. */
internal enum class GlowMood { CALM, ACCEL, BRAKE, RADAR }

internal fun glowMood(longG: Float, radarAhead: Boolean): Pair<GlowMood, Float> = when {
    radarAhead -> GlowMood.RADAR to 1f
    longG <= -0.25f -> GlowMood.BRAKE to ((-longG - 0.25f) / 0.45f).coerceIn(0.3f, 1f)
    longG >= 0.12f -> GlowMood.ACCEL to ((longG - 0.12f) / 0.28f).coerceIn(0.2f, 1f)
    else -> GlowMood.CALM to 0f
}

/**
 * Luz ambiente: un halo fino en los bordes de la pantalla, del color del modo de conducción.
 * Rodea los cuatro bordes. Se aviva al acelerar, se vuelve rojo al frenar fuerte y late en rojo con un radar delante.
 * Va en su propia capa (graphicsLayer) y solo cambia por pasos de 0,05 g.
 */
@Composable
fun AmbientGlow(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val enabled = remember { AppSettings(ctx).ambientLight }
    if (!enabled) return
    val g: State<Float> = remember {
        Telemetry.state.map { (it.longG / 0.05f).roundToInt() * 0.05f }.distinctUntilChanged()
    }.collectAsState(0f)
    val radar by remember { SpeedCameras.state.map { it.ahead != null }.distinctUntilChanged() }.collectAsState(false)
    val (mood, level) = glowMood(g.value, radar)
    val base = Cockpit.Blue
    val color by animateColorAsState(
        when (mood) { GlowMood.RADAR, GlowMood.BRAKE -> Color(0xFFFF2A1A); GlowMood.ACCEL -> Cockpit.BlueLight; GlowMood.CALM -> base },
        tween(500), label = "glowColor",
    )
    val strength by animateFloatAsState(0.22f + 0.6f * level, tween(450, easing = FastOutSlowInEasing), label = "glowStrength")
    val pulse = if (mood == GlowMood.RADAR) {
        rememberInfiniteTransition(label = "glowPulse").animateFloat(0.45f, 1f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "p")
    } else remember { mutableFloatStateOf(1f) }

    Canvas(modifier.fillMaxSize().graphicsLayer { alpha = (strength * pulse.value).coerceIn(0f, 1f) }) {
        val edge = 22.dp.toPx()
        val c = color
        // Los cuatro bordes: envuelve la pantalla.
        drawRect(Brush.verticalGradient(listOf(c, Color.Transparent), endY = edge), size = Size(size.width, edge), blendMode = BlendMode.Plus)
        drawRect(Brush.horizontalGradient(listOf(c, Color.Transparent), endX = edge), size = Size(edge, size.height), blendMode = BlendMode.Plus)
        drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, c), startX = size.width - edge, endX = size.width),
            topLeft = Offset(size.width - edge, 0f), size = Size(edge, size.height), blendMode = BlendMode.Plus,
        )
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, c), startY = size.height - edge * 1.4f, endY = size.height),
            topLeft = Offset(0f, size.height - edge * 1.4f), size = Size(size.width, edge * 1.4f), blendMode = BlendMode.Plus,
        )
    }
}
