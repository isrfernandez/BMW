package com.cockpitf30.ui.components

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.cockpitf30.weather.WeatherKind
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Intensidad de la precipitación según el código WMO (0 = nada, 1 = fuerte). */
fun precipitation(code: Int): Float = when (code) {
    51, 56 -> 0.25f
    53, 61, 80 -> 0.45f
    55, 57, 63, 66, 71, 81, 85 -> 0.7f
    65, 67, 73, 75, 77, 82, 86, 95, 96, 99 -> 1f
    else -> 0.5f
}

/** Segundos de animación; solo avanza mientras la pantalla está en primer plano. */
@Composable
private fun rememberSceneTime(): State<Float> {
    val time = remember { mutableFloatStateOf(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val offset = (time.floatValue * 1e9).toLong()
            val start = withFrameNanos { it } - offset
            while (true) withFrameNanos { time.floatValue = (it - start) / 1e9f }
        }
    }
    return time
}

/** Semillas fijas de las partículas: x, velocidad, tamaño y fase en [0,1). Sin objetos nuevos por frame. */
private class Particles(count: Int, seed: Int) {
    val n = count
    val x = FloatArray(count)
    val speed = FloatArray(count)
    val size = FloatArray(count)
    val phase = FloatArray(count)

    init {
        val r = Random(seed)
        for (i in 0 until count) {
            x[i] = r.nextFloat(); speed[i] = r.nextFloat(); size[i] = r.nextFloat(); phase[i] = r.nextFloat()
        }
    }
}

/** Nubes pre-renderizadas y objetos reutilizables de una escena, creados una vez por tamaño. */
private class SceneCache(val clouds: List<ImageBitmap>, val darkClouds: List<ImageBitmap>, val flake: ImageBitmap) {
    val bolt = Path()
    var boltCycle = -1
    val gust = Path()
}

/**
 * Escena animada y realista del tiempo: cielo degradado, sol volumétrico o luna con estrellas,
 * cúmulos esponjosos en tres capas con parallax, lluvia en dos profundidades inclinada por el
 * viento real, tormenta con rayos ramificados, nieve desenfocada, niebla y ráfagas de viento.
 * [maxParticles] limita el coste (unas 110 a pantalla completa, menos en la tarjeta).
 */
@Composable
fun WeatherScene(
    kind: WeatherKind, isDay: Boolean, code: Int, modifier: Modifier = Modifier, maxParticles: Int = 100,
    /** Posición del sol o la luna, en fracción del ancho y alto. */
    sunAt: Offset = Offset(0.78f, 0.3f),
    windKmh: Int = 0,
    /** De dónde viene el viento (grados, 0 = norte). */
    windDir: Int = 270,
    /** Luz cálida en el horizonte (amanecer o atardecer). */
    warm: Boolean = false,
    drawSky: Boolean = true,
) {
    val time = rememberSceneTime()
    val drops = remember(maxParticles) { Particles(maxParticles, 1) }
    val stars = remember { Particles(50, 2) }
    val intensity = precipitation(code)
    // Hacia dónde sopla: componente horizontal (+ derecha), y fuerza 0..1.
    val windX = -sin(Math.toRadians(windDir.toDouble())).toFloat()
    val windForce = (windKmh / 60f).coerceIn(0f, 1f)
    Spacer(
        // Capa propia: la escena se anima en cada fotograma y no debe repintar lo que la rodea.
        modifier.graphicsLayer().clipToBounds().drawWithCache {
            val cloudW = (size.width * 0.36f).toInt()
            val cache = SceneCache(
                clouds = List(3) { renderCloud(cloudW, 11 + it * 7, false, this) },
                darkClouds = List(3) { renderCloud(cloudW, 31 + it * 5, true, this) },
                flake = renderFlake((size.minDimension * 0.03f).toInt().coerceIn(8, 40), this),
            )
            onDrawBehind {
                val t = time.value
                if (drawSky) sky(kind, isDay, warm)
                val sun = Offset(size.width * sunAt.x, size.height * sunAt.y)
                when (kind) {
                    WeatherKind.CLEAR -> {
                        if (isDay) glowSun(sun, size.minDimension * 0.13f, t) else night(t, stars, sun)
                        cloudLayers(t, cache.clouds, density = 0.25f, windX, windForce)
                    }
                    WeatherKind.PARTLY_CLOUDY -> {
                        if (isDay) glowSun(sun, size.minDimension * 0.11f, t) else night(t, stars, sun)
                        cloudLayers(t, cache.clouds, density = 0.65f, windX, windForce)
                    }
                    WeatherKind.CLOUDY -> cloudLayers(t, if (isDay) cache.clouds else cache.darkClouds, density = 1f, windX, windForce)
                    WeatherKind.FOG -> {
                        cloudLayers(t, cache.clouds, density = 0.5f, windX, windForce, alpha = 0.6f)
                        fog(t)
                    }
                    WeatherKind.DRIZZLE, WeatherKind.RAIN -> {
                        cloudLayers(t, cache.darkClouds, density = 1f, windX, windForce)
                        rain(t, drops, (drops.n * intensity).toInt().coerceAtLeast(14), intensity, windX * windForce, fine = kind == WeatherKind.DRIZZLE)
                    }
                    WeatherKind.STORM -> {
                        val flash = flashLevel(t)
                        cloudLayers(t, cache.darkClouds, density = 1f, windX, windForce, glow = flash)
                        rain(t, drops, drops.n, 1f, windX * windForce, fine = false)
                        lightning(t, flash, cache)
                    }
                    WeatherKind.SNOW -> {
                        cloudLayers(t, cache.clouds, density = 0.8f, windX, windForce)
                        snow(t, drops, (drops.n * intensity).toInt().coerceAtLeast(18), cache.flake, windX * windForce)
                    }
                }
                if (windKmh > 15) gusts(t, windKmh, windX, cache.gust)
            }
        },
    )
}

private fun DrawScope.sky(kind: WeatherKind, isDay: Boolean, warm: Boolean) {
    val colors = when {
        !isDay -> listOf(Color(0xFF02060F), Color(0xFF0A1630), Color(0xFF14254A))
        kind == WeatherKind.STORM -> listOf(Color(0xFF1B1726), Color(0xFF2E2B40), Color(0xFF433F57))
        kind == WeatherKind.CLEAR || kind == WeatherKind.PARTLY_CLOUDY -> listOf(Color(0xFF0B4AA8), Color(0xFF1F6FD0), Color(0xFF3A8EE6))
        kind == WeatherKind.SNOW || kind == WeatherKind.FOG -> listOf(Color(0xFF5C6E86), Color(0xFF8295AD), Color(0xFFA9B8CA))
        else -> listOf(Color(0xFF34465E), Color(0xFF4D627D), Color(0xFF6B7F99))
    }
    drawRect(Brush.verticalGradient(colors))
    if (warm) drawRect(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xFFFF9A4D).copy(alpha = 0.45f)))
}

/** Luna y estrellas que titilan. */
private fun DrawScope.night(t: Float, stars: Particles, at: Offset) {
    for (i in 0 until stars.n) {
        val a = 0.3f + 0.7f * (0.5f + 0.5f * sin(t * (0.8f + stars.speed[i] * 2f) + stars.phase[i] * 6.28f))
        drawCircle(Color.White.copy(alpha = a), 0.8f + stars.size[i] * 1.8f, Offset(stars.x[i] * size.width, stars.phase[i] * size.height * 0.75f))
    }
    softMoon(at, size.minDimension * 0.1f)
}

/**
 * Tres capas de cúmulos con parallax: al fondo pequeñas y tenues, delante grandes. [density]
 * decide cuántas nubes se ven. Se desplazan hacia donde sopla el viento.
 */
private fun DrawScope.cloudLayers(
    t: Float, bitmaps: List<ImageBitmap>, density: Float, windX: Float, windForce: Float,
    alpha: Float = 1f, glow: Float = 0f,
) {
    val dir = if (windX < 0) -1f else 1f
    val layers = listOf(Triple(0.55f, 0.55f, 0.04f), Triple(0.8f, 0.8f, 0.14f), Triple(1.1f, 0.95f, 0.26f))
    layers.forEachIndexed { li, (scale, a, yFrac) ->
        val count = (1 + density * 3).toInt() - (if (li == 0 && density < 0.5f) 1 else 0)
        val speed = size.width * (0.006f + 0.012f * li) * (0.6f + windForce * 2f)
        val bmp = bitmaps[li % bitmaps.size]
        val w = bmp.width * scale
        val h = bmp.height * scale
        val span = size.width + w
        for (k in 0 until count) {
            val base = k * span / count + li * 173f
            val x = ((base + dir * t * speed) % span + span) % span - w
            val y = size.height * yFrac + (k % 2) * size.height * 0.05f
            drawImage(
                bmp, dstOffset = IntOffset(x.toInt(), y.toInt()), dstSize = IntSize(w.toInt(), h.toInt()),
                alpha = (a * alpha).coerceIn(0f, 1f),
            )
            if (glow > 0f) {
                // Los relámpagos iluminan las nubes desde dentro.
                drawOval(Brush.radialGradient(listOf(Color(0xFFE8E4FF).copy(alpha = glow * 0.5f), Color.Transparent), Offset(x + w / 2, y + h * 0.6f), w * 0.45f), Offset(x, y), Size(w, h))
            }
        }
    }
}

/**
 * Lluvia en dos profundidades: lejos gotas finas y lentas, cerca más gruesas y rápidas. Trazos
 * con cola tenue y cabeza más opaca, inclinados por el viento, y salpicaduras abajo.
 */
private fun DrawScope.rain(t: Float, p: Particles, count: Int, intensity: Float, wind: Float, fine: Boolean) {
    val n = count.coerceAtMost(p.n)
    val slant = 0.18f + wind * 0.6f
    for (i in 0 until n) {
        val near = i % 3 == 0
        val v = (if (near) 1.6f else 1.0f) + p.speed[i] * 0.6f
        val len = size.height * (if (fine) 0.035f else if (near) 0.09f else 0.055f) * (0.7f + intensity * 0.5f)
        val y = ((p.phase[i] + t * v) % 1.15f) * size.height - len
        val x = p.x[i] * size.width * 1.3f - size.width * 0.15f + (y / size.height) * slant * size.height
        val dx = len * slant
        val width = (if (fine) 1f else if (near) 2.4f else 1.4f) * (0.8f + p.size[i] * 0.5f)
        drawLine(Color(0xFFCFE6FF).copy(alpha = if (near) 0.25f else 0.15f), Offset(x - dx, y - len), Offset(x, y), width, StrokeCap.Round)
        drawLine(Color(0xFFE6F3FF).copy(alpha = if (near) 0.75f else 0.45f), Offset(x - dx * 0.35f, y - len * 0.35f), Offset(x, y), width, StrokeCap.Round)
        if (near && !fine && y > size.height * 0.92f) {
            val s = (y - size.height * 0.92f) / (size.height * 0.08f)
            drawOval(Color(0xFFE6F3FF).copy(alpha = (0.5f * (1f - s)).coerceAtLeast(0f)), Offset(x - 8f * s - 3f, size.height - 6f), Size(16f * s + 6f, 4f))
        }
    }
}

/** Copos desenfocados de varios tamaños que caen con vaivén y deriva por el viento. */
private fun DrawScope.snow(t: Float, p: Particles, count: Int, flake: ImageBitmap, wind: Float) {
    for (i in 0 until count.coerceAtMost(p.n)) {
        val near = i % 4 == 0
        val v = (if (near) 0.16f else 0.08f) + p.speed[i] * 0.1f
        val y = ((p.phase[i] + t * v) % 1.1f) * size.height - 20f
        val x = p.x[i] * size.width + sin(t * (0.6f + p.speed[i]) + p.phase[i] * 6.28f) * size.width * 0.02f + wind * y * 0.3f
        val s = (flake.width * (if (near) 1f else 0.55f) * (0.6f + p.size[i] * 0.6f)).toInt()
        drawImage(flake, dstOffset = IntOffset(((x % size.width) + size.width).toInt() % size.width.toInt(), y.toInt()), dstSize = IntSize(s, s), alpha = if (near) 0.95f else 0.7f)
    }
}

/** Intensidad del destello en este instante (0 si no hay relámpago). */
private fun flashLevel(t: Float): Float {
    val local = t % 6.5f
    return when {
        local < 0.07f -> 1f
        local < 0.14f -> 0.3f
        local < 0.21f -> 0.85f
        local < 0.45f -> 0.85f * (1f - (local - 0.21f) / 0.24f)
        else -> 0f
    }
}

/** Rayo ramificado (uno distinto en cada descarga) e iluminación breve del cielo. */
private fun DrawScope.lightning(t: Float, flash: Float, cache: SceneCache) {
    if (flash <= 0f) return
    val cycle = (t / 6.5f).toInt()
    if (cycle != cache.boltCycle) {
        cache.boltCycle = cycle
        val x0 = size.width * (0.2f + 0.6f * Random(cycle).nextFloat())
        lightningPath(Offset(x0, size.height * 0.18f), size.height * 0.7f, cycle, cache.bolt)
    }
    drawRect(Color(0xFFDCD8FF).copy(alpha = flash * 0.22f))
    drawPath(cache.bolt, Color(0xFFB9B2FF).copy(alpha = flash * 0.35f), style = Stroke(12f, cap = StrokeCap.Round))
    drawPath(cache.bolt, Color.White.copy(alpha = flash), style = Stroke(3f, cap = StrokeCap.Round))
}

/** Bancos de niebla: grandes manchas difusas que se desplazan. */
private fun DrawScope.fog(t: Float) {
    for (i in 0 until 6) {
        val y = size.height * (0.3f + i * 0.12f)
        val r = size.width * (0.28f + (i % 3) * 0.06f)
        val span = size.width + r * 2
        val x = ((t * size.width * (0.012f + i * 0.004f) + i * 311f) % span) - r
        drawOval(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent), Offset(x, y), r),
            Offset(x - r, y - r * 0.25f), Size(r * 2, r * 0.5f),
        )
    }
}

/** Líneas de flujo curvas y translúcidas que cruzan la pantalla a la velocidad del viento real. */
private fun DrawScope.gusts(t: Float, windKmh: Int, windX: Float, path: Path) {
    val dir = if (windX < 0) -1f else 1f
    val speed = size.width * (0.15f + windKmh / 100f)
    val count = if (windKmh > 35) 7 else 4
    for (i in 0 until count) {
        val len = size.width * (0.18f + (i % 3) * 0.06f)
        val span = size.width + len * 2
        val head = ((t * speed + i * span / count) % span) - len
        val x = if (dir > 0) head else size.width - head
        val y = size.height * (0.2f + (i * 0.137f) % 0.65f)
        val amp = size.height * 0.02f
        path.reset()
        path.moveTo(x - dir * len, y)
        path.cubicTo(x - dir * len * 0.66f, y - amp, x - dir * len * 0.33f, y + amp, x, y + amp * cos(t + i))
        drawPath(path, Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.35f)), if (dir > 0) x - len else x, if (dir > 0) x else x + len), style = Stroke(2.2f, cap = StrokeCap.Round))
    }
}
