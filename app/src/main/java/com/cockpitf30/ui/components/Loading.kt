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
import androidx.compose.ui.graphics.asImageBitmap
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
 * Pantalla de arranque: la imagen de Athos (triángulo de cristal y rótulo «Athos UI»).
 * Antes era un vídeo: tardaba en salir (el sonido se oía antes que la imagen) y se entrecortaba
 * mientras la app cargaba detrás. Ahora la imagen se decodifica aparte y, cuando está lista,
 * aparece desde negro a la vez que empieza el sonido del logo. Se queda [HOLD_MS], espera a la precarga (como mucho [maxMs]), funde a negro, avisa con
 * [Startup.splashDone] (barrido de agujas y saludo) y el negro se retira dejando ver la app.
 */
@Composable
fun StartupSplash(maxMs: Long = 9_000) {
    // Si la actividad se recrea, el arranque ya pasó: no se repite.
    if (remember { Startup.splashDone.value }) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val shown = remember { androidx.compose.animation.core.Animatable(0f) }
    var gone by remember { mutableIntStateOf(0) } // 1 = el negro se retira
    var image by remember { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(Unit) {
        val started = System.currentTimeMillis()
        // Imagen y sonido se preparan a la vez, fuera del hilo principal.
        val sound = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            image = runCatching {
                android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.splash_cockpit)?.asImageBitmapCompat()
            }.getOrNull()
            if (com.cockpitf30.system.AppSettings(ctx).startSound) runCatching { logoPlayer(ctx) }.getOrNull() else null
        }
        // Mismo fotograma: empieza el fundido desde negro y suena el logo.
        withFrameNanos { }
        runCatching { sound?.start() }
        shown.animateTo(1f, androidx.compose.animation.core.tween(FADE_IN_MS, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
        delay(HOLD_MS)
        // Si la app aún no ha terminado de cargar, la imagen sigue (como mucho hasta maxMs).
        while (!Startup.ready.value && System.currentTimeMillis() - started < maxMs) delay(50)
        shown.animateTo(0f, androidx.compose.animation.core.tween(FADE_OUT_MS, easing = androidx.compose.animation.core.LinearEasing))
        Startup.splashDone.value = true
        gone = 1
        delay(1_500)
        runCatching { sound?.release() }
    }
    AnimatedVisibility(gone == 0, enter = androidx.compose.animation.EnterTransition.None, exit = fadeOut(androidx.compose.animation.core.tween(400))) {
        Box(
            Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } },
            contentAlignment = Alignment.Center,
        ) {
            image?.let {
                androidx.compose.foundation.Image(
                    it, contentDescription = "Athos",
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = shown.value },
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            }
        }
    }
}

private fun android.graphics.Bitmap.asImageBitmapCompat(): androidx.compose.ui.graphics.ImageBitmap = asImageBitmap()

/** Sonido de Athos para la vista del Inicio: suena una vez y se libera solo (si está activado). */
object AthosSound {
    private var player: android.media.MediaPlayer? = null

    fun play(ctx: android.content.Context) {
        if (!com.cockpitf30.system.AppSettings(ctx).startSound) return
        stop()
        player = runCatching { logoPlayer(ctx) }.getOrNull()?.apply {
            setOnCompletionListener { it.release(); if (player === it) player = null }
            start()
        }
    }

    fun stop() { player?.let { runCatching { it.release() } }; player = null }
}

/** Sonido del logo como multimedia sin pedir el foco de audio: suena encima de la radio sin pararla. */
internal fun logoPlayer(ctx: android.content.Context): android.media.MediaPlayer {
    val afd = ctx.resources.openRawResourceFd(R.raw.logo_sound)
    return android.media.MediaPlayer().apply {
        setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        afd.close()
        prepare()
    }
}

// res/drawable-nodpi/splash_cockpit.webp: la imagen de inicio de Athos a 1920×720 (la franja con
// el triángulo y el rótulo, con los laterales prolongados y fundidos). res/raw/logo_sound.ogg: el
// «cinematic boom» del usuario recortado a 5,65 s; la subida acompaña al fundido de entrada y el
// golpe (~2,8 s) cae con la imagen ya entera; la app entra mientras se apaga.
private const val FADE_IN_MS = 900
private const val HOLD_MS = 3_500L
private const val FADE_OUT_MS = 500

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
