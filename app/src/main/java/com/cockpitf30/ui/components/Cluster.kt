package com.cockpitf30.ui.components

import androidx.compose.foundation.border
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
import kotlinx.coroutines.flow.first
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shadow
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
import com.cockpitf30.ui.theme.DriveMode
import com.cockpitf30.ui.theme.DriveModes
import com.cockpitf30.ui.theme.numeric
import com.cockpitf30.vehicle.VehicleRepository
import com.cockpitf30.vehicle.SourceStatus
import com.cockpitf30.vehicle.VehicleState
import com.cockpitf30.vehicle.GearEstimator
import kotlin.math.abs
import kotlin.math.hypot

private val Cyan: Color get() = when (DriveModes.current) {
    DriveMode.NORMAL -> Color(0xFF29B6F6)
    DriveMode.SPORT -> Color(0xFFFF4A3A)
    DriveMode.NIGHT -> Color(0xFFFFB238)
}
/** Cifras de la escala: blanco con un punto de azul hielo; rojo en la zona roja de las rpm. */
private val SpeedLabel = Color(0xFFF4F8FC)
private val RpmLabel = Color(0xFFF4F8FC)
private val RpmRed = Color(0xFFFF4A3D)
/** Halo de las cifras grandes, del color de su marcador. */
private val SpeedGlow: Color get() = SpeedMetal.light
private val RpmGlow: Color get() = SteelMetal.light
/** Fondo del panel, del mismo azul marino que la escena del coche para que se funda. */
private val SceneTop = Color(0xFF091A2D)

/** Marcas de velocidad. Escala no lineal: 0–150 ocupa más recorrido que 150–250, como en el iDrive. */
private val SPEED_MARKS = listOf(0, 30, 60, 90, 120, 150, 200, 250)
private fun speedFraction(kmh: Int): Float {
    val v = kmh.coerceIn(0, 250)
    return if (v <= 150) v / 150f * 0.625f else 0.625f + (v - 150) / 100f * 0.375f
}

private fun speedFromFraction(f: Float): Int =
    (if (f <= 0.625f) f / 0.625f * 150f else 150f + (f - 0.625f) / 0.375f * 100f).roundToInt()

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
    val needle = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
    val realSpeed by animateFloatAsState(state.speedKmh?.let(::speedFraction) ?: 0f, needle, label = "speed")
    val realRpm by animateFloatAsState(((state.rpm ?: 0).coerceIn(0, RPM_MAX)).toFloat() / RPM_MAX, needle, label = "rpm")
    // Bienvenida: al arrancar la app, el cuadro se enciende y las dos barras barren hasta arriba y
    // vuelven, como el autotest de las agujas de un BMW. Solo una vez por arranque.
    val sweep = remember { Animatable(0f) }
    val fadeIn = remember { Animatable(if (ClusterIntro.swept) 1f else 0f) }
    suspend fun doSweep() {
        sweep.snapTo(0f)
        sweep.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
        delay(300)
        sweep.animateTo(0f, tween(1300, easing = FastOutSlowInEasing))
    }
    // Primera vez: al terminar de irse la imagen de arranque (antes quedaba tapado por su fundido).
    LaunchedEffect(Unit) {
        if (ClusterIntro.swept) return@LaunchedEffect
        Startup.splashDone.first { it }
        delay(450)
        launch { fadeIn.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
        delay(200)
        doSweep()
        ClusterIntro.swept = true
    }
    // Y cada vez que se arranca el motor (rpm de parado a encendido), como en el coche.
    LaunchedEffect(Unit) {
        var offSince: Long? = System.currentTimeMillis()
        VehicleRepository.state.collect { st ->
            val now = System.currentTimeMillis()
            val r = st.rpm ?: 0
            if (r < 100) { if (offSince == null) offSince = now }
            else if (r >= 400) {
                val since = offSince
                offSince = null
                if (since != null && now - since >= ClusterIntro.ENGINE_OFF_MS && ClusterIntro.swept) launch { doSweep() }
            }
        }
    }
    // Se leen al pintar las capas: el barrido no recompone el cuadro.
    val speed by remember { derivedStateOf { maxOf(realSpeed, sweep.value) } }
    val rpm by remember { derivedStateOf { maxOf(realRpm, sweep.value) } }

    // Indicador de cambio (caja manual): flecha junto a la marcha.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val shiftOn = remember { com.cockpitf30.system.AppSettings(ctx).shiftIndicator }
    val advisor = remember { com.cockpitf30.vehicle.ShiftAdvisor() }
    val gearText = state.gear ?: GearEstimator.estimate(state.speedKmh, state.rpm)
    val shift = if (!shiftOn) null else advisor.update(
        System.currentTimeMillis(), gearText?.toIntOrNull(), state.rpm, state.speedKmh,
        if (DriveModes.current == DriveMode.SPORT) com.cockpitf30.vehicle.ShiftAdvisor.SPORT else com.cockpitf30.vehicle.ShiftAdvisor.ECO,
    )
    // El halo de las cifras solo necesita unos pocos niveles: así el texto no se recompone en cada fotograma.
    val speedGlow by remember { derivedStateOf { (speed * 10).roundToInt() / 10f } }
    val rpmGlow by remember { derivedStateOf { (rpm * 10).roundToInt() / 10f } }
    // Muchos rótulos se miden en cada fotograma (hay animación continua): caché amplia.
    val measurer = rememberTextMeasurer(cacheSize = 96)
    val scaleStyle = numeric(28, FontWeight.SemiBold)
    val smallStyle = numeric(17, FontWeight.SemiBold)
    val maxStyle = numeric(19, FontWeight.Bold).copy(letterSpacing = 2.sp)
    val captionStyle = numeric(17, FontWeight.Medium)
    val homeRes = cachedBitmap(R.drawable.home_car_scene)
    val trianglesRes = cachedBitmap(R.drawable.bg_triangles)
    // Presiones y autonomía en grande para leerlas de un vistazo conduciendo.
    // Se recuerdan: si fueran objetos nuevos en cada recomposición, las capas del cuadro se repintarían.
    val labelLarge = MaterialTheme.typography.labelLarge
    val overlay = remember(labelLarge) {
        Overlay(
            value = numeric(38, FontWeight.SemiBold),
            unit = numeric(19, FontWeight.Medium),
            caption = labelLarge.copy(fontSize = 16.sp, letterSpacing = 1.sp),
            alert = labelLarge,
        )
    }
    val mapText = remember {
        MapText(
            hud = numeric(20, FontWeight.SemiBold).copy(letterSpacing = 3.sp),
            small = numeric(14, FontWeight.Medium).copy(letterSpacing = 1.sp),
            alert = numeric(20, FontWeight.SemiBold).copy(letterSpacing = 4.sp),
        )
    }

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
            .graphicsLayer {
                alpha = fadeIn.value
                val k = 0.97f + 0.03f * fadeIn.value
                scaleX = k; scaleY = k
            }
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
        val g = remember(maxWidth, maxHeight) { Geometry(maxWidth, maxHeight) }
        // Cada capa captura solo los datos que pinta: la velocidad cambia 10 veces por segundo y no
        // debe repintar el coche ni las cifras.
        val rangeKm = state.rangeKm
        // Redondeada a medio grado: la capa del coche no se repinta por décimas.
        val outsideTemp = state.outsideTempC?.let { t -> String.format(java.util.Locale.US, "%.1f", (t * 2).roundToInt() / 2f).replace('.', ',') }
        val fuelLevel = state.fuelLevel
        val scene = remember(homeRes, trianglesRes) { CarScene(homeRes, trianglesRes) }
        // El cuadro se pinta en capas independientes: cada una solo se vuelve a dibujar cuando cambia
        // lo suyo. Antes un único lienzo repintaba todo (coche, mapa, marcos, cifras) en cada
        // fotograma por el destello de las barras; ahora ese destello es una capa mínima.
        val geoCache = remember { GeoCache() }
        // Qué cifras de la escala quedan ya cubiertas por el relleno (una máscara de bits): la capa
        // de cifras solo se repinta cuando la aguja cruza una marca, no en cada fotograma.
        val speedEngraved by remember { derivedStateOf { engravedMask(SPEED_FRACTIONS, speed) } }
        val rpmEngraved by remember { derivedStateOf { engravedMask(RPM_FRACTIONS, rpm) } }
        // 1) Panel central: coche o mapa. Con el coche a la vista no se repinta nunca.
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            tileVersion // se vuelve a dibujar cuando llega una tesela
            val geo = geoCache.get(g, this)
            val pg = page.value
            // Posición, zoom y fase solo se leen si el mapa se ve: el mapa se mueve sin recomponer el cuadro.
            val map = if (pg > 0f) smooth.value.let { now -> MapFrame(route, now.position, now.bearing, zoom, phase) }
            else MapFrame(route, route.position, route.bearing, 17f, 0f)
            drawPanel(geo, scene, rangeKm, outsideTemp, measurer, overlay, pg, map, mapText, focused)
        }
        // 2) Franjas vacías de acero: fijas.
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            val geo = geoCache.get(g, this)
            drawFrameBase(geo, mirrored = false)
            drawFrameBase(geo, mirrored = true)
        }
        // 3) Relleno de velocidad y rpm: solo cuando se mueve la aguja.
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            val geo = geoCache.get(g, this)
            drawFrameFill(geo, mirrored = false, fraction = speed, look = SpeedMetal)
            drawFrameFill(geo, mirrored = true, fraction = rpm, look = RpmMetal)
        }
        // 4) Destello que recorre las barras: lo único que se pinta en cada fotograma.
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            val geo = geoCache.get(g, this)
            // Con las dos barras vacías (parado y motor apagado) no se lee la fase: no se repinta nada.
            val s = speed; val r = rpm
            if (s > 0f) drawFrameShimmer(geo, mirrored = false, fraction = s, phase = phase)
            if (r > 0f) drawFrameShimmer(geo, mirrored = true, fraction = r, phase = (phase + 0.5f) % 1f)
        }
        // 5) Marcas, bisel cromado, cifras y barras pequeñas: casi fijo.
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            val geo = geoCache.get(g, this)
            drawFrameTop(geo, mirrored = false, look = SpeedMetal, marks = SPEED_FRACTIONS)
            drawFrameTop(geo, mirrored = true, look = RpmMetal, marks = RPM_FRACTIONS)

            // El 0 no se rotula (no cabe colgando bajo la última marca); su marca sí se dibuja.
            val sMask = speedEngraved
            // La cifra de arriba del todo (250 / 8) quedaba bajada y descolocada: se rotula "MAX".
            SPEED_MARKS.forEachIndexed { i, v ->
                val top = i == SPEED_MARKS.lastIndex
                if (i > 0) drawMark(geo, measurer, if (top) "MAX" else "$v", SPEED_FRACTIONS[i], false, if (top) maxStyle else scaleStyle, SpeedLabel, sMask and (1 shl i) != 0, SpeedMetal)
            }
            val rMask = rpmEngraved
            (1..8).forEach { k ->
                drawMark(geo, measurer, if (k == 8) "MAX" else "$k", RPM_FRACTIONS[k], true, if (k == 8) maxStyle else scaleStyle, if (k * 1000 >= RPM_RED) RpmRed else RpmLabel, rMask and (1 shl k) != 0, RpmMetal)
            }
            drawCaption(geo, measurer, "km/h", mirrored = false, captionStyle)
            drawCaption(geo, measurer, "1/min x 1000", mirrored = true, captionStyle)

            // Solo combustible: la temperatura del aceite no llega por ninguna vía (ni CAN ni radio).
            drawMiniBar(geo, measurer, fuelLevel, listOf("1", "1/2", "0"), mirrored = false, smallStyle)
        }

        // Velocidad y marcha en pestañas oscuras sobre el panel, con halo del color de su barra.
        Row(
            Modifier.fillMaxWidth().height(g.frameBottom).padding(top = g.frameTop + 40.dp, bottom = 110.dp)
                .padding(start = g.contentInset, end = g.contentInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Durante el barrido las cifras suben y bajan con las barras.
            val sw = sweep.value
            val shownSpeed = if (sw > 0f) maxOf(state.speedKmh ?: 0, speedFromFraction(sw)) else state.speedKmh
            val shownRpm = if (sw > 0f) maxOf(state.rpm ?: 0, (sw * RPM_MAX).roundToInt() / 50 * 50) else state.rpm
            TabValue(shownSpeed?.toString() ?: "--", "km/h", Modifier.weight(1f), alignEnd = false, glow = SpeedGlow, intensity = speedGlow)
            Spacer(Modifier.weight(1.5f))
            TabValue(gearText ?: "--", shownRpm?.let { "$it rpm" } ?: "-- rpm", Modifier.weight(1f), alignEnd = true, glow = RpmGlow, intensity = rpmGlow, shift = shift)
        }
        // Datos de apoyo bajo las cifras grandes, solo en la vista del coche (se van con el mapa).
        CarInfoChips(
            state.avgConsumption, state.fuelLevel,
            Modifier.fillMaxWidth().height(g.frameBottom)
                .graphicsLayer {
                    val p = page.value
                    alpha = (1f - p * 1.6f).coerceIn(0f, 1f)
                    translationY = p * 24.dp.toPx()
                },
            inset = g.contentInset + 34.dp,
        )
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
    /** Motor parado al menos esto para repetir el barrido al arrancarlo. */
    const val ENGINE_OFF_MS = 5_000L
    @Volatile var done = false
    /** Ya se hizo el barrido de bienvenida de las agujas. */
    @Volatile var swept = false
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
            w = size.width, h = size.height, y0 = frameTop.toPx(), y1 = frameBottom.toPx(),
            xv = (scaleWidth + 6.dp).toPx(), cut = cut.toPx(), band = band.toPx(), density = density,
        )
    }
}

private class GeoPx(val w: Float, val h: Float, val y0: Float, val y1: Float, val xv: Float, val cut: Float, val band: Float, val density: Float) {
    val ym = (y0 + y1) / 2
    val half = (y1 - y0) / 2
    fun dp(v: Float) = v * density

    /** x del chevron izquierdo desplazado [inset] hacia dentro, a la altura [y]. */
    fun chevronX(y: Float, inset: Float) = xv + inset + cut * abs(y - ym) / half
    fun mirror(x: Float, mirrored: Boolean) = if (mirrored) w - x else x

    private val left by lazy(LazyThreadSafetyMode.NONE) { FramePaths(this, false) }
    private val right by lazy(LazyThreadSafetyMode.NONE) { FramePaths(this, true) }
    fun frame(mirrored: Boolean) = if (mirrored) right else left
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
    g: GeoPx, scene: CarScene, rangeKm: Int?, outsideTemp: String?, tm: TextMeasurer, st: Overlay,
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
        if (page < 1f) translate(left = -page * pw) { clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) { drawCar(g, scene, rangeKm, outsideTemp, tm, st, bounds) } }
    }
    // Sport y Night tiñen la escena (coche y mapa) del color del modo, manteniendo luces y sombras.
    DriveModes.palette.tint?.let { tint ->
        drawPath(panel, tint, alpha = 0.9f, blendMode = BlendMode.Hue)
        if (DriveModes.palette.dim > 0f) drawPath(panel, Color.Black.copy(alpha = DriveModes.palette.dim))
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
    g: GeoPx, scene: CarScene, rangeKm: Int?, outsideTemp: String?, tm: TextMeasurer, st: Overlay, bounds: Rect,
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
        // Sin presiones de rueda: la caja CAN no las manda (se ven en el iDrive original).
        // Autonomía arriba a la derecha, sin línea.
        val range = at(905f, 250f)
        marker(tm, st, "AUTONOMÍA", rangeKm?.toString() ?: "--", " km", null, range, alignRight = false)
        // Y en simetría, a la izquierda del coche, la temperatura exterior.
        marker(tm, st, "EXTERIOR", outsideTemp ?: "--", " °C", null, Offset(2 * bounds.center.x - range.x, range.y), alignRight = true)
    }
}

/**
 * Marcador de cristal, igual que los de la pantalla Vehículo: panel ahumado con borde biselado del
 * color de estado, punto de estado, rótulo y valor en Saira. Con [wheel], una línea de luz hasta la
 * rueda con un punto brillante encima (solo el aro si la rueda queda oculta).
 */
private fun DrawScope.marker(
    tm: TextMeasurer, st: Overlay, caption: String, value: String, unit: String,
    wheel: Offset?, label: Offset, alignRight: Boolean, hidden: Boolean = false, status: Color = Cockpit.OnDarkAccent,
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
private class MetalLook(val dark: Color, val mid: Color, val bright: Color, val tip: Color, val light: Color) {
    /** Perfil metálico ya calculado para los [METAL_STEPS] chevrons de la franja. */
    val shades: List<Color> by lazy(LazyThreadSafetyMode.NONE) { List(METAL_STEPS) { metalShade(this, (it + 0.5f) / METAL_STEPS) } }
}

/** Relleno de velocidad y rpm en cobre rosado metalizado, como en el cuadro del iDrive 8.5. */
private val CopperRose = MetalLook(Color(0xFF4E2418), Color(0xFFC07A5C), Color(0xFFF5C8AE), Color(0xFFFFF0E4), Color(0xFFE59A78))
/** Franja vacía en azul hielo metalizado suave. */
private val IceSteel = MetalLook(Color(0xFF1F3549), Color(0xFF6489AE), Color(0xFFC0D8EE), Color.White, Color(0xFFA9C8E4))
/** Sport: relleno rojo metalizado sobre titanio. */
private val RedMetal = MetalLook(Color(0xFF4A0A06), Color(0xFFC8261A), Color(0xFFFF8070), Color(0xFFFFE8E2), Color(0xFFFF5A46))
private val Titanium = MetalLook(Color(0xFF26292E), Color(0xFF7C838D), Color(0xFFCDD2D9), Color.White, Color(0xFFB8BEC8))
/** Night: ámbar sobre bronce oscuro. */
private val AmberMetal = MetalLook(Color(0xFF4A2A04), Color(0xFFC8841A), Color(0xFFFFD27A), Color(0xFFFFF4DC), Color(0xFFF0A838))
private val Bronze = MetalLook(Color(0xFF2C2212), Color(0xFF7A6446), Color(0xFFC8B28A), Color(0xFFFFF3DA), Color(0xFFB89E76))

private val SpeedMetal: MetalLook get() = when (DriveModes.current) {
    DriveMode.NORMAL -> CopperRose
    DriveMode.SPORT -> RedMetal
    DriveMode.NIGHT -> AmberMetal
}
private val RpmMetal: MetalLook get() = SpeedMetal
private val SteelMetal: MetalLook get() = when (DriveModes.current) {
    DriveMode.NORMAL -> IceSteel
    DriveMode.SPORT -> Titanium
    DriveMode.NIGHT -> Bronze
}

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

/** Geometría en píxeles y trazados fijos del cuadro: se calculan una vez por tamaño, no por fotograma. */
private class GeoCache {
    private var geo: GeoPx? = null
    fun get(g: Geometry, scope: DrawScope): GeoPx {
        val cur = geo
        if (cur != null && cur.w == scope.size.width && cur.h == scope.size.height && cur.density == scope.density) return cur
        return g.px(scope).also { geo = it }
    }
}

private const val METAL_STEPS = 24

/** Trazados de un marco (izquierdo o reflejado) que no dependen del valor. */
private class FramePaths(g: GeoPx, val mirrored: Boolean) {
    private fun o(g: GeoPx, x: Float, y: Float) = Offset(g.mirror(x, mirrored), y)
    private fun chevron(g: GeoPx, inset: Float, top: Float, bottom: Float) = Path().apply {
        val a = o(g, g.chevronX(bottom, inset), bottom); val v = o(g, g.xv + inset, g.ym); val b = o(g, g.chevronX(top, inset), top)
        moveTo(a.x, a.y); lineTo(v.x, v.y); lineTo(b.x, b.y)
    }

    /** Chevrons concéntricos con los que se pinta el perfil metálico de la franja. */
    val metal: List<Path> = (g.band / METAL_STEPS).let { step ->
        List(METAL_STEPS) { i -> chevron(g, step * (i + 0.5f), g.y0 - g.band, g.y1 + g.band) }
    }
    val metalStroke = Stroke(g.band / METAL_STEPS + 1f)
    val full = Path().also { band(g, it, g.y0, g.y1) }
    val outer = chevron(g, 0f, g.y0, g.y1)
    val inner = chevron(g, g.band, g.y0, g.y1)
    /** Trazado del relleno: se reutiliza en cada fotograma. */
    val filled = Path()
    val shimmer = Path()

    /** Franja entre el borde exterior y el interior, de la altura [yTop] a [yBottom]. */
    fun band(g: GeoPx, into: Path, yTop: Float, yBottom: Float): Path {
        into.rewind()
        val crosses = yTop < g.ym && yBottom > g.ym
        var first = true
        fun add(p: Offset) { if (first) { into.moveTo(p.x, p.y); first = false } else into.lineTo(p.x, p.y) }
        add(o(g, g.chevronX(yBottom, 0f), yBottom))
        if (crosses) add(o(g, g.xv, g.ym))
        add(o(g, g.chevronX(yTop, 0f), yTop))
        add(o(g, g.chevronX(yTop, g.band), yTop))
        if (crosses) add(o(g, g.xv + g.band, g.ym))
        add(o(g, g.chevronX(yBottom, g.band), yBottom))
        into.close()
        return into
    }
}

/** Pinta la franja con el perfil metálico de [m] mediante chevrons concéntricos. */
private fun DrawScope.metal(f: FramePaths, m: MetalLook, alpha: Float) {
    val shades = m.shades
    for (i in 0 until METAL_STEPS) drawPath(f.metal[i], shades[i], alpha = alpha, style = f.metalStroke)
}

/** Máscara de bits con las marcas de [fractions] que el relleno ([level], 0..1) ya ha cubierto. */
private fun engravedMask(fractions: List<Float>, level: Float): Int {
    var mask = 0
    for (i in fractions.indices) if (fractions[i] <= level) mask = mask or (1 shl i)
    return mask
}

/**
 * Marco en chevron con aspecto metálico, por capas. Base: líneas de plata y franja vacía de acero
 * oscuro con reflejo claro arriba (la luz viene de arriba, como en el iDrive).
 */
private fun DrawScope.drawFrameBase(g: GeoPx, mirrored: Boolean) {
    val f = g.frame(mirrored)
    fun o(x: Float, y: Float) = Offset(g.mirror(x, mirrored), y)
    // Líneas horizontales arriba y abajo, en plata, que se pierden hacia el centro.
    val x0 = g.chevronX(g.y0, 0f)
    val topEnd = x0 + g.w * 0.17f
    val bottomEnd = g.w * 0.5f - g.dp(40f)
    val silver = Color(0xFFC9D6E4)
    val fade = if (mirrored) listOf(Color.Transparent, silver) else listOf(silver, Color.Transparent)
    drawLine(Brush.horizontalGradient(fade, o(x0, 0f).x, o(topEnd, 0f).x), o(x0, g.y0), o(topEnd, g.y0), g.dp(2f))
    drawLine(Brush.horizontalGradient(fade, o(x0, 0f).x, o(bottomEnd, 0f).x), o(g.chevronX(g.y1, 0f), g.y1), o(bottomEnd, g.y1), g.dp(2.5f))

    drawPath(f.full, Color(0xFF111A24))
    clipPath(f.full) {
        metal(f, SteelMetal, 0.72f)
        drawRect(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.16f), 0.35f to Color.Transparent, 0.75f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.35f),
                startY = g.y0, endY = g.y1,
            ),
            Offset(0f, g.y0), Size(size.width, g.y1 - g.y0),
        )
    }
}

/** Relleno del valor, de abajo arriba, en metal pulido ([look]) con la punta encendida. */
private fun DrawScope.drawFrameFill(g: GeoPx, mirrored: Boolean, fraction: Float, look: MetalLook) {
    if (fraction <= 0f) return
    val f = g.frame(mirrored)
    fun o(x: Float, y: Float) = Offset(g.mirror(x, mirrored), y)
    val level = g.y1 - (g.y1 - g.y0) * fraction
    val span = g.y1 - level
    clipPath(f.band(g, f.filled, level, g.y1)) {
        metal(f, look, 1f)
        // Calor hacia la punta y algo más de sombra abajo: da volumen a la barra.
        drawRect(
            Brush.verticalGradient(
                0f to look.tip.copy(alpha = 0.55f), 0.18f to look.bright.copy(alpha = 0.18f), 0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.30f),
                startY = level, endY = level + maxOf(span, g.dp(60f)),
            ),
            Offset(0f, level), Size(size.width, span),
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

/** Reflejo que recorre la barra hasta el nivel, una y otra vez. */
private fun DrawScope.drawFrameShimmer(g: GeoPx, mirrored: Boolean, fraction: Float, phase: Float) {
    if (fraction <= 0f) return
    val f = g.frame(mirrored)
    val level = g.y1 - (g.y1 - g.y0) * fraction
    val span = g.y1 - level
    clipPath(f.band(g, f.shimmer, level, g.y1)) {
        val sy = g.y1 - (span + g.band) * phase
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.22f), Color.Transparent), startY = sy - g.band, endY = sy + g.band),
            Offset(0f, sy - g.band), Size(size.width, g.band * 2), blendMode = BlendMode.Plus,
        )
    }
}

/** Marcas de escala grabadas en la franja y bisel cromado por fuera. */
private fun DrawScope.drawFrameTop(g: GeoPx, mirrored: Boolean, look: MetalLook, marks: List<Float>) {
    val f = g.frame(mirrored)
    fun o(x: Float, y: Float) = Offset(g.mirror(x, mirrored), y)
    // Marcas de escala grabadas en la franja: largas en cada cifra, cortas a mitad de camino.
    val ticks = marks + marks.zipWithNext { a, b -> (a + b) / 2 }
    ticks.forEach { fr ->
        val y = g.y1 - (g.y1 - g.y0) * fr
        val major = fr in marks
        val len = g.band * if (major) 0.26f else 0.14f
        val a = o(g.chevronX(y, g.dp(3f)), y); val b = o(g.chevronX(y, len), y)
        drawLine(Color.Black.copy(alpha = 0.45f), a + Offset(0f, g.dp(1.5f)), b + Offset(0f, g.dp(1.5f)), g.dp(2f))
        drawLine(Color.White.copy(alpha = if (major) 0.85f else 0.5f), a, b, g.dp(if (major) 2f else 1.4f))
    }

    // Bisel cromado: sombra exterior, cuerpo de plata con reflejos y filo de luz; borde interior de acero.
    val outer = f.outer
    drawPath(outer, Color.Black.copy(alpha = 0.6f), style = Stroke(g.dp(8f)))
    // Cromo con un punto de azul hielo, como el bisel de la franja.
    fun chrome(c: Color, k: Float = 0.25f) = androidx.compose.ui.graphics.lerp(c, SteelMetal.light, k)
    drawPath(
        outer,
        Brush.verticalGradient(
            0f to chrome(Color(0xFFF2F7FC), 0.25f), 0.2f to chrome(Color(0xFF7D91A8)), 0.42f to chrome(Color(0xFFE3ECF5), 0.25f), 0.5f to Color(0xFFFFFFFF),
            0.62f to chrome(Color(0xFF5E6F84)), 0.85f to chrome(Color(0xFFC3D2E2), 0.3f), 1f to chrome(Color(0xFF6A7C92)),
            startY = g.y0, endY = g.y1,
        ),
        style = Stroke(g.dp(4f)),
    )
    drawPath(outer, look.light.copy(alpha = 0.12f), style = Stroke(g.dp(12f)), blendMode = BlendMode.Plus)
    val inner = f.inner
    drawPath(inner, Color.Black.copy(alpha = 0.5f), style = Stroke(g.dp(4f)))
    drawPath(
        inner,
        Brush.verticalGradient(listOf(chrome(Color(0xFFB8C7D8)), chrome(Color(0xFF4A5A6E)), chrome(Color(0xFF9AAAC0))), startY = g.y0, endY = g.y1),
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
    engraved: Boolean, look: MetalLook,
) {
    val label = tm.measure(text, style)
    val y = g.y1 - (g.y1 - g.y0) * fraction
    // La cifra va centrada en la altura de su marca (el 1 en la raya de 1000 rpm), a la derecha
    // de la raya. Solo la de arriba del todo baja lo justo para no salirse de la franja.
    val cy = y.coerceAtLeast(g.y0 + g.dp(3f) + label.size.height / 2f)
    val cx = g.chevronX(cy, g.band * 0.60f)
    val center = g.mirror(cx, mirrored)
    val topLeft = Offset(center - label.size.width / 2f, cy - label.size.height / 2f)
    if (engraved) drawText(label, color = look.dark.copy(alpha = 0.92f), topLeft = topLeft, shadow = EngraveLight)
    else drawText(label, color = color, topLeft = topLeft, shadow = LabelShadow)
}

private fun DrawScope.drawCaption(g: GeoPx, tm: TextMeasurer, text: String, mirrored: Boolean, style: TextStyle) {
    val label = tm.measure(text, style)
    val x = g.chevronX(g.y0, 0f) + g.w * 0.17f - label.size.width
    val left = if (mirrored) g.w - x - label.size.width else x
    drawText(label, color = Cockpit.Ink.copy(alpha = 0.85f), topLeft = Offset(left, g.y0 - label.size.height - g.dp(2f)))
}

/** Barra inclinada de segmentos blancos (combustible o aceite) junto al tramo inferior del chevron. */
private fun DrawScope.drawMiniBar(g: GeoPx, tm: TextMeasurer, fraction: Float?, labels: List<String>, mirrored: Boolean, style: TextStyle) {
    val inset = g.band + g.dp(34f)
    val yTop = g.ym + g.dp(26f)
    val yBottom = g.y1 - g.dp(22f)
    val bw = g.dp(10f)
    // Franja continua inclinada como el marco; se llena de forma progresiva hasta [fraction].
    fun strip(from: Float, to: Float) = Path().apply {
        val xa = g.chevronX(from, inset); val xb = g.chevronX(to, inset)
        moveTo(g.mirror(xa, mirrored), from); lineTo(g.mirror(xa + bw, mirrored), from)
        lineTo(g.mirror(xb + bw, mirrored), to); lineTo(g.mirror(xb, mirrored), to); close()
    }
    drawPath(strip(yBottom, yTop), Color.White.copy(alpha = 0.16f))
    if (fraction != null && fraction > 0f) {
        val yFill = yBottom - (yBottom - yTop) * fraction.coerceIn(0f, 1f)
        val low = fraction < 0.15f
        drawPath(
            strip(yBottom, yFill),
            Brush.verticalGradient(
                if (low) listOf(Color(0xFFFFB347), Color(0xFFE5533D)) else listOf(Color.White, Color(0xFFBFD6EE)),
                startY = yFill, endY = yBottom,
            ),
        )
        // Filo de luz en el nivel.
        val xl = g.chevronX(yFill, inset)
        drawLine(Color.White, Offset(g.mirror(xl - g.dp(3f), mirrored), yFill), Offset(g.mirror(xl + bw + g.dp(3f), mirrored), yFill), g.dp(2f))
    }
    // Rayas de 1/4 y 3/4 entre las cifras.
    listOf(0.25f, 0.75f).forEach { q ->
        val y = yBottom - (yBottom - yTop) * q
        val x = g.chevronX(y, inset)
        drawLine(Color.Black.copy(alpha = 0.5f), Offset(g.mirror(x, mirrored), y), Offset(g.mirror(x + bw, mirrored), y), g.dp(1.5f))
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
private fun TabValue(
    value: String, caption: String, modifier: Modifier = Modifier, alignEnd: Boolean, glow: Color, intensity: Float,
    shift: com.cockpitf30.vehicle.Shift? = null,
) {
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically) {
        // Flecha de cambio a la izquierda de la marcha (solo en la pestaña de la marcha).
        if (alignEnd) ShiftArrow(shift, Modifier.size(width = 46.dp, height = 70.dp))
        // Cifra sobre la imagen: sombra oscura para que se lea y, encima, halo del color de su barra.
        Box(Modifier.padding(horizontal = 20.dp)) {
            val big = numeric(80, FontWeight.Bold)
            Text(value, style = big.copy(shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 3f), 18f)), color = Cockpit.OnDark, maxLines = 1)
            Text(
                value,
                style = big.copy(shadow = Shadow(glow.copy(alpha = 0.55f + 0.4f * intensity), Offset.Zero, 26f + 20f * intensity)),
                color = Cockpit.OnDark, maxLines = 1,
            )
        }
        }
        Text(
            caption,
            style = numeric(20, FontWeight.Medium),
            color = glow,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

private val ShiftUp = Color(0xFF3DDC97)
private val ShiftDown = Color(0xFFFFB238)

/**
 * Flecha de cambio de marcha: doble chevron verde hacia arriba (subir) o ámbar hacia abajo
 * (bajar), con halo y un latido suave. Aparece y se va con un fundido.
 */
@Composable
internal fun ShiftArrow(shift: com.cockpitf30.vehicle.Shift?, modifier: Modifier) {
    var last by remember { mutableStateOf(com.cockpitf30.vehicle.Shift.UP) }
    if (shift != null) last = shift
    androidx.compose.animation.AnimatedVisibility(
        shift != null, modifier,
        enter = androidx.compose.animation.fadeIn(tween(160)) + androidx.compose.animation.scaleIn(tween(160), initialScale = 0.7f),
        exit = androidx.compose.animation.fadeOut(tween(220)),
    ) {
        val up = last == com.cockpitf30.vehicle.Shift.UP
        val color = if (up) ShiftUp else ShiftDown
        val beat by rememberInfiniteTransition(label = "shift").animateFloat(
            0.55f, 1f, infiniteRepeatable(tween(520, easing = LinearEasing), androidx.compose.animation.core.RepeatMode.Reverse), label = "beat",
        )
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            val w = size.width; val h = size.height
            val k = beat
            fun chevron(cy: Float, a: Float) {
                val dy = h * 0.13f * (if (up) -1 else 1)
                val p = Path().apply { moveTo(w * 0.12f, cy - dy); lineTo(w * 0.5f, cy + dy); lineTo(w * 0.88f, cy - dy) }
                drawPath(p, color.copy(alpha = 0.30f * a), style = Stroke(w * 0.30f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round), blendMode = BlendMode.Plus)
                drawPath(p, color.copy(alpha = a), style = Stroke(w * 0.13f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
            // El chevron delantero (en el sentido del cambio) late; el otro queda más tenue.
            val lead = if (up) h * 0.36f else h * 0.64f
            val tail = if (up) h * 0.64f else h * 0.36f
            chevron(tail, 0.45f)
            chevron(lead, k)
        }
    }
}

/**
 * Línea inferior: franjas M y hora, surtidor y autonomía a la izquierda; estado y temperatura
 * exterior a la derecha.
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
        // Sin coche rojo en el centro: con una puerta abierta ya se abre la pantalla Vehículo.
        Spacer(Modifier.weight(1f))
        Text(
            if (status.connected) "READY" else "SIN DATOS",
            style = MaterialTheme.typography.labelLarge,
            color = if (status.connected) Cockpit.Text else Cockpit.Alert,
        )
        Spacer(Modifier.width(32.dp))
        // La caja CAN del F30 no da la temperatura exterior: si no llega, la del tiempo de la zona.
        val weather by com.cockpitf30.weather.WeatherRepository.state.collectAsStateWithLifecycle()
        val outside = state.outsideTempC ?: weather.forecast?.tempC?.toFloat()
        Text(
            outside?.let { if (state.outsideTempC != null) "%+.1f".format(it) else "%+.0f".format(it) } ?: "--",
            style = numeric(28, FontWeight.SemiBold),
            color = Cockpit.Text,
        )
        Text(" °C", style = numeric(18), color = Cockpit.TextDim)
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


/**
 * Fichas de cristal bajo la velocidad (consumo y depósito) y bajo la marcha (viaje en curso:
 * distancia y tiempo). Redundan con otras pantallas, pero llenan la vista del coche con datos
 * útiles. El viaje se lee aquí para que sus cambios solo recompongan las fichas.
 */
@Composable
private fun CarInfoChips(avgConsumption: Float?, fuelLevel: Float?, modifier: Modifier, inset: Dp) {
    val trips by com.cockpitf30.vehicle.trip.TripComputer.state.collectAsStateWithLifecycle()
    val trip = trips.current ?: trips.last
    val consumption = avgConsumption ?: trip?.avgConsumption(trips.tankLiters)
    val liters = fuelLevel?.let { (it * trips.tankLiters).roundToInt() }
    val km = trip?.distanceKm
    val minutes = trip?.let { (it.durationMillis / 60_000L).toInt() }
    Box(modifier) {
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = inset, bottom = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoChip("CONSUMO", consumption?.let { String.format(java.util.Locale.US, "%.1f", it).replace('.', ',') } ?: "--", "l/100")
            InfoChip("DEPÓSITO", liters?.toString() ?: "--", "l", warn = fuelLevel != null && fuelLevel < 0.15f)
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = inset, bottom = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoChip("VIAJE", km?.let { if (it < 100) String.format(java.util.Locale.US, "%.1f", it).replace('.', ',') else "${it.roundToInt()}" } ?: "--", "km")
            InfoChip("TIEMPO", minutes?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" } ?: "--", "h")
        }
    }
}

private val ChipShape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)

@Composable
private fun InfoChip(caption: String, value: String, unit: String, warn: Boolean = false) {
    val accent = if (warn) Color(0xFFFFB347) else Cockpit.OnDarkAccent
    Column(
        Modifier
            .width(150.dp)
            .background(Color(0xFF050B14).copy(alpha = 0.58f), ChipShape)
            .border(
                1.dp,
                Brush.linearGradient(listOf(Color.White.copy(alpha = 0.38f), Color.White.copy(alpha = 0.06f), accent.copy(alpha = 0.45f))),
                ChipShape,
            )
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(accent, androidx.compose.foundation.shape.CircleShape))
            Spacer(Modifier.width(7.dp))
            Text(caption, style = MaterialTheme.typography.labelMedium.copy(fontSize = 14.sp, letterSpacing = 1.sp), color = Color.White.copy(alpha = 0.7f), maxLines = 1)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = numeric(30, FontWeight.SemiBold), color = if (warn) accent else Cockpit.OnDark, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Text(unit, style = numeric(15, FontWeight.Medium), color = Cockpit.OnDarkDim, maxLines = 1, modifier = Modifier.padding(bottom = 5.dp))
        }
    }
}
