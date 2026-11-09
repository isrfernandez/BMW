package com.cockpitf30.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.cockpitf30.R
import kotlin.math.sin

/** Colores de cada función: el degradado de fondo y el del glifo. */
enum class CardTheme(val from: Color, val to: Color) {
    MUSIC(Color(0xFF8E2DE2), Color(0xFFFF4FB8)),
    NAV(Color(0xFF00C9A7), Color(0xFF29B6F6)),
    TRIP(Color(0xFF1C69D8), Color(0xFF4FA3FF)),
    WEATHER(Color(0xFF0E7CC2), Color(0xFF7FD3FF)),
    VEHICLE(Color(0xFF5B7FAE), Color(0xFFC9D6E8)),
    PHONE(Color(0xFF1DB954), Color(0xFF7CF29C)),
    DIAG(Color(0xFFFF8F00), Color(0xFFFFD54F)),
    FUEL(Color(0xFFE8473B), Color(0xFFFFA36B)),
    ASSIST(Color(0xFF3D5AFE), Color(0xFF3FE0FF)),
}

/**
 * Fondo de tarjeta tipo cristal: halo del color de la función detrás de la ilustración, un
 * reflejo diagonal y oscurecido abajo para que el título se lea.
 */
fun DrawScope.glassBackground(theme: CardTheme) {
    val w = size.width
    val h = size.height
    drawRect(Brush.radialGradient(listOf(theme.from.copy(alpha = 0.38f), Color.Transparent), Offset(w * 0.72f, h * 0.36f), w * 0.55f))
    val shine = Path().apply {
        moveTo(w * 0.40f, 0f); lineTo(w * 0.62f, 0f); lineTo(w * 0.30f, h); lineTo(w * 0.08f, h); close()
    }
    drawPath(shine, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent)))
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)), startY = h * 0.55f, endY = h))
}

/** Ilustración grande de cada tarjeta, en la mitad derecha. */
@Composable
fun BoxScope.CardArt(theme: CardTheme) {
    when (theme) {
        CardTheme.MUSIC -> Box(Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 18.dp).size(width = 230.dp, height = 150.dp)) {
            Canvas(Modifier.fillMaxSize()) { equalizer(theme) }
            GlassBadge(theme, Modifier.align(Alignment.Center).size(118.dp)) {
                Canvas(Modifier.size(56.dp)) { musicNote(theme) }
            }
        }
        CardTheme.NAV -> Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp).size(width = 250.dp, height = 160.dp)) {
            Canvas(Modifier.fillMaxSize()) { mapGrid(theme) }
        }
        CardTheme.PHONE -> Box(Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 30.dp).size(160.dp)) {
            Canvas(Modifier.fillMaxSize()) { rings(theme) }
            GlassBadge(theme, Modifier.align(Alignment.Center).size(110.dp)) { GradientIcon(Icons.Filled.Phone, theme, 54) }
        }
        CardTheme.DIAG -> Box(Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 18.dp).size(width = 230.dp, height = 150.dp)) {
            Canvas(Modifier.fillMaxSize()) { pulse(theme) }
            GlassBadge(theme, Modifier.align(Alignment.Center).size(110.dp)) { GradientIcon(Icons.Filled.Build, theme, 50) }
        }
        CardTheme.VEHICLE -> Image(
            com.cockpitf30.ui.components.cachedPainter(R.drawable.car_rear_scene), null,
            Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp).size(width = 300.dp, height = 150.dp)
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    // Funde los bordes de la foto con la tarjeta.
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black, Color.Black, Color.Transparent)), blendMode = BlendMode.DstIn)
                    drawRect(Brush.verticalGradient(listOf(Color.Black, Color.Black, Color.Transparent)), blendMode = BlendMode.DstIn)
                },
            contentScale = ContentScale.Crop,
        )
        CardTheme.TRIP -> Box(Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 14.dp).size(width = 240.dp, height = 120.dp)) {
            Canvas(Modifier.fillMaxSize()) { lineChart(theme) }
        }
        CardTheme.FUEL -> Box(Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 22.dp).size(width = 230.dp, height = 150.dp)) {
            Canvas(Modifier.fillMaxSize()) { routeDots(theme) }
            GlassBadge(theme, Modifier.align(Alignment.Center).size(110.dp)) {
                Canvas(Modifier.size(56.dp)) { fuelPump(theme) }
            }
        }
        CardTheme.ASSIST -> AssistantArt(theme)
        CardTheme.WEATHER -> Unit
    }
}

/** Círculo de cristal: relleno translúcido, borde fino y reflejo arriba a la izquierda. */
@Composable
fun GlassBadge(theme: CardTheme, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            drawCircle(theme.from.copy(alpha = 0.35f), r * 1.15f)
            drawCircle(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.22f), theme.from.copy(alpha = 0.25f), Color.White.copy(alpha = 0.04f))), r)
            drawCircle(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0.08f))), r, style = Stroke(1.5.dp.toPx()))
            drawArc(Color.White.copy(alpha = 0.35f), 200f, 70f, false, Offset(r * 0.25f, r * 0.25f), Size(r * 1.5f, r * 1.5f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
        content()
    }
}

/** Icono de Material relleno con el degradado de la función. */
@Composable
fun GradientIcon(icon: ImageVector, theme: CardTheme, sizeDp: Int) {
    Icon(
        icon, null,
        Modifier.size(sizeDp.dp)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(Brush.linearGradient(listOf(Color.White, theme.to, theme.from)), blendMode = BlendMode.SrcIn)
            },
        tint = Color.White,
    )
}

private fun DrawScope.equalizer(theme: CardTheme) {
    val n = 16
    val gap = 5.dp.toPx()
    val bw = (size.width - gap * (n - 1)) / n
    for (i in 0 until n) {
        val k = 0.35f + 0.65f * (0.5f + 0.5f * sin(i * 0.9f + 1.3f)) * (0.6f + 0.4f * sin(i * 0.37f).let { it * it })
        val bh = size.height * k
        drawRoundRect(
            Brush.verticalGradient(listOf(theme.to.copy(alpha = 0.75f), theme.from.copy(alpha = 0.15f)), startY = size.height - bh, endY = size.height),
            Offset(i * (bw + gap), size.height - bh), Size(bw, bh), androidx.compose.ui.geometry.CornerRadius(bw / 2),
        )
    }
}

private fun DrawScope.musicNote(theme: CardTheme) {
    val w = size.width
    val h = size.height
    val brush = Brush.linearGradient(listOf(Color.White, theme.to))
    val stem = w * 0.09f
    drawOval(brush, Offset(w * 0.04f, h * 0.68f), Size(w * 0.34f, h * 0.26f))
    drawOval(brush, Offset(w * 0.56f, h * 0.58f), Size(w * 0.34f, h * 0.26f))
    drawRect(brush, Offset(w * 0.30f, h * 0.16f), Size(stem, h * 0.66f))
    drawRect(brush, Offset(w * 0.82f, h * 0.06f), Size(stem, h * 0.66f))
    val beam = Path().apply {
        moveTo(w * 0.30f, h * 0.16f); lineTo(w * 0.91f, h * 0.06f); lineTo(w * 0.91f, h * 0.22f); lineTo(w * 0.30f, h * 0.32f); close()
    }
    drawPath(beam, brush)
}

/** Rejilla de mapa en perspectiva con una ruta y el pin de destino. */
private fun DrawScope.mapGrid(theme: CardTheme) {
    val w = size.width
    val h = size.height
    val vx = w * 0.55f
    val vy = h * 0.05f
    for (i in -6..6) {
        drawLine(theme.from.copy(alpha = 0.28f), Offset(vx + i * w * 0.02f, vy), Offset(vx + i * w * 0.16f, h), 1.dp.toPx())
    }
    for (i in 1..7) {
        val t = (i / 7f).let { it * it }
        val y = vy + (h - vy) * t
        val half = w * 0.12f + w * 0.85f * t
        drawLine(theme.from.copy(alpha = 0.28f), Offset(vx - half, y), Offset(vx + half, y), 1.dp.toPx())
    }
    val route = Path().apply {
        moveTo(w * 0.30f, h); cubicTo(w * 0.40f, h * 0.70f, w * 0.80f, h * 0.70f, w * 0.68f, h * 0.38f)
    }
    drawPath(route, theme.to.copy(alpha = 0.35f), style = Stroke(14.dp.toPx(), cap = StrokeCap.Round))
    drawPath(route, Brush.linearGradient(listOf(theme.from, theme.to)), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
    // Pin: gota con brillo.
    val cx = w * 0.68f
    val cy = h * 0.22f
    val r = h * 0.15f
    drawCircle(theme.to.copy(alpha = 0.35f), r * 1.7f, Offset(cx, cy))
    val pin = Path().apply {
        moveTo(cx, cy + r * 2.1f)
        cubicTo(cx - r * 0.4f, cy + r * 1.3f, cx - r * 1.1f, cy + r * 0.8f, cx - r, cy)
        cubicTo(cx - r, cy - r * 1.35f, cx + r, cy - r * 1.35f, cx + r, cy)
        cubicTo(cx + r * 1.1f, cy + r * 0.8f, cx + r * 0.4f, cy + r * 1.3f, cx, cy + r * 2.1f)
        close()
    }
    drawPath(pin, Brush.linearGradient(listOf(Color.White, theme.to, theme.from), Offset(cx - r, cy - r), Offset(cx + r, cy + r * 2)))
    drawCircle(Color(0xFF0B0C10), r * 0.38f, Offset(cx, cy))
}

private fun DrawScope.rings(theme: CardTheme) {
    val c = center
    listOf(0.98f to 0.18f, 0.84f to 0.28f, 0.70f to 0.4f).forEach { (k, a) ->
        drawCircle(theme.to.copy(alpha = a), size.minDimension / 2 * k, c, style = Stroke(2.dp.toPx()))
    }
}

/** Línea de pulso, como un osciloscopio. */
private fun DrawScope.pulse(theme: CardTheme) {
    val w = size.width
    val y = size.height * 0.5f
    val p = Path().apply {
        moveTo(0f, y); lineTo(w * 0.12f, y); lineTo(w * 0.17f, y - size.height * 0.25f); lineTo(w * 0.22f, y + size.height * 0.3f)
        lineTo(w * 0.27f, y); lineTo(w * 0.73f, y); lineTo(w * 0.78f, y - size.height * 0.4f); lineTo(w * 0.83f, y + size.height * 0.2f)
        lineTo(w * 0.88f, y); lineTo(w, y)
    }
    drawPath(p, theme.to.copy(alpha = 0.3f), style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
    drawPath(p, Brush.horizontalGradient(listOf(theme.from.copy(alpha = 0.2f), theme.to, theme.from.copy(alpha = 0.2f))), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
}

/** Gráfico de línea suave con relleno, para los datos de viaje. */
private fun DrawScope.lineChart(theme: CardTheme) {
    val w = size.width
    val h = size.height
    val pts = (0..24).map { i ->
        val x = w * i / 24f
        val v = 0.5f + 0.28f * sin(i * 0.45f) + 0.12f * sin(i * 1.3f + 1f)
        Offset(x, h * (1f - v))
    }
    val line = Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
    val area = Path().apply { addPath(line); lineTo(w, h); lineTo(0f, h); close() }
    drawPath(area, Brush.verticalGradient(listOf(theme.to.copy(alpha = 0.35f), Color.Transparent)))
    drawPath(line, Brush.horizontalGradient(listOf(theme.from, theme.to)), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
}

/** Surtidor: cuerpo con ventanilla, manguera y boquerel, con el degradado de la función. */
private fun DrawScope.fuelPump(theme: CardTheme) {
    val w = size.width
    val h = size.height
    val brush = Brush.linearGradient(listOf(theme.to, theme.from), Offset(0f, 0f), Offset(w, h))
    val body = Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(w * 0.12f, h * 0.08f, w * 0.62f, h * 0.92f, w * 0.08f, w * 0.08f))
    }
    drawPath(body, brush)
    // Ventanilla del contador.
    drawRoundRect(Color.White.copy(alpha = 0.85f), Offset(w * 0.2f, h * 0.18f), Size(w * 0.34f, h * 0.22f), androidx.compose.ui.geometry.CornerRadius(w * 0.03f))
    // Base.
    drawRect(brush, Offset(w * 0.04f, h * 0.88f), Size(w * 0.66f, h * 0.08f))
    // Manguera hasta el boquerel colgado a la derecha.
    val hose = Path().apply {
        moveTo(w * 0.62f, h * 0.30f)
        lineTo(w * 0.76f, h * 0.30f)
        quadraticBezierTo(w * 0.86f, h * 0.30f, w * 0.86f, h * 0.42f)
        lineTo(w * 0.86f, h * 0.72f)
        quadraticBezierTo(w * 0.86f, h * 0.80f, w * 0.78f, h * 0.80f)
        quadraticBezierTo(w * 0.70f, h * 0.80f, w * 0.70f, h * 0.70f)
        lineTo(w * 0.70f, h * 0.56f)
    }
    drawPath(hose, brush, style = Stroke(w * 0.07f, cap = StrokeCap.Round))
    drawLine(brush, Offset(w * 0.78f, h * 0.14f), Offset(w * 0.90f, h * 0.30f), w * 0.07f, StrokeCap.Round)
}

/** Ruta punteada hasta un destino, detrás del surtidor. */
private fun DrawScope.routeDots(theme: CardTheme) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.02f, h * 0.9f)
        cubicTo(w * 0.3f, h * 0.95f, w * 0.25f, h * 0.35f, w * 0.55f, h * 0.45f)
        cubicTo(w * 0.8f, h * 0.55f, w * 0.85f, h * 0.15f, w * 0.98f, h * 0.1f)
    }
    drawPath(
        path, Brush.horizontalGradient(listOf(theme.from.copy(alpha = 0.1f), theme.to.copy(alpha = 0.7f))),
        style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2f, 14f))),
    )
    drawCircle(theme.to, 5.dp.toPx(), Offset(w * 0.98f, h * 0.1f))
    drawCircle(theme.to.copy(alpha = 0.3f), 12.dp.toPx(), Offset(w * 0.98f, h * 0.1f))
}
