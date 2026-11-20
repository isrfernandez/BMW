package com.cockpitf30.ui.components

import androidx.compose.animation.AnimatedVisibility
import kotlinx.coroutines.flow.first
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
    /** El vídeo ya se está pintando en el panel del cuadro (para retirar el negro de la presentación). */
    val clusterVideo = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** «Ver la intro» (Ajustes): cada vez que sube se vuelve a poner la presentación. */
    val replay = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** Se está viendo otra vez la presentación: el cuadro le cede el vídeo. */
    val replaying = kotlinx.coroutines.flow.MutableStateFlow(false)
}

/**
 * Pantalla de arranque: el vídeo de Athoox en bucle (cristal vivo, órbitas y rótulo).
 *
 * El vídeo va en un SurfaceView: sus fotogramas llegan a la pantalla sin pasar por el hilo
 * principal, que en el arranque está muy ocupado (precarga de imágenes, Inicio, servicios); con un
 * TextureView daba tirones. Los fundidos son un velo negro encima ([veil]).
 *
 * Orden: el reproductor se prepara en negro; con el primer fotograma (o como mucho
 * [FIRST_FRAME_MAX_MS]) arranca, suena el logo y el velo se retira. Se queda [HOLD_MS], espera a la
 * precarga (como mucho [maxMs]) dando vueltas al bucle, el velo vuelve a negro y se avisa con
 * [Startup.splashDone]: el mismo reproductor pasa al panel del cuadro (vista Athoox) y, cuando ya
 * ha pintado allí ([Startup.clusterVideo], como mucho [HANDOVER_MAX_MS]), el negro se funde
 * despacio ([REVEAL_MS]) dejando ver el Inicio.
 */
@Composable
fun StartupSplash(maxMs: Long = 9_000, replay: Boolean = false) {
    // Si la actividad se recrea, el arranque ya pasó: no se repite (salvo «Ver la intro»).
    if (!replay && remember { Startup.splashDone.value }) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val veil = remember { androidx.compose.animation.core.Animatable(1f) }
    var gone by remember { mutableIntStateOf(0) } // 1 = terminado
    var handedOver by remember { androidx.compose.runtime.mutableStateOf(false) }
    var playing by remember { androidx.compose.runtime.mutableStateOf(false) }
    val firstFrame = remember { kotlinx.coroutines.CompletableDeferred<Unit>() }
    LaunchedEffect(Unit) {
        val started = System.currentTimeMillis()
        if (replay) { Startup.replaying.value = true; Startup.clusterVideo.value = false }
        // El sonido se prepara fuera del hilo principal y suena en cuanto está listo, sin esperar
        // al vídeo (en 0.9.114–0.9.118 esperaba a su primer fotograma y en la radio dejó de oírse).
        val enabled = com.cockpitf30.system.AppSettings(ctx).startSound
        val sound = if (!enabled) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { logoPlayer(ctx) }.onFailure { com.cockpitf30.assistant.VoiceAssistant.note("sonido de inicio: no se pudo preparar (${it.javaClass.simpleName}: ${it.message})") }.getOrNull()
        }
        sound?.setOnErrorListener { _, what, extra -> com.cockpitf30.assistant.VoiceAssistant.note("sonido de inicio: error $what/$extra"); true }
        runCatching { sound?.start() }.onFailure { com.cockpitf30.assistant.VoiceAssistant.note("sonido de inicio: no arranca (${it.message})") }
        com.cockpitf30.assistant.VoiceAssistant.note(
            if (!enabled) "sonido de inicio: desactivado en Ajustes"
            else if (sound?.isPlaying == true) "sonido de inicio: sonando (volumen multimedia ${mediaVolume(ctx)})" else "sonido de inicio: no suena",
        )
        kotlinx.coroutines.withTimeoutOrNull(FIRST_FRAME_MAX_MS) { firstFrame.await() }
        // Arranca el vídeo y se retira el velo (el sonido ya va: su subida acompaña el fundido).
        playing = true
        withFrameNanos { }
        veil.animateTo(0f, androidx.compose.animation.core.tween(FADE_IN_MS, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
        delay(HOLD_MS)
        // Si la app aún no ha terminado de cargar, el vídeo sigue en bucle (como mucho hasta maxMs).
        while (!Startup.ready.value && System.currentTimeMillis() - started < maxMs) delay(50)
        veil.animateTo(1f, androidx.compose.animation.core.tween(FADE_OUT_MS, easing = androidx.compose.animation.core.LinearEasing))
        // En negro: el vídeo pasa al cuadro y, cuando ya se ve allí, el negro se funde al Inicio.
        handedOver = true
        if (replay) {
            // «Ver la intro»: vuelve al Inicio con la vista Athoox, como al arrancar.
            DemoTour.navigator?.invoke(com.cockpitf30.ui.screens.Screen.HOME)
            ClusterIntro.replay.value++
            Startup.replaying.value = false
        } else Startup.splashDone.value = true
        kotlinx.coroutines.withTimeoutOrNull(HANDOVER_MAX_MS) { Startup.clusterVideo.first { v -> v } }
        veil.animateTo(0f, androidx.compose.animation.core.tween(REVEAL_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        gone = 1
        delay(4_000)
        runCatching { sound?.release() }
    }
    if (gone == 1) return
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }) {
        if (!handedOver) {
            LoopVideo(
                com.cockpitf30.R.raw.athos_intro, aspect = 1920f / 720f, playing = playing, surface = true,
                modifier = Modifier.fillMaxSize(),
                onFirstFrame = { firstFrame.complete(Unit) },
            )
        }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = veil.value }.background(Color.Black))
    }
}

private fun mediaVolume(ctx: android.content.Context): String = runCatching {
    val am = ctx.getSystemService(android.media.AudioManager::class.java)
    "${am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)}"
}.getOrDefault("?")

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

// res/raw/athos_intro.mp4: la segunda versión del vídeo del usuario (1920×720 a 60 fps), pasada a 30 fps (el
// decodificador de la radio no llega a 60 a esta resolución), 4,6 s en bucle sin
// salto (H.264 Main sin fotogramas B, sin audio, 1,7 MB). res/raw/logo_sound.ogg: el
// «cinematic boom» del usuario recortado a 5,65 s; la subida acompaña al fundido de entrada y el
// golpe (~2,8 s) cae con la imagen ya entera; la app entra mientras se apaga.
private const val FADE_IN_MS = 900
private const val HOLD_MS = 3_500L
private const val FADE_OUT_MS = 500
private const val FIRST_FRAME_MAX_MS = 2_000L
private const val HANDOVER_MAX_MS = 800L
private const val REVEAL_MS = 1_100

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
