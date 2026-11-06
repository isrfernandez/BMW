package com.cockpitf30.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import android.graphics.Bitmap
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cockpitf30.nav.GeoPoint
import com.cockpitf30.nav.MapTiles
import com.cockpitf30.nav.RouteState
import com.cockpitf30.nav.RouteTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.cockpitf30.R
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cockpitf30.ui.theme.Cockpit
import com.cockpitf30.ui.theme.numeric
import com.cockpitf30.vehicle.SourceStatus
import com.cockpitf30.vehicle.VehicleState
import com.cockpitf30.vehicle.GearEstimator
import kotlin.math.abs
import kotlin.math.hypot

private val Cyan = Color(0xFF29B6F6)
/** Cifras de la escala: champán en la velocidad, plata azulada en las rpm, rojo en la zona roja. */
private val SpeedLabel = Color(0xFFF6E7CC)
private val RpmLabel = Color(0xFFDCE8F6)
private val RpmRed = Color(0xFFFF4A3D)
/** Halo de las cifras grandes, del color de su marcador. */
private val SpeedGlow = Color(0xFFFFB45E)
private val RpmGlow = Color(0xFF5DA8FF)
/** Fondo del panel, del mismo azul marino que la escena del coche para que se funda. */
private val SceneTop = Color(0xFF091A2D)

/** Marcas de velocidad. Escala no lineal: 0–150 ocupa más recorrido que 150–250, como en el iDrive. */
private val SPEED_MARKS = listOf(0, 30, 60, 90, 120, 150, 200, 250)
private fun speedFraction(kmh: Int): Float {
    val v = kmh.coerceIn(0, 250)
    return if (v <= 150) v / 150f * 0.625f else 0.625f + (v - 150) / 100f * 0.375f
}

private const val RPM_MAX = 8000
private const val RPM_RED = 6000

/** Altura (0..1) de cada cifra de la escala, para grabar las marcas en la franja. */
private val SPEED_FRACTIONS = SPEED_MARKS.map(::speedFraction)
private val RPM_FRACTIONS = (0..8).map { it * 1000f / RPM_MAX }

/**
 * Cuadro de instrumentos estilo iDrive 8: marcos en chevron cian (velocidad) y rojo (rpm) con las
 * cifras por fuera, panel central con la imagen del coche en vista 3/4 (cerrado o con lo abierto en rojo) y sus indicadores,
 * velocidad y marcha en pestañas oscuras, combustible y aceite, y una línea de estado debajo.
 * Lo que no llega se muestra "--".
 */
@Composable
fun ClusterStrip(state: VehicleState, status: SourceStatus, modifier: Modifier = Modifier) {
    // Muelle en vez de tween: los datos llegan cada 50 ms y un tween reiniciado a cada dato va a
    // tirones; el muelle conserva la velocidad de la aguja al cambiar de objetivo.
    val needle = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    val speed by animateFloatAsState(state.speedKmh?.let(::speedFraction) ?: 0f, needle, label = "speed")
    val rpm by animateFloatAsState(((state.rpm ?: 0).coerceIn(0, RPM_MAX)).toFloat() / RPM_MAX, needle, label = "rpm")
    // El halo de las cifras solo necesita unos pocos niveles: así el texto no se recompone en cada fotograma.
    val speedGlow by remember { derivedStateOf { (speed * 10).roundToInt() / 10f } }
    val rpmGlow by remember { derivedStateOf { (rpm * 10).roundToInt() / 10f } }
    // Muchos rótulos se miden en cada fotograma (hay animación continua): caché amplia.
    val measurer = rememberTextMeasurer(cacheSize = 96)
    val scaleStyle = numeric(28, FontWeight.SemiBold)
    val smallStyle = numeric(17, FontWeight.SemiBold)
    val captionStyle = numeric(17, FontWeight.Medium)
    val homeRes = ImageBitmap.imageResource(R.drawable.home_car_scene)
    val trianglesRes = ImageBitmap.imageResource(R.drawable.bg_triangles)
    // Presiones y autonomía en grande para leerlas de un vistazo conduciendo.
    val overlay = Overlay(
        value = numeric(38, FontWeight.SemiBold),
        unit = numeric(19, FontWeight.Medium),
        caption = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp, letterSpacing = 1.sp),
        alert = MaterialTheme.typography.labelLarge,
    )
    val mapText = MapText(
        hud = numeric(20, FontWeight.SemiBold).copy(letterSpacing = 3.sp),
        small = numeric(14, FontWeight.Medium).copy(letterSpacing = 1.sp),
        alert = numeric(20, FontWeight.SemiBold).copy(letterSpacing = 4.sp),
    )

    // Fase de las animaciones de luz (destello de las barras, onda del GPS, barrido del mapa).
    val phase by rememberInfiniteTransition(label = "light").animateFloat(
        0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "phase",
    )

    // Zona central deslizante: 0 = coche, 1 = mapa. Al arrancar la app se ve el coche y a los
    // 10 s pasa al mapa; después el Inicio abre ya en el mapa (p. ej. al volver de la pantalla de
    // puertas). Deslizando u OK se vuelve a ver el coche, que se queda hasta volver a deslizar.
    var wantMap by remember { mutableStateOf(ClusterIntro.done) }
    val page = remember { Animatable(if (wantMap) 1f else 0f) }
    val scope = rememberCoroutineScope()
    fun choose(map: Boolean) {
        wantMap = map
        ClusterIntro.done = true
        scope.launch { page.animateTo(if (map) 1f else 0f, tween(450, easing = FastOutSlowInEasing)) }
    }
    LaunchedEffect(Unit) {
        if (ClusterIntro.done) return@LaunchedEffect
        delay(ClusterIntro.CAR_MS)
        if (!ClusterIntro.done) {
            ClusterIntro.done = true
            wantMap = true
            page.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        }
    }
    var focused by remember { mutableStateOf(false) }

    val route by RouteTracker.state.collectAsStateWithLifecycle()
    val tileVersion by MapTiles.version.collectAsStateWithLifecycle()
    val smooth = rememberSmoothRoute(route)
    val zoom by animateFloatAsState(
        17f - ((state.speedKmh ?: 0).coerceIn(0, 130) / 130f) * 2f, tween(2500), label = "zoom",
    )

    BoxWithConstraints(
        modifier
            .pointerInput(Unit) {
                // Deslizar a izquierda o derecha en el panel cambia entre coche y mapa.
                var start = 0f
                detectHorizontalDragGestures(
                    onDragStart = { start = page.value },
                    onDragEnd = {
                        val moved = page.value - start
                        choose(if (abs(moved) > 0.18f) moved > 0 else start > 0.5f)
                    },
                    onDragCancel = { choose(start > 0.5f) },
                ) { change, dx ->
                    change.consume()
                    val w = size.width * 0.55f
                    scope.launch { page.snapTo((page.value - dx / w).coerceIn(0f, 1f)) }
                }
            }
            // Ruleta: el panel es un elemento más; OK alterna entre coche y mapa.
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                choose(!wantMap)
            },
    ) {
        val g = Geometry(maxWidth, maxHeight)
        val scene = remember(homeRes, trianglesRes) { CarScene(homeRes, trianglesRes) }
        Canvas(Modifier.fillMaxSize()) {
            tileVersion // se vuelve a dibujar cuando llega una tesela
            val geo = g.px(this)
            val now = smooth.value // se lee al dibujar: el mapa se mueve sin recomponer el cuadro
            val map = MapFrame(route, now.position, now.bearing, zoom, phase)
            drawPanel(geo, scene, state, measurer, overlay, page.value, map, mapText, focused)
            drawFrame(geo, mirrored = false, fraction = speed, look = SpeedMetal, marks = SPEED_FRACTIONS, phase = phase)
            drawFrame(geo, mirrored = true, fraction = rpm, look = RpmMetal, marks = RPM_FRACTIONS, phase = (phase + 0.5f) % 1f)

            // El 0 no se rotula (no cabe colgando bajo la última marca); su marca sí se dibuja.
            SPEED_MARKS.drop(1).forEach { v -> drawMark(geo, measurer, "$v", speedFraction(v), false, scaleStyle, SpeedLabel, speed, SpeedMetal) }
            (1..8).forEach { k ->
                drawMark(geo, measurer, "$k", k * 1000f / RPM_MAX, true, scaleStyle, if (k * 1000 >= RPM_RED) RpmRed else RpmLabel, rpm, RpmMetal)
            }
            drawCaption(geo, measurer, "km/h", mirrored = false, captionStyle)
            drawCaption(geo, measurer, "1/min x 1000", mirrored = true, captionStyle)

            drawMiniBar(geo, measurer, state.fuelLevel, listOf("1", "1/2", "0"), mirrored = false, smallStyle)
            val oil = state.oilTempC?.let { ((it - 50) / 100f).coerceIn(0f, 1f) }
            drawMiniBar(geo, measurer, oil, listOf("150", "100", "50"), mirrored = true, smallStyle)
        }

        // Velocidad y marcha en pestañas oscuras sobre el panel, con halo del color de su barra.
        Row(
            Modifier.fillMaxWidth().height(g.frameBottom).padding(top = g.frameTop + 40.dp, bottom = 110.dp)
                .padding(start = g.contentInset, end = g.contentInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabValue(state.speedKmh?.toString() ?: "--", "km/h", Modifier.weight(1f), alignEnd = false, glow = SpeedGlow, intensity = speedGlow)
            Spacer(Modifier.weight(1.5f))
            TabValue(state.gear ?: GearEstimator.estimate(state.speedKmh, state.rpm) ?: "--", state.rpm?.let { "$it rpm" } ?: "-- rpm", Modifier.weight(1f), alignEnd = true, glow = RpmGlow, intensity = rpmGlow)
        }
        StatusLine(
            state, status,
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(g.statusHeight)
                .padding(start = g.scaleWidth - 10.dp, end = g.scaleWidth - 10.dp, top = 8.dp),
        )
    }
}

/** Presentación del Inicio: el coche se ve solo durante los primeros segundos tras arrancar la app. */
object ClusterIntro {
    const val CAR_MS = 10_000L
    @Volatile var done = false
}

/** Posición y rumbo suavizados: el GPS da una lectura por segundo y el mapa no debe ir a saltos. */
private class SmoothRoute(val position: GeoPoint?, val bearing: Float)

/**
 * Posición y rumbo suavizados como estado que se lee al dibujar (no en la composición). Cada tramo
 * dura lo que tardó en llegar el último dato: 1 s con GPS real, 50 ms en la demo, así el mapa va
 * a velocidad constante en vez de ir siempre por detrás reiniciando una animación de 1 s.
 */
@Composable
private fun rememberSmoothRoute(route: RouteState): State<SmoothRoute> {
    var from by remember { mutableStateOf(route.position) }
    var to by remember { mutableStateOf(route.position) }
    val progress = remember { Animatable(1f) }
    val bearing = remember { Animatable(route.bearing) }
    val lastUpdate = remember { longArrayOf(0L) }
    LaunchedEffect(route.position) {
        val target = route.position
        val current = from?.let { f -> to?.let { t -> lerp(f, t, progress.value) } } ?: target
        val now = System.currentTimeMillis()
        val interval = (now - lastUpdate[0]).coerceIn(50L, 1000L).toInt()
        lastUpdate[0] = now
        // Un salto grande (primer fijo, cambio de fuente) no se anima.
        if (current == null || target == null || RouteTracker.distance(current, target) > 300) {
            from = target; to = target; progress.snapTo(1f)
            return@LaunchedEffect
        }
        from = current; to = target
        progress.snapTo(0f)
        progress.animateTo(1f, tween(interval, easing = LinearEasing))
    }
    LaunchedEffect(route.bearing) {
        // Por el camino más corto (de 350° a 10° son 20°, no 340°). Muelle: no frena a cada dato.
        val delta = ((route.bearing - bearing.value) % 360 + 540) % 360 - 180
        bearing.animateTo(bearing.value + delta, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessVeryLow))
    }
    return remember {
        derivedStateOf {
            val f = from; val t = to
            SmoothRoute(if (f != null && t != null) lerp(f, t, progress.value) else t, bearing.value)
        }
    }
}

private fun lerp(a: GeoPoint, b: GeoPoint, f: Float) = GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)

private val PANEL_TOP = 16.dp
private val PANEL_BOTTOM = 10.dp

/** Medidas del cuadro en dp, compartidas entre el dibujo y los textos. */
private class Geometry(val width: Dp, val height: Dp) {
    val frameTop = 30.dp
    val statusHeight = 50.dp
    val frameBottom = height - statusHeight
    /** Margen exterior: las cifras van ya dentro de la franja, así que basta un respiro. */
    val scaleWidth = 18.dp
    /** Ancho de las barras laterales (antes 64 dp): caben dentro las cifras de la escala. */
    val band = 100.dp
    val cut = (frameBottom - frameTop) * 0.24f
    val contentInset = scaleWidth + cut + band + 20.dp
    /** Alto del panel central (y de la imagen del coche): ver [drawPanel]. */
    val carHeight = frameBottom - frameTop - PANEL_TOP - PANEL_BOTTOM

    fun px(scope: DrawScope) = with(scope) {
        GeoPx(
            w = size.width, y0 = frameTop.toPx(), y1 = frameBottom.toPx(),
            xv = (scaleWidth + 6.dp).toPx(), cut = cut.toPx(), band = band.toPx(), density = density,
        )
    }
}

private class GeoPx(val w: Float, val y0: Float, val y1: Float, val xv: Float, val cut: Float, val band: Float, val density: Float) {
    val ym = (y0 + y1) / 2
    val half = (y1 - y0) / 2
    fun dp(v: Float) = v * density

    /** x del chevron izquierdo desplazado [inset] hacia dentro, a la altura [y]. */
    fun chevronX(y: Float, inset: Float) = xv + inset + cut * abs(y - ym) / half
    fun mirror(x: Float, mirrored: Boolean) = if (mirrored) w - x else x
}

/** Polígono con las esquinas redondeadas. */
private fun roundedPolygon(points: List<Offset>, radius: Float) = Path().apply {
    val n = points.size
    for (i in 0 until n) {
        val prev = points[(i - 1 + n) % n]; val cur = points[i]; val next = points[(i + 1) % n]
        fun toward(from: Offset, to: Offset): Offset {
            val d = hypot(to.x - from.x, to.y - from.y)
            val r = minOf(radius, d / 2) / d
            return Offset(from.x + (to.x - from.x) * r, from.y + (to.y - from.y) * r)
        }
        val a = toward(cur, prev); val b = toward(cur, next)
        if (i == 0) moveTo(a.x, a.y) else lineTo(a.x, a.y)
        quadraticBezierTo(cur.x, cur.y, b.x, b.y)
    }
    close()
}

/** Estilos de los indicadores que van encima de la imagen del coche. */
/** Centro del coche en home_car_scene: el recorte del panel se centra aquí. */
private val CAR_CENTER = Offset(705f, 365f)

private class Overlay(val value: TextStyle, val unit: TextStyle, val caption: TextStyle, val alert: TextStyle)

/**
 * Panel central: la escena del coche visto desde atrás, ajustada a la altura del panel y fundida
 * con una viñeta oscura hacia los chevrons. Encima, presiones de las ruedas y testigos de
 * puertas, maletero y capó abiertos.
 */
/** Vista 3/4 del coche: cerrado y con puertas, capó y maletero abiertos (mismo encuadre). */
/** Escena del Inicio (coche 3/4 ya posado sobre el fondo de triángulos, 1394x768) y el fondo solo. */
private class CarScene(val home: ImageBitmap, val triangles: ImageBitmap)

private fun DrawScope.drawPanel(
    g: GeoPx, scene: CarScene, state: VehicleState, tm: TextMeasurer, st: Overlay,
    page: Float, map: MapFrame, mapText: MapText, focused: Boolean,
) {
    val inset = g.band + g.dp(14f)
    val top = g.y0 + g.dp(PANEL_TOP.value)
    val bottom = g.y1 - g.dp(PANEL_BOTTOM.value)
    val pts = listOf(
        Offset(g.chevronX(top, inset), top), Offset(g.w - g.chevronX(top, inset), top),
        Offset(g.w - g.xv - inset, g.ym), Offset(g.w - g.chevronX(bottom, inset), bottom),
        Offset(g.chevronX(bottom, inset), bottom), Offset(g.xv + inset, g.ym),
    )
    val panel = roundedPolygon(pts, g.dp(22f))

    val pw = g.w - 2 * (g.xv + inset)
    val bounds = Rect(g.xv + inset, top, g.w - g.xv - inset, bottom)

    clipPath(panel) {
        // La escena del coche ya trae su fondo: el de triángulos solo hace falta bajo el mapa.
        if (page > 0f) drawFacets(g, pts, scene.triangles, bounds)
        // Página del mapa, entrando por la derecha.
        if (page > 0f) translate(left = (1f - page) * pw) { clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) { drawMap(bounds, map, tm, mapText) } }
        if (page < 1f) translate(left = -page * pw) { clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) { drawCar(g, scene, state, tm, st, bounds) } }
    }
    val edge = if (focused) Cyan.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.10f)
    if (focused) drawPath(panel, Cyan.copy(alpha = 0.25f), style = Stroke(g.dp(8f)), blendMode = BlendMode.Plus)
    drawPath(panel, edge, style = Stroke(g.dp(if (focused) 2f else 1.2f)))
    drawPageDots(g, bottom, page)
}

/**
 * Fondo del hexágono en facetas: se unen los vértices con el centro y cada triángulo lleva su
 * degradado, más claro hacia el borde y con un tono distinto según mire a la luz (arriba a la
 * izquierda), como una pieza tallada. Las aristas brillan cerca de los vértices.
 */
private fun DrawScope.drawFacets(g: GeoPx, pts: List<Offset>, triangles: ImageBitmap, bounds: Rect) {
    val c = Offset(g.w / 2, g.ym)
    // Mismo fondo de triángulos que las pantallas secundarias, recortado para cubrir el panel.
    val scale = maxOf(bounds.width / triangles.width, bounds.height / triangles.height)
    val sw = (bounds.width / scale).roundToInt()
    val sh = (bounds.height / scale).roundToInt()
    drawImage(
        triangles,
        srcOffset = IntOffset((triangles.width - sw) / 2, (triangles.height - sh) / 2), srcSize = IntSize(sw, sh),
        dstOffset = IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()), dstSize = IntSize(bounds.width.roundToInt(), bounds.height.roundToInt()),
        filterQuality = FilterQuality.High,
    )
    // Velo hacia los bordes del hexágono para que el coche y los marcadores destaquen.
    drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)), center = c, radius = bounds.width * 0.55f), bounds.topLeft, bounds.size)
    // Aristas: del centro a cada vértice, invisibles en el centro y con brillo hacia el vértice.
    pts.forEach { v ->
        val brush = Brush.linearGradient(listOf(Color.Transparent, Color(0xFF9CC7FF).copy(alpha = 0.10f), Color(0xFFDDEBFF).copy(alpha = 0.55f)), start = c, end = v)
        drawLine(brush, c, v, g.dp(1.2f))
        drawLine(Brush.linearGradient(listOf(Color.Transparent, Cyan.copy(alpha = 0.22f)), start = c, end = v), c, v, g.dp(5f), blendMode = BlendMode.Plus)
    }
}

/** Dos marcas abajo en el centro: cuál de las dos vistas del panel se está viendo. */
private fun DrawScope.drawPageDots(g: GeoPx, bottom: Float, page: Float) {
    val y = bottom - g.dp(12f)
    val w = g.dp(26f)
    val gap = g.dp(8f)
    listOf(1f - page, page).forEachIndexed { i, on ->
        val x = g.w / 2 + (i - 1) * (w + gap) + gap / 2
        drawLine(Color.White.copy(alpha = 0.22f), Offset(x, y), Offset(x + w, y), g.dp(3f), StrokeCap.Round)
        if (on > 0.01f) {
            drawLine(Cyan.copy(alpha = on * 0.35f), Offset(x, y), Offset(x + w, y), g.dp(9f), StrokeCap.Round, blendMode = BlendMode.Plus)
            drawLine(Color.White.copy(alpha = on), Offset(x, y), Offset(x + w, y), g.dp(3f), StrokeCap.Round)
        }
    }
}

/**
 * Coche en vista 3/4 con presiones. Si hay algo abierto se funde con la imagen de puertas, capó y
 * maletero abiertos (brillo rojo) y se rotula arriba qué está abierto.
 */
private fun DrawScope.drawCar(
    g: GeoPx, scene: CarScene, state: VehicleState, tm: TextMeasurer, st: Overlay, bounds: Rect,
) {
    run {
        val top = bounds.top
        val ph = bounds.height
        val bottom = bounds.bottom
        // Escena recortada para cubrir el panel, ampliada un poco alrededor del coche.
        val img = scene.home
        val zoom = 1.22f
        val sc = maxOf(bounds.width / img.width, bounds.height / img.height) * zoom
        val sw = bounds.width / sc
        val sh = bounds.height / sc
        val sl = (CAR_CENTER.x - sw / 2).coerceIn(0f, img.width - sw)
        val st0 = (CAR_CENTER.y - sh / 2).coerceIn(0f, img.height - sh)
        drawImage(
            img,
            srcOffset = IntOffset(sl.roundToInt(), st0.roundToInt()), srcSize = IntSize(sw.roundToInt(), sh.roundToInt()),
            dstOffset = IntOffset(bounds.left.roundToInt(), top.roundToInt()), dstSize = IntSize(bounds.width.roundToInt(), ph.roundToInt()),
            filterQuality = FilterQuality.High,
        )
        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent), startY = top, endY = top + ph * 0.18f), Offset(0f, top), Size(size.width, ph * 0.18f))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.30f)), startY = bottom - ph * 0.15f, endY = bottom), Offset(0f, bottom - ph * 0.15f), Size(size.width, ph * 0.15f))

        // Punto de la escena (píxeles de home_car_scene) en pantalla.
        fun at(px: Float, py: Float) = Offset(bounds.left + (px - sl) * sc, top + (py - st0) * sc)
        val d = state.doors
        val red = Cockpit.Alert

        // Rótulos de lo que está abierto, arriba en el centro.
        val open = buildList {
            if (d.driver == true) add("PUERTA CONDUCTOR")
            if (d.passenger == true) add("PUERTA ACOMPAÑANTE")
            if (d.rearLeft == true) add("PUERTA TRASERA IZQ.")
            if (d.rearRight == true) add("PUERTA TRASERA DER.")
            if (d.trunk == true) add("MALETERO ABIERTO")
            if (d.hood == true) add("CAPÓ ABIERTO")
        }
        var y = top + g.dp(14f)
        open.forEach { text ->
            val l = tm.measure(text, st.alert)
            val pad = g.dp(10f)
            drawRoundRect(Color.Black.copy(alpha = 0.6f), Offset(g.w / 2 - l.size.width / 2f - pad, y - g.dp(3f)), Size(l.size.width + pad * 2, l.size.height + g.dp(6f)), CornerRadius(g.dp(6f)))
            drawText(l, color = red, topLeft = Offset(g.w / 2 - l.size.width / 2f, y))
            y += l.size.height + g.dp(12f)
        }

        // Presión de cada rueda con una línea hasta ella. Las del lado derecho quedan ocultas tras el
        // coche: su punto es solo un aro. Coordenadas medidas sobre home_car_scene (1394x768).
        val t = state.tyres
        fun bar(v: Float?) = v?.let { "%.1f".format(it) } ?: "--"
        marker(tm, st, "DEL. IZQ.", bar(t.frontLeft), " bar", at(687f, 457f), at(746f, 482f), alignRight = false, status = tyreStatusColor(t.frontLeft))
        marker(tm, st, "TRAS. IZQ.", bar(t.rearLeft), " bar", at(860f, 352f), at(930f, 395f), alignRight = false, status = tyreStatusColor(t.rearLeft))
        marker(tm, st, "DEL. DER.", bar(t.frontRight), " bar", at(548f, 432f), at(505f, 450f), alignRight = true, hidden = true, status = tyreStatusColor(t.frontRight))
        marker(tm, st, "TRAS. DER.", bar(t.rearRight), " bar", at(742f, 292f), at(560f, 270f), alignRight = true, hidden = true, status = tyreStatusColor(t.rearRight))
        // Autonomía arriba a la derecha, sin línea.
        marker(tm, st, "AUTONOMÍA", state.rangeKm?.toString() ?: "--", " km", null, at(905f, 250f), alignRight = false)
    }
}

/**
 * Marcador de cristal, igual que los de la pantalla Vehículo: panel ahumado con borde biselado del
 * color de estado, punto de estado, rótulo y valor en Saira. Con [wheel], una línea de luz hasta la
 * rueda con un punto brillante encima (solo el aro si la rueda queda oculta).
 */
private fun DrawScope.marker(
    tm: TextMeasurer, st: Overlay, caption: String, value: String, unit: String,
    wheel: Offset?, label: Offset, alignRight: Boolean, hidden: Boolean = false, status: Color = Cockpit.BlueLight,
) {
    val c = tm.measure(caption, st.caption)
    val v = tm.measure(value, st.value)
    val u = tm.measure(unit, st.unit)
    val gap = 8.dp.toPx()
    val padX = 16.dp.toPx()
    val padY = 9.dp.toPx()
    val dot = 12.dp.toPx()
    val width = maxOf(c.size.width + dot + 6.dp.toPx(), (v.size.width + u.size.width).toFloat()) + padX * 2
    val h = c.size.height + v.size.height + padY * 2
    val x = if (alignRight) label.x - gap - width else label.x + gap
    val y = label.y - h / 2f
    if (wheel != null) {
        val edge = Offset(if (alignRight) x + width else x, label.y)
        drawLine(Brush.linearGradient(listOf(status.copy(alpha = 0.85f), status.copy(alpha = 0.2f)), start = wheel, end = edge), wheel, edge, 1.5.dp.toPx())
        drawCircle(status.copy(alpha = if (hidden) 0.12f else 0.25f), 14.dp.toPx(), wheel)
        drawCircle(status.copy(alpha = if (hidden) 0.4f else 0.6f), 8.dp.toPx(), wheel, style = Stroke(1.5.dp.toPx()))
        if (!hidden) drawCircle(Color.White, 3.dp.toPx(), wheel)
    }
    // Panel de cristal.
    val r = CornerRadius(16.dp.toPx())
    val tl = Offset(x, y)
    val sz = Size(width, h)
    drawRoundRect(Color(0xFF050B14).copy(alpha = 0.55f), tl, sz, r)
    drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.02f), status.copy(alpha = 0.05f)), startY = y, endY = y + h), tl, sz, r)
    val sw = 1.2.dp.toPx()
    drawRoundRect(
        Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.42f), 0.3f to Color.White.copy(alpha = 0.10f),
            0.75f to Color.White.copy(alpha = 0.04f), 1f to status.copy(alpha = 0.5f),
            start = tl, end = Offset(x + width, y + h),
        ),
        Offset(x + sw / 2, y + sw / 2), Size(width - sw, h - sw), r, style = Stroke(sw),
    )
    drawLine(
        Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.45f), Color.Transparent), startX = x, endX = x + width),
        Offset(x + r.x, y + sw * 1.6f), Offset(x + width - r.x, y + sw * 1.6f), 1.dp.toPx(),
    )
    // Contenido: rótulo con punto de estado del lado de la rueda y valor grande.
    val inner = width - padX * 2
    val cy = y + padY
    val dotX: Float
    val capX: Float
    if (alignRight) {
        dotX = x + width - padX - dot / 2
        capX = x + width - padX - dot - 6.dp.toPx() - c.size.width
    } else {
        dotX = x + padX + dot / 2
        capX = x + padX + dot + 6.dp.toPx()
    }
    val dotC = Offset(dotX, cy + c.size.height / 2f)
    drawCircle(status.copy(alpha = 0.3f), dot / 2, dotC)
    drawCircle(status, dot / 4, dotC)
    drawText(c, color = Color.White.copy(alpha = 0.7f), topLeft = Offset(capX, cy))
    val vx = if (alignRight) x + padX + inner - v.size.width - u.size.width else x + padX
    drawText(v, color = Color.White, topLeft = Offset(vx, cy + c.size.height))
    drawText(u, color = Color.White.copy(alpha = 0.7f), topLeft = Offset(vx + v.size.width, cy + c.size.height + v.size.height - u.size.height - 6.dp.toPx()))
}

/**
 * Colores de un marcador en metal: [dark]/[mid]/[bright] forman el brillo cilíndrico del relleno,
 * [tip] la punta encendida y [light] la luz que se derrama hacia el centro del cuadro.
 */
private class MetalLook(val dark: Color, val mid: Color, val bright: Color, val tip: Color, val light: Color)

/** Velocidad en oro/champán; rpm en acero azulado. */
private val SpeedMetal = MetalLook(Color(0xFF4A2E12), Color(0xFFC8954E), Color(0xFFFFE3B3), Color(0xFFFFF6E6), Color(0xFFFFB45E))
private val RpmMetal = MetalLook(Color(0xFF14273F), Color(0xFF6E9CCB), Color(0xFFD9EBFF), Color(0xFFF2F8FF), Color(0xFF5DA8FF))
/** Acero de la franja vacía y del bisel. */
private val SteelMetal = MetalLook(Color(0xFF0E141D), Color(0xFF2C3B4E), Color(0xFF6E86A1), Color.White, Color(0xFF9FB8D3))

/**
 * Perfil de brillo a lo ancho de la franja (0 = borde exterior, 1 = interior), como una pieza de
 * metal curvada: filo brillante, banda clara, zona oscura y reflejo suave cerca del interior.
 */
private fun metalShade(look: MetalLook, t: Float): Color {
    val stops = listOf(
        0f to look.bright, 0.07f to look.mid, 0.30f to look.bright, 0.52f to look.dark,
        0.78f to look.mid, 0.92f to look.dark, 1f to look.dark,
    )
    for (i in 1 until stops.size) {
        val (t1, c1) = stops[i]
        if (t <= t1) {
            val (t0, c0) = stops[i - 1]
            return androidx.compose.ui.graphics.lerp(c0, c1, ((t - t0) / (t1 - t0)).coerceIn(0f, 1f))
        }
    }
    return look.dark
}

/**
 * Marco en chevron con aspecto metálico: bisel cromado por fuera, franja de acero con marcas de
 * escala y, de abajo arriba, el relleno del valor en metal pulido ([look]) con la punta encendida.
 */
private fun DrawScope.drawFrame(g: GeoPx, mirrored: Boolean, fraction: Float, look: MetalLook, marks: List<Float>, phase: Float) {
    fun o(x: Float, y: Float) = Offset(g.mirror(x, mirrored), y)
    fun chevron(inset: Float, top: Float, bottom: Float) = Path().apply {
        val a = o(g.chevronX(bottom, inset), bottom); val v = o(g.xv + inset, g.ym); val b = o(g.chevronX(top, inset), top)
        moveTo(a.x, a.y); lineTo(v.x, v.y); lineTo(b.x, b.y)
    }

    /** Franja entre el borde exterior y el interior, de la altura [yTop] a [yBottom]. */
    fun band(yTop: Float, yBottom: Float) = Path().apply {
        val outer = mutableListOf(o(g.chevronX(yBottom, 0f), yBottom))
        if (yTop < g.ym && yBottom > g.ym) outer += o(g.xv, g.ym)
        outer += o(g.chevronX(yTop, 0f), yTop)
        val inner = mutableListOf(o(g.chevronX(yTop, g.band), yTop))
        if (yTop < g.ym && yBottom > g.ym) inner += o(g.xv + g.band, g.ym)
        inner += o(g.chevronX(yBottom, g.band), yBottom)
        (outer + inner).forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        close()
    }

    /** Pinta la franja con el perfil metálico de [m] mediante chevrons concéntricos. */
    fun DrawScope.metal(m: MetalLook, alpha: Float) {
        val steps = 24
        val step = g.band / steps
        for (i in 0 until steps) {
            val t = (i + 0.5f) / steps
            drawPath(chevron(step * (i + 0.5f), g.y0 - g.band, g.y1 + g.band), metalShade(m, t).copy(alpha = alpha), style = Stroke(step + 1f))
        }
    }

    // Líneas horizontales arriba y abajo, en plata, que se pierden hacia el centro.
    val x0 = g.chevronX(g.y0, 0f)
    val topEnd = x0 + g.w * 0.17f
    val bottomEnd = g.w * 0.5f - g.dp(40f)
    val silver = Color(0xFFC9D6E4)
    val fade = if (mirrored) listOf(Color.Transparent, silver) else listOf(silver, Color.Transparent)
    drawLine(Brush.horizontalGradient(fade, o(x0, 0f).x, o(topEnd, 0f).x), o(x0, g.y0), o(topEnd, g.y0), g.dp(2f))
    drawLine(Brush.horizontalGradient(fade, o(x0, 0f).x, o(bottomEnd, 0f).x), o(g.chevronX(g.y1, 0f), g.y1), o(bottomEnd, g.y1), g.dp(2.5f))

    // Franja vacía: acero oscuro con reflejo claro arriba (la luz viene de arriba, como en el iDrive).
    val full = band(g.y0, g.y1)
    drawPath(full, Color(0xFF0C1219))
    clipPath(full) {
        metal(SteelMetal, 0.65f)
        drawRect(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.16f), 0.35f to Color.Transparent, 0.75f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.35f),
                startY = g.y0, endY = g.y1,
            ),
            Offset(0f, g.y0), Size(size.width, g.y1 - g.y0),
        )
    }

    // Relleno del valor, de abajo arriba, en metal pulido.
    if (fraction > 0f) {
        val level = g.y1 - (g.y1 - g.y0) * fraction
        val span = g.y1 - level
        val filled = band(level, g.y1)
        clipPath(filled) {
            metal(look, 1f)
            // Calor hacia la punta y algo más de sombra abajo: da volumen a la barra.
            drawRect(
                Brush.verticalGradient(
                    0f to look.tip.copy(alpha = 0.55f), 0.18f to look.bright.copy(alpha = 0.18f), 0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.30f),
                    startY = level, endY = level + maxOf(span, g.dp(60f)),
                ),
                Offset(0f, level), Size(size.width, span),
            )
            // Reflejo que recorre la barra hasta el nivel, una y otra vez.
            val sy = g.y1 - (span + g.band) * phase
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.22f), Color.Transparent), startY = sy - g.band, endY = sy + g.band),
                Offset(0f, sy - g.band), Size(size.width, g.band * 2), blendMode = BlendMode.Plus,
            )
        }
        // Luz que sale de la barra hacia el centro del indicador, más intensa cuanto más alto el valor.
        val innerX = g.chevronX(level, g.band)
        val clipLeft = if (mirrored) g.w / 2 else 0f
        val clipRight = if (mirrored) g.w else g.w / 2
        val pool = g.band * (3.2f + 2.8f * fraction)
        clipRect(clipLeft, g.y0, clipRight, g.y1) {
            drawCircle(
                Brush.radialGradient(
                    0f to look.light.copy(alpha = 0.10f + 0.20f * fraction), 0.5f to look.mid.copy(alpha = 0.05f + 0.08f * fraction), 1f to Color.Transparent,
                    center = o(innerX, level), radius = pool,
                ),
                radius = pool, center = o(innerX, level), blendMode = BlendMode.Screen,
            )
        }
        // Punta: filo blanco cálido y un foco suave.
        val tip = o(g.chevronX(level, g.band * 0.45f), level)
        val bloom = g.band * 1.3f
        drawCircle(
            Brush.radialGradient(
                0f to look.tip.copy(alpha = 0.55f), 0.25f to look.light.copy(alpha = 0.25f), 1f to Color.Transparent,
                center = tip, radius = bloom,
            ),
            radius = bloom, center = tip, blendMode = BlendMode.Plus,
        )
        drawLine(look.tip, o(g.chevronX(level, 0f), level), o(g.chevronX(level, g.band), level), g.dp(3f), StrokeCap.Round)
    }

    // Marcas de escala grabadas en la franja: largas en cada cifra, cortas a mitad de camino.
    val ticks = marks + marks.zipWithNext { a, b -> (a + b) / 2 }
    ticks.forEach { f ->
        val y = g.y1 - (g.y1 - g.y0) * f
        val major = f in marks
        val len = g.band * if (major) 0.26f else 0.14f
        val a = o(g.chevronX(y, g.dp(3f)), y); val b = o(g.chevronX(y, len), y)
        drawLine(Color.Black.copy(alpha = 0.45f), a + Offset(0f, g.dp(1.5f)), b + Offset(0f, g.dp(1.5f)), g.dp(2f))
        drawLine(Color.White.copy(alpha = if (major) 0.85f else 0.5f), a, b, g.dp(if (major) 2f else 1.4f))
    }

    // Bisel cromado: sombra exterior, cuerpo de plata con reflejos y filo de luz; borde interior de acero.
    val outer = chevron(0f, g.y0, g.y1)
    drawPath(outer, Color.Black.copy(alpha = 0.6f), style = Stroke(g.dp(8f)))
    drawPath(
        outer,
        Brush.verticalGradient(
            0f to Color(0xFFF2F7FC), 0.2f to Color(0xFF7D91A8), 0.42f to Color(0xFFE3ECF5), 0.5f to Color(0xFFFFFFFF),
            0.62f to Color(0xFF5E6F84), 0.85f to Color(0xFFC3D2E2), 1f to Color(0xFF6A7C92),
            startY = g.y0, endY = g.y1,
        ),
        style = Stroke(g.dp(4f)),
    )
    drawPath(outer, look.light.copy(alpha = 0.12f), style = Stroke(g.dp(12f)), blendMode = BlendMode.Plus)
    val inner = chevron(g.band, g.y0, g.y1)
    drawPath(inner, Color.Black.copy(alpha = 0.5f), style = Stroke(g.dp(4f)))
    drawPath(
        inner,
        Brush.verticalGradient(listOf(Color(0xFFB8C7D8), Color(0xFF4A5A6E), Color(0xFF9AAAC0)), startY = g.y0, endY = g.y1),
        style = Stroke(g.dp(1.6f)),
    )
}

private val EngraveLight = Shadow(Color.White.copy(alpha = 0.45f), Offset(0f, 1.5f), 1.5f)
private val LabelShadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset(0f, 2f), 8f)

/**
 * Cifra de escala dentro de la franja, junto a su marca, como en el cuadro del iDrive 8. Sobre la
 * parte ya rellena (por debajo de [level]) la cifra queda grabada en el metal, en oscuro con un
 * filo de luz; por encima, en su color con sombra.
 */
private fun DrawScope.drawMark(
    g: GeoPx, tm: TextMeasurer, text: String, fraction: Float, mirrored: Boolean, style: TextStyle, color: Color,
    level: Float, look: MetalLook,
) {
    val label = tm.measure(text, style)
    val y = g.y1 - (g.y1 - g.y0) * fraction
    // La cifra cuelga justo debajo de su marca: así la de arriba del todo cabe en la franja y no
    // choca con la siguiente.
    val cy = y + g.dp(2f) + label.size.height / 2f
    val cx = g.chevronX(cy, g.band * 0.60f)
    val center = g.mirror(cx, mirrored)
    val topLeft = Offset(center - label.size.width / 2f, cy - label.size.height / 2f)
    // Grabada si la cifra (que cuelga bajo su marca) queda dentro de la parte rellena.
    val engraved = (g.y1 - cy) / (g.y1 - g.y0) <= level
    if (engraved) drawText(label, color = look.dark.copy(alpha = 0.92f), topLeft = topLeft, shadow = EngraveLight)
    else drawText(label, color = color, topLeft = topLeft, shadow = LabelShadow)
}

private fun DrawScope.drawCaption(g: GeoPx, tm: TextMeasurer, text: String, mirrored: Boolean, style: TextStyle) {
    val label = tm.measure(text, style)
    val x = g.chevronX(g.y0, 0f) + g.w * 0.17f - label.size.width
    val left = if (mirrored) g.w - x - label.size.width else x
    drawText(label, color = Color.White.copy(alpha = 0.85f), topLeft = Offset(left, g.y0 - label.size.height - g.dp(2f)))
}

/** Barra inclinada de segmentos blancos (combustible o aceite) junto al tramo inferior del chevron. */
private fun DrawScope.drawMiniBar(g: GeoPx, tm: TextMeasurer, fraction: Float?, labels: List<String>, mirrored: Boolean, style: TextStyle) {
    val inset = g.band + g.dp(34f)
    val yTop = g.ym + g.dp(26f)
    val yBottom = g.y1 - g.dp(22f)
    val blocks = 6
    val bh = (yBottom - yTop) / blocks
    val bw = g.dp(10f)
    for (i in 0 until blocks) {
        val yb = yBottom - i * bh
        val lit = fraction != null && fraction * blocks >= i + 0.5f
        val x1 = g.chevronX(yb, inset); val x2 = g.chevronX(yb - bh + g.dp(3f), inset)
        val seg = Path().apply {
            moveTo(g.mirror(x1, mirrored), yb); lineTo(g.mirror(x1 + bw, mirrored), yb)
            lineTo(g.mirror(x2 + bw, mirrored), yb - bh + g.dp(3f)); lineTo(g.mirror(x2, mirrored), yb - bh + g.dp(3f)); close()
        }
        drawPath(seg, if (lit) Color.White else Color.White.copy(alpha = 0.18f))
    }
    labels.forEachIndexed { i, text ->
        val y = yTop + (yBottom - yTop) * i / (labels.size - 1)
        val label = tm.measure(text, style)
        val x = g.chevronX(y, inset) + bw + g.dp(10f)
        val left = if (mirrored) g.w - x - label.size.width else x
        drawText(label, color = Color.White, topLeft = Offset(left, y - label.size.height / 2f))
    }
}

@Composable
private fun TabValue(value: String, caption: String, modifier: Modifier = Modifier, alignEnd: Boolean, glow: Color, intensity: Float) {
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        // Cifra sobre la imagen: sombra oscura para que se lea y, encima, halo del color de su barra.
        Box(Modifier.padding(horizontal = 20.dp)) {
            val big = numeric(80, FontWeight.Bold)
            Text(value, style = big.copy(shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 3f), 18f)), color = Cockpit.Text, maxLines = 1)
            Text(
                value,
                style = big.copy(shadow = Shadow(glow.copy(alpha = 0.55f + 0.4f * intensity), Offset.Zero, 26f + 20f * intensity)),
                color = Cockpit.Text, maxLines = 1,
            )
        }
        Text(
            caption,
            style = numeric(20, FontWeight.Medium),
            color = Cockpit.BlueLight,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

/**
 * Línea inferior: franjas M y hora, surtidor y autonomía a la izquierda; segmentos azules en el
 * centro (coche en rojo si hay una puerta abierta); estado y temperatura exterior a la derecha.
 */
@Composable
private fun StatusLine(state: VehicleState, status: SourceStatus, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        MStripe(segment = 14.dp)
        Spacer(Modifier.width(18.dp))
        ClockText(style = numeric(28, FontWeight.SemiBold))
        Spacer(Modifier.width(36.dp))
        FuelPump(Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(state.rangeKm?.let { "$it" } ?: "--", style = numeric(28, FontWeight.SemiBold), color = Cockpit.Text)
        Text(" km", style = numeric(18), color = Cockpit.TextDim)
        Spacer(Modifier.weight(1f))
        if (state.doors.anyOpen) {
            CarIcon(state, Modifier.size(width = 60.dp, height = 26.dp))
            Spacer(Modifier.width(16.dp))
        }
        Segments(Modifier.size(width = 240.dp, height = 22.dp))
        Spacer(Modifier.weight(1f))
        Text(
            if (status.connected) "READY" else "SIN DATOS",
            style = MaterialTheme.typography.labelLarge,
            color = if (status.connected) Cockpit.Text else Cockpit.Alert,
        )
        Spacer(Modifier.width(32.dp))
        Text(
            state.outsideTempC?.let { "%+.1f".format(it) } ?: "--",
            style = numeric(28, FontWeight.SemiBold),
            color = Cockpit.Text,
        )
        Text(" °C", style = numeric(18), color = Cockpit.TextDim)
    }
}

/** Segmentos azules decorativos del centro de la línea de estado. */
@Composable
private fun Segments(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val n = 4
        val gap = 6.dp.toPx()
        val sw = (size.width - gap * (n - 1)) / n
        val s = size.height * 0.5f
        for (i in 0 until n) {
            val x = i * (sw + gap)
            val p = Path().apply {
                moveTo(x + s, 0f); lineTo(x + sw, 0f); lineTo(x + sw - s, size.height); lineTo(x, size.height); close()
            }
            drawPath(p, Brush.verticalGradient(listOf(Cockpit.Blue.copy(alpha = 0.9f), Cockpit.MDark)))
        }
    }
}

/** Icono de surtidor dibujado con formas simples. */
@Composable
private fun FuelPump(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawRoundRect(Cockpit.TextDim, Offset(w * 0.1f, h * 0.1f), Size(w * 0.5f, h * 0.85f), CornerRadius(w * 0.08f))
        drawRect(Cockpit.Background, Offset(w * 0.2f, h * 0.2f), Size(w * 0.3f, h * 0.22f))
        drawLine(Cockpit.TextDim, Offset(w * 0.6f, h * 0.3f), Offset(w * 0.85f, h * 0.45f), w * 0.08f)
        drawLine(Cockpit.TextDim, Offset(w * 0.85f, h * 0.45f), Offset(w * 0.85f, h * 0.8f), w * 0.08f)
    }
}

/** Coche visto desde arriba (morro a la derecha); puertas abiertas en rojo. */
@Composable
private fun CarIcon(state: VehicleState, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val body = Size(size.width, size.height * 0.8f)
        val origin = Offset(0f, (size.height - body.height) / 2)
        drawRoundRect(Cockpit.SurfaceHigh, origin, body, CornerRadius(body.height / 2.5f))
        drawRoundRect(Cockpit.TextDim, origin, body, CornerRadius(body.height / 2.5f), style = Stroke(1.5.dp.toPx()))
        fun door(open: Boolean?, x: Float, top: Boolean) {
            if (open != true) return
            val y = if (top) origin.y else origin.y + body.height
            drawLine(Cockpit.Alert, Offset(x, y), Offset(x + size.width * 0.18f, y), 3.dp.toPx())
        }
        door(state.doors.driver, size.width * 0.5f, top = true)
        door(state.doors.passenger, size.width * 0.5f, top = false)
        door(state.doors.rearLeft, size.width * 0.28f, top = true)
        door(state.doors.rearRight, size.width * 0.28f, top = false)
        if (state.doors.trunk == true) drawLine(Cockpit.Alert, Offset(0f, origin.y + 4.dp.toPx()), Offset(0f, origin.y + body.height - 4.dp.toPx()), 3.dp.toPx())
        if (state.doors.hood == true) drawLine(Cockpit.Alert, Offset(size.width, origin.y + 4.dp.toPx()), Offset(size.width, origin.y + body.height - 4.dp.toPx()), 3.dp.toPx())
    }
}
