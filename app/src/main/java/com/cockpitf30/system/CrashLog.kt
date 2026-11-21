package com.cockpitf30.system

import android.content.Context
import java.io.File

/**
 * Cierres inesperados: si la app se cae, guarda la hora, la versión y el error (las primeras líneas
 * de la traza) en un fichero antes de dejar que Android la cierre. Diagnóstico enseña el último.
 * No hace nada mientras la app funciona.
 */
object CrashLog {
    private const val FILE = "ultimo_cierre.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
                val time = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale("es", "ES")).format(java.util.Date())
                val trace = e.stackTraceToString().lineSequence().take(25).joinToString("\n")
                File(app.filesDir, FILE).writeText("$time · v$version · hilo ${thread.name}\n$trace")
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** El último cierre guardado, o null si nunca se ha caído. */
    fun last(context: Context): String? = runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(context: Context) { runCatching { File(context.filesDir, FILE).delete() } }
}
