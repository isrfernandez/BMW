package com.cockpitf30.assistant

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/** Audio PCM de 16 bits mono. */
class Pcm(val samples: ShortArray, val rate: Int) {
    val durationMs: Long get() = samples.size * 1000L / rate
}

/**
 * Voz «robot de misión» al estilo TARS: el motor de voz de la radio ya habla más grave y lento
 * (tono y velocidad), y aquí se le da el timbre metálico de altavoz de máquina: resonancia corta de
 * carcasa (dos filtros peine), un zumbido de modulación en anillo muy suave, banda de altavoz
 * (sin graves de bombo ni agudos de siseo), un poco de saturación y volumen normalizado.
 *
 * Todo es Kotlin puro y sin conexión: se procesa el WAV que genera el TextToSpeech de la radio.
 */
object RobotVoice {

    /** Tono y velocidad que se piden al TextToSpeech (1.0 = normal). */
    const val TTS_PITCH = 0.72f
    const val TTS_RATE = 0.9f

    /** Lee un WAV PCM de 16 bits (mono o estéreo, que se mezcla a mono). null si no es ese formato. */
    fun readWav(bytes: ByteArray): Pcm? {
        if (bytes.size < 44) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var pos = 12
        var channels = 0
        var rate = 0
        var bits = 0
        var format = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            // Algunos motores escriben 0 o un tamaño enorme en «data» porque la escriben a trozos.
            var size = b.getInt(pos + 4)
            val body = pos + 8
            if (size < 0 || body + size > bytes.size || (size == 0 && id == "data")) size = bytes.size - body
            when (id) {
                "fmt " -> {
                    format = b.getShort(body).toInt() and 0xFFFF
                    channels = b.getShort(body + 2).toInt()
                    rate = b.getInt(body + 4)
                    bits = b.getShort(body + 14).toInt()
                }
                "data" -> {
                    if (format != 1 || bits != 16 || channels !in 1..2 || rate <= 0) return null
                    val frames = size / (2 * channels)
                    val out = ShortArray(frames)
                    for (i in 0 until frames) {
                        var s = 0
                        for (c in 0 until channels) s += b.getShort(body + (i * channels + c) * 2)
                        out[i] = (s / channels).toShort()
                    }
                    return Pcm(out, rate)
                }
            }
            pos = body + size + (size and 1)
        }
        return null
    }

    fun readWav(file: File): Pcm? = runCatching { readWav(file.readBytes()) }.getOrNull()

    /** Aplica el timbre de robot. */
    fun process(input: Pcm): Pcm {
        val sr = input.rate
        val n = input.samples.size
        val pad = sr / 8 // cola para que la resonancia no se corte en seco
        val x = FloatArray(n + pad) { if (it < n) input.samples[it] / 32768f else 0f }

        // 1) Banda de altavoz: fuera retumbe (<120 Hz) y siseo (>5,5 kHz); algo de presencia a 2,2 kHz.
        Biquad.highPass(sr, 120.0).run(x)
        Biquad.lowPass(sr, 5_500.0).run(x)
        Biquad.peak(sr, 2_200.0, gainDb = 4.0, q = 1.0).run(x)

        // 2) Carcasa metálica: dos peines cortos (resonancias inarmónicas, suena a caja de metal).
        val wet = FloatArray(x.size)
        comb(x, wet, delay = (sr * 0.0047).toInt(), feedback = 0.5f, gain = 0.32f)
        comb(x, wet, delay = (sr * 0.0071).toInt(), feedback = 0.42f, gain = 0.22f)

        // 3) Zumbido electrónico: modulación en anillo a 55 Hz, muy poca, para que no suene a Dalek.
        val ring = 0.10f
        val w = 2 * PI * 55.0 / sr
        for (i in x.indices) {
            val dry = x[i] + wet[i]
            x[i] = dry * (1 - ring) + dry * ring * sin(w * i).toFloat()
        }

        // 4) Saturación suave y volumen al 90 %.
        val drive = 1.8f
        val norm = tanh(drive)
        var peak = 1e-6f
        for (i in x.indices) {
            x[i] = tanh(x[i] * drive) / norm
            peak = maxOf(peak, abs(x[i]))
        }
        // Recorta la cola en silencio que haya quedado.
        var end = x.size
        while (end > n && abs(x[end - 1]) < 1e-4f) end--
        val k = 0.9f / peak
        // Fundido de 5 ms al principio y al final para que no haya chasquidos.
        val fade = (sr * 0.005).toInt().coerceAtLeast(1)
        val out = ShortArray(end) { i ->
            val f = minOf(1f, i / fade.toFloat(), (end - 1 - i) / fade.toFloat()).coerceAtLeast(0f)
            (x[i] * k * f * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return Pcm(out, sr)
    }

    private fun comb(x: FloatArray, out: FloatArray, delay: Int, feedback: Float, gain: Float) {
        if (delay <= 0) return
        val buf = FloatArray(x.size)
        for (i in x.indices) {
            val d = if (i >= delay) buf[i - delay] else 0f
            buf[i] = x[i] + d * feedback
            out[i] += d * gain
        }
    }

    /** Escribe un WAV PCM de 16 bits mono (para la prueba en el ordenador). */
    fun toWav(pcm: Pcm): ByteArray {
        val data = pcm.samples.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(pcm.rate).putInt(pcm.rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        pcm.samples.forEach { b.putShort(it) }
        return b.array()
    }

    /** Filtro de segundo orden (fórmulas del «Audio EQ Cookbook»). */
    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        fun run(x: FloatArray) {
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            for (i in x.indices) {
                val x0 = x[i].toDouble()
                val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = x0; y2 = y1; y1 = y0
                x[i] = y0.toFloat()
            }
        }

        companion object {
            private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
                Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

            fun lowPass(sr: Int, f: Double, q: Double = 0.707): Biquad {
                val w = 2 * PI * f / sr; val c = kotlin.math.cos(w); val a = sin(w) / (2 * q)
                return make((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + a, -2 * c, 1 - a)
            }

            fun highPass(sr: Int, f: Double, q: Double = 0.707): Biquad {
                val w = 2 * PI * f / sr; val c = kotlin.math.cos(w); val a = sin(w) / (2 * q)
                return make((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + a, -2 * c, 1 - a)
            }

            fun peak(sr: Int, f: Double, gainDb: Double, q: Double): Biquad {
                val amp = exp(gainDb / 40 * kotlin.math.ln(10.0))
                val w = 2 * PI * f / sr; val c = kotlin.math.cos(w); val a = sin(w) / (2 * q)
                return make(1 + a * amp, -2 * c, 1 - a * amp, 1 + a / amp, -2 * c, 1 - a / amp)
            }
        }
    }
}
