package com.cockpitf30.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import com.cockpitf30.R
import java.util.concurrent.ConcurrentHashMap

/**
 * Imágenes grandes decodificadas una sola vez para toda la app. painterResource vuelve a
 * decodificar el WebP cada vez que se entra en una pantalla (el fondo de triángulos ocupa
 * 1394×768), y eso se notaba como un tirón al cambiar de pantalla en la radio.
 */
object Images {
    private val cache = ConcurrentHashMap<Int, ImageBitmap>()

    /** Las de uso frecuente, para decodificarlas en segundo plano al arrancar. */
    val COMMON = listOf(R.drawable.bg_triangles, R.drawable.home_car_scene, R.drawable.car_34_closed, R.drawable.car_rear_scene)

    fun get(context: Context, @DrawableRes id: Int): ImageBitmap = cache.getOrPut(id) {
        BitmapFactory.decodeResource(context.resources, id, BitmapFactory.Options().apply { inScaled = false }).asImageBitmap()
    }

    /** Suelta imágenes que ya no se van a usar (las de la pantalla de arranque, unos 6 MB). */
    fun evict(vararg ids: Int) = ids.forEach { cache.remove(it) }

    fun preload(context: Context, ids: List<Int> = COMMON) {
        ids.forEach { runCatching { get(context.applicationContext, it) } }
    }
}

@Composable
fun cachedBitmap(@DrawableRes id: Int): ImageBitmap {
    val context = LocalContext.current
    return remember(id) { Images.get(context, id) }
}

@Composable
fun cachedPainter(@DrawableRes id: Int): Painter {
    val bmp = cachedBitmap(id)
    return remember(bmp) { BitmapPainter(bmp) }
}
