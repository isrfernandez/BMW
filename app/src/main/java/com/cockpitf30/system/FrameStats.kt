package com.cockpitf30.system

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window

/**
 * Fluidez medida en la propia radio (Diagnóstico › Fluidez): cuántos fotogramas tardan más de lo
 * que permite la pantalla, en el último minuto y desde que arrancó la app, y la memoria usada.
 * Para saber si los tirones del vídeo vienen de la app (fotogramas lentos, memoria que crece) o del
 * vídeo. Usa los FrameMetrics de Android: solo cuenta los fotogramas que se dibujan, en su propio
 * hilo, sin coste apreciable.
 */
object FrameStats {
    @Volatile var frames = 0L; private set
    @Volatile var slow = 0L; private set   // > 34 ms (se pierde al menos un fotograma a 30 fps)
    @Volatile var frozen = 0L; private set // > 100 ms (tirón claro)
    @Volatile var worstMs = 0L; private set
    private val minute = ArrayDeque<Pair<Long, Long>>() // (hora, duración ms) de los lentos
    @Volatile var installed = false; private set

    fun install(activity: Activity) {
        if (installed) return
        installed = true
        val thread = HandlerThread("fluidez").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, m, _ ->
            val ms = m.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000
            frames++
            if (ms > 34) {
                slow++
                if (ms > 100) frozen++
                if (ms > worstMs) worstMs = ms
                val now = System.currentTimeMillis()
                synchronized(minute) {
                    minute.addLast(now to ms)
                    while (minute.isNotEmpty() && now - minute.first().first > 60_000) minute.removeFirst()
                }
            }
        }
        runCatching { activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
    }

    fun summary(): List<String> {
        val now = System.currentTimeMillis()
        val last = synchronized(minute) { minute.filter { now - it.first <= 60_000 } }
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val maxMb = rt.maxMemory() / (1024 * 1024)
        val pct = if (frames > 0) slow * 100.0 / frames else 0.0
        return listOf(
            "Último minuto: ${last.size} fotogramas lentos" + (last.maxOfOrNull { it.second }?.let { " (peor $it ms)" } ?: ""),
            "Desde el arranque: $frames fotogramas · lentos $slow (${"%.1f".format(pct)} %) · tirones >100 ms $frozen · peor $worstMs ms",
            "Memoria de la app: $usedMb MB de $maxMb MB",
        )
    }
}
