package com.cockpitf30.nav

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/** Proyección Web Mercator: coordenadas de mundo en píxeles de tesela (256 por tesela) a un zoom. */
object TileMath {
    const val TILE = 256

    fun worldX(lon: Double, z: Int) = (lon + 180.0) / 360.0 * TILE * (1 shl z)

    fun worldY(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * TILE * (1 shl z)
    }

    /** Metros que mide un píxel de tesela a esa latitud y zoom. */
    fun metersPerPixel(lat: Double, z: Int) = 156_543.034 * cos(Math.toRadians(lat)) / (1 shl z)
}

/**
 * Servidor de teselas, sin clave de API. [url] recibe z, x, y. [dark] = ya viene oscuro; si no,
 * se invierte al teñirlo. [maxZoom]: a partir de ahí se amplía la última tesela disponible.
 * (CARTO ya no sirve: sin clave devuelve teselas con "API KEY REQUIRED".)
 */
enum class TileProvider(val attribution: String, val dark: Boolean, val maxZoom: Int) {
    ESRI_DARK("© Esri, HERE, Garmin, © OpenStreetMap", dark = true, maxZoom = 16) {
        override fun url(z: Int, x: Int, y: Int) =
            "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/$z/$y/$x"
    },
    OSM("© OpenStreetMap", dark = false, maxZoom = 19) {
        override fun url(z: Int, x: Int, y: Int) = "https://tile.openstreetmap.org/$z/$x/$y.png"
    };

    abstract fun url(z: Int, x: Int, y: Int): String
}

/**
 * Teselas del mapa oscuro (Esri Dark Gray Canvas; si falla, OpenStreetMap oscurecido). Se guardan en
 * disco al descargarlas, así las calles por las que ya se ha pasado se ven sin conexión.
 * [version] cambia cada vez que llega una tesela, para que el mapa se vuelva a dibujar.
 */
object MapTiles {
    private const val MAX_DISK_TILES = 6000

    val version = MutableStateFlow(0)

    /** Servidor en uso: si Esri falla varias veces seguidas se pasa a OpenStreetMap. */
    val provider = MutableStateFlow(TileProvider.ESRI_DARK)

    /** Último error de descarga (p. ej. "HTTP 403"), para mostrarlo en el mapa; null si va bien. */
    val lastError = MutableStateFlow<String?>(null)
    @Volatile private var failuresInRow = 0

    private val memory = LruCache<String, ImageBitmap>(96)
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val network = Semaphore(4)
    @Volatile private var dir: File? = null

    fun init(context: Context) {
        if (dir != null) return
        dir = File(context.cacheDir, "tiles").apply {
            mkdirs()
            // Teselas de la 0.9.4 de CARTO (llevaban la marca "API KEY REQUIRED"): fuera.
            listFiles()?.filter { f -> TileProvider.entries.none { f.name.startsWith(it.name.lowercase() + "-") } }?.forEach { it.delete() }
        }
        scope.launch { trimDisk() }
    }

    /** La tesela si ya está en memoria; si no, la pide (disco o red) y devuelve null de momento. */
    fun get(z: Int, x: Int, y: Int): ImageBitmap? {
        val n = 1 shl z
        if (y !in 0 until n) return null
        val wx = ((x % n) + n) % n
        val key = "${provider.value.name}/$z/$wx/$y"
        memory.get(key)?.let { return it }
        val failed = failedAt[key]
        if (failed != null && System.currentTimeMillis() - failed < 30_000) return null
        if (pending.add(key)) scope.launch { load(key, provider.value, z, wx, y) }
        return null
    }

    /** Solo lo que ya está en memoria, sin pedir nada (para rellenar con teselas de otro zoom). */
    fun peek(z: Int, x: Int, y: Int): ImageBitmap? {
        val n = 1 shl z
        if (y !in 0 until n) return null
        return memory.get("${provider.value.name}/$z/${((x % n) + n) % n}/$y")
    }

    private suspend fun load(key: String, p: TileProvider, z: Int, x: Int, y: Int) {
        try {
            val file = dir?.let { File(it, "${p.name.lowercase()}-$z-$x-$y.png") }
            var bytes = file?.takeIf { it.exists() }?.readBytes()
            if (bytes == null) {
                bytes = network.withPermit { download(p, z, x, y) }
                if (bytes != null && file != null) runCatching { file.writeBytes(bytes) }
            } else {
                file?.setLastModified(System.currentTimeMillis())
            }
            val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bmp == null) {
                failedAt[key] = System.currentTimeMillis()
            } else {
                memory.put(key, bmp.asImageBitmap())
                failedAt.remove(key)
                version.value++
            }
        } catch (_: Throwable) {
            failedAt[key] = System.currentTimeMillis()
        } finally {
            pending.remove(key)
        }
    }

    private fun download(p: TileProvider, z: Int, x: Int, y: Int): ByteArray? {
        return try {
            val conn = URL(p.url(z, x, y)).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 6_000
                conn.readTimeout = 8_000
                conn.setRequestProperty("User-Agent", "CockpitF30/0.9 (Android head unit; personal use)")
                val code = conn.responseCode
                if (code == 200) {
                    failuresInRow = 0
                    lastError.value = null
                    conn.inputStream.use { it.readBytes() }
                } else {
                    fail(p, "HTTP $code")
                    null
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: java.io.IOException) {
            // Sin conexión no cambiamos de servidor: el problema no es el servidor.
            lastError.value = "Sin conexión"
            null
        }
    }

    /** Esri responde con error varias veces seguidas: pasamos a OpenStreetMap. */
    private fun fail(p: TileProvider, error: String) {
        lastError.value = "${p.name}: $error"
        if (p == TileProvider.ESRI_DARK && ++failuresInRow >= 4) {
            provider.value = TileProvider.OSM
            failedAt.clear()
            version.value++
        }
    }

    /** Mantiene la caché de disco acotada (unos 6000 teselas, ~150 MB como mucho). */
    private fun trimDisk() {
        val files = dir?.listFiles() ?: return
        if (files.size <= MAX_DISK_TILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_DISK_TILES).forEach { it.delete() }
    }
}
