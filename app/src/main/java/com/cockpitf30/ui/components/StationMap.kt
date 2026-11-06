package com.cockpitf30.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.cockpitf30.nav.GeoPoint
import com.cockpitf30.nav.MapTiles
import com.cockpitf30.nav.TileMath
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/** Color de las gasolineras en el mapa (el mismo que su tarjeta del Inicio). */
val FuelMarker = Color(0xFFFF7A45)

/** Una gasolinera para dibujar: posición, número en la lista y si es el destino. */
class StationMark(val point: GeoPoint, val number: Int, val selected: Boolean, val label: String)

/**
 * Centro y zoom (fraccionario) que dejan ver todos los [points] dentro del [fill] del panel de
 * [widthDp] x [heightDp], acotado para no alejarse demasiado ni pasar del detalle de calle.
 */
fun fitView(points: List<GeoPoint>, widthDp: Float, heightDp: Float, fill: Float = 0.8f): Pair<GeoPoint, Float> {
    val xs = points.map { TileMath.worldX(it.lon, 0) }
    val ys = points.map { TileMath.worldY(it.lat, 0) }
    val dx = (xs.max() - xs.min()).coerceAtLeast(1e-9)
    val dy = (ys.max() - ys.min()).coerceAtLeast(1e-9)
    val scale = minOf(widthDp * fill / dx, heightDp * fill / dy)
    val zoom = (ln(scale) / ln(2.0)).toFloat().coerceIn(9f, 16f)
    val center = GeoPoint((points.maxOf { it.lat } + points.minOf { it.lat }) / 2, (points.maxOf { it.lon } + points.minOf { it.lon }) / 2)
    return center to zoom
}

/**
 * Mapa con el norte arriba centrado en [center]: teselas oscuras (las mismas que el mapa del Inicio),
 * las gasolineras numeradas, el coche y una línea discontinua hasta el destino.
 */
fun DrawScope.drawStationMap(
    bounds: Rect,
    center: GeoPoint,
    zoom: Float,
    car: GeoPoint?,
    carBearing: Float,
    marks: List<StationMark>,
    tm: TextMeasurer,
    numberStyle: TextStyle,
    labelStyle: TextStyle,
    phase: Float,
) = clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) {
    val u = density
    val provider = MapTiles.provider.value
    drawRect(MapBase, bounds.topLeft, bounds.size)
    val z = minOf(floor(zoom).toInt(), provider.maxZoom)
    val k = u * 2f.pow(zoom - z)
    val tile = TileMath.TILE * k
    val wx = TileMath.worldX(center.lon, z)
    val wy = TileMath.worldY(center.lat, z)
    val tint = if (provider.dark) MapTint else MapTintInverted
    fun screen(p: GeoPoint) = Offset(
        bounds.center.x + ((TileMath.worldX(p.lon, z) - wx) * k).toFloat(),
        bounds.center.y + ((TileMath.worldY(p.lat, z) - wy) * k).toFloat(),
    )

    val halfW = bounds.width / 2 / k
    val halfH = bounds.height / 2 / k
    for (ty in floor((wy - halfH) / TileMath.TILE).toInt()..floor((wy + halfH) / TileMath.TILE).toInt()) {
        for (tx in floor((wx - halfW) / TileMath.TILE).toInt()..floor((wx + halfW) / TileMath.TILE).toInt()) {
            val left = bounds.center.x + ((tx * TileMath.TILE - wx) * k).toFloat()
            val top = bounds.center.y + ((ty * TileMath.TILE - wy) * k).toFloat()
            val dst = IntOffset(floor(left).toInt(), floor(top).toInt())
            val size = IntSize(ceil(left + tile).toInt() - dst.x + 1, ceil(top + tile).toInt() - dst.y + 1)
            val img = MapTiles.get(z, tx, ty)
            if (img != null) {
                drawImage(img, dstOffset = dst, dstSize = size, colorFilter = tint, filterQuality = FilterQuality.Medium)
            } else {
                MapTiles.peek(z - 1, tx shr 1, ty shr 1)?.let { parent ->
                    val half = parent.width / 2
                    drawImage(
                        parent, srcOffset = IntOffset((tx and 1) * half, (ty and 1) * half), srcSize = IntSize(half, half),
                        dstOffset = dst, dstSize = size, colorFilter = tint, filterQuality = FilterQuality.Medium,
                    )
                }
            }
        }
    }
    // Rejilla tenue, por si aún no han llegado las teselas (o no hay conexión).
    val step = 64f * u
    var gx = bounds.left + ((-(wx * k) % step + step) % step).toFloat()
    while (gx < bounds.right) { drawLine(RouteGlow.copy(alpha = 0.05f), Offset(gx, bounds.top), Offset(gx, bounds.bottom), u); gx += step }
    var gy = bounds.top + ((-(wy * k) % step + step) % step).toFloat()
    while (gy < bounds.bottom) { drawLine(RouteGlow.copy(alpha = 0.05f), Offset(bounds.left, gy), Offset(bounds.right, gy), u); gy += step }

    // Viñeta hacia los bordes.
    drawRect(
        Brush.radialGradient(0f to Color.Transparent, 0.6f to Color.Transparent, 1f to MapBase.copy(alpha = 0.8f), center = bounds.center, radius = bounds.width * 0.62f),
        bounds.topLeft, bounds.size,
    )

    val carAt = car?.let(::screen)
    val dest = marks.firstOrNull { it.selected }
    // Línea discontinua del coche al destino, con un brillo que avanza.
    if (carAt != null && dest != null) {
        val to = screen(dest.point)
        val dash = 14f * u
        drawLine(FuelMarker.copy(alpha = 0.25f), carAt, to, 10 * u, cap = StrokeCap.Round, blendMode = BlendMode.Plus)
        drawLine(
            FuelMarker, carAt, to, 3 * u, cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 0.7f), -phase * dash * 1.7f * 4),
        )
    }

    // Las demás primero; el destino encima.
    for (m in marks.sortedBy { it.selected }) {
        val p = screen(m.point)
        if (!bounds.inflate(40 * u).contains(p)) continue
        if (m.selected) {
            // Chincheta grande con halo que late.
            val pulse = (phase * 2f) % 1f
            drawCircle(FuelMarker.copy(alpha = 0.5f * (1 - pulse)), (14 + 30 * pulse) * u, p, style = Stroke(2 * u))
            val pin = Path().apply {
                moveTo(p.x, p.y)
                cubicTo(p.x - 6 * u, p.y - 14 * u, p.x - 18 * u, p.y - 22 * u, p.x - 18 * u, p.y - 36 * u)
                cubicTo(p.x - 18 * u, p.y - 46 * u, p.x - 10 * u, p.y - 54 * u, p.x, p.y - 54 * u)
                cubicTo(p.x + 10 * u, p.y - 54 * u, p.x + 18 * u, p.y - 46 * u, p.x + 18 * u, p.y - 36 * u)
                cubicTo(p.x + 18 * u, p.y - 22 * u, p.x + 6 * u, p.y - 14 * u, p.x, p.y)
                close()
            }
            drawPath(pin, FuelMarker.copy(alpha = 0.45f), style = Stroke(8 * u, join = StrokeJoin.Round), blendMode = BlendMode.Plus)
            drawPath(pin, Brush.verticalGradient(listOf(Color(0xFFFFB38A), FuelMarker), startY = p.y - 54 * u, endY = p.y))
            drawPath(pin, Color.White, style = Stroke(1.5f * u, join = StrokeJoin.Round))
            val n = tm.measure(m.number.toString(), numberStyle)
            drawText(n, Color.White, Offset(p.x - n.size.width / 2f, p.y - 36 * u - n.size.height / 2f))
            val l = tm.measure(m.label, labelStyle)
            val lx = p.x + 24 * u
            val ly = p.y - 44 * u
            drawRoundRect(
                MapBase.copy(alpha = 0.85f), Offset(lx - 8 * u, ly - 4 * u),
                androidx.compose.ui.geometry.Size(l.size.width + 16f * u, l.size.height + 8f * u),
                androidx.compose.ui.geometry.CornerRadius(6 * u),
            )
            drawText(l, Color.White, Offset(lx, ly))
        } else {
            drawCircle(MapBase.copy(alpha = 0.9f), 13 * u, p)
            drawCircle(FuelMarker.copy(alpha = 0.85f), 13 * u, p, style = Stroke(2 * u))
            val n = tm.measure(m.number.toString(), labelStyle)
            drawText(n, Color.White.copy(alpha = 0.9f), Offset(p.x - n.size.width / 2f, p.y - n.size.height / 2f))
        }
    }

    // El coche: flecha blanca con brillo azul, girada según el rumbo (el mapa va con el norte arriba).
    if (carAt != null) {
        drawCircle(
            Brush.radialGradient(listOf(RouteGlow.copy(alpha = 0.4f), Color.Transparent), center = carAt, radius = 48 * u),
            48 * u, carAt, blendMode = BlendMode.Plus,
        )
        rotate(carBearing, carAt) {
            val arrow = Path().apply {
                moveTo(carAt.x, carAt.y - 18 * u)
                lineTo(carAt.x + 13 * u, carAt.y + 13 * u)
                lineTo(carAt.x, carAt.y + 6 * u)
                lineTo(carAt.x - 13 * u, carAt.y + 13 * u)
                close()
            }
            drawPath(arrow, Color.White)
            drawPath(arrow, RouteGlow, style = Stroke(1.5f * u, join = StrokeJoin.Round))
        }
    }

    val att = tm.measure(provider.attribution, labelStyle)
    drawText(att, Color.White.copy(alpha = 0.3f), Offset(bounds.right - att.size.width - 12 * u, bounds.bottom - att.size.height - 8 * u))
}
