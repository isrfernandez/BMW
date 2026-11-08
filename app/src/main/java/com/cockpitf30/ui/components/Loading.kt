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
private const val CELL = 160
private const val FRAME_MS = 30L

/** El triángulo de cobre y luz azul del usuario (60 fotogramas de su GIF, fondo quitado). */
@Composable
fun CockpitSpinner(modifier: Modifier = Modifier.size(140.dp)) {
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
 * Pantalla de arranque: el spinner sobre fondo opaco hasta que acaba la precarga (y al menos
 * [minMs], para que no sea un destello). Luego se funde y deja ver la app.
 */
@Composable
fun StartupSplash(minMs: Long = 1200) {
    val ready by Startup.ready.collectAsState()
    var minDone by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { delay(minMs); minDone = 1 }
    AnimatedVisibility(!(ready && minDone == 1), enter = fadeIn(), exit = fadeOut(androidx.compose.animation.core.tween(600))) {
        Box(
            Modifier.fillMaxSize().background(Cockpit.Background).pointerInput(Unit) { detectTapGestures { } },
            contentAlignment = Alignment.Center,
        ) {
            CockpitSpinner(Modifier.size(200.dp))
        }
    }
}
