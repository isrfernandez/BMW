package com.cockpitf30.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import com.cockpitf30.R
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cockpitf30.system.AppSettings
import com.cockpitf30.ui.screens.CardOrder
import com.cockpitf30.ui.theme.Cockpit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Ilustración de cada botón del menú lateral y su color de acento. Todo el menú va en cobre metalizado
 * y azul acero: los acentos alternan entre los dos y [second] es el metal que acompaña a cada uno.
 */
enum class SideArt(private val steelFirst: Boolean) {
    HOME(true),
    NAV(false),
    PERF(true),
    MUSIC(false),
    APPS(true),
    SETTINGS(false);

    // Se leen al pintar: siguen la paleta del modo de conducción.
    val accent: Color get() = if (steelFirst) Cockpit.SteelBlue else Cockpit.Copper
    val second: Color get() = if (steelFirst) Cockpit.Copper else Cockpit.SteelBlue
}

/** [label] ya no se ve (solo iconos): queda para accesibilidad. El id para el orden es [art]. */
class SideItem(
    val label: String,
    val art: SideArt,
    val active: Boolean,
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

/**
 * Modo mover de la barra lateral, como el de las tarjetas del Inicio (ver Reorder): OK mantenido
 * en el iDrive sobre un botón lo coge; la ruleta o arriba/abajo lo mueven; OK lo suelta y Back
 * cancela. [focused] es el botón con el foco (null si el foco está fuera de la barra).
 */
object SidebarMove {
    val moving = MutableStateFlow<String?>(null)
    var focused: String? = null
    var step: ((Int) -> Unit)? = null
    var start: ((String) -> Unit)? = null

    /** OK mantenido: true si lo ha cogido la barra (el foco estaba en ella). */
    fun longPress(): Boolean {
        val id = focused ?: return false
        if (moving.value != null) return true
        start?.invoke(id) ?: return false
        return true
    }
}

/**
 * Columna fija de accesos rápidos a la izquierda, la zona más cercana al conductor. Cada botón
 * es una placa de metal con la ilustración de su función, grande y sin rótulo, y mide 100 dp de alto para poder
 * pulsarlo sin mirar. El orden se cambia como en la botonera del Inicio: pulsación larga y
 * arrastrar (o OK mantenido y girar la ruleta) y se guarda en Ajustes.
 */
@Composable
fun Sidebar(items: List<SideItem>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val byId = items.associateBy { it.art.name }
    var order by remember { mutableStateOf(CardOrder.merge(settings.sidebarOrder, SideArt.entries.map { it.name })) }
    var beforeMove by remember { mutableStateOf(order) }
    val moving by SidebarMove.moving.collectAsStateWithLifecycle()
    var dragY by remember { mutableFloatStateOf(0f) }
    var dragged by remember { mutableStateOf(false) }
    var suppressClick by remember { mutableStateOf(false) }

    fun startMove(id: String) {
        beforeMove = order
        SidebarMove.moving.value = id
    }
    fun step(dir: Int) {
        val id = SidebarMove.moving.value ?: return
        val from = order.indexOf(id)
        order = CardOrder.move(order, from, from + dir)
    }
    fun finish() {
        settings.sidebarOrder = order
        SidebarMove.moving.value = null
    }
    fun cancel() {
        order = beforeMove
        SidebarMove.moving.value = null
    }
    DisposableEffect(Unit) {
        val st: (Int) -> Unit = ::step
        val sm: (String) -> Unit = ::startMove
        SidebarMove.step = st
        SidebarMove.start = sm
        onDispose {
            if (SidebarMove.step === st) SidebarMove.step = null
            if (SidebarMove.start === sm) SidebarMove.start = null
            SidebarMove.moving.value = null
        }
    }
    BackHandler(enabled = moving != null) { cancel() }
    val stepPx = with(LocalDensity.current) { (TILE_HEIGHT + TILE_GAP).toPx() }

    Column(
        modifier
            .width(132.dp)
            .fillMaxHeight()
            .then(
                // Diurno: sin fondo propio, se ve el mismo fondo claro que el resto de la pantalla.
                if (Cockpit.Light) Modifier
                else Modifier.background(Brush.horizontalGradient(listOf(Color(0xFF080B12), Color(0xFF0B0F18).copy(alpha = 0.7f)))),
            )
            // Desplazable: si la pantalla es más baja de lo previsto, nada queda fuera de alcance.
            .verticalScroll(rememberScrollState(), enabled = moving == null)
            .padding(vertical = 16.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TILE_GAP),
    ) {
        AthosStripe(Modifier.padding(bottom = 2.dp), segment = 22.dp)
        order.forEach { id ->
            val item = byId[id] ?: return@forEach
            key(id) {
                val isMoving = moving == id
                val wobble = if (isMoving) {
                    rememberInfiniteTransition(label = "wobble").animateFloat(
                        -1.2f, 1.2f, infiniteRepeatable(tween(140), RepeatMode.Reverse), label = "rot",
                    ).value
                } else 0f
                FocusTile(
                    onClick = {
                        when {
                            suppressClick -> suppressClick = false
                            isMoving -> finish()
                            moving == null -> item.onClick()
                        }
                    },
                    selected = item.active || isMoving,
                    modifier = Modifier.fillMaxWidth().height(TILE_HEIGHT)
                        .then(if (isMoving) Modifier.zIndex(1f) else Modifier)
                        .graphicsLayer {
                            translationY = if (isMoving) dragY else 0f
                            rotationZ = wobble
                            val sc = if (isMoving) 1.06f else 1f
                            scaleX = sc; scaleY = sc
                        }
                        .semantics { contentDescription = item.label }
                        .onFocusChanged {
                            if (it.isFocused) SidebarMove.focused = id
                            else if (SidebarMove.focused == id) SidebarMove.focused = null
                        }
                        .pointerInput(id) {
                            // Táctil: pulsación larga y arrastrar arriba/abajo; al soltar se guarda.
                            // Pulsación larga sin arrastrar: su atajo (Home → Diagnóstico).
                            detectDragGesturesAfterLongPress(
                                onDragStart = { suppressClick = true; dragged = false; startMove(id) },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragY += amount.y
                                    if (kotlin.math.abs(dragY) > 12.dp.toPx()) dragged = true
                                    while (dragY > stepPx / 2 && order.indexOf(id) < order.lastIndex) { step(1); dragY -= stepPx }
                                    while (dragY < -stepPx / 2 && order.indexOf(id) > 0) { step(-1); dragY += stepPx }
                                },
                                onDragEnd = {
                                    dragY = 0f
                                    finish()
                                    if (!dragged) item.onLongClick?.invoke()
                                },
                                onDragCancel = { dragY = 0f; finish() },
                            )
                        },
                ) { focused ->
                    val lit = item.active || focused || isMoving
                    Box(
                        Modifier.fillMaxSize().drawWithContent {
                            drawContent()
                            glassOverlay(item.art.accent, shade = if (Cockpit.Light) 0f else 0.30f)
                        },
                    ) {
                        SideArtwork(item.art, lit, Modifier.matchParentSize())
                        // Marca del ítem activo: filo del color de acento a la izquierda.
                        if (item.active) {
                            Box(
                                Modifier.align(Alignment.CenterStart).width(4.dp).height(48.dp)
                                    .background(item.art.accent, RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)),
                            )
                        }
                    }
                }
            }
        }
    }
}

private val TILE_HEIGHT = 100.dp
private val TILE_GAP = 9.dp


/**
 * Fondo común: placa de metal cepillado entre azul acero oscuro y cobre, con un reflejo diagonal
 * y un halo del color de la función arriba a la derecha.
 */
private fun DrawScope.backdrop(art: SideArt, dim: Float) {
    val w = size.width; val h = size.height
    if (Cockpit.Light) {
        lightBackdrop(art, dim)
        return
    }
    val top = if (com.cockpitf30.ui.theme.DriveModes.current == com.cockpitf30.ui.theme.DriveMode.NORMAL) Color(0xFF1A2A3E) else Cockpit.SteelBlueDark
    drawRect(Brush.verticalGradient(listOf(top, Color(0xFF0A111B), Color(0xFF07090E))))
    // Viso cobre abajo a la izquierda y azul acero arriba a la derecha.
    drawRect(
        Brush.linearGradient(
            0f to Cockpit.Copper.copy(alpha = 0.34f * dim), 0.45f to Color.Transparent,
            0.6f to Color.Transparent, 1f to Cockpit.SteelBlue.copy(alpha = 0.30f * dim),
            start = Offset(0f, h), end = Offset(w, 0f),
        ),
    )
    // Cepillado: finas líneas horizontales claras y oscuras alternas.
    val step = 2.dp.toPx()
    var y = 0f; var i = 0
    while (y < h) {
        val a = 0.025f + 0.02f * sin(i * 2.3f).let { it * it }
        drawLine((if (i % 2 == 0) Color.White else Color.Black).copy(alpha = a), Offset(0f, y), Offset(w, y), 1f)
        y += step; i++
    }
    // Reflejo metálico en banda diagonal.
    drawRect(
        Brush.linearGradient(
            0.30f to Color.Transparent, 0.42f to art.second.copy(alpha = 0.16f * dim), 0.47f to Color.White.copy(alpha = 0.10f * dim),
            0.52f to art.accent.copy(alpha = 0.12f * dim), 0.64f to Color.Transparent,
            start = Offset(0f, 0f), end = Offset(w, h),
        ),
    )
    drawCircle(
        Brush.radialGradient(listOf(art.accent.copy(alpha = 0.40f * dim), Color.Transparent), Offset(w * 0.78f, h * 0.22f), w * 0.9f),
        w * 0.9f, Offset(w * 0.78f, h * 0.22f),
    )
}


/**
 * Placa del tema diurno: aluminio claro (blanco a gris perla) con el mismo viso cobre/acero y
 * halo del color de la función, muy suaves, y una franja clara abajo para el rótulo.
 */
private fun DrawScope.lightBackdrop(art: SideArt, dim: Float) {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color.White, Color(0xFFE9EDF2), Color(0xFFDDE3EA))))
    drawRect(
        Brush.linearGradient(
            0f to Cockpit.Copper.copy(alpha = 0.16f * dim), 0.45f to Color.Transparent,
            0.6f to Color.Transparent, 1f to Cockpit.SteelBlue.copy(alpha = 0.16f * dim),
            start = Offset(0f, h), end = Offset(w, 0f),
        ),
    )
    val step = 2.dp.toPx()
    var y = 0f; var i = 0
    while (y < h) {
        drawLine(Cockpit.Ink.copy(alpha = 0.012f + 0.012f * sin(i * 2.3f).let { it * it }), Offset(0f, y), Offset(w, y), 1f)
        y += step; i++
    }
    drawCircle(
        Brush.radialGradient(listOf(art.accent.copy(alpha = 0.22f * dim), Color.Transparent), Offset(w * 0.78f, h * 0.22f), w * 0.9f),
        w * 0.9f, Offset(w * 0.78f, h * 0.22f),
    )
}

// ---------------------------------------------------------------- ilustraciones

/**
 * Ilustración del botón (la de siempre), ahora sin rótulo: se agranda y se centra en la placa.
 * Apagada cuando no está activo ni enfocado.
 */
@Composable
private fun SideArtwork(art: SideArt, lit: Boolean, modifier: Modifier = Modifier) {
    val dim = if (lit) 1f else 0.9f
    Box(modifier) {
        Canvas(Modifier.matchParentSize()) { backdrop(art, dim) }
        if (art == SideArt.HOME) {
            // El propio coche, en vista 3/4, ocupando la placa entera (antes dejaba sitio al rótulo).
            Image(
                cachedPainter(R.drawable.car_34_closed), null,
                Modifier.matchParentSize().graphicsLayer { scaleX = 1.12f; scaleY = 1.12f },
                contentScale = ContentScale.Crop,
                alpha = dim,
            )
        } else {
            Canvas(Modifier.matchParentSize()) {
                val w = size.width; val h = size.height
                // Los dibujos estaban arriba a la derecha (abajo iba el nombre): se llevan al centro y se agrandan.
                val (px, py) = ART_CENTER.getValue(art)
                withTransform({
                    translate(w * (0.5f - px), h * (0.5f - py))
                    scale(ART_SCALE, ART_SCALE, Offset(w * px, h * py))
                }) {
                    when (art) {
                        SideArt.NAV -> navArt(art.accent, dim)
                        SideArt.MUSIC -> musicArt(art.accent, art.second, dim)
                        SideArt.APPS -> appsArt(dim)
                        SideArt.SETTINGS -> settingsArt(art.accent, dim)
                        SideArt.PERF -> perfArt(art.accent, art.second, dim)
                        SideArt.HOME -> Unit
                    }
                }
            }
        }
    }
}

private const val ART_SCALE = 1.22f

/** Centro aproximado de cada dibujo (fracción de la placa), que se lleva al centro. */
private val ART_CENTER = mapOf(
    SideArt.NAV to (0.55f to 0.45f),
    SideArt.MUSIC to (0.52f to 0.42f),
    SideArt.APPS to (0.62f to 0.38f),
    SideArt.SETTINGS to (0.52f to 0.36f),
    SideArt.PERF to (0.50f to 0.42f),
    SideArt.HOME to (0.5f to 0.5f),
)

/** Calles en perspectiva, una ruta luminosa y el pin de destino. */
private fun DrawScope.navArt(accent: Color, dim: Float) {
    val w = size.width; val h = size.height
    val vx = w * 0.62f; val vy = h * 0.10f
    val line = Color(0xFF6FA6D8).copy(alpha = 0.22f * dim)
    for (i in -5..5) drawLine(line, Offset(vx + i * w * 0.03f, vy), Offset(vx + i * w * 0.28f, h), 1.dp.toPx())
    for (i in 1..6) {
        val t = (i / 6f).let { it * it }
        val y = vy + (h - vy) * t
        val half = w * 0.1f + w * 1.2f * t
        drawLine(line, Offset(vx - half, y), Offset(vx + half, y), 1.dp.toPx())
    }
    val route = Path().apply { moveTo(w * 0.18f, h); cubicTo(w * 0.32f, h * 0.62f, w * 0.85f, h * 0.70f, w * 0.70f, h * 0.36f) }
    drawPath(route, accent.copy(alpha = 0.35f * dim), style = Stroke(12.dp.toPx(), cap = StrokeCap.Round), blendMode = BlendMode.Plus)
    drawPath(route, Brush.linearGradient(listOf(accent.copy(alpha = dim), Color.White.copy(alpha = dim))), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
    val c = Offset(w * 0.70f, h * 0.24f)
    val r = h * 0.10f
    drawCircle(accent.copy(alpha = 0.3f * dim), r * 2f, c)
    val pin = Path().apply {
        moveTo(c.x, c.y + r * 2f)
        cubicTo(c.x - r * 0.4f, c.y + r * 1.3f, c.x - r * 1.1f, c.y + r * 0.8f, c.x - r, c.y)
        cubicTo(c.x - r, c.y - r * 1.35f, c.x + r, c.y - r * 1.35f, c.x + r, c.y)
        cubicTo(c.x + r * 1.1f, c.y + r * 0.8f, c.x + r * 0.4f, c.y + r * 1.3f, c.x, c.y + r * 2f)
        close()
    }
    drawPath(pin, Brush.linearGradient(listOf(Color.White, accent), Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r * 2)), alpha = dim)
    drawCircle(Color(0xFF0B1220), r * 0.38f, c)
}

/** Disco de vinilo asomando por la derecha y un ecualizador. */
private fun DrawScope.musicArt(accent: Color, second: Color, dim: Float) {
    val w = size.width; val h = size.height
    val c = Offset(w * 0.86f, h * 0.36f)
    val r = h * 0.48f
    drawCircle(Brush.radialGradient(listOf(Color(0xFF1A1424), Color(0xFF07050B)), c, r), r, c, alpha = dim)
    for (k in 1..7) drawCircle(Cockpit.Ink.copy(alpha = 0.05f * dim), r * (0.35f + k * 0.09f), c, style = Stroke(1.dp.toPx()))
    drawArc(Cockpit.Ink.copy(alpha = 0.25f * dim), 200f, 50f, false, Offset(c.x - r * 0.85f, c.y - r * 0.85f), Size(r * 1.7f, r * 1.7f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    drawCircle(Brush.linearGradient(listOf(Cockpit.CopperLight, second, Cockpit.CopperDark), Offset(c.x - r * 0.3f, c.y - r * 0.3f), Offset(c.x + r * 0.3f, c.y + r * 0.3f)), r * 0.3f, c, alpha = dim)
    drawCircle(Color(0xFF07050B), r * 0.05f, c)
    val n = 9
    val gap = 3.dp.toPx()
    val bw = (w * 0.62f - gap * (n - 1)) / n
    for (i in 0 until n) {
        val k = 0.3f + 0.7f * (0.5f + 0.5f * sin(i * 1.1f + 0.6f)) * (0.55f + 0.45f * sin(i * 0.5f).let { it * it })
        val bh = h * 0.42f * k
        val top = h * 0.70f - bh
        drawRoundRect(
            Brush.verticalGradient(listOf(Cockpit.SteelBlueLight.copy(alpha = 0.9f * dim), accent.copy(alpha = 0.15f * dim)), startY = top, endY = h * 0.70f),
            Offset(w * 0.08f + i * (bw + gap), top), Size(bw, bh), CornerRadius(bw / 2),
        )
    }
}

/** Rejilla de aplicaciones: placas de colores en ligera perspectiva. */
private fun DrawScope.appsArt(dim: Float) {
    val w = size.width; val h = size.height
    // Placas alternas de cobre y azul acero, como piezas de metal.
    val colors = List(9) { if (it % 2 == 0) Cockpit.Copper else Cockpit.SteelBlue }
    val s = w * 0.2f
    val gap = w * 0.06f
    rotate(-12f, Offset(w * 0.62f, h * 0.38f)) {
        for (row in 0 until 3) for (col in 0 until 3) {
            val c = colors[row * 3 + col]
            val o = Offset(w * 0.30f + col * (s + gap), h * 0.02f + row * (s + gap))
            drawRoundRect(c.copy(alpha = 0.25f * dim), o - Offset(3f, 3f), Size(s + 6f, s + 6f), CornerRadius(s * 0.32f))
            drawRoundRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.85f), c, c.copy(alpha = 0.55f)), o, o + Offset(s, s)), o, Size(s, s), CornerRadius(s * 0.28f), alpha = 0.85f * dim)
        }
    }
}

/** Engranaje de acero asomando por la esquina y unos reguladores. */
private fun DrawScope.settingsArt(accent: Color, dim: Float) {
    val w = size.width; val h = size.height
    val c = Offset(w * 0.80f, h * 0.30f)
    val ro = h * 0.42f
    val ri = ro * 0.80f
    val teeth = 10
    val gear = Path().apply {
        for (i in 0 until teeth * 4) {
            val a = (i / (teeth * 4f)) * 2 * PI - PI / 2
            val r = if (i % 4 == 1 || i % 4 == 2) ro else ri
            val p = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
            if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        close()
    }
    drawPath(gear, Brush.linearGradient(listOf(Cockpit.CopperLight, Cockpit.CopperDark, Cockpit.Copper, Color(0xFF2A160C)), Offset(c.x - ro, c.y - ro), Offset(c.x + ro, c.y + ro)), alpha = 0.85f * dim)
    drawCircle(Color(0xFF0B1220), ro * 0.38f, c)
    drawCircle(accent.copy(alpha = 0.8f * dim), ro * 0.38f, c, style = Stroke(2.dp.toPx()))
    listOf(0.30f to 0.62f, 0.48f to 0.35f, 0.66f to 0.78f).forEach { (fy, fx) ->
        val y = h * fy * 0.8f
        drawLine(Cockpit.Ink.copy(alpha = 0.18f * dim), Offset(w * 0.10f, y), Offset(w * 0.62f, y), 3.dp.toPx(), StrokeCap.Round)
        drawLine(accent.copy(alpha = 0.85f * dim), Offset(w * 0.10f, y), Offset(w * (0.10f + 0.52f * fx), y), 3.dp.toPx(), StrokeCap.Round)
        drawCircle(Color.White.copy(alpha = dim), 4.5.dp.toPx(), Offset(w * (0.10f + 0.52f * fx), y))
    }
}

/** G-meter: anillos de fuerzas G con el punto desplazado y su estela, y una aguja de potencia. */
private fun DrawScope.perfArt(accent: Color, second: Color, dim: Float) {
    val w = size.width; val h = size.height
    val c = Offset(w * 0.70f, h * 0.36f)
    val r = h * 0.34f
    for (k in 1..3) drawCircle(Cockpit.Ink.copy(alpha = (0.10f + 0.05f * k) * dim), r * k / 3f, c, style = Stroke(1.dp.toPx()))
    drawLine(Cockpit.Ink.copy(alpha = 0.18f * dim), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
    drawLine(Cockpit.Ink.copy(alpha = 0.18f * dim), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())
    val trail = Path().apply {
        moveTo(c.x, c.y)
        cubicTo(c.x + r * 0.1f, c.y - r * 0.4f, c.x + r * 0.5f, c.y - r * 0.5f, c.x + r * 0.62f, c.y - r * 0.18f)
    }
    drawPath(trail, accent.copy(alpha = 0.45f * dim), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round), blendMode = BlendMode.Plus)
    drawPath(trail, Brush.linearGradient(listOf(accent.copy(alpha = 0.2f * dim), Color.White.copy(alpha = dim)), c, Offset(c.x + r * 0.62f, c.y - r * 0.18f)), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    val dot = Offset(c.x + r * 0.62f, c.y - r * 0.18f)
    drawCircle(accent.copy(alpha = 0.35f * dim), 9.dp.toPx(), dot)
    drawCircle(Color.White.copy(alpha = dim), 4.dp.toPx(), dot)
    // Arco de potencia abajo a la izquierda.
    val pc = Offset(w * 0.24f, h * 0.62f)
    val pr = h * 0.26f
    drawArc(Cockpit.Ink.copy(alpha = 0.15f * dim), 150f, 240f, false, Offset(pc.x - pr, pc.y - pr), Size(pr * 2, pr * 2), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
    drawArc(Brush.sweepGradient(listOf(second, accent, second), pc), 150f, 170f, false, Offset(pc.x - pr, pc.y - pr), Size(pr * 2, pr * 2), alpha = dim, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
}
