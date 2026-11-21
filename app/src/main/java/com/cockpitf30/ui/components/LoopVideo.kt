package com.cockpitf30.ui.components

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.annotation.RawRes
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
 * Reproductores de los vídeos en bucle, uno por vídeo (res/raw) y compartido por todas las pantallas
 * que lo usan: la presentación tiene el suyo (athos_intro, 1920×720) y la vista Athoox del cuadro
 * el suyo (athoox_core, solo la franja del cristal y a menor tamaño).
 *
 * Desde 0.9.115 hasta 0.9.122 la vista Athoox reutilizaba el de la presentación: decodificaba el
 * vídeo entero de 1920×720 para enseñar solo un recorte, y en la radio, con el rato, se notaba
 * más lento y con saltos. Ahora su vídeo tiene 3,4 veces menos píxeles por fotograma. El cambio de
 * reproductor ocurre con la pantalla en negro (velo de la presentación), así que no se ve.
 *
 * Cada pantalla que lo usa es un «dueño»: la superficie es la del último que la pide
 * ([LoopVideo] con attached = true) y se reproduce mientras alguno quiera. Sin dueños durante
 * [IDLE_RELEASE_MS] se libera (no queda un decodificador ocupado fuera de donde se ve).
 */
@OptIn(UnstableApi::class)
internal object SharedLoop {
    private class Entry(val player: ExoPlayer) {
        val wants = HashMap<Any, Boolean>()
        val owners = HashSet<Any>()
        val releaseIdle = Runnable { if (owners.isEmpty()) release() }
        fun release() { player.release(); entries.values.remove(this) }
    }
    private val entries = HashMap<Int, Entry>()
    private val main = Handler(Looper.getMainLooper())

    private fun entry(ctx: Context, @RawRes id: Int): Entry = entries.getOrPut(id) {
        Entry(
            ExoPlayer.Builder(ctx.applicationContext)
                // Vídeo local de 1–2 MB: basta con poco búfer (menos memoria y arranca antes).
                .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1_500, 5_000, 250, 250).build())
                .build().apply {
                    repeatMode = Player.REPEAT_MODE_ONE
                    volume = 0f
                    playWhenReady = false
                    setMediaItem(MediaItem.fromUri(RawResourceDataSource.buildRawResourceUri(id)))
                    prepare()
                },
        )
    }

    fun join(ctx: Context, @RawRes id: Int, owner: Any): ExoPlayer {
        val e = entry(ctx, id)
        e.owners += owner
        main.removeCallbacks(e.releaseIdle)
        return e.player
    }

    private fun of(player: ExoPlayer) = entries.values.firstOrNull { it.player === player }

    fun leave(player: ExoPlayer, owner: Any, view: android.view.View?) {
        val e = of(player) ?: return
        e.owners -= owner; e.wants -= owner
        when (view) {
            is TextureView -> player.clearVideoTextureView(view)
            is android.view.SurfaceView -> player.clearVideoSurfaceView(view)
        }
        player.playWhenReady = e.wants.values.any { it }
        if (e.owners.isEmpty()) main.postDelayed(e.releaseIdle, IDLE_RELEASE_MS)
    }

    fun attach(player: ExoPlayer, view: android.view.View) {
        if (of(player) == null) return
        when (view) {
            is TextureView -> player.setVideoTextureView(view)
            is android.view.SurfaceView -> player.setVideoSurfaceView(view)
        }
    }

    fun play(player: ExoPlayer, owner: Any, want: Boolean) {
        val e = of(player) ?: return
        e.wants[owner] = want
        player.playWhenReady = e.wants.values.any { it }
    }

    private const val IDLE_RELEASE_MS = 20_000L
}

/**
 * Vídeo en bucle (res/raw), sin sonido, pintado en un TextureView para que se pueda recortar,
 * fundir y deslizar con el resto de Compose (un SurfaceView no se recorta ni se funde). Usa el
 * reproductor compartido [SharedLoop].
 *
 * El bucle no salta: el vídeo se ha preparado para que el último fotograma enlace con el primero
 * (fundido de las últimas 12 imágenes sobre las primeras, sin fotogramas repetidos) y va en H.264
 * Main sin fotogramas B, con una imagen clave cada segundo. ExoPlayer con REPEAT_MODE_ONE ya tiene
 * el principio cargado cuando llega al final, así que vuelve al fotograma 0 sin hueco.
 *
 * [surface] = true: en un SurfaceView (solo pantalla completa, sin recortes ni fundidos sobre él):
 * sus fotogramas van directos a la pantalla y no esperan al hilo principal, así que no dan
 * tirones aunque la app esté cargando detrás (la presentación).
 *
 * [attached]: esta pantalla quiere la superficie (la última que la pide se la queda).
 * [playing]: quiere que corra. Parado no decodifica nada y conserva el fotograma.
 *
 * Escala como ContentScale.Crop, pero cubriendo el hueco con la parte [region] del vídeo
 * (fracciones; lo de fuera, como el rótulo, puede quedar recortado). [focus] es el punto del
 * vídeo que se coloca en [anchor] (fracciones del hueco), sin salirse de [region].
 */
@Composable
fun LoopVideo(
    @RawRes res: Int,
    aspect: Float,
    playing: Boolean,
    modifier: Modifier = Modifier,
    attached: Boolean = true,
    region: Rect = Rect(0f, 0f, 1f, 1f),
    focus: Offset = Offset(0.5f, 0.5f),
    anchor: Offset = Offset(0.5f, 0.5f),
    onFirstFrame: () -> Unit = {},
    onView: (TextureView) -> Unit = {},
    surface: Boolean = false,
) {
    val ctx = LocalContext.current
    val owner = remember { Any() }
    val firstFrame = rememberUpdatedState(onFirstFrame)
    val isAttached = rememberUpdatedState(attached)
    val holder = remember { arrayOfNulls<android.view.View>(1) }
    val player = remember(res) { SharedLoop.join(ctx, res, owner) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { if (isAttached.value) firstFrame.value() }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); SharedLoop.leave(player, owner, holder[0]) }
    }
    LaunchedEffect(player, attached) { holder[0]?.let { if (attached) SharedLoop.attach(player, it) } }
    LaunchedEffect(player, playing) { SharedLoop.play(player, owner, playing) }

    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.TopStart) {
        val density = LocalDensity.current
        val cw = constraints.maxWidth.toFloat()
        val ch = constraints.maxHeight.toFloat()
        val ra = aspect * region.width / region.height
        val vw = if (cw / ch > ra) cw / region.width else ch / region.height * aspect
        val vh = vw / aspect
        // Sin salirse de la franja (el rango puede quedar vacío por redondeo: entonces, su borde).
        fun clamp(v: Float, lo: Float, hi: Float) = if (lo <= hi) v.coerceIn(lo, hi) else hi
        val x = clamp(anchor.x * cw - focus.x * vw, cw - region.right * vw, -region.left * vw)
        val y = clamp(anchor.y * ch - focus.y * vh, ch - region.bottom * vh, -region.top * vh)
        // Más grande que el hueco: se coloca desde la esquina (unbounded) y el sobrante se recorta.
        AndroidView(
            factory = { c ->
                val v: android.view.View = if (surface) android.view.SurfaceView(c) else TextureView(c).also(onView)
                v.also { holder[0] = it; if (attached) SharedLoop.attach(player, it) }
            },
            modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .requiredSize(with(density) { vw.toDp() }, with(density) { vh.toDp() }),
        )
    }
}
