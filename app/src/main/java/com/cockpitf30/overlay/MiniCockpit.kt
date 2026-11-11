package com.cockpitf30.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.cockpitf30.assistant.AssistantPhase
import com.cockpitf30.assistant.AssistantState
import com.cockpitf30.assistant.VoiceAssistant
import com.cockpitf30.nav.SpeedCameras
import com.cockpitf30.system.AppSettings
import com.cockpitf30.ui.MainActivity
import com.cockpitf30.ui.components.glassPanel
import com.cockpitf30.ui.components.radarDistance
import com.cockpitf30.ui.theme.Cockpit
import com.cockpitf30.ui.theme.CockpitOverlayTheme
import com.cockpitf30.ui.theme.DriveMode
import com.cockpitf30.ui.theme.DriveModes
import com.cockpitf30.ui.theme.numeric
import com.cockpitf30.vehicle.GearEstimator
import com.cockpitf30.vehicle.Shift
import com.cockpitf30.vehicle.ShiftAdvisor
import com.cockpitf30.vehicle.VehicleRepository
import kotlinx.coroutines.delay

/**
 * Mini cockpit flotante: con Google Maps (u otra app) en pantalla completa, una barra pequeña
 * encima con velocidad, marcha y flecha de cambio, rpm, autonomía, el radar más cercano y el
 * asistente (lo que oye y lo que responde; tocar el micrófono = hablar sin «Hola BMW»).
 *
 * Android no deja meter la ventana de otra app (Maps) dentro de la nuestra; lo que sí deja es
 * pintar encima de las demás con el permiso «Mostrar sobre otras apps». Se arrastra a donde
 * moleste menos y recuerda el sitio; tocar la velocidad lo pliega y el icono de la derecha vuelve
 * al cockpit.
 */
@SuppressLint("StaticFieldLeak") // Solo guarda el applicationContext.
object MiniCockpit {
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var params: WindowManager.LayoutParams? = null
    private lateinit var app: Context

    fun canShow(context: Context): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)

    /** Pantalla del sistema para dar el permiso «Mostrar sobre otras apps». */
    fun requestPermission(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.recoverCatching {
        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    fun show(context: Context) {
        app = context.applicationContext
        if (view != null || !canShow(app)) return
        val wm = app.getSystemService(WindowManager::class.java) ?: return
        val (x, y) = AppSettings(app).miniCockpitPos
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // Sin foco: el teclado y la ruleta siguen yendo a Maps; los toques fuera, también.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x.coerceAtLeast(0)
            this.y = if (y >= 0) y else 14
        }
        val o = OverlayOwner()
        val v = ComposeView(app).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setViewTreeViewModelStoreOwner(o)
            setContent {
                CockpitOverlayTheme {
                    MiniCockpitBar(
                        onDrag = { dx, dy -> moveBy(dx, dy) },
                        onDragEnd = { savePosition() },
                        onSize = { size -> if (x < 0) centerOnce(size) },
                    )
                }
            }
        }
        runCatching { wm.addView(v, p) }.onFailure { o.destroy(); return }
        o.resume()
        view = v
        owner = o
        params = p
    }

    fun hide() {
        val v = view ?: return
        runCatching { app.getSystemService(WindowManager::class.java)?.removeView(v) }
        owner?.destroy()
        view = null
        owner = null
        params = null
    }

    private var centered = false

    /** La primera vez (sin posición guardada) se centra arriba. */
    private fun centerOnce(size: IntSize) {
        if (centered) return
        centered = true
        val p = params ?: return
        val w = app.resources.displayMetrics.widthPixels
        p.x = ((w - size.width) / 2).coerceAtLeast(0)
        update()
    }

    private fun moveBy(dx: Float, dy: Float) {
        val p = params ?: return
        val m = app.resources.displayMetrics
        val v = view ?: return
        p.x = (p.x + dx.toInt()).coerceIn(0, (m.widthPixels - v.width).coerceAtLeast(0))
        p.y = (p.y + dy.toInt()).coerceIn(0, (m.heightPixels - v.height).coerceAtLeast(0))
        update()
    }

    private fun savePosition() {
        val p = params ?: return
        AppSettings(app).miniCockpitPos = p.x to p.y
    }

    private fun update() {
        val v = view ?: return
        val p = params ?: return
        runCatching { app.getSystemService(WindowManager::class.java)?.updateViewLayout(v, p) }
    }

    /** Ciclo de vida propio: la ventana flotante no pertenece a ninguna actividad. */
    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        override val viewModelStore = ViewModelStore()

        init {
            saved.performAttach()
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
        }

        fun resume() { registry.currentState = Lifecycle.State.RESUMED }
        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}

/** Cuánto se queda a la vista la última respuesta del asistente. */
private const val REPLY_MS = 8_000L

@Composable
internal fun MiniCockpitBar(
    onDrag: (Float, Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    onSize: (IntSize) -> Unit = {},
) {
    val ctx = LocalContext.current
    val settings = remember { AppSettings(ctx) }
    val car by VehicleRepository.state.collectAsState()
    val voice by VoiceAssistant.state.collectAsState()
    val cams by SpeedCameras.state.collectAsState()
    var compact by remember { mutableStateOf(settings.miniCockpitCompact) }

    val gear = car.gear ?: GearEstimator.estimate(car.speedKmh, car.rpm)
    val advisor = remember { ShiftAdvisor() }
    val shiftOn = remember { settings.shiftIndicator }
    val shift = if (!shiftOn) null else advisor.update(
        System.currentTimeMillis(), gear?.toIntOrNull(), car.rpm, car.speedKmh,
        if (DriveModes.current == DriveMode.SPORT) ShiftAdvisor.SPORT else ShiftAdvisor.ECO,
    )

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    val bubble = bubbleText(voice, now)

    Column(
        Modifier
            .onSizeChanged(onSize)
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = onDragEnd) { change, drag ->
                    change.consume()
                    onDrag(drag.x, drag.y)
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.height(84.dp).glassPanel(42.dp, Cockpit.BlueLight, smoke = 0.88f).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // Velocidad: tocar pliega o despliega.
            Row(
                Modifier.fillMaxHeight().clickable(remember { MutableInteractionSource() }, null) {
                    compact = !compact
                    settings.miniCockpitCompact = compact
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(car.speedKmh?.toString() ?: "--", style = numeric(48, FontWeight.SemiBold), color = Cockpit.Text)
                Spacer(Modifier.width(6.dp))
                Text("km/h", style = numeric(16, FontWeight.Medium), color = Cockpit.TextDim, modifier = Modifier.padding(top = 14.dp))
            }
            if (!compact) {
                GearBox(gear, shift)
                RpmBar(car.rpm)
                car.rangeKm?.let { Stat(it.toString(), "km", "AUTONOMÍA") }
                cams.ahead?.let { a -> RadarChip(radarDistance(a.meters), a.camera.maxspeed) }
            }
            MicOrb(voice) { VoiceAssistant.tap(ctx) }
            if (!compact) AppButton { MainActivity.show(ctx) }
        }
        AnimatedVisibility(bubble != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Text(
                bubble.orEmpty(),
                Modifier.padding(top = 8.dp).widthIn(max = 760.dp)
                    .glassPanel(22.dp, Cockpit.BlueLight, smoke = 0.92f).padding(horizontal = 22.dp, vertical = 12.dp),
                style = MaterialTheme.typography.titleMedium,
                color = Cockpit.Text,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Lo que se enseña bajo la barra: lo que oye mientras escucha y la respuesta unos segundos. */
private fun bubbleText(s: AssistantState, now: Long): String? = when (s.phase) {
    AssistantPhase.LISTENING -> s.heard?.takeIf { it.isNotBlank() }?.let { "«$it»" } ?: if (s.paused) "En pausa · di «sigue»" else "Te escucho…"
    AssistantPhase.ANSWERING -> s.reply
    else -> s.reply?.takeIf { now - s.replyAt < REPLY_MS }
}

@Composable
private fun GearBox(gear: String?, shift: Shift?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(54.dp).glassPanel(12.dp, Cockpit.BlueLight, smoke = 0.3f),
            contentAlignment = Alignment.Center,
        ) {
            Text(gear ?: "–", style = numeric(34, FontWeight.SemiBold), color = Cockpit.Text)
        }
        if (shift != null) {
            Spacer(Modifier.width(6.dp))
            val up = shift == Shift.UP
            val color = if (up) Cockpit.Ok else Color(0xFFFFB020)
            Canvas(Modifier.size(width = 26.dp, height = 34.dp)) {
                val w = size.width
                val h = size.height
                val path = Path().apply {
                    if (up) { moveTo(w / 2, 0f); lineTo(w, h * 0.55f); lineTo(0f, h * 0.55f) }
                    else { moveTo(w / 2, h); lineTo(w, h * 0.45f); lineTo(0f, h * 0.45f) }
                    close()
                }
                drawPath(path, color)
                val stemTop = if (up) h * 0.55f else h * 0.08f
                drawRect(color, Offset(w * 0.32f, stemTop), Size(w * 0.36f, h * 0.37f))
            }
        }
    }
}

@Composable
private fun RpmBar(rpm: Int?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(rpm?.let { "%.1f".format(java.util.Locale("es"), it / 1000f) } ?: "--", style = numeric(24, FontWeight.SemiBold), color = Cockpit.Text)
            Spacer(Modifier.width(4.dp))
            Text("x1000 rpm", style = numeric(13, FontWeight.Medium), color = Cockpit.TextDim)
        }
        val accent = Cockpit.Blue
        Canvas(Modifier.size(width = 130.dp, height = 8.dp)) {
            val f = ((rpm ?: 0) / 5000f).coerceIn(0f, 1f)
            val r = CornerRadius(size.height / 2)
            drawRoundRect(Cockpit.Track, cornerRadius = r)
            if (f > 0f) drawRoundRect(
                Brush.horizontalGradient(listOf(accent, if (f > 0.84f) Cockpit.Alert else Cockpit.BlueLight)),
                size = Size(size.width * f, size.height), cornerRadius = r,
            )
        }
    }
}

@Composable
private fun Stat(value: String, unit: String, caption: String) {
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = numeric(26, FontWeight.SemiBold), color = Cockpit.Text)
            Spacer(Modifier.width(4.dp))
            Text(unit, style = numeric(14, FontWeight.Medium), color = Cockpit.TextDim)
        }
        Text(caption, style = MaterialTheme.typography.labelSmall, color = Cockpit.TextDim)
    }
}

@Composable
private fun RadarChip(distance: String, limit: Int?) {
    Row(
        Modifier.glassPanel(14.dp, Cockpit.Alert, smoke = 0.55f).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (limit != null) {
            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(38.dp)) {
                    drawCircle(Color.White)
                    drawCircle(Cockpit.Alert, style = Stroke(5.dp.toPx()), radius = size.minDimension / 2 - 2.5.dp.toPx())
                }
                Text(limit.toString(), style = numeric(17, FontWeight.Bold), color = Color.Black)
            }
        }
        Column {
            Text("RADAR", style = MaterialTheme.typography.labelSmall, color = Cockpit.Alert)
            Text(distance, style = numeric(20, FontWeight.SemiBold), color = Cockpit.Text)
        }
    }
}

/** Orbe del asistente: tenue esperando «Hola BMW», late al escuchar, cobre al responder. */
@Composable
private fun MicOrb(s: AssistantState, onTap: () -> Unit) {
    val listening = s.phase == AssistantPhase.LISTENING
    val answering = s.phase == AssistantPhase.ANSWERING
    val off = s.phase == AssistantPhase.PAUSED || s.phase == AssistantPhase.OFF || s.phase == AssistantPhase.NO_PERMISSION
    val pulse by rememberInfiniteTransition(label = "orb").animateFloat(
        0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "orbPulse",
    )
    val color = when {
        answering -> Cockpit.Copper
        listening -> Cockpit.BlueLight
        off -> Cockpit.TextDim
        else -> Cockpit.Blue
    }
    val level = s.level
    Box(
        Modifier.size(62.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(62.dp)) {
            val r = size.minDimension / 2
            val glow = if (listening) 0.35f + 0.35f * maxOf(pulse, level) else if (answering) 0.4f else 0.15f
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = glow), Color.Transparent)), r)
            drawCircle(color.copy(alpha = if (off) 0.35f else 0.9f), r * 0.52f, style = Stroke(2.5.dp.toPx()))
            // Micrófono: cápsula y soporte.
            val w = r * 0.30f
            val h = r * 0.52f
            val cx = center.x
            val top = center.y - h * 0.62f
            drawRoundRect(color, Offset(cx - w / 2, top), Size(w, h), CornerRadius(w / 2))
            val stroke = 2.dp.toPx()
            drawArc(color, 0f, 180f, false, Offset(cx - w, top + h * 0.35f), Size(w * 2, h * 0.9f), style = Stroke(stroke))
            drawLine(color, Offset(cx, top + h * 1.25f), Offset(cx, top + h * 1.5f), stroke)
        }
    }
}

/** Volver al cockpit: cuadradito con la rejilla de la barra lateral. */
@Composable
private fun AppButton(onTap: () -> Unit) {
    val color = Cockpit.Text
    Box(
        Modifier.size(54.dp).glassPanel(14.dp, Cockpit.BlueLight, smoke = 0.3f)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val g = size.width * 0.14f
            val c = (size.width - g) / 2
            for (i in 0..1) for (j in 0..1) {
                drawRoundRect(color.copy(alpha = 0.9f), Offset(i * (c + g), j * (c + g)), Size(c, c), CornerRadius(3.dp.toPx()))
            }
        }
    }
}
