package com.cockpitf30.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cockpitf30.assistant.AssistantPhase
import com.cockpitf30.assistant.AssistantState
import com.cockpitf30.assistant.VoiceAssistant
import com.cockpitf30.assistant.VoiceModel
import com.cockpitf30.ui.theme.Cockpit
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/** Cuánto se queda a la vista la última respuesta. */
private const val REPLY_VISIBLE_MS = 7_000L

private val AssistantState.active get() = phase == AssistantPhase.LISTENING || phase == AssistantPhase.ANSWERING

/** Hora actual que se refresca cada segundo, para ocultar respuestas viejas. */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    return now
}

/** Texto de estado de la tarjeta del Inicio. */
fun assistantStatus(s: AssistantState, now: Long): String = when (s.phase) {
    AssistantPhase.OFF -> "Desactivado · toca para activarlo"
    AssistantPhase.NO_PERMISSION -> "Toca para permitir el micrófono"
    AssistantPhase.DOWNLOADING -> "Descargando la voz (${VoiceModel.SIZE_MB} MB)… ${((s.progress ?: 0f) * 100).toInt()} %"
    AssistantPhase.NO_MODEL -> "Sin voz sin conexión" + (s.error?.let { " ($it)" } ?: "") + " · toca para hablar"
    AssistantPhase.LOADING -> "Preparando el reconocimiento…"
    AssistantPhase.LISTENING -> s.heard?.let { "«$it»" } ?: "Te escucho…"
    AssistantPhase.ANSWERING -> s.reply ?: ""
    AssistantPhase.WAITING, AssistantPhase.PAUSED ->
        s.reply?.takeIf { now - s.replyAt < REPLY_VISIBLE_MS } ?: "Di «Hola BMW» o toca aquí"
}

/** Ilustración de la tarjeta: onda de voz que se mueve con el micrófono y un orbe de cristal. */
@Composable
fun BoxScope.AssistantArt(theme: CardTheme) {
    val s by VoiceAssistant.state.collectAsStateWithLifecycle()
    Box(Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 18.dp).size(width = 240.dp, height = 150.dp)) {
        VoiceWave(theme, s, Modifier.fillMaxSize())
        GlassBadge(theme, Modifier.align(Alignment.Center).size(104.dp)) {
            Canvas(Modifier.size(50.dp)) { microphone(theme, s.phase == AssistantPhase.LISTENING) }
        }
    }
}

/** Ondas senoidales; más altas cuanto más fuerte llega la voz, quietas sin micrófono. */
@Composable
fun VoiceWave(theme: CardTheme, s: AssistantState, modifier: Modifier = Modifier) {
    val listening = s.phase == AssistantPhase.WAITING || s.active
    val amp by animateFloatAsState(
        when {
            s.phase == AssistantPhase.LISTENING -> 0.25f + 0.75f * s.level
            s.phase == AssistantPhase.ANSWERING -> 0.55f
            listening -> 0.08f + 0.5f * s.level
            else -> 0.04f
        },
        tween(120), label = "amp",
    )
    val t by rememberInfiniteTransition(label = "wave").animateFloat(
        0f, (2 * PI).toFloat(), infiniteRepeatable(tween(if (s.active) 1400 else 3200, easing = LinearEasing)), label = "t",
    )
    Canvas(modifier) {
        val colors = listOf(theme.to, theme.from, Color.White)
        for (k in 0 until 3) {
            val path = Path()
            val mid = size.height / 2
            val steps = 60
            for (i in 0..steps) {
                val x = size.width * i / steps
                val u = i.toFloat() / steps
                // Envolvente: más alta en el centro, nula en los extremos.
                val env = sin(PI * u).toFloat()
                val y = mid + env * amp * size.height * 0.42f * sin(u * 9f + t * (1f + k * 0.35f) + k * 1.7f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, colors[k].copy(alpha = 0.55f - k * 0.12f), style = Stroke(width = (2.6f - k * 0.6f).dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

private fun DrawScope.microphone(theme: CardTheme, hot: Boolean) {
    val w = size.width
    val h = size.height
    val brush = Brush.linearGradient(listOf(Color.White, if (hot) Color.White else theme.to))
    // Cápsula
    drawRoundRect(brush, Offset(w * 0.34f, h * 0.04f), androidx.compose.ui.geometry.Size(w * 0.32f, h * 0.56f), androidx.compose.ui.geometry.CornerRadius(w * 0.16f))
    // Horquilla
    val arc = Stroke(w * 0.07f, cap = StrokeCap.Round)
    drawArc(brush, 0f, 180f, false, Offset(w * 0.20f, h * 0.18f), androidx.compose.ui.geometry.Size(w * 0.60f, h * 0.56f), style = arc)
    drawLine(brush, Offset(w * 0.5f, h * 0.74f), Offset(w * 0.5f, h * 0.92f), w * 0.07f, StrokeCap.Round)
    drawLine(brush, Offset(w * 0.32f, h * 0.94f), Offset(w * 0.68f, h * 0.94f), w * 0.07f, StrokeCap.Round)
}

/** Contenido de la tarjeta del Inicio. */
@Composable
fun AssistantCardBody() {
    val s by VoiceAssistant.state.collectAsStateWithLifecycle()
    val now = rememberNow()
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
        Text("ASISTENTE DE VOZ", style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim)
        Box(Modifier.weight(1f))
        Text("Hola BMW", style = MaterialTheme.typography.headlineSmall)
        Text(
            assistantStatus(s, now), style = MaterialTheme.typography.bodyMedium,
            color = if (s.active) Cockpit.Text else Cockpit.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Burbuja que aparece abajo, en cualquier pantalla, mientras el asistente escucha o responde:
 * así se ve qué ha entendido aunque no se esté en el Inicio.
 */
@Composable
fun BoxScope.AssistantOverlay() {
    val s by VoiceAssistant.state.collectAsStateWithLifecycle()
    AnimatedVisibility(
        s.active,
        Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
    ) {
        Row(
            Modifier.width(820.dp).glassPanel(28.dp, CardTheme.ASSIST.to, smoke = 0.94f).padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(Modifier.size(width = 150.dp, height = 64.dp)) { VoiceWave(CardTheme.ASSIST, s, Modifier.fillMaxSize()) }
            Column(Modifier.weight(1f)) {
                val heard = s.heard
                if (s.phase == AssistantPhase.LISTENING) {
                    Text(if (heard.isNullOrBlank()) "Te escucho…" else "«$heard»", style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (s.engine == "Google") "Con el reconocedor de Google" else "Di qué quieres: gasolineras, vehículo, tiempo…", style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim)
                } else {
                    if (!heard.isNullOrBlank()) Text("«$heard»", style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.reply.orEmpty(), Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
