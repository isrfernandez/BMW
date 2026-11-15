package com.cockpitf30.assistant

import android.media.AudioTrack
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cuánto «suena» la voz del asistente ahora mismo (0..1), para animar el triángulo de la vista
 * Athos. Con la voz TARS se conoce el audio: se guarda su energía cada 20 ms y se lee en la
 * posición que está sonando. Con la voz normal el motor no da el audio, pero sí avisa al empezar
 * cada palabra: un golpe por palabra que decae, con un temblor de sílabas encima.
 */
object SpeechPulse {
    private const val WINDOW_MS = 20

    @Volatile private var track: AudioTrack? = null
    @Volatile private var rate = 22050
    private val env = ArrayList<Float>()
    @Volatile var wordAt = 0L
        private set
    @Volatile private var speaking = false

    /** Empieza (o acaba) una frase. */
    fun speaking(on: Boolean) {
        speaking = on
        if (!on) synchronized(env) { env.clear(); track = null }
    }

    /** Una palabra nueva con la voz normal. */
    fun word() { wordAt = System.currentTimeMillis() }

    /** Nueva pista de la voz TARS: la energía se cuenta desde su primera muestra. */
    fun robotTrack(t: AudioTrack?, sampleRate: Int) = synchronized(env) { env.clear(); track = t; rate = sampleRate }

    /** Muestras que se acaban de escribir en la pista TARS (en orden). */
    fun robotSamples(s: ShortArray, n: Int = s.size) {
        val win = (rate * WINDOW_MS / 1000).coerceAtLeast(1)
        val out = ArrayList<Float>(n / win + 1)
        var i = 0
        while (i < n) {
            val end = minOf(n, i + win)
            var sum = 0.0
            for (k in i until end) { val v = s[k] / 32768.0; sum += v * v }
            out += (sqrt(sum / (end - i)) * 4.0).toFloat().coerceIn(0f, 1f)
            i = end
        }
        synchronized(env) { env.addAll(out) }
    }

    /** Nivel actual, 0..1. [now] en ms. */
    fun level(now: Long): Float {
        if (!speaking) return 0f
        val t = track
        if (t != null) {
            val head = runCatching { t.playbackHeadPosition }.getOrDefault(0)
            val idx = head / (rate * WINDOW_MS / 1000).coerceAtLeast(1)
            synchronized(env) { if (idx in env.indices) return env[idx] }
        }
        val dt = (now - wordAt).coerceAtLeast(0)
        // Aún no ha empezado a sonar (o el motor no avisa por palabras): un latido suave.
        if (dt > 1_200) return 0.06f + 0.04f * sin(now / 300.0).toFloat()
        val hit = exp(-dt / 220.0).toFloat()
        val syll = 0.5f + 0.5f * sin(now / 55.0).toFloat() * sin(now / 137.0).toFloat()
        return (0.18f + 0.55f * hit + 0.27f * syll * (0.4f + hit)).coerceIn(0f, 1f)
    }
}
