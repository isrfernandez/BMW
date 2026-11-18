package com.cockpitf30.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
 * Icono de cada botón del menú lateral y su color de acento. Todo el menú va en cobre metalizado
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
 * es una placa de metal con un icono grande, sin rótulo, y mide 100 dp de alto para poder
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
                        Canvas(Modifier.matchParentSize()) {
                            backdrop(item.art, if (lit) 1f else 0.9f)
                            sideIcon(item.art, lit)
                        }
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

// ---------------------------------------------------------------- iconos

/**
 * Icono del botón, centrado y grande (unos 56 dp), dibujado en una rejilla de 24 x 24 como los
 * iconos de sistema. Metal claro hacia el color de acento; con foco o activo, además un halo.
 * Diurno: tinta oscura hacia el acento.
 */
private fun DrawScope.sideIcon(art: SideArt, lit: Boolean) {
    val side = min(size.width, size.height) * 0.60f
    val u = side / 24f
    val origin = Offset((size.width - side) / 2f, (size.height - side) / 2f)
    val alpha = if (lit) 1f else 0.82f
    val top = if (Cockpit.Light) Cockpit.Ink else Color.White
    val brush = Brush.linearGradient(
        0f to top, 0.45f to (if (Cockpit.Light) Cockpit.Ink else Color.White).copy(alpha = 0.92f), 1f to art.accent,
        start = origin, end = origin + Offset(side, side),
    )
    val shapes = iconShapes(art)
    withTransform({ translate(origin.x, origin.y); scale(u, u, Offset.Zero) }) {
        // Halo del color de acento detrás (solo en oscuro: con luz de día no se ve y ensucia).
        if (lit && !Cockpit.Light) shapes.forEach { (path, stroke) ->
            drawPath(path, art.accent.copy(alpha = 0.55f), style = Stroke(if (stroke) 5.5f else 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Plus)
        }
        shapes.forEach { (path, stroke) ->
            drawPath(path, brush, alpha = alpha, style = if (stroke) Stroke(2.1f, cap = StrokeCap.Round, join = StrokeJoin.Round) else Fill)
        }
    }
}

/** Trazos del icono en la rejilla de 24: (camino, true si es línea / false si es relleno). */
private fun iconShapes(art: SideArt): List<Pair<Path, Boolean>> = when (art) {
    // Casa con su puerta (antes era el coche, que se confundía con Vehículo).
    SideArt.HOME -> listOf(
        Path().apply {
            moveTo(2.5f, 11.6f); lineTo(12f, 3.6f); lineTo(21.5f, 11.6f)
            moveTo(5.2f, 9.6f); lineTo(5.2f, 20.4f); lineTo(18.8f, 20.4f); lineTo(18.8f, 9.6f)
            moveTo(10f, 20.4f); lineTo(10f, 14.6f); lineTo(14f, 14.6f); lineTo(14f, 20.4f)
        } to true,
    )
    // Flecha de navegación (la de los navegadores), algo girada, sobre un punto de ruta.
    SideArt.NAV -> listOf(
        Path().apply {
            val pts = listOf(Offset(12f, 2f), Offset(19.5f, 20.5f), Offset(12f, 16.4f), Offset(4.5f, 20.5f))
            val c = Offset(12f, 12.5f)
            val a = 30.0 * PI / 180
            pts.map { p ->
                val d = p - c
                Offset(c.x + (d.x * cos(a) - d.y * sin(a)).toFloat(), c.y + (d.x * sin(a) + d.y * cos(a)).toFloat())
            }.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            close()
        } to false,
    )
    // Velocímetro: arco con marcas, aguja y eje.
    SideArt.PERF -> buildList {
        val c = Offset(12f, 13.5f)
        val r = 9.5f
        add(Path().apply { arcTo(Rect(c, r), 150f, 240f, true) } to true)
        val ticks = Path()
        for (k in 0..6) {
            val ang = (150f + k * 40f) * PI.toFloat() / 180f
            val o = Offset(c.x + cos(ang) * (r - 1.2f), c.y + sin(ang) * (r - 1.2f))
            val i = Offset(c.x + cos(ang) * (r - 3.2f), c.y + sin(ang) * (r - 3.2f))
            ticks.moveTo(o.x, o.y); ticks.lineTo(i.x, i.y)
        }
        add(ticks to true)
        val na = (-48f) * PI.toFloat() / 180f
        add(Path().apply { moveTo(c.x, c.y); lineTo(c.x + cos(na) * 7.4f, c.y + sin(na) * 7.4f) } to true)
        add(Path().apply { addOval(Rect(c, 2.1f)) } to false)
        // Base del reloj.
        add(Path().apply { moveTo(6.5f, 21f); lineTo(17.5f, 21f) } to true)
    }
    // Dos corcheas unidas.
    SideArt.MUSIC -> listOf(
        Path().apply {
            addOval(Rect(Offset(6.8f, 18.2f), 3.0f).let { Rect(it.left - 0.4f, it.top + 0.4f, it.right + 0.4f, it.bottom - 0.4f) })
            addOval(Rect(Offset(17.3f, 16.2f), 3.0f).let { Rect(it.left - 0.4f, it.top + 0.4f, it.right + 0.4f, it.bottom - 0.4f) })
            // Barra que une las plicas.
            moveTo(8.9f, 5.6f); lineTo(20.3f, 2.8f); lineTo(20.3f, 6.2f); lineTo(8.9f, 9.0f); close()
        } to false,
        Path().apply { moveTo(9.4f, 18f); lineTo(9.4f, 6.4f); moveTo(19.9f, 16f); lineTo(19.9f, 3.6f) } to true,
    )
    // Rejilla de 3 x 3 apps.
    SideArt.APPS -> listOf(
        Path().apply {
            for (row in 0 until 3) for (col in 0 until 3) {
                val x = 2.6f + col * 7f; val y = 2.6f + row * 7f
                addRoundRect(androidx.compose.ui.geometry.RoundRect(x, y, x + 5.4f, y + 5.4f, CornerRadius(1.5f)))
            }
        } to false,
    )
    // Engranaje con su agujero.
    SideArt.SETTINGS -> listOf(
        Path().apply {
            fillType = PathFillType.EvenOdd
            val c = Offset(12f, 12f)
            val teeth = 8
            val ro = 10.6f; val ri = 8.0f
            for (i in 0 until teeth * 4) {
                val a = (i / (teeth * 4f)) * 2 * PI - PI / 2 - PI / (teeth * 4)
                val r = if (i % 4 == 1 || i % 4 == 2) ro else ri
                val p = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
            addOval(Rect(c, 3.6f))
        } to false,
    )
}
