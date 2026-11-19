package com.cockpitf30.ui.components

import android.view.TextureView
import androidx.annotation.OptIn
import androidx.annotation.RawRes
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.roundToInt

/**
 * Vídeo en bucle (res/raw), sin sonido, pintado en un TextureView para que se pueda recortar,
 * fundir y deslizar con el resto de Compose (un SurfaceView no se recorta ni se funde).
 *
 * El bucle no salta: los vídeos se han preparado para que el último fotograma enlace con el
 * primero (fundido de las últimas 12 imágenes sobre las primeras, sin fotogramas repetidos) y
 * van en H.264 Main sin fotogramas B, con una imagen clave cada segundo: el decodificador de la
 * MediaTek los lleva sin esfuerzo. ExoPlayer con REPEAT_MODE_ONE ya tiene el principio cargado
 * cuando llega al final, así que vuelve al fotograma 0 sin hueco.
 *
 * El reproductor se crea y se prepara en cuanto el vídeo entra en la pantalla, aunque no se vea
 * ([playing] = false): así el primer fotograma ya está pintado cuando aparece, sin negro. Parado
 * no decodifica nada.
 *
 * Escala como ContentScale.Crop ([aspect] = ancho/alto del vídeo). [focus] es el punto del vídeo
 * (en fracciones) que se coloca en [anchor] (fracciones del hueco), sin dejar bordes vacíos.
 */
@OptIn(UnstableApi::class)
@Composable
fun LoopVideo(
    @RawRes res: Int,
    aspect: Float,
    playing: Boolean,
    modifier: Modifier = Modifier,
    focus: Offset = Offset(0.5f, 0.5f),
    anchor: Offset = Offset(0.5f, 0.5f),
    onFirstFrame: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val firstFrame = rememberUpdatedState(onFirstFrame)
    val player = remember(res) {
        ExoPlayer.Builder(ctx.applicationContext)
            // Vídeo local de 1–2 MB: basta con poco búfer (menos memoria y arranca antes).
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1_500, 5_000, 250, 250).build())
            .build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 0f
                playWhenReady = false
                setMediaItem(MediaItem.fromUri(RawResourceDataSource.buildRawResourceUri(res)))
                addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { firstFrame.value() }
                })
                prepare()
            }
    }
    DisposableEffect(player) { onDispose { player.clearVideoSurface(); player.release() } }
    LaunchedEffect(player, playing) { player.playWhenReady = playing }

    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.TopStart) {
        val density = LocalDensity.current
        val cw = constraints.maxWidth.toFloat()
        val ch = constraints.maxHeight.toFloat()
        val (vw, vh) = if (cw / ch > aspect) cw to cw / aspect else ch * aspect to ch
        val x = (anchor.x * cw - focus.x * vw).coerceIn(cw - vw, 0f)
        val y = (anchor.y * ch - focus.y * vh).coerceIn(ch - vh, 0f)
        // Más grande que el hueco: se coloca desde la esquina (unbounded) y el sobrante se recorta.
        AndroidView(
            factory = { c -> TextureView(c).also { player.setVideoTextureView(it) } },
            modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .requiredSize(with(density) { vw.toDp() }, with(density) { vh.toDp() }),
        )
    }
}
