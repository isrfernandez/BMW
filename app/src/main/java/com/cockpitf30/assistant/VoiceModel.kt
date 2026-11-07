package com.cockpitf30.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Modelo de voz en español de Vosk (reconocimiento sin conexión, ~39 MB). No va dentro del APK:
 * se descarga una vez al primer arranque y se guarda en la memoria interna de la app.
 *
 * Sin Internet en la radio se puede copiar el zip a mano (adb push) en
 * `Android/data/com.cockpitf30/files/vosk-model-small-es-0.42.zip`: se usa ese en vez de descargarlo.
 */
object VoiceModel {
    const val NAME = "vosk-model-small-es-0.42"
    private const val URL_ZIP = "https://alphacephei.com/vosk/models/$NAME.zip"
    const val SIZE_MB = 39

    fun dir(context: Context) = File(context.filesDir, NAME)
    fun isReady(context: Context) = File(dir(context), ".ready").exists()
    fun sideloadFile(context: Context): File? = context.getExternalFilesDir(null)?.let { File(it, "$NAME.zip") }

    /** Descarga (o toma el zip copiado a mano) y descomprime. [progress] va de 0 a 1. */
    suspend fun install(context: Context, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val target = dir(context)
        val tmp = File(context.filesDir, "$NAME.tmp")
        tmp.deleteRecursively()
        val sideload = sideloadFile(context)?.takeIf { it.isFile && it.length() > 1_000_000 }
        val zip = sideload ?: File(context.cacheDir, "$NAME.zip").also { download(it, progress) }
        progress(1f)
        unzip(zip, tmp)
        // El zip trae una carpeta con el nombre del modelo; la quitamos si está.
        val root = tmp.listFiles()?.singleOrNull { it.isDirectory && File(it, "conf").exists() } ?: tmp
        target.deleteRecursively()
        if (!root.renameTo(target)) error("No se pudo guardar el modelo")
        tmp.deleteRecursively()
        File(target, ".ready").writeText(NAME)
        if (sideload == null) zip.delete()
    }

    private suspend fun download(out: File, progress: (Float) -> Unit) {
        var url = URL(URL_ZIP)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
            }
            val code = conn.responseCode
            if (code in 300..399 && redirects++ < 5) {
                url = URL(url, conn.getHeaderField("Location"))
                conn.disconnect()
                continue
            }
            if (code != 200) error("HTTP $code")
            break
        }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: (SIZE_MB * 1_000_000L)
        conn.inputStream.use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    if (done - lastReport > 256 * 1024) {
                        lastReport = done
                        progress((done.toFloat() / total).coerceIn(0f, 0.99f))
                    }
                }
            }
        }
        conn.disconnect()
    }

    private fun unzip(zip: File, into: File) {
        into.mkdirs()
        val base = into.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val f = File(into, e.name)
                // Evita rutas que salgan de la carpeta ("../").
                if (!f.canonicalPath.startsWith(base)) continue
                if (e.isDirectory) {
                    f.mkdirs()
                } else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { zin.copyTo(it) }
                }
            }
        }
    }
}
