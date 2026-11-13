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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
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
    /** La pantalla de arranque ya se ha ido (empieza la bienvenida del cuadro y el saludo). */
    val splashDone = kotlinx.coroutines.flow.MutableStateFlow(false)
}

/**
 * Pantalla de arranque: el vídeo del usuario (triángulo de cristal y rótulo «CockPit F30»).
 * Mientras se ve, la app se carga detrás (precarga de MainActivity). Se ve entero, centrado a
 * toda la altura, con los laterales fundidos a negro porque es casi 16:9 y la pantalla 21:8.
 * Al acabar funde a negro; si la precarga aún no ha terminado espera en negro (como mucho hasta
 * [maxMs] desde el arranque). Entonces avisa con [Startup.splashDone] (barrido de agujas y
 * después el saludo) y el negro se retira dejando ver la app.
 */
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun StartupSplash(maxMs: Long = 12_000) {
    // Si la actividad se recrea, el arranque ya pasó: no se repite.
    if (remember { Startup.splashDone.value }) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val black = remember { androidx.compose.animation.core.Animatable(0f) }
    var gone by remember { mutableIntStateOf(0) } // 1 = el negro se retira
    val player = remember {
        androidx.media3.exoplayer.ExoPlayer.Builder(ctx).build().apply {
            volume = 0f
            setMediaItem(androidx.media3.common.MediaItem.fromUri(androidx.media3.datasource.RawResourceDataSource.buildRawResourceUri(R.raw.intro)))
            prepare()
            playWhenReady = true
        }
    }
    androidx.compose.runtime.DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player) {
        val started = System.currentTimeMillis()
        // Fin del vídeo: el fundido a negro termina justo cuando acaba (o al fallar el vídeo).
        while (true) {
            val st = player.playbackState
            if (player.playerError != null || st == androidx.media3.common.Player.STATE_ENDED) break
            val dur = player.duration
            if (st == androidx.media3.common.Player.STATE_READY && dur > 0 && dur - player.currentPosition <= FADE_MS) break
            if (System.currentTimeMillis() - started > maxMs) break
            delay(40)
        }
        black.animateTo(1f, androidx.compose.animation.core.tween(FADE_MS.toInt(), easing = androidx.compose.animation.core.LinearEasing))
        // En negro hasta que la app esté lista (imágenes y lista de apps).
        while (!Startup.ready.value && System.currentTimeMillis() - started < maxMs) delay(50)
        player.stop()
        Startup.splashDone.value = true
        gone = 1
    }
    // Proporción real del vídeo: se lee del propio fichero, así basta con cambiar res/raw/intro.mp4.
    var aspect by remember { androidx.compose.runtime.mutableFloatStateOf(16f / 9f) }
    androidx.compose.runtime.DisposableEffect(player) {
        val l = object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(v: androidx.media3.common.VideoSize) {
                if (v.width > 0 && v.height > 0) aspect = v.width * v.pixelWidthHeightRatio / v.height
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    AnimatedVisibility(gone == 0, enter = androidx.compose.animation.EnterTransition.None, exit = fadeOut(androidx.compose.animation.core.tween(500))) {
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } },
            contentAlignment = Alignment.Center,
        ) {
            // Entero y centrado: a toda la altura si es más estrecho que la pantalla (laterales
            // fundidos a negro), a todo el ancho si es más panorámico.
            val narrower = aspect < maxWidth / maxHeight
            Box(if (narrower) Modifier.fillMaxHeight().aspectRatio(aspect) else Modifier.fillMaxWidth().aspectRatio(aspect)) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { c -> android.view.TextureView(c).also { player.setVideoTextureView(it) } },
                    modifier = Modifier.fillMaxSize(),
                )
                if (narrower) {
                    val edge = androidx.compose.ui.graphics.Brush.horizontalGradient(
                        0f to Color.Black, 0.07f to Color.Black.copy(alpha = 0.55f), 0.14f to Color.Transparent,
                        0.86f to Color.Transparent, 0.93f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black,
                    )
                    Box(Modifier.fillMaxSize().background(edge))
                }
            }
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = black.value }.background(Color.Black))
        }
    }
}

// res/raw/intro.mp4: el vídeo del usuario recomprimido (H.264, sin audio). Para cambiarlo basta
// con sustituir el fichero: duración y proporción se leen al reproducirlo.
private const val FADE_MS = 700L

/**
 * «COCKPIT F30»: COCKPIT fino y muy espaciado en plata, F30 en cobre pulido, con un filo de luz
 * azul debajo que se apaga hacia los lados, como el triángulo del spinner.
 */
@Composable
fun BrandTitle() {
    // Arranque sobre negro: colores de la paleta oscura también con el modo diurno.
    val pal = com.cockpitf30.ui.theme.DriveModes.darkPalette
    val silver = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFF4F8FC), pal.steelLight, Color(0xFF8FA6BF)))
    val copper = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(pal.copperLight, pal.copper, Color(0xFF8A4A28)))
    val glow = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(pal.blueLight.copy(alpha = 0.55f), androidx.compose.ui.geometry.Offset.Zero, 18f))
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
                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, pal.blueLight.copy(alpha = 0.9f), pal.copperLight.copy(alpha = 0.8f), Color.Transparent)),
            ),
        )
    }
}
