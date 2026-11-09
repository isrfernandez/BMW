package com.cockpitf30.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cockpitf30.ui.components.FocusTile
import com.cockpitf30.ui.components.InitialFocus
import com.cockpitf30.ui.components.PanelTitle
import com.cockpitf30.ui.components.TopBar
import com.cockpitf30.ui.components.TriangleBackground
import com.cockpitf30.ui.components.glassPanel
import com.cockpitf30.ui.theme.Cockpit
import com.cockpitf30.ui.theme.DriveMode
import com.cockpitf30.ui.theme.DriveModes
import com.cockpitf30.ui.theme.ModePalette
import com.cockpitf30.ui.theme.NightDetector
import com.cockpitf30.ui.theme.numeric
import com.cockpitf30.vehicle.telemetry.AltPoint
import com.cockpitf30.vehicle.telemetry.GSource
import com.cockpitf30.vehicle.telemetry.Telemetry
import com.cockpitf30.vehicle.telemetry.TelemetryState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Escalas que pidió el usuario (su motor anda por 190 CV / 400 Nm). */
private const val MAX_CV = 250f
private const val MAX_NM = 500f
private const val MAX_G = 1.2f

private val es = Locale("es")

/**
 * Rendimiento: G-meter, potencia y par estimados (relojes estilo M), perfil de altitud tipo
 * «etapa» con la pendiente, y el selector de modos de conducción.
 */
@Composable
fun PerformanceScreen() {
    val t by Telemetry.state.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    InitialFocus(first)

    TriangleBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Rendimiento") {
                Text(
                    "Fuerzas G: ${t.gSource.label} · Potencia y par estimados",
                    style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim,
                )
            }
            Column(
                Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(Modifier.fillMaxWidth().height(372.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    GMeterPanel(t, Modifier.width(420.dp).fillMaxHeight())
                    PowerPanel(t, Modifier.weight(1f).fillMaxHeight())
                    ModePanel(first, Modifier.width(330.dp).fillMaxHeight())
                }
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AltitudePanel(t, Modifier.weight(1f).fillMaxHeight())
                    AltitudeStats(t, Modifier.width(330.dp).fillMaxHeight())
                }
            }
        }
    }
}

// ---------------------------------------------------------------- G-meter

@Composable
private fun GMeterPanel(t: TelemetryState, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val label = numeric(13, FontWeight.Medium)
    Column(modifier.glassPanel(16.dp).padding(16.dp)) {
        PanelTitle("Fuerzas G")
        Row(Modifier.fillMaxSize().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(270.dp)) { gMeter(t, measurer, label) }
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GValue("Acel.", t.longG.coerceAtLeast(0f), t.peakAccelG)
                GValue("Freno", (-t.longG).coerceAtLeast(0f), t.peakBrakeG)
                GValue("Curva", abs(t.latG), t.peakLatG)
                Spacer(Modifier.weight(1f))
                if (t.gSource == GSource.SENSOR) {
                    SmallAction(if (t.calibrated) "Recalibrar" else "Calibrar", Modifier.fillMaxWidth().height(40.dp), Telemetry::calibrate)
                }
            }
        }
    }
}

@Composable
private fun GValue(label: String, now: Float, peak: Float) {
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("%.2f".format(es, now), style = numeric(26, FontWeight.Medium))
            Text(" G", style = numeric(15), color = Cockpit.TextDim, modifier = Modifier.padding(bottom = 4.dp))
        }
        Text("máx %.2f".format(es, peak), style = numeric(13), color = Cockpit.BlueLight)
    }
}

private fun DrawScope.gMeter(t: TelemetryState, tm: TextMeasurer, label: TextStyle) {
    val c = center
    val r = size.minDimension / 2 - 4.dp.toPx()
    val accent = Cockpit.BlueLight
    drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.35f)), c, r), r, c)
    // Anillos cada 0,4 G.
    listOf(0.4f, 0.8f, 1.2f).forEach { g ->
        val rr = r * g / MAX_G
        drawCircle(Color.White.copy(alpha = if (g == 1.2f) 0.35f else 0.16f), rr, c, style = Stroke(1.dp.toPx(), pathEffect = if (g == 1.2f) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
        val txt = tm.measure("%.1f".format(es, g), label)
        drawText(txt, Color.White.copy(alpha = 0.45f), Offset(c.x + rr * 0.71f + 2.dp.toPx(), c.y - rr * 0.71f - txt.size.height))
    }
    drawLine(Color.White.copy(alpha = 0.2f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
    drawLine(Color.White.copy(alpha = 0.2f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())
    fun at(long: Float, lat: Float): Offset {
        val x = lat / MAX_G; val y = long / MAX_G
        val len = kotlin.math.sqrt(x * x + y * y)
        val k = if (len > 1f) 1f / len else 1f
        return Offset(c.x + x * k * r, c.y - y * k * r)
    }
    // Estela de los últimos segundos, cada vez más tenue.
    val trail = t.trail
    for (i in 1 until trail.size) {
        val a = i / trail.size.toFloat()
        drawLine(accent.copy(alpha = a * 0.8f), at(trail[i - 1].first, trail[i - 1].second), at(trail[i].first, trail[i].second), (1.5f + a * 3f).dp.toPx(), StrokeCap.Round)
    }
    val p = at(t.longG, t.latG)
    drawCircle(accent.copy(alpha = 0.35f), 16.dp.toPx(), p, blendMode = BlendMode.Plus)
    drawCircle(Brush.radialGradient(listOf(Color.White, accent), p, 8.dp.toPx()), 8.dp.toPx(), p)
    // Rótulos de los ejes.
    val up = tm.measure("ACEL", label); drawText(up, Color.White.copy(alpha = 0.5f), Offset(c.x - up.size.width / 2, c.y - r + 4.dp.toPx()))
    val dn = tm.measure("FRENO", label); drawText(dn, Color.White.copy(alpha = 0.5f), Offset(c.x - dn.size.width / 2, c.y + r - dn.size.height - 4.dp.toPx()))
}

// ---------------------------------------------------------------- Potencia y par

@Composable
private fun PowerPanel(t: TelemetryState, modifier: Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val needle = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)
    val cv by animateFloatAsState((t.powerCv ?: 0f) / MAX_CV, needle, label = "cv")
    val nm by animateFloatAsState((t.torqueNm ?: 0f) / MAX_NM, needle, label = "nm")
    val tick = numeric(16, FontWeight.SemiBold)
    val big = numeric(54, FontWeight.Medium)
    val unit = numeric(17, FontWeight.Medium)
    Column(modifier.glassPanel(16.dp).padding(16.dp)) {
        PanelTitle("Potencia y par entregados", trailing = {
            Text("estimación física · 1.600 kg", style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim)
        })
        Row(Modifier.fillMaxSize().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                dial(cv, t.peakCv / MAX_CV, MAX_CV, 25f, 50f, if (t.powerCv == null) "--" else "${t.powerCv.roundToInt()}", "CV", "POTENCIA", "máx ${t.peakCv.roundToInt()}", measurer, tick, big, unit)
            }
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                dial(nm, t.peakNm / MAX_NM, MAX_NM, 50f, 100f, if (t.torqueNm == null) "--" else "${t.torqueNm.roundToInt()}", "Nm", "PAR", "máx ${t.peakNm.roundToInt()}", measurer, tick, big, unit)
            }
        }
    }
}

/**
 * Reloj estilo M: arco de 270°, marcas finas y gruesas con cifras, relleno en degradado del color
 * del modo con el último 15 % en rojo, aguja con brillo y la cifra en el centro.
 */
private fun DrawScope.dial(
    frac: Float, peak: Float, max: Float, minor: Float, major: Float, value: String, unitText: String, title: String, peakText: String,
    tm: TextMeasurer, tick: TextStyle, big: TextStyle, unit: TextStyle,
) {
    val f = frac.coerceIn(0f, 1.02f)
    val c = Offset(size.width / 2, size.height * 0.53f)
    val r = min(size.width, size.height) * 0.46f
    val start = 135f
    val sweep = 270f
    val band = 12.dp.toPx()
    val tl = Offset(c.x - r, c.y - r)
    val sz = Size(r * 2, r * 2)
    // Fondo del reloj.
    drawCircle(Brush.radialGradient(listOf(Color(0xFF0D1118), Color(0xFF05070B)), c, r * 1.05f), r * 1.05f, c)
    drawCircle(Brush.sweepGradient(listOf(Cockpit.CopperLight, Cockpit.SteelBlueDark, Cockpit.SteelBlueLight, Cockpit.CopperDark, Cockpit.CopperLight), c), r * 1.05f, c, style = Stroke(2.dp.toPx()))
    drawArc(Color.White.copy(alpha = 0.07f), start, sweep, false, tl + Offset(band / 2, band / 2), Size(sz.width - band, sz.height - band), style = Stroke(band))
    // Zona roja.
    drawArc(Cockpit.Alert.copy(alpha = 0.55f), start + sweep * 0.85f, sweep * 0.15f, false, tl + Offset(band / 2, band / 2), Size(sz.width - band, sz.height - band), style = Stroke(band * 0.35f))
    // Relleno.
    if (f > 0f) {
        drawArc(
            Brush.sweepGradient(0f to Cockpit.Blue, 0.375f to Cockpit.Blue, 0.75f to Cockpit.BlueLight, 1f to Cockpit.Blue, center = c),
            start, sweep * min(f, 1f), false, tl + Offset(band / 2, band / 2), Size(sz.width - band, sz.height - band), style = Stroke(band),
        )
        drawArc(Cockpit.BlueLight.copy(alpha = 0.35f), start, sweep * min(f, 1f), false, tl + Offset(band / 2, band / 2), Size(sz.width - band, sz.height - band), style = Stroke(band * 2.2f), blendMode = BlendMode.Plus)
    }
    // Marcas.
    var v = 0f
    while (v <= max + 0.1f) {
        val isMajor = abs(v / major - (v / major).roundToInt()) < 0.01f
        val a = Math.toRadians((start + sweep * v / max).toDouble())
        val r0 = r - band - (if (isMajor) 14.dp.toPx() else 7.dp.toPx())
        val r1 = r - band - 2.dp.toPx()
        val red = v / max >= 0.85f
        drawLine(
            (if (red) Cockpit.Alert else Color.White).copy(alpha = if (isMajor) 0.95f else 0.45f),
            Offset(c.x + r0 * cos(a).toFloat(), c.y + r0 * sin(a).toFloat()), Offset(c.x + r1 * cos(a).toFloat(), c.y + r1 * sin(a).toFloat()),
            (if (isMajor) 3f else 1.5f).dp.toPx(), StrokeCap.Round,
        )
        if (isMajor) {
            val txt = tm.measure("${v.roundToInt()}", tick)
            val rt = r0 - 16.dp.toPx()
            drawText(txt, if (red) Cockpit.Alert else Color.White.copy(alpha = 0.85f), Offset(c.x + rt * cos(a).toFloat() - txt.size.width / 2, c.y + rt * sin(a).toFloat() - txt.size.height / 2))
        }
        v += minor
    }
    // Marca del máximo alcanzado.
    if (peak > 0.01f) {
        val a = Math.toRadians((start + sweep * min(peak, 1f)).toDouble())
        val p = Offset(c.x + (r - band / 2) * cos(a).toFloat(), c.y + (r - band / 2) * sin(a).toFloat())
        drawCircle(Color.White, 4.dp.toPx(), p)
    }
    // Aguja.
    rotate(start + sweep * min(f, 1f) + 90f, c) {
        val needle = Path().apply {
            moveTo(c.x - 3.dp.toPx(), c.y - r * 0.30f)
            lineTo(c.x, c.y - (r - band * 0.6f))
            lineTo(c.x + 3.dp.toPx(), c.y - r * 0.30f)
            close()
        }
        drawPath(needle, Cockpit.BlueLight.copy(alpha = 0.5f), style = Stroke(6.dp.toPx()), blendMode = BlendMode.Plus)
        drawPath(needle, Brush.verticalGradient(listOf(Color.White, Cockpit.BlueLight), c.y - r, c.y))
    }
    drawCircle(Brush.radialGradient(listOf(Color(0xFF2A3038), Color(0xFF0A0C10)), c, r * 0.27f), r * 0.27f, c)
    drawCircle(Color.White.copy(alpha = 0.15f), r * 0.27f, c, style = Stroke(1.dp.toPx()))
    // Cifras.
    val vt = tm.measure(value, big)
    val tt = tm.measure(title, unit.copy(fontSize = unit.fontSize * 0.85f))
    drawText(tt, Cockpit.TextDim, Offset(c.x - tt.size.width / 2, c.y - vt.size.height / 2 - tt.size.height + 6.dp.toPx()))
    drawText(vt, Color.White, Offset(c.x - vt.size.width / 2, c.y - vt.size.height / 2))
    val ut = tm.measure(unitText, unit)
    drawText(ut, Cockpit.BlueLight, Offset(c.x - ut.size.width / 2, c.y + r * 0.30f))
    val pt = tm.measure(peakText, unit.copy(fontSize = unit.fontSize * 0.85f))
    drawText(pt, Color.White.copy(alpha = 0.6f), Offset(c.x - pt.size.width / 2, c.y + r * 0.62f))
}

// ---------------------------------------------------------------- Modos de conducción

@Composable
private fun ModePanel(first: FocusRequester, modifier: Modifier) {
    val ctx = LocalContext.current
    Column(modifier.glassPanel(16.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        PanelTitle("Modo de conducción")
        DriveMode.entries.forEachIndexed { i, mode ->
            ModeButton(
                mode, DriveModes.current == mode,
                Modifier.fillMaxWidth().weight(1f).let { if (i == 0) it.focusRequester(first) else it },
            ) { DriveModes.select(ctx, mode) }
        }
        val night = when {
            !DriveModes.autoNight -> "Night automático: no"
            DriveModes.nightWithHeadlights -> "Night solo: anochecer y faros"
            else -> "Night solo: al anochecer"
        }
        FocusTile(
            onClick = {
                // No → anochecer y faros → solo anochecer → no.
                when {
                    !DriveModes.autoNight -> { DriveModes.setAutoNight(ctx, true); DriveModes.setNightWithHeadlights(ctx, true) }
                    DriveModes.nightWithHeadlights -> DriveModes.setNightWithHeadlights(ctx, false)
                    else -> DriveModes.setAutoNight(ctx, false)
                }
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp)) {
                Text(night, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text("Ahora: ${NightDetector.reason}", style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim, maxLines = 1)
            }
        }
    }
}

private fun paletteOf(mode: DriveMode): ModePalette = when (mode) {
    DriveMode.NORMAL -> DriveModes.NORMAL_PALETTE
    DriveMode.SPORT -> DriveModes.SPORT_PALETTE
    DriveMode.NIGHT -> DriveModes.NIGHT_PALETTE
}

/**
 * Botón de modo con volumen: placa biselada que se inclina en 3D, da media vuelta al elegirla y
 * late suavemente mientras está activa. Cada uno lleva su propio color y emblema.
 */
@Composable
internal fun ModeButton(mode: DriveMode, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val flip = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) {
            flip.snapTo(-180f)
            flip.animateTo(0f, tween(650, easing = FastOutSlowInEasing))
        }
    }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.55f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse), label = "p",
    )
    val glow = if (active) pulse else 0f
    FocusTile(onClick = onClick, selected = active, modifier = modifier.graphicsLayer {
        rotationX = flip.value
        rotationY = if (active) 0f else 6f
        cameraDistance = 14f * density
    }) { focused -> ModeFace(mode, active, focused, glow) }
}

/** Cara del botón de modo (sin foco propio): también la usa la tarjeta del Inicio. */
@Composable
internal fun ModeFace(mode: DriveMode, active: Boolean, lit: Boolean, glow: Float, hint: String? = null) {
    val p = paletteOf(mode)
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) { modePlate(mode, p, active || lit, glow) }
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(62.dp))
            Column(Modifier.weight(1f)) {
                Text(mode.label.uppercase(), style = numeric(24, FontWeight.Bold).copy(letterSpacing = 3.sp), color = if (active) Color.White else p.blueLight)
                Text(
                    hint ?: if (mode == DriveMode.NIGHT && DriveModes.nightIsAuto) "Puesto solo: ${NightDetector.reason}" else mode.subtitle,
                    style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim, maxLines = 1,
                )
            }
        }
    }
}

private fun DrawScope.modePlate(mode: DriveMode, p: ModePalette, lit: Boolean, glow: Float) {
    val w = size.width; val h = size.height
    val r = CornerRadius(12.dp.toPx())
    // Placa con relieve: luz arriba, sombra abajo, color del modo de fondo.
    drawRoundRect(Brush.verticalGradient(listOf(p.steelDark.copy(alpha = 0.95f), Color(0xFF07090D))), cornerRadius = r)
    drawRoundRect(Brush.horizontalGradient(listOf(p.blue.copy(alpha = if (lit) 0.45f else 0.22f), Color.Transparent), endX = w * 0.7f), cornerRadius = r)
    drawRoundRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.22f), 0.45f to Color.White.copy(alpha = 0.04f), 0.5f to Color.Transparent), cornerRadius = r)
    drawRoundRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)), startY = h * 0.6f), cornerRadius = r)
    if (glow > 0f) drawRoundRect(p.blueLight.copy(alpha = 0.18f * glow), cornerRadius = r, blendMode = BlendMode.Plus)
    // Emblema a la izquierda.
    val c = Offset(16.dp.toPx() + 26.dp.toPx(), h / 2)
    val er = min(h * 0.34f, 24.dp.toPx())
    drawCircle(Brush.radialGradient(listOf(p.blueLight.copy(alpha = 0.5f + 0.4f * glow), Color.Transparent), c, er * 1.8f), er * 1.8f, c, blendMode = BlendMode.Plus)
    drawCircle(Brush.linearGradient(listOf(p.steelLight, p.steel, p.steelDark), Offset(c.x - er, c.y - er), Offset(c.x + er, c.y + er)), er, c)
    drawCircle(Color(0xFF07090D), er * 0.82f, c)
    when (mode) {
        DriveMode.NORMAL -> {
            // Onda suave.
            val path = Path().apply {
                moveTo(c.x - er * 0.6f, c.y)
                cubicTo(c.x - er * 0.3f, c.y - er * 0.5f, c.x - er * 0.1f, c.y - er * 0.5f, c.x, c.y)
                cubicTo(c.x + er * 0.1f, c.y + er * 0.5f, c.x + er * 0.3f, c.y + er * 0.5f, c.x + er * 0.6f, c.y)
            }
            drawPath(path, p.blueLight, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
        DriveMode.SPORT -> {
            // Tres chevrons hacia delante.
            for (k in 0..2) {
                val x = c.x - er * 0.5f + k * er * 0.38f
                val ch = Path().apply { moveTo(x, c.y - er * 0.42f); lineTo(x + er * 0.28f, c.y); lineTo(x, c.y + er * 0.42f) }
                drawPath(ch, p.blueLight.copy(alpha = 0.5f + 0.25f * k), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        DriveMode.NIGHT -> {
            // Luna creciente.
            drawCircle(p.blueLight, er * 0.48f, c)
            drawCircle(Color(0xFF07090D), er * 0.42f, Offset(c.x + er * 0.24f, c.y - er * 0.14f))
        }
    }
    // Filo metálico.
    drawRoundRect(
        Brush.linearGradient(listOf(p.copperLight.copy(alpha = 0.7f), p.steel.copy(alpha = 0.2f), p.steelLight.copy(alpha = 0.6f)), Offset.Zero, Offset(w, h)),
        cornerRadius = r, style = Stroke(1.5.dp.toPx()),
    )
}

// ---------------------------------------------------------------- Altitud

@Composable
private fun AltitudePanel(t: TelemetryState, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val label = numeric(13, FontWeight.Medium)
    val badge = numeric(16, FontWeight.SemiBold)
    Column(modifier.glassPanel(16.dp).padding(16.dp)) {
        PanelTitle("Perfil de altitud", trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf("< 3 %" to GradeEasy, "3–6 %" to GradeMid, "6–9 %" to GradeHard, "> 9 %" to GradeSteep).forEach { (s, col) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(10.dp)) { drawRoundRect(col, cornerRadius = CornerRadius(3.dp.toPx())) }
                        Text(" $s", style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim)
                    }
                }
            }
        })
        Box(Modifier.fillMaxSize().padding(top = 8.dp)) {
            if (t.profile.size < 2) {
                Text(
                    if (t.altitudeM == null) "Esperando altitud del GPS…" else "El perfil se dibuja al circular",
                    style = MaterialTheme.typography.bodyLarge, color = Cockpit.TextDim, modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Canvas(Modifier.fillMaxSize()) { stageMap(t.profile, measurer, label, badge) }
            }
        }
    }
}

private val GradeEasy = Color(0xFF3DDC97)
private val GradeMid = Color(0xFFFFD54F)
private val GradeHard = Color(0xFFFF9800)
private val GradeSteep = Color(0xFFE5393D)

private fun gradeColor(pct: Float): Color {
    val g = abs(pct)
    return when {
        g < 3f -> GradeEasy
        g < 6f -> GradeMid
        g < 9f -> GradeHard
        else -> GradeSteep
    }
}

/** Perfil tipo etapa ciclista: relieve relleno por tramos del color de su pendiente y la posición actual. */
private fun DrawScope.stageMap(points: List<AltPoint>, tm: TextMeasurer, label: TextStyle, badge: TextStyle) {
    // Como mucho los últimos 20 km.
    val last = points.last()
    val pts = points.filter { last.km - it.km <= 20f }
    val km0 = pts.first().km
    val spanKm = max(last.km - km0, 1f)
    var lo = pts.minOf { it.altM }; var hi = pts.maxOf { it.altM }
    if (hi - lo < 60f) { val mid = (hi + lo) / 2; lo = mid - 30f; hi = mid + 30f }
    lo -= (hi - lo) * 0.15f; hi += (hi - lo) * 0.25f
    val left = 52.dp.toPx(); val right = size.width - 90.dp.toPx(); val top = 4.dp.toPx(); val bottom = size.height - 22.dp.toPx()
    fun x(km: Float) = left + (km - km0) / spanKm * (right - left)
    fun y(a: Float) = bottom - (a - lo) / (hi - lo) * (bottom - top)
    // Rejilla y cotas.
    val step = niceStep((hi - lo) / 4f)
    var a = (lo / step).toInt() * step
    while (a <= hi) {
        if (a >= lo) {
            drawLine(Color.White.copy(alpha = 0.08f), Offset(left, y(a)), Offset(right, y(a)), 1f)
            val txt = tm.measure("${a.roundToInt()}", label)
            drawText(txt, Color.White.copy(alpha = 0.45f), Offset(left - txt.size.width - 8.dp.toPx(), y(a) - txt.size.height / 2))
        }
        a += step
    }
    // Tramos de unos 250 m, coloreados por pendiente.
    var i = 0
    while (i < pts.size - 1) {
        var j = i + 1
        while (j < pts.size - 1 && pts[j].km - pts[i].km < 0.25f) j++
        val p0 = pts[i]; val p1 = pts[j]
        val grade = (p1.altM - p0.altM) / max((p1.km - p0.km) * 1000f, 1f) * 100f
        val col = gradeColor(grade)
        val poly = Path().apply {
            moveTo(x(p0.km), bottom)
            for (k in i..j) lineTo(x(pts[k].km), y(pts[k].altM))
            lineTo(x(p1.km), bottom)
            close()
        }
        drawPath(poly, Brush.verticalGradient(listOf(col.copy(alpha = 0.85f), col.copy(alpha = 0.18f)), startY = y(max(p0.altM, p1.altM)), endY = bottom))
        i = j
    }
    // Contorno.
    val line = Path().apply { pts.forEachIndexed { k, p -> if (k == 0) moveTo(x(p.km), y(p.altM)) else lineTo(x(p.km), y(p.altM)) } }
    drawPath(line, Color.White.copy(alpha = 0.9f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    // Kilómetros abajo.
    val kmStep = niceStep(spanKm / 5f)
    var k = kotlin.math.ceil(km0 / kmStep) * kmStep
    while (k <= last.km) {
        val txt = tm.measure("${"%.1f".format(es, k - km0).removeSuffix(",0")} km", label)
        drawText(txt, Color.White.copy(alpha = 0.4f), Offset(x(k) - txt.size.width / 2, bottom + 4.dp.toPx()))
        k += kmStep
    }
    // Posición actual.
    val px = x(last.km); val py = y(last.altM)
    drawLine(Color.White.copy(alpha = 0.5f), Offset(px, top), Offset(px, bottom), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f)))
    drawCircle(Cockpit.BlueLight.copy(alpha = 0.4f), 12.dp.toPx(), Offset(px, py), blendMode = BlendMode.Plus)
    drawCircle(Color.White, 5.dp.toPx(), Offset(px, py))
    val bt = tm.measure("${last.altM.roundToInt()} m", badge)
    val bx = px + 10.dp.toPx(); val by = (py - bt.size.height / 2).coerceIn(top, bottom - bt.size.height)
    drawRoundRect(Cockpit.Blue.copy(alpha = 0.85f), Offset(bx - 6.dp.toPx(), by - 2.dp.toPx()), Size(bt.size.width + 12.dp.toPx(), bt.size.height + 4.dp.toPx()), CornerRadius(6.dp.toPx()))
    drawText(bt, Color.White, Offset(bx, by))
}

private fun niceStep(raw: Float): Float {
    if (raw <= 0f) return 1f
    val mag = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(raw.toDouble()))).toFloat()
    return listOf(1f, 2f, 2.5f, 5f, 10f).map { it * mag }.first { it >= raw }
}

@Composable
private fun AltitudeStats(t: TelemetryState, modifier: Modifier) {
    Column(modifier.glassPanel(16.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("ALTITUD", style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(t.altitudeM?.roundToInt()?.toString() ?: "--", style = numeric(36, FontWeight.Medium))
                    Text(" m", style = numeric(16), color = Cockpit.TextDim, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text("PENDIENTE", style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val s = t.slopePct
                    Canvas(Modifier.size(26.dp)) { slopeIcon(s ?: 0f, gradeColor(s ?: 0f)) }
                    Text(
                        s?.let { (if (it > 0.05f) "+" else "") + "%.1f".format(es, it) } ?: "--",
                        style = numeric(36, FontWeight.Medium), color = s?.let(::gradeColor) ?: Cockpit.Text,
                    )
                    Text(" %", style = numeric(16), color = Cockpit.TextDim)
                }
            }
        }
        Text(
            "▲ ${t.ascentM.roundToInt()} m    ▼ ${t.descentM.roundToInt()} m",
            style = numeric(20, FontWeight.Medium),
        )
        Text("Fuente: ${t.altSource}", style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().height(46.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction("Borrar perfil", Modifier.weight(1f).fillMaxHeight(), Telemetry::resetProfile)
            SmallAction("Borrar máx.", Modifier.weight(1f).fillMaxHeight(), Telemetry::resetPeaks)
        }
    }
}

@Composable
private fun SmallAction(text: String, modifier: Modifier, onClick: () -> Unit) {
    FocusTile(onClick = onClick, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.align(Alignment.Center))
    }
}

private fun DrawScope.slopeIcon(pct: Float, color: Color) {
    val w = size.width; val h = size.height
    val k = (pct / 15f).coerceIn(-1f, 1f) * 0.8f
    val path = Path().apply {
        moveTo(0f, h * 0.85f)
        lineTo(w, h * 0.85f)
        lineTo(w, h * (0.5f - k * 0.4f))
        close()
    }
    if (pct >= 0) drawPath(path, color)
    else {
        val down = Path().apply { moveTo(0f, h * 0.85f); lineTo(w, h * 0.85f); lineTo(0f, h * (0.5f + k * 0.4f)); close() }
        drawPath(down, color)
    }
}
