package com.cockpitf30.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.cockpitf30.nav.GeoPoint
import com.cockpitf30.nav.MapTiles
import com.cockpitf30.nav.RouteState
import com.cockpitf30.nav.TileMath
import com.cockpitf30.ui.theme.Cockpit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Fondo del mapa: azul marino casi negro, a juego con el panel del coche. */
private val MapBase = Color(0xFF050B14)
private val RouteGlow = Color(0xFF29B6F6)
private val RouteCore = Color(0xFFBFE9FF)

/**
 * Las teselas oscuras vienen en grises: las pasamos a luminancia y las teñimos de azul acero,
 * algo más oscuras, para que el mapa quede "apagado" detrás del cuadro y la ruta destaque.
 */
private val MapTint = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            // Ganancia > 1 y desplazamiento negativo: el fondo gris se hunde y las calles se separan.
            0.3f * 0.50f, 0.59f * 0.50f, 0.11f * 0.50f, 0f, -10f,
            0.3f * 0.80f, 0.59f * 0.80f, 0.11f * 0.80f, 0f, -12f,
            0.3f * 1.30f, 0.59f * 1.30f, 0.11f * 1.30f, 0f, -14f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

/** OpenStreetMap viene claro: invertimos la luminancia (fondo oscuro, calles claras) y teñimos igual. */
private val MapTintInverted = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -0.3f * 0.30f, -0.59f * 0.30f, -0.11f * 0.30f, 0f, 2f + 255f * 0.30f,
            -0.3f * 0.48f, -0.59f * 0.48f, -0.11f * 0.48f, 0f, 6f + 255f * 0.48f,
            -0.3f * 0.80f, -0.59f * 0.80f, -0.11f * 0.80f, 0f, 14f + 255f * 0.80f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

/** Estilos del texto del mapa. */
class MapText(val hud: TextStyle, val small: TextStyle, val alert: TextStyle)

/** Lo que el mapa necesita para dibujarse en un fotograma. */
class MapFrame(
    val route: RouteState,
    /** Posición suavizada entre dos lecturas del GPS. */
    val position: GeoPoint?,
    val bearing: Float,
    val zoom: Float,
    /** Fase de las animaciones, 0..1 en bucle. */
    val phase: Float,
)

/**
 * Mapa oscuro con el rumbo hacia arriba: el coche fijo abajo en el centro, la ruta recorrida en
 * cian luminoso, rejilla triangular y datos de rumbo y coordenadas, estilo "sistema especial".
 * Se dibuja dentro de [bounds]; quien llama ya ha recortado al panel.
 */
fun DrawScope.drawMap(bounds: Rect, frame: MapFrame, tm: TextMeasurer, text: MapText) {
    val provider = MapTiles.provider.value
    val u = density
    drawRect(MapBase, bounds.topLeft, bounds.size)
    val anchor = Offset(bounds.center.x, bounds.top + bounds.height * 0.64f)
    val pos = frame.position

    // Por encima del zoom máximo del servidor se amplía su última tesela.
    val z = minOf(floor(frame.zoom).toInt(), provider.maxZoom)
    val k = u * 2f.pow(frame.zoom - z)
    val tile = TileMath.TILE * k
    val tint = if (provider.dark) MapTint else MapTintInverted

    if (pos != null) {
        val wx = TileMath.worldX(pos.lon, z)
        val wy = TileMath.worldY(pos.lat, z)
        // Radio que cubre el panel aunque esté girado.
        val reach = maxOf(
            hypot(anchor.x - bounds.left, anchor.y - bounds.top),
            hypot(bounds.right - anchor.x, anchor.y - bounds.top),
            hypot(anchor.x - bounds.left, bounds.bottom - anchor.y),
        ).toDouble() / k
        rotate(-frame.bearing, anchor) {
            drawGrid(anchor, wx, wy, k, reach)
            val x0 = floor((wx - reach) / TileMath.TILE).toInt(); val x1 = floor((wx + reach) / TileMath.TILE).toInt()
            val y0 = floor((wy - reach) / TileMath.TILE).toInt(); val y1 = floor((wy + reach) / TileMath.TILE).toInt()
            for (ty in y0..y1) for (tx in x0..x1) {
                val left = anchor.x + ((tx * TileMath.TILE - wx) * k).toFloat()
                val top = anchor.y + ((ty * TileMath.TILE - wy) * k).toFloat()
                val dst = IntOffset(floor(left).toInt(), floor(top).toInt())
                val size = IntSize(ceil(left + tile).toInt() - dst.x + 1, ceil(top + tile).toInt() - dst.y + 1)
                val img = MapTiles.get(z, tx, ty)
                if (img != null) {
                    drawImage(img, dstOffset = dst, dstSize = size, colorFilter = tint, filterQuality = FilterQuality.Medium)
                } else {
                    // Mientras llega, la tesela del zoom anterior ampliada (si está en memoria).
                    MapTiles.peek(z - 1, tx shr 1, ty shr 1)?.let { parent ->
                        val half = parent.width / 2
                        drawImage(
                            parent, srcOffset = IntOffset((tx and 1) * half, (ty and 1) * half), srcSize = IntSize(half, half),
                            dstOffset = dst, dstSize = size, colorFilter = tint, filterQuality = FilterQuality.Medium,
                        )
                    }
                }
            }
            drawTrack(frame.route.track, anchor, wx, wy, z, k)
        }
    } else {
        drawGrid(anchor, 0.0, 0.0, k, (bounds.width / k).toDouble())
    }

    drawHud(bounds, anchor, frame, tm, text)
}

/** Rejilla fina que se mueve con el mapa: se ve aunque no hayan llegado las teselas. */
private fun DrawScope.drawGrid(anchor: Offset, wx: Double, wy: Double, k: Float, reach: Double) {
    val step = TileMath.TILE / 4.0
    val color = RouteGlow.copy(alpha = 0.06f)
    val r = (reach * k).toFloat()
    var gx = floor((wx - reach) / step) * step
    while (gx <= wx + reach) {
        val x = anchor.x + ((gx - wx) * k).toFloat()
        drawLine(color, Offset(x, anchor.y - r), Offset(x, anchor.y + r), density)
        gx += step
    }
    var gy = floor((wy - reach) / step) * step
    while (gy <= wy + reach) {
        val y = anchor.y + ((gy - wy) * k).toFloat()
        drawLine(color, Offset(anchor.x - r, y), Offset(anchor.x + r, y), density)
        gy += step
    }
}

/** Ruta recorrida: varias pasadas de trazo para que parezca luz, más brillante cerca del coche. */
private fun DrawScope.drawTrack(track: List<GeoPoint>, anchor: Offset, wx: Double, wy: Double, z: Int, k: Float) {
    if (track.size < 2) return
    val path = Path()
    track.forEachIndexed { i, p ->
        val x = anchor.x + ((TileMath.worldX(p.lon, z) - wx) * k).toFloat()
        val y = anchor.y + ((TileMath.worldY(p.lat, z) - wy) * k).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    // Hasta el coche, para que la línea no se quede corta entre dos lecturas.
    path.lineTo(anchor.x, anchor.y)
    val u = density
    fun stroke(w: Float) = Stroke(w * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawPath(path, RouteGlow.copy(alpha = 0.10f), style = stroke(22f), blendMode = BlendMode.Plus)
    drawPath(path, RouteGlow.copy(alpha = 0.22f), style = stroke(11f), blendMode = BlendMode.Plus)
    drawPath(path, RouteGlow.copy(alpha = 0.85f), style = stroke(5f))
    drawPath(path, RouteCore, style = stroke(1.8f))
}

/** Viñeta, malla triangular, barrido, flecha del coche y datos de navegación. */
private fun DrawScope.drawHud(bounds: Rect, anchor: Offset, frame: MapFrame, tm: TextMeasurer, text: MapText) {
    val u = density
    // Malla triangular arriba, como el fondo del cuadro del iDrive 8, que se desvanece hacia abajo.
    val meshBrush = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0.07f), Color.Transparent), startY = bounds.top, endY = bounds.top + bounds.height * 0.55f,
    )
    val step = 46f * u
    val slope = 1.732f
    val h = bounds.height * 0.55f
    var x = bounds.left - h / slope
    while (x < bounds.right + h / slope) {
        drawLine(meshBrush, Offset(x, bounds.top), Offset(x + h / slope, bounds.top + h), u)
        drawLine(meshBrush, Offset(x, bounds.top), Offset(x - h / slope, bounds.top + h), u)
        x += step
    }
    var y = bounds.top
    while (y < bounds.top + h) {
        drawLine(meshBrush, Offset(bounds.left, y), Offset(bounds.right, y), u)
        y += step * slope / 2
    }

    // Viñeta: el mapa se apaga hacia los bordes y deja ver solo el entorno del coche.
    drawRect(
        Brush.radialGradient(
            0f to Color.Transparent, 0.45f to Color.Transparent, 1f to MapBase.copy(alpha = 0.92f),
            center = anchor, radius = bounds.width * 0.42f,
        ),
        bounds.topLeft, bounds.size,
    )
    drawRect(
        Brush.verticalGradient(listOf(MapBase.copy(alpha = 0.85f), Color.Transparent), startY = bounds.top, endY = bounds.top + bounds.height * 0.3f),
        bounds.topLeft, Size(bounds.width, bounds.height * 0.3f),
    )

    // Barrido de escáner muy tenue, de arriba abajo.
    val sweepY = bounds.top + bounds.height * frame.phase
    drawRect(
        Brush.verticalGradient(listOf(Color.Transparent, RouteGlow.copy(alpha = 0.05f), Color.Transparent), startY = sweepY - 40 * u, endY = sweepY + 40 * u),
        Offset(bounds.left, sweepY - 40 * u), Size(bounds.width, 80 * u), blendMode = BlendMode.Plus,
    )

    val pos = frame.position
    if (pos == null) {
        val pulse = 0.5f + 0.5f * sin(frame.phase * 2 * Math.PI).toFloat()
        val l = tm.measure("BUSCANDO SEÑAL GPS", text.alert)
        drawText(l, color = RouteCore.copy(alpha = 0.4f + 0.5f * pulse), topLeft = Offset(anchor.x - l.size.width / 2f, anchor.y - l.size.height / 2f - 40 * u))
        drawCircle(RouteGlow.copy(alpha = 0.5f * (1 - frame.phase)), radius = (12 + 60 * frame.phase) * u, center = anchor, style = Stroke(1.5f * u))
        return
    }

    // Luz bajo el coche y onda que se expande.
    drawCircle(
        Brush.radialGradient(listOf(RouteGlow.copy(alpha = 0.35f), Color.Transparent), center = anchor, radius = 70 * u),
        radius = 70 * u, center = anchor, blendMode = BlendMode.Plus,
    )
    val ring = (frame.phase * 2f) % 1f
    drawCircle(RouteGlow.copy(alpha = 0.45f * (1 - ring)), radius = (16 + 40 * ring) * u, center = anchor, style = Stroke(1.5f * u))

    // Flecha del coche (siempre hacia arriba: el mapa gira).
    val arrow = Path().apply {
        moveTo(anchor.x, anchor.y - 20 * u)
        lineTo(anchor.x + 14 * u, anchor.y + 14 * u)
        lineTo(anchor.x, anchor.y + 6 * u)
        lineTo(anchor.x - 14 * u, anchor.y + 14 * u)
        close()
    }
    drawPath(arrow, RouteGlow.copy(alpha = 0.5f), style = Stroke(6 * u, join = StrokeJoin.Round), blendMode = BlendMode.Plus)
    drawPath(arrow, Color.White)
    drawPath(arrow, RouteGlow, style = Stroke(1.5f * u, join = StrokeJoin.Round))

    // Rumbo arriba en el centro: "NO 312°" con marcas de brújula a los lados.
    val heading = ((frame.bearing % 360) + 360) % 360
    val hl = tm.measure("${cardinal(heading)}  ${heading.roundToInt()}°", text.hud)
    val hy = bounds.top + 14 * u
    drawText(hl, color = Color.White.copy(alpha = 0.9f), topLeft = Offset(bounds.center.x - hl.size.width / 2f, hy))
    for (i in 1..6) {
        val dx = hl.size.width / 2f + 14 * u + i * 12 * u
        val a = 0.5f - i * 0.07f
        val len = if (i % 3 == 0) 10 * u else 5 * u
        val cy = hy + hl.size.height / 2f
        drawLine(RouteGlow.copy(alpha = a), Offset(bounds.center.x - dx, cy - len / 2), Offset(bounds.center.x - dx, cy + len / 2), 1.5f * u)
        drawLine(RouteGlow.copy(alpha = a), Offset(bounds.center.x + dx, cy - len / 2), Offset(bounds.center.x + dx, cy + len / 2), 1.5f * u)
    }

    // Coordenadas bajo el coche y escala.
    val coords = "%.5f° %s   %.5f° %s".format(abs(pos.lat), if (pos.lat >= 0) "N" else "S", abs(pos.lon), if (pos.lon >= 0) "E" else "O")
    val cl = tm.measure(coords, text.small)
    // A la derecha del coche, para que la ruta (que sale hacia abajo) no las tape.
    val cx = anchor.x + 44 * u
    val cy = anchor.y - cl.size.height / 2f
    drawLine(RouteGlow.copy(alpha = 0.4f), Offset(anchor.x + 22 * u, anchor.y), Offset(cx - 6 * u, anchor.y), u)
    drawText(cl, color = RouteCore.copy(alpha = 0.6f), topLeft = Offset(cx, cy))
    if (!frame.route.live) {
        val dl = tm.measure("SIMULACIÓN", text.small)
        drawText(dl, color = Cockpit.Alert.copy(alpha = 0.8f), topLeft = Offset(cx, cy + cl.size.height + 2 * u))
    }

    val z = floor(frame.zoom).toInt()
    val metersPerPx = TileMath.metersPerPixel(pos.lat, z) / 2.0.pow((frame.zoom - z).toDouble()) / u
    val meters = listOf(50, 100, 200, 500, 1000).lastOrNull { it / metersPerPx < 110 * u } ?: 50
    val barW = (meters / metersPerPx).toFloat()
    val bx = bounds.center.x + 150 * u
    val by = bounds.bottom - 34 * u
    drawLine(Color.White.copy(alpha = 0.5f), Offset(bx, by), Offset(bx + barW, by), 1.5f * u)
    drawLine(Color.White.copy(alpha = 0.5f), Offset(bx, by - 4 * u), Offset(bx, by + 4 * u), 1.5f * u)
    drawLine(Color.White.copy(alpha = 0.5f), Offset(bx + barW, by - 4 * u), Offset(bx + barW, by + 4 * u), 1.5f * u)
    val sl = tm.measure(if (meters >= 1000) "${meters / 1000} km" else "$meters m", text.small)
    drawText(sl, color = Color.White.copy(alpha = 0.55f), topLeft = Offset(bx + barW + 8 * u, by - sl.size.height / 2f))

    // Atribución obligatoria de los datos del mapa.
    val provider = MapTiles.provider.value
    val att = tm.measure(provider.attribution, text.small)
    drawText(att, color = Color.White.copy(alpha = 0.28f), topLeft = Offset(bounds.center.x - 150 * u - att.size.width, by - att.size.height / 2f))
    // Si el servidor del mapa falla, lo decimos (ayuda a saber por qué no se ven calles).
    MapTiles.lastError.value?.let { err ->
        val el = tm.measure("MAPA · $err", text.small)
        drawText(el, color = Cockpit.Alert.copy(alpha = 0.85f), topLeft = Offset(bounds.center.x - 150 * u - el.size.width, by - att.size.height / 2f - el.size.height - 4 * u))
    }
}

private fun cardinal(deg: Float): String {
    val names = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
    return names[((deg + 22.5f) / 45f).toInt() % 8]
}
