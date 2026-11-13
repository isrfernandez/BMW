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
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.draw.drawBehind
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Escalas que pidió el usuario (su motor anda por 190 CV / 400 Nm). */
private const val MAX_CV = 250f
private const val MAX_NM = 500f
/**
 * Borde del G-meter. Conduciendo normal se anda por 0,1–0,3 G: con 1,2 G el punto apenas se
 * movía del centro. A 0,6 G lo habitual llena medio círculo; lo que pase de ahí se queda en el borde.
 */
private const val MAX_G = 0.6f
private val G_RINGS = listOf(0.2f, 0.4f, 0.6f)

private val es = Locale("es")

/**
 * Rendimiento: G-meter, potencia y par estimados (relojes estilo M), perfil de altitud tipo
 * «etapa» con la pendiente, y el selector de modos de conducción.
 */
@Composable
fun PerformanceScreen() {
    val t by Telemetry.state.collectAsStateWithLifecycle()
    val drag by com.cockpitf30.vehicle.telemetry.DragTimes.state.collectAsStateWithLifecycle()
    var details by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = details) { details = false }
    val first = remember { FocusRequester() }
    InitialFocus(first)
    if (details) {
        DragDetails(drag) { details = false }
        return
    }

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
                    GMeterPanel(t, Modifier.width(470.dp).fillMaxHeight())
                    PowerPanel(t, Modifier.weight(1f).fillMaxHeight())
                    ModePanel(first, Modifier.width(330.dp).fillMaxHeight())
                }
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AltitudePanel(t, Modifier.weight(1f).fillMaxHeight())
                    DragPanel(drag, Modifier.width(360.dp).fillMaxHeight()) { details = true }
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
    val label = numeric(15, FontWeight.SemiBold)
    Column(modifier.glassPanel(16.dp).padding(16.dp)) {
        PanelTitle("Fuerzas G")
        Row(Modifier.fillMaxSize().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(300.dp)) { gMeter(t, measurer, label) }
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
            Text("%.2f".format(es, now), style = numeric(30, FontWeight.SemiBold))
            Text(" G", style = numeric(16), color = Cockpit.TextDim, modifier = Modifier.padding(bottom = 4.dp))
        }
        Text("máx %.2f".format(es, peak), style = numeric(16, FontWeight.Medium), color = Cockpit.BlueLight)
    }
}

private fun DrawScope.gMeter(t: TelemetryState, tm: TextMeasurer, label: TextStyle) {
    val c = center
    val r = size.minDimension / 2 - 4.dp.toPx()
    val accent = Cockpit.BlueLight
    val ink = Cockpit.Ink
    drawCircle(
        if (Cockpit.Light) Brush.radialGradient(listOf(accent.copy(alpha = 0.06f), ink.copy(alpha = 0.07f)), c, r)
        else Brush.radialGradient(listOf(accent.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.35f)), c, r),
        r, c,
    )
    // Anillos cada 0,2 G: el de fuera, continuo.
    G_RINGS.forEach { g ->
        val rr = r * g / MAX_G
        val outer = g == G_RINGS.last()
        drawCircle(ink.copy(alpha = if (outer) 0.5f else 0.28f), rr, c, style = Stroke((if (outer) 2f else 1.5f).dp.toPx(), pathEffect = if (outer) null else PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
        val txt = tm.measure("%.1f".format(es, g), label)
        // Cifra justo por dentro del anillo, en diagonal (la de fuera ya no se sale del círculo).
        drawText(txt, ink.copy(alpha = 0.7f), Offset(c.x + rr * 0.71f - txt.size.width - 2.dp.toPx(), c.y - rr * 0.71f + 2.dp.toPx()))
    }
    drawLine(ink.copy(alpha = 0.3f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.5.dp.toPx())
    drawLine(ink.copy(alpha = 0.3f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.5.dp.toPx())
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
        drawLine(accent.copy(alpha = a * 0.85f), at(trail[i - 1].first, trail[i - 1].second), at(trail[i].first, trail[i].second), (2.5f + a * 4f).dp.toPx(), StrokeCap.Round)
    }
    // Línea desde el centro hasta el punto: dirección y fuerza de un vistazo.
    val p = at(t.longG, t.latG)
    drawLine(accent.copy(alpha = 0.6f), c, p, 3.dp.toPx(), StrokeCap.Round)
    if (!Cockpit.Light) drawCircle(accent.copy(alpha = 0.35f), 22.dp.toPx(), p, blendMode = BlendMode.Plus)
    else drawCircle(accent.copy(alpha = 0.2f), 20.dp.toPx(), p)
    drawCircle(Brush.radialGradient(listOf(Color.White, accent), p, 12.dp.toPx()), 12.dp.toPx(), p)
    drawCircle(accent, 12.dp.toPx(), p, style = Stroke(2.dp.toPx()))
    // Rótulos de los ejes.
    val up = tm.measure("ACEL", label); drawText(up, ink.copy(alpha = 0.75f), Offset(c.x - up.size.width / 2, c.y - r + 6.dp.toPx()))
    val dn = tm.measure("FRENO", label); drawText(dn, ink.copy(alpha = 0.75f), Offset(c.x - dn.size.width / 2, c.y + r - dn.size.height - 6.dp.toPx()))
}

// ---------------------------------------------------------------- Potencia y par

@Composable
private fun PowerPanel(t: TelemetryState, modifier: Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    // Muelle rígido y casi sin rebote: la cifra y los segmentos siguen al pie al instante.
    val needle = spring<Float>(dampingRatio = 0.82f, stiffness = 900f)
    val cv by animateFloatAsState((t.powerCv ?: 0f) / MAX_CV, needle, label = "cv")
    val nm by animateFloatAsState((t.torqueNm ?: 0f) / MAX_NM, needle, label = "nm")
    val tick = numeric(16, FontWeight.SemiBold)
    val big = numeric(60, FontWeight.Medium)
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
 * Reloj al estilo del cuadro M digital: arco abierto de 250° hecho de segmentos finos que se
 * encienden del color del modo al rojo, con la punta más larga y luminosa, un filo continuo
 * interior con brillo, marca del máximo retenido, escala discreta por dentro y la cifra grande
 * en el centro. Sin aguja ni esfera: todo luz sobre el cristal.
 */
private fun DrawScope.dial(
    frac: Float, peak: Float, max: Float, minor: Float, major: Float, value: String, unitText: String, title: String, peakText: String,
    tm: TextMeasurer, tick: TextStyle, big: TextStyle, unit: TextStyle,
) {
    val f = frac.coerceIn(0f, 1f)
    val c = Offset(size.width / 2, size.height * 0.54f)
    val r = min(size.width, size.height) * 0.47f
    val start = 145f
    val sweep = 250f
    val red = 0.85f
    val accent = Cockpit.BlueLight
    val deep = Cockpit.Blue
    fun at(rad: Float, deg: Float): Offset {
        val a = Math.toRadians(deg.toDouble())
        return Offset(c.x + rad * cos(a).toFloat(), c.y + rad * sin(a).toFloat())
    }
    // Halo de fondo del color del modo, más intenso cuanto más se pide al motor.
    drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.06f + 0.14f * f), Color.Transparent), c, r * 1.1f), r * 1.1f, c)
    // Filo exterior fino (cromo), abierto abajo.
    drawArc(
        Brush.sweepGradient(listOf(Cockpit.SteelBlueLight.copy(alpha = 0.5f), Cockpit.Ink.copy(alpha = 0.15f), Cockpit.CopperLight.copy(alpha = 0.45f), Cockpit.Ink.copy(alpha = 0.15f), Cockpit.SteelBlueLight.copy(alpha = 0.5f)), c),
        start - 3f, sweep + 6f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round),
    )
    // Segmentos.
    val n = 48
    val r0 = r * 0.80f
    val r1 = r * 0.94f
    val segW = (2 * PI.toFloat() * r1 * sweep / 360f / n) * 0.5f
    for (i in 0 until n) {
        val k = (i + 0.5f) / n
        val deg = start + sweep * k
        val lit = k <= f
        val isRed = k >= red
        val tip = lit && k + 1f / n > f
        val col = when {
            !lit -> if (isRed) Cockpit.Alert.copy(alpha = 0.22f) else Cockpit.Ink.copy(alpha = 0.07f)
            isRed -> Cockpit.Alert
            else -> androidx.compose.ui.graphics.lerp(deep, accent, (k / red).coerceIn(0f, 1f))
        }
        val outer = if (tip) r * 1.0f else r1
        if (lit) drawLine(col.copy(alpha = 0.35f), at(r0, deg), at(outer, deg), segW * 2.6f, StrokeCap.Butt, blendMode = BlendMode.Plus)
        drawLine(if (tip) Cockpit.Ink else col, at(r0, deg), at(outer, deg), segW, StrokeCap.Butt)
    }
    // Filo continuo interior con brillo hasta el valor.
    val ri = r * 0.75f
    val box = Offset(c.x - ri, c.y - ri)
    val bs = Size(ri * 2, ri * 2)
    drawArc(Cockpit.Ink.copy(alpha = 0.06f), start, sweep, false, box, bs, style = Stroke(2.dp.toPx()))
    if (f > 0f) {
        drawArc(accent.copy(alpha = 0.45f), start, sweep * f, false, box, bs, style = Stroke(7.dp.toPx(), cap = StrokeCap.Round), blendMode = BlendMode.Plus)
        drawArc(Brush.sweepGradient(0f to Cockpit.Ink, 1f to accent, center = c), start, sweep * f, false, box, bs, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        val p = at(ri, start + sweep * f)
        drawCircle(accent.copy(alpha = 0.5f), 9.dp.toPx(), p, blendMode = BlendMode.Plus)
        drawCircle(Cockpit.Ink, 3.5.dp.toPx(), p)
    }
    // Máximo retenido: una marca fina por fuera.
    if (peak > 0.01f) {
        val deg = start + sweep * min(peak, 1f)
        drawLine(Cockpit.Ink.copy(alpha = 0.85f), at(r * 1.01f, deg), at(r * 1.08f, deg), 2.5.dp.toPx(), StrokeCap.Round)
    }
    // Escala: cifras pequeñas por dentro y rayitas finas.
    var v = 0f
    while (v <= max + 0.1f) {
        val isMajor = abs(v / major - (v / major).roundToInt()) < 0.01f
        val deg = start + sweep * v / max
        val inRed = v / max >= red
        drawLine(
            (if (inRed) Cockpit.Alert else Cockpit.Ink).copy(alpha = if (isMajor) 0.7f else 0.25f),
            at(r * 0.70f, deg), at(r * (if (isMajor) 0.735f else 0.72f), deg), 1.2.dp.toPx(),
        )
        if (isMajor) {
            val txt = tm.measure("${v.roundToInt()}", tick)
            val p = at(r * 0.60f, deg)
            drawText(txt, (if (inRed) Cockpit.Alert else Cockpit.Ink).copy(alpha = 0.6f), Offset(p.x - txt.size.width / 2, p.y - txt.size.height / 2))
        }
        v += minor
    }
    // Cifras.
    val vt = tm.measure(value, big)
    val tt = tm.measure(title, unit.copy(fontSize = unit.fontSize * 0.8f, letterSpacing = 3.sp))
    drawText(tt, Cockpit.TextDim, Offset(c.x - tt.size.width / 2, c.y - vt.size.height / 2 - tt.size.height + 4.dp.toPx()))
    drawText(vt, Cockpit.Ink, Offset(c.x - vt.size.width / 2, c.y - vt.size.height / 2))
    val ut = tm.measure(unitText, unit)
    drawText(ut, accent, Offset(c.x - ut.size.width / 2, c.y + vt.size.height / 2 - 6.dp.toPx()))
    // Abajo, en la abertura del arco: el máximo y una barrita de lo que queda hasta el tope.
    val pt = tm.measure(peakText, unit.copy(fontSize = unit.fontSize * 0.85f))
    val by = c.y + r * 0.78f
    drawText(pt, Cockpit.Ink.copy(alpha = 0.65f), Offset(c.x - pt.size.width / 2, by - pt.size.height))
    val bw = r * 0.5f
    drawLine(Cockpit.Ink.copy(alpha = 0.10f), Offset(c.x - bw / 2, by + 4.dp.toPx()), Offset(c.x + bw / 2, by + 4.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
    drawLine(Brush.horizontalGradient(listOf(deep, accent), c.x - bw / 2, c.x + bw / 2), Offset(c.x - bw / 2, by + 4.dp.toPx()), Offset(c.x - bw / 2 + bw * min(peak, 1f), by + 4.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
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
    // El latido se lee al dibujar la placa: animarlo no recompone el botón en cada fotograma.
    val glow = { if (active) pulse else 0f }
    FocusTile(onClick = onClick, selected = active, modifier = modifier.graphicsLayer {
        rotationX = flip.value
        rotationY = if (active) 0f else 6f
        cameraDistance = 14f * density
    }) { focused -> ModeFace(mode, active, focused, glow) }
}

/** Cara del botón de modo (sin foco propio): también la usa la tarjeta del Inicio. */
@Composable
internal fun ModeFace(mode: DriveMode, active: Boolean, lit: Boolean, glow: () -> Float, hint: String? = null) {
    val p = paletteOf(mode)
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().graphicsLayer()) { modePlate(mode, p, active || lit, glow()) }
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(62.dp))
            Column(Modifier.weight(1f)) {
                Text(mode.label.uppercase(), style = numeric(24, FontWeight.Bold).copy(letterSpacing = 3.sp), color = if (active) Color.White else p.blueLight)
                Text(
                    hint ?: if (mode == DriveMode.NIGHT && DriveModes.nightIsAuto) "Puesto solo: ${NightDetector.reason}" else mode.subtitle,
                    style = MaterialTheme.typography.labelMedium, color = Cockpit.OnDarkDim, maxLines = 1,
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
    val label = numeric(15, FontWeight.SemiBold)
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
            drawLine(Cockpit.Ink.copy(alpha = 0.08f), Offset(left, y(a)), Offset(right, y(a)), 1f)
            val txt = tm.measure("${a.roundToInt()}", label)
            drawText(txt, Cockpit.Ink.copy(alpha = 0.45f), Offset(left - txt.size.width - 8.dp.toPx(), y(a) - txt.size.height / 2))
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
    drawPath(line, Cockpit.Ink.copy(alpha = 0.9f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    // Kilómetros abajo.
    val kmStep = niceStep(spanKm / 5f)
    var k = kotlin.math.ceil(km0 / kmStep) * kmStep
    while (k <= last.km) {
        val txt = tm.measure("${"%.1f".format(es, k - km0).removeSuffix(",0")} km", label)
        drawText(txt, Cockpit.Ink.copy(alpha = 0.4f), Offset(x(k) - txt.size.width / 2, bottom + 4.dp.toPx()))
        k += kmStep
    }
    // Posición actual.
    val px = x(last.km); val py = y(last.altM)
    drawLine(Cockpit.Ink.copy(alpha = 0.5f), Offset(px, top), Offset(px, bottom), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f)))
    drawCircle(Cockpit.BlueLight.copy(alpha = 0.4f), 12.dp.toPx(), Offset(px, py), blendMode = BlendMode.Plus)
    drawCircle(Cockpit.Ink, 5.dp.toPx(), Offset(px, py))
    val bt = tm.measure("${last.altM.roundToInt()} m", badge)
    val bx = px + 10.dp.toPx(); val by = (py - bt.size.height / 2).coerceIn(top, bottom - bt.size.height)
    drawRoundRect(Cockpit.Blue.copy(alpha = 0.85f), Offset(bx - 6.dp.toPx(), by - 2.dp.toPx()), Size(bt.size.width + 12.dp.toPx(), bt.size.height + 4.dp.toPx()), CornerRadius(6.dp.toPx()))
    drawText(bt, Cockpit.Ink, Offset(bx, by))
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

// ---------------------------------------------------------------- Cronómetro de aceleración

private fun secs(v: Float) = "%.1f".format(es, v)

/** Panel compacto: la prueba en marcha en grande o la última y mejor marca de cada prueba. */
@Composable
private fun DragPanel(d: com.cockpitf30.vehicle.telemetry.DragState, modifier: Modifier, onDetails: () -> Unit) {
    Column(modifier.glassPanel(16.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PanelTitle("Cronómetro", trailing = {
            Text(
                when {
                    d.running.isNotEmpty() -> "MIDIENDO"
                    d.armed -> "LISTO: SAL"
                    else -> "AUTO"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (d.running.isNotEmpty() || d.armed) Cockpit.Ok else Cockpit.TextDim,
            )
        })
        val run = d.running.firstOrNull { it.test != com.cockpitf30.vehicle.telemetry.DragTest.QUARTER_400 } ?: d.running.firstOrNull()
        if (run != null) {
            Text(run.test.label, style = MaterialTheme.typography.labelLarge, color = Cockpit.BlueLight)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(secs(run.elapsed), style = numeric(46, FontWeight.Medium))
                Text(" s", style = numeric(18), color = Cockpit.TextDim, modifier = Modifier.padding(bottom = 8.dp))
                Spacer(Modifier.weight(1f))
                Text("${run.kmh.roundToInt()} km/h", style = numeric(20, FontWeight.Medium), color = Cockpit.TextDim, modifier = Modifier.padding(bottom = 8.dp))
            }
        } else if (d.fresh != null) {
            FreshMark(d.fresh, isBest = d.best[d.fresh.test]?.at == d.fresh.at, Modifier.weight(1f).fillMaxWidth())
        } else {
            com.cockpitf30.vehicle.telemetry.DragTest.entries.forEach { test ->
                val best = d.best[test]
                val fresh = d.fresh?.test == test
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(test.label, style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim, modifier = Modifier.width(118.dp))
                    Text(
                        d.last[test]?.let { secs(it.seconds) + " s" } ?: "--",
                        style = numeric(22, FontWeight.Medium), color = if (fresh) Cockpit.Ok else Cockpit.Text,
                        modifier = Modifier.width(90.dp),
                    )
                    Text(best?.let { "mejor " + secs(it.seconds) } ?: "", style = numeric(15), color = Cockpit.BlueLight)
                }
            }
        }
        if (run != null || d.fresh == null) Spacer(Modifier.weight(1f))
        SmallAction("Ver marcas", Modifier.fillMaxWidth().height(46.dp), onDetails)
    }
}

/**
 * Marca recién lograda: la cifra entra grande con un rebote, late con un halo del color del
 * modo y, si es la mejor, lo dice. Se ve de un vistazo que se ha completado la prueba.
 */
@Composable
private fun FreshMark(r: com.cockpitf30.vehicle.telemetry.DragResult, isBest: Boolean, modifier: Modifier) {
    val pop = remember(r.at) { Animatable(0.4f) }
    LaunchedEffect(r.at) {
        pop.animateTo(1.25f, tween(260, easing = FastOutSlowInEasing))
        pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 300f))
    }
    val pulse by rememberInfiniteTransition(label = "fresh").animateFloat(
        0f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "freshPulse",
    )
    val accent = if (isBest) Cockpit.Ok else Cockpit.BlueLight
    Box(
        modifier.drawBehind {
            val r0 = size.minDimension * 0.62f
            drawCircle(
                Brush.radialGradient(listOf(accent.copy(alpha = 0.18f + 0.22f * pulse), Color.Transparent), center, r0 * (1f + 0.15f * pulse)),
                r0 * (1f + 0.15f * pulse), center,
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer {
            scaleX = pop.value; scaleY = pop.value
        }) {
            Text(
                if (isBest) "¡NUEVA MEJOR MARCA!" else "¡${r.test.label}!",
                style = MaterialTheme.typography.labelLarge, color = accent, maxLines = 1,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(secs(r.seconds), style = numeric(64, FontWeight.Bold), color = Cockpit.Text, maxLines = 1)
                Text(" s", style = numeric(22, FontWeight.Medium), color = Cockpit.TextDim, modifier = Modifier.padding(bottom = 12.dp))
            }
            Text(
                if (isBest) r.test.label else "${r.endKmh.roundToInt()} km/h",
                style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim, maxLines = 1,
            )
        }
    }
}

/** Detalle: mejores marcas e historial de las últimas pruebas. */
@Composable
internal fun DragDetails(d: com.cockpitf30.vehicle.telemetry.DragState, onBack: () -> Unit) {
    val back = remember { FocusRequester() }
    InitialFocus(back)
    val fmt = remember { java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm", es) }
    fun date(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).format(fmt)
    TriangleBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Cronómetro de aceleración") {
                Text("Se mide solo: sal desde parado (0–100 y 0–400 m) o acelera pasando de 60 (60–120)", style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim)
            }
            Row(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.width(560.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    com.cockpitf30.vehicle.telemetry.DragTest.entries.forEach { test ->
                        val b = d.best[test]
                        Column(Modifier.fillMaxWidth().weight(1f).glassPanel(16.dp).padding(16.dp)) {
                            Text("MEJOR ${test.label.uppercase()}", style = MaterialTheme.typography.labelLarge, color = Cockpit.TextDim)
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(b?.let { secs(it.seconds) } ?: "--", style = numeric(52, FontWeight.Medium))
                                Text(" s", style = numeric(20), color = Cockpit.TextDim, modifier = Modifier.padding(bottom = 10.dp))
                                Spacer(Modifier.weight(1f))
                                if (b != null) Text(
                                    (if (test == com.cockpitf30.vehicle.telemetry.DragTest.QUARTER_400) "${b.endKmh.roundToInt()} km/h · " else "") + date(b.at),
                                    style = MaterialTheme.typography.bodyMedium, color = Cockpit.BlueLight, modifier = Modifier.padding(bottom = 12.dp),
                                )
                            }
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight().glassPanel(16.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PanelTitle("Últimas pruebas")
                    if (d.history.isEmpty()) Text("Todavía no hay marcas", style = MaterialTheme.typography.bodyLarge, color = Cockpit.TextDim)
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(d.history.size) { i ->
                            val r = d.history[i]
                            val isBest = d.best[r.test]?.at == r.at
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(r.test.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(200.dp))
                                Text(secs(r.seconds) + " s", style = numeric(22, FontWeight.Medium), color = if (isBest) Cockpit.Ok else Cockpit.Text, modifier = Modifier.width(120.dp))
                                Text(if (isBest) "MEJOR" else "", style = MaterialTheme.typography.labelMedium, color = Cockpit.Ok, modifier = Modifier.width(90.dp))
                                Text(date(r.at), style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FocusTile(onClick = onBack, modifier = Modifier.weight(1f).fillMaxHeight().focusRequester(back)) {
                            Text("‹  Volver", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center))
                        }
                        SmallAction("Borrar marcas", Modifier.weight(1f).fillMaxHeight(), com.cockpitf30.vehicle.telemetry.DragTimes::clear)
                    }
                }
            }
        }
    }
}
