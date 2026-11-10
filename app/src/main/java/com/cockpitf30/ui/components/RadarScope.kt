package com.cockpitf30.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cockpitf30.nav.CameraState
import com.cockpitf30.ui.theme.Cockpit
import com.cockpitf30.ui.theme.numeric
import kotlin.math.cos
import kotlin.math.sin

/** Rojo del aviso de radar (igual en todos los modos). */
val RadarAlert = Color(0xFFFF3B30)

/**
 * Radar de barrido con el coche en el centro y la marcha hacia arriba: anillos de distancia,
 * un haz que gira y los radares cercanos como ecos que se encienden al pasar el haz. El que
 * tenemos delante late en rojo.
 */
@Composable
fun RadarScope(state: CameraState, modifier: Modifier = Modifier, rangeM: Float = 2000f, rings: List<Float> = listOf(500f, 1000f, 2000f), labels: Boolean = true) {
    val t = rememberInfiniteTransition(label = "radar")
    val sweep by t.animateFloat(0f, 360f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "sweep")
    val blink by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "blink")
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val label = numeric(12, FontWeight.Medium)
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2 - 2.dp.toPx()
        val accent = Cockpit.BlueLight
        val alert = state.ahead != null
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.16f), Color(0xFF03060A).copy(alpha = 0.9f)), c, r), r, c)
        rings.forEach { m ->
            val rr = r * m / rangeM
            drawCircle(accent.copy(alpha = if (m == rings.last()) 0.5f else 0.22f), rr, c, style = Stroke(1.dp.toPx(), pathEffect = if (m == rings.last()) null else PathEffect.dashPathEffect(floatArrayOf(5f, 6f))))
            if (labels) {
                val txt = measurer.measure(if (m >= 1000) "${(m / 1000).let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }} km" else "${m.toInt()} m", label)
                drawText(txt, Color.White.copy(alpha = 0.4f), Offset(c.x + 3.dp.toPx(), c.y - rr))
            }
        }
        drawLine(accent.copy(alpha = 0.15f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
        drawLine(accent.copy(alpha = 0.15f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
        // Cono de la marcha (lo que cuenta como «delante»).
        val cone = Path().apply {
            moveTo(c.x, c.y)
            lineTo(c.x + r * sin(Math.toRadians(35.0)).toFloat(), c.y - r * cos(Math.toRadians(35.0)).toFloat())
            lineTo(c.x - r * sin(Math.toRadians(35.0)).toFloat(), c.y - r * cos(Math.toRadians(35.0)).toFloat())
            close()
        }
        drawPath(cone, Brush.verticalGradient(listOf(accent.copy(alpha = 0.10f), Color.Transparent), c.y - r, c.y))
        // Haz giratorio con estela.
        rotate(sweep, c) {
            drawCircle(
                Brush.sweepGradient(0f to Color.Transparent, 0.82f to Color.Transparent, 1f to (if (alert) RadarAlert else accent).copy(alpha = 0.45f), center = c),
                r, c, blendMode = BlendMode.Plus,
            )
            drawLine((if (alert) RadarAlert else accent).copy(alpha = 0.9f), c, Offset(c.x + r, c.y), 2.dp.toPx())
        }
        // Ecos.
        state.near.forEach { v ->
            if (v.meters > rangeM) return@forEach
            val ang = v.relBearing
            val d = (v.meters / rangeM).toFloat() * r
            val p = Offset(c.x + d * sin(Math.toRadians(ang.toDouble())).toFloat(), c.y - d * cos(Math.toRadians(ang.toDouble())).toFloat())
            // El haz va a 0° = derecha y gira en sentido horario: ángulo de pantalla del eco.
            val screen = ((ang - 90f) % 360f + 360f) % 360f
            val since = ((sweep - screen) % 360f + 360f) % 360f
            val lit = (1f - since / 360f).let { it * it }
            val isAhead = state.ahead?.camera?.id == v.camera.id
            val col = if (isAhead) RadarAlert else Color(0xFFFFC94D)
            val k = if (isAhead) blink else 0.35f + 0.65f * lit
            drawCircle(col.copy(alpha = 0.35f * k), 11.dp.toPx(), p, blendMode = BlendMode.Plus)
            drawCircle(col.copy(alpha = k), 4.5.dp.toPx(), p)
        }
        // El coche.
        val car = Path().apply {
            moveTo(c.x, c.y - 9.dp.toPx()); lineTo(c.x + 6.dp.toPx(), c.y + 7.dp.toPx()); lineTo(c.x, c.y + 4.dp.toPx()); lineTo(c.x - 6.dp.toPx(), c.y + 7.dp.toPx()); close()
        }
        drawPath(car, Color.White)
        if (alert) drawCircle(RadarAlert.copy(alpha = 0.8f * blink), r, c, style = Stroke(3.dp.toPx()))
    }
}

/** Señal de límite de velocidad: círculo blanco con borde rojo y la cifra. */
@Composable
fun SpeedSign(limit: Int, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            drawCircle(Color.White)
            drawCircle(RadarAlert, style = Stroke(this.size.minDimension * 0.12f), radius = this.size.minDimension / 2 * 0.88f)
        }
        Text("$limit", style = numeric((size.value * 0.36f).toInt(), FontWeight.Bold), color = Color.Black)
    }
}

/** Distancia redondeada para leer o decir: «350 m», «1,2 km». */
fun radarDistance(m: Double): String = if (m < 1000) "${maxOf(50, (m / 50).toInt() * 50)} m" else "%.1f km".format(java.util.Locale("es"), m / 1000)

