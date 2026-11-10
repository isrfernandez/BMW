package com.cockpitf30.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.withStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.cockpitf30.R
import com.cockpitf30.ui.theme.Cockpit
import kotlinx.coroutines.delay

private const val FRAMES = 60
private const val COLS = 10
private const val CELL = 256
private const val FRAME_MS = 30L

/** El triángulo de cobre y luz azul del usuario (60 fotogramas de su GIF, fondo quitado). */
@Composable
fun CockpitSpinner(modifier: Modifier = Modifier.size(240.dp)) {
    val sheet = cachedBitmap(R.drawable.spinner_sheet)
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { now -> frame = (((now - start) / 1_000_000 / FRAME_MS) % FRAMES).toInt() }
    }
    Canvas(modifier) {
        val f = frame
        drawImage(
            sheet,
            srcOffset = IntOffset((f % COLS) * CELL, (f / COLS) * CELL),
            srcSize = IntSize(CELL, CELL),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        )
    }
}

/**
 * Pantalla velada con el spinner mientras [visible]. Solo aparece si la carga dura más de
 * [graceMs] para que las cargas rápidas no parpadeen. Se come los toques mientras se ve.
 */
@Composable
fun LoadingOverlay(visible: Boolean, text: String? = null, graceMs: Long = 150) {
    var show by remember { mutableIntStateOf(0) }
    LaunchedEffect(visible) {
        if (visible) { delay(graceMs); show = 1 } else show = 0
    }
    AnimatedVisibility(show == 1, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).pointerInput(Unit) { detectTapGestures { } },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CockpitSpinner()
                if (text != null) Text(text, style = MaterialTheme.typography.bodyLarge, color = Cockpit.TextDim)
            }
        }
    }
}

/** true cuando las imágenes grandes ya están decodificadas (lo pone la precarga de MainActivity). */
object Startup {
    val ready = kotlinx.coroutines.flow.MutableStateFlow(false)
}

/**
 * Pantalla de arranque: la imagen del usuario (triángulo y rótulo «CockPit F30») con un haz de
 * luz que recorre las letras. Dura al menos [minMs] y sigue mientras la precarga no termine
 * (imágenes y lista de apps), con un tope de [maxMs] para no quedarse nunca colgada.
 */
@Composable
fun StartupSplash(minMs: Long = 3000, maxMs: Long = 7000) {
    val ready by Startup.ready.collectAsState()
    var elapsed by remember { mutableIntStateOf(0) } // 0 = antes de minMs, 1 = pasado, 2 = tope
    LaunchedEffect(Unit) { delay(minMs); elapsed = 1; delay(maxMs - minMs); elapsed = 2 }
    val visible = !(elapsed == 2 || (elapsed == 1 && ready))
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(androidx.compose.animation.core.tween(700))) {
        val scene = cachedBitmap(R.drawable.splash_scene)
        val mask = cachedBitmap(R.drawable.splash_text_mask)
        val beam by androidx.compose.animation.core.rememberInfiniteTransition(label = "haz").animateFloat(
            -0.35f, 1.35f,
            androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(1700, delayMillis = 350, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            ),
            label = "haz",
        )
        Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } }) {
            Canvas(Modifier.fillMaxSize()) {
                drawImage(scene, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                // El haz solo ilumina las letras: se pinta dentro de su silueta en una capa
                // aparte que se suma (Plus) a la imagen.
                // La silueta está recortada de splash_scene en su tamaño real (1920×720).
                val kx = size.width / SPLASH_W
                val ky = size.height / SPLASH_H
                val left = TEXT_X * kx
                val top = TEXT_Y * ky
                val w = mask.width * kx
                val h = mask.height * ky
                val layer = androidx.compose.ui.geometry.Rect(left, top, left + w, top + h)
                val canvas = drawContext.canvas
                canvas.saveLayer(layer, androidx.compose.ui.graphics.Paint().apply { blendMode = androidx.compose.ui.graphics.BlendMode.Plus })
                drawImage(mask, dstOffset = IntOffset(left.toInt(), top.toInt()), dstSize = IntSize(w.toInt(), h.toInt()))
                val cx = left + w * beam
                val band = w * 0.16f
                drawRect(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        0f to Color.Transparent, 0.35f to Color(0xFF9FD4FF).copy(alpha = 0.55f), 0.5f to Color.White,
                        0.65f to Color(0xFFFFD2B0).copy(alpha = 0.55f), 1f to Color.Transparent,
                        start = androidx.compose.ui.geometry.Offset(cx - band, top), end = androidx.compose.ui.geometry.Offset(cx + band, top + h * 0.6f),
                    ),
                    topLeft = androidx.compose.ui.geometry.Offset(left, top), size = androidx.compose.ui.geometry.Size(w, h),
                    blendMode = androidx.compose.ui.graphics.BlendMode.SrcIn,
                )
                canvas.restore()
            }
        }
    }
}

// splash_scene es la imagen 21:9 del usuario ajustada a 1920×720; splash_text_mask, el recorte
// de sus letras, empieza en (654, 554) de esa imagen.
private const val SPLASH_W = 1920f
private const val SPLASH_H = 720f
private const val TEXT_X = 654f
private const val TEXT_Y = 554f

/**
 * «COCKPIT F30»: COCKPIT fino y muy espaciado en plata, F30 en cobre pulido, con un filo de luz
 * azul debajo que se apaga hacia los lados, como el triángulo del spinner.
 */
@Composable
fun BrandTitle() {
    val silver = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFF4F8FC), Cockpit.SteelBlueLight, Color(0xFF8FA6BF)))
    val copper = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Cockpit.CopperLight, Cockpit.Copper, Color(0xFF8A4A28)))
    val glow = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Cockpit.BlueLight.copy(alpha = 0.55f), androidx.compose.ui.geometry.Offset.Zero, 18f))
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                withStyle(androidx.compose.ui.text.SpanStyle(brush = silver, fontFamily = com.cockpitf30.ui.theme.Inter, fontWeight = androidx.compose.ui.text.font.FontWeight.Light, letterSpacing = 14.sp)) { append("COCKPIT") }
                append("  ")
                withStyle(androidx.compose.ui.text.SpanStyle(brush = copper, fontFamily = com.cockpitf30.ui.theme.Saira, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, letterSpacing = 4.sp)) { append("F30") }
            },
            fontSize = 44.sp,
            style = glow,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.size(width = 420.dp, height = 2.dp).background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, Cockpit.BlueLight.copy(alpha = 0.9f), Cockpit.CopperLight.copy(alpha = 0.8f), Color.Transparent)),
            ),
        )
    }
}
