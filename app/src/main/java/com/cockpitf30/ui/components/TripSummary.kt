package com.cockpitf30.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cockpitf30.ui.screens.duration
import com.cockpitf30.ui.screens.km
import com.cockpitf30.ui.theme.Cockpit
import com.cockpitf30.ui.theme.numeric
import com.cockpitf30.vehicle.trip.TripComputer
import com.cockpitf30.vehicle.trip.TripSummaryInfo
import com.cockpitf30.vehicle.trip.WeekSummary
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

private val es = Locale("es", "ES")
private const val AUTO_CLOSE_MS = 40_000L

/** Color de la nota de eficiencia: verde, ámbar o rojo. */
fun scoreColor(score: Int?): Color = when {
    score == null -> Cockpit.TextDim
    score >= 80 -> Color(0xFF3DDC97)
    score >= 60 -> Color(0xFFFFC94D)
    else -> Color(0xFFFF6B5A)
}

/**
 * Resumen del trayecto al parar el motor: nota de eficiencia en un anillo, los datos del viaje,
 * el consejo y la semana. Se cierra tocando, con OK, con atrás o solo a los 40 s.
 */
@Composable
fun BoxScope.TripSummaryOverlay() {
    val info by TripComputer.summary.collectAsStateWithLifecycle()
    AnimatedVisibility(
        info != null, Modifier.matchParentSize(),
        enter = fadeIn(tween(300)) + scaleIn(tween(380, easing = FastOutSlowInEasing), initialScale = 0.94f),
        exit = fadeOut(tween(250)) + scaleOut(tween(250), targetScale = 0.97f),
    ) {
        val last = remember { arrayOfNulls<TripSummaryInfo>(1) }
        info?.let { last[0] = it }
        val s = last[0] ?: return@AnimatedVisibility
        BackHandler { TripComputer.dismissSummary() }
        LaunchedEffect(s.at) {
            delay(AUTO_CLOSE_MS)
            TripComputer.dismissSummary()
        }
        LaunchedEffect(s.at) {
            // Una frase corta por voz (si el asistente está libre).
            val text = "Trayecto de ${km(s.trip.distanceKm).replace('.', ',')} kilómetros en ${duration(s.trip.durationMillis)}." +
                (s.score?.let { " Eficiencia ${it.score} sobre 100. ${it.tip}" } ?: "")
            com.cockpitf30.assistant.VoiceAssistant.announceIfFree(text)
        }
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f))
                .pointerInput(Unit) { detectTapGestures { TripComputer.dismissSummary() } },
            contentAlignment = Alignment.Center,
        ) {
            SummaryPanel(s)
        }
    }
}

@Composable
internal fun SummaryPanel(s: TripSummaryInfo) {
    val close = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(10) { if (runCatching { close.requestFocus() }.isSuccess) return@LaunchedEffect; delay(50) } }
    val score = s.score
    val accent = scoreColor(score?.score)
    Column(
        Modifier.width(1240.dp).height(560.dp).glassPanel(28.dp, accent, smoke = 0.92f).padding(horizontal = 40.dp, vertical = 30.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (s.trip.finished) "ÚLTIMO TRAYECTO" else "TRAYECTO TERMINADO", style = MaterialTheme.typography.labelLarge, color = Cockpit.TextDim)
                Spacer(Modifier.height(6.dp))
                MStripe()
            }
            FocusTile(onClick = { TripComputer.dismissSummary() }, modifier = Modifier.focusRequester(close)) {
                Text("Cerrar", Modifier.padding(horizontal = 26.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            // Nota de eficiencia.
            Column(Modifier.width(300.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                ScoreRing(score?.score, Modifier.size(250.dp))
                Spacer(Modifier.height(14.dp))
                Text(
                    score?.tip ?: "Trayecto corto: sin nota de eficiencia.",
                    style = MaterialTheme.typography.bodyLarge, color = Cockpit.Text, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            // Datos.
            Column(Modifier.width(380.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val cons = s.trip.avgConsumption(s.tankLiters)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Datum("Distancia", km(s.trip.distanceKm), "km", Modifier.weight(1f))
                    Datum("Tiempo", duration(s.trip.durationMillis), null, Modifier.weight(1f))
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Datum("Consumo", cons?.let { "%.1f".format(es, it) } ?: "--", "l/100", Modifier.weight(1f))
                    Datum("Vel. media", s.trip.avgSpeedKmh?.toString() ?: "--", "km/h", Modifier.weight(1f))
                }
            }
            // Semana.
            WeekChart(s.week, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun Datum(label: String, value: String, unit: String?, modifier: Modifier) {
    Column(modifier.fillMaxHeight().glassPanel(16.dp).padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.Center) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = numeric(38, FontWeight.Medium), maxLines = 1)
            if (unit != null) Text(" $unit", style = numeric(16), color = Cockpit.TextDim, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

/** Anillo de la nota: arco que se llena de 0 a la nota con el color del resultado. */
@Composable
fun ScoreRing(score: Int?, modifier: Modifier) {
    val color = scoreColor(score)
    val fill = remember { Animatable(0f) }
    LaunchedEffect(score) { fill.snapTo(0f); fill.animateTo((score ?: 0) / 100f, tween(1100, easing = FastOutSlowInEasing)) }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.075f
            val inset = stroke
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val tl = Offset(inset, inset)
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.16f), Color.Transparent)), size.minDimension / 2)
            drawArc(Cockpit.Ink.copy(alpha = 0.08f), 135f, 270f, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val sweep = 270f * fill.value
            if (sweep > 0.5f) {
                drawArc(color.copy(alpha = 0.30f), 135f, sweep, false, tl, arcSize, style = Stroke(stroke * 2.2f, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
                drawArc(Brush.sweepGradient(listOf(color.copy(alpha = 0.7f), color, Color.White, color.copy(alpha = 0.7f))), 135f, sweep, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // La cifra sube con el arco.
            Text(score?.let { (fill.value * 100).roundToInt().coerceAtMost(it).toString() } ?: "--", style = numeric(84, FontWeight.Bold), color = Cockpit.Text)
            Text("EFICIENCIA", style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

/**
 * Semana de lunes a domingo: barras de km por día (hoy resaltado) y, debajo, totales y la
 * comparación con la semana anterior.
 */
@Composable
fun WeekChart(w: WeekSummary, modifier: Modifier, title: String = "Esta semana") {
    Column(modifier.glassPanel(16.dp).padding(horizontal = 20.dp, vertical = 16.dp)) {
        PanelTitle(title, trailing = {
            w.avgScore?.let { Text("media $it", style = numeric(16, FontWeight.SemiBold), color = scoreColor(it)) }
        })
        Spacer(Modifier.height(10.dp))
        val labels = listOf("L", "M", "X", "J", "V", "S", "D")
        val max = (w.kmPerDay.maxOrNull() ?: 0.0).coerceAtLeast(10.0)
        val tm = androidx.compose.ui.text.rememberTextMeasurer()
        val label = numeric(14, FontWeight.SemiBold)
        val accent = Cockpit.BlueLight
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val n = 7
            val gap = size.width * 0.035f
            val bw = (size.width - gap * (n - 1)) / n
            val labelH = 22.dp.toPx()
            val top = 22.dp.toPx()
            val chartH = size.height - labelH - top
            for (i in 0 until n) {
                val x = i * (bw + gap)
                val v = w.kmPerDay[i]
                val h = (v / max * chartH).toFloat().coerceAtLeast(if (v > 0) 4.dp.toPx() else 0f)
                val today = i == w.today
                val future = i > w.today
                drawRoundRect(Cockpit.Ink.copy(alpha = if (future) 0.03f else 0.06f), Offset(x, top), Size(bw, chartH), CornerRadius(6.dp.toPx()))
                if (h > 0f) {
                    val c = if (today) Cockpit.Ink else accent
                    drawRoundRect(
                        Brush.verticalGradient(listOf(c, accent.copy(alpha = 0.35f)), startY = top + chartH - h, endY = top + chartH),
                        Offset(x, top + chartH - h), Size(bw, h), CornerRadius(6.dp.toPx()),
                    )
                    if (today) drawRoundRect(accent.copy(alpha = 0.35f), Offset(x - 2.dp.toPx(), top + chartH - h - 2.dp.toPx()), Size(bw + 4.dp.toPx(), h + 4.dp.toPx()), CornerRadius(8.dp.toPx()), style = Stroke(3.dp.toPx()), blendMode = BlendMode.Plus)
                    if (v >= 1) {
                        val t = tm.measure(v.roundToInt().toString(), label)
                        drawText(t, Cockpit.Ink.copy(alpha = 0.85f), Offset(x + bw / 2 - t.size.width / 2, top + chartH - h - t.size.height - 2.dp.toPx()))
                    }
                }
                val l = tm.measure(labels[i], label)
                drawText(l, if (today) Cockpit.Ink else Cockpit.Ink.copy(alpha = 0.5f), Offset(x + bw / 2 - l.size.width / 2, size.height - l.size.height))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(km(w.km), style = numeric(30, FontWeight.Medium))
            Text(" km · ${w.trips} trayecto${if (w.trips == 1) "" else "s"}", style = numeric(16), color = Cockpit.TextDim, modifier = Modifier.padding(bottom = 5.dp))
            Spacer(Modifier.weight(1f))
            w.avgConsumption?.let { Text("%.1f l/100".format(es, it), style = numeric(16), color = Cockpit.TextDim, modifier = Modifier.padding(bottom = 5.dp)) }
        }
        val prev = w.previousKm
        Text(
            when {
                prev < 1 -> "Sin datos de la semana anterior"
                else -> {
                    val pct = ((w.km - prev) / prev * 100).roundToInt()
                    "${if (pct >= 0) "+" else ""}$pct % frente a la semana anterior (${km(prev)} km)"
                }
            },
            style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
