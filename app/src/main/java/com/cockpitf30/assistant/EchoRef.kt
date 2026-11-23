package com.cockpitf30.assistant

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Para poder interrumpir al asistente en una tablet (altavoz pegado al micrófono): se compara lo que
 * oye el micrófono con lo que está sonando de verdad por el altavoz.
 *
 * Antes solo se miraba el nivel del micrófono frente al de los segundos anteriores (percentil 80 de
 * su propio eco): hablando a volumen normal no se pasaba nunca de 1,9 veces su eco y en los dos
 * primeros segundos ni se miraba, así que no había forma de cortarla.
 *
 * Ahora el motor de voz nos da el audio que va a sonar ([ref]); se calcula su energía cada 20 ms y
 * se aprende solo (y se recuerda entre frases):
 * - el retraso entre lo que sale y lo que vuelve al micrófono (correlación de envolventes);
 * - cuánto de su voz vuelve al micrófono (ganancia del eco).
 * Con eso se predice, trama a trama, cuánto eco debería oírse. Si el micrófono oye bastante más de
 * lo previsto durante ~0,3 s (sobre todo en los huecos entre sus palabras, donde el eco es casi
 * nulo), es que alguien habla encima.
 */
class EchoRef {
    companion object {
        const val FRAME_MS = 20
        private const val CAP = 1024 // ~20 s de tramas
        /** Ventana para aprender retraso y ganancia (3 s). */
        private const val WIN = 150
        /** Retraso máximo que se busca (1,2 s: el motor de voz va por delante de lo que suena). */
        const val MAX_LAG = 60
        /** Correlación mínima para fiarse del retraso. */
        private const val MIN_CORR = 0.45
        /**
         * En los últimos 0,3 s, cuánto más que el eco previsto (más el ruido) tiene que oírse para
         * contar como voz encima. Hablando a volumen normal sobre su voz sale ~2,5.
         */
        const val OVER = 1.8f
        /** Lo que tiene que sobrar de media por trama sobre lo previsto (que no salte con ruido flojo). */
        private const val MIN_EXCESS = 300f
        private const val HIST = 15
        /** La cola del eco (reverberación del coche): lo que queda de cada trama en las siguientes. */
        private const val TAIL = 0.6f
        private const val TAIL_FRAMES = 6
    }

    private val refE = FloatArray(CAP)
    private val refTag = LongArray(CAP) { -1 }
    private val micE = FloatArray(CAP)
    private val micTag = LongArray(CAP) { -1 }
    private val voice = BooleanArray(CAP)

    /** Retraso aprendido, en tramas (se recuerda entre frases). */
    @Volatile var lag = -1; private set
    /** Ganancia del eco: energía en el micrófono por unidad de energía que sale. */
    @Volatile var gain = 0f; private set
    /** Ruido de fondo del micrófono (sin hablar nadie). */
    @Volatile var floor = 0f; private set
    /** Última correlación del retraso (Diagnóstico). */
    @Volatile var corr = 0.0; private set

    /** Hasta dónde llega lo que va a sonar (ms de reloj, según llega el audio). */
    @Volatile var refEndMs = 0L; private set
    private var accSum = 0.0
    private var accN = 0
    private var lastMicFrame = -1L
    private var blocks = 0
    /** Tramas de referencia de esta frase: sin ellas (el motor no da su audio) no se decide nada. */
    @Volatile private var refFrames = 0

    /** ¿Ya sabe separar su eco de una voz encima? */
    val ready: Boolean get() = lag >= 0 && gain > 0f

    /** Listo y con el audio de la frase en curso (si el motor no lo da, se usa el método antiguo). */
    val active: Boolean get() = ready && refFrames >= 10

    /** Empieza una frase nueva: se olvida el audio anterior (no lo aprendido). */
    @Synchronized fun newSpeech() {
        refTag.fill(-1); micTag.fill(-1); voice.fill(false)
        refEndMs = 0L; accSum = 0.0; accN = 0; lastMicFrame = -1L; blocks = 0; refFrames = 0
    }

    // ------------------------------------------------------------------ lo que suena

    /** Audio PCM 16 bits little-endian que el motor va a reproducir (onAudioAvailable). */
    fun refBytes(bytes: ByteArray, rate: Int, nowMs: Long) {
        val n = bytes.size / 2
        val s = ShortArray(n) { i -> ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort() }
        ref(s, n, rate, nowMs)
    }

    /** Muestras que van a sonar ahora (o justo después de las anteriores, si aún no han sonado). */
    @Synchronized fun ref(samples: ShortArray, n: Int, rate: Int, nowMs: Long) {
        if (rate <= 0) return
        // Un hueco (entre frases o al empezar): el audio nuevo suena desde ahora.
        if (refEndMs < nowMs - FRAME_MS) { refEndMs = nowMs; accSum = 0.0; accN = 0 }
        val per = (rate * FRAME_MS / 1000).coerceAtLeast(1)
        for (i in 0 until n) {
            val v = samples[i].toDouble()
            accSum += v * v
            if (++accN >= per) {
                putRef(refEndMs / FRAME_MS, sqrt(accSum / accN).toFloat())
                refFrames++
                refEndMs += FRAME_MS
                accSum = 0.0; accN = 0
            }
        }
    }

    // ------------------------------------------------------------------ lo que oye

    /** Micrófono sin que hable el asistente: para saber el ruido de fondo. */
    fun idle(buf: ShortArray, n: Int) {
        val per = 16_000 * FRAME_MS / 1000
        var i = 0
        while (i + per <= n) { trackFloor(rms(buf, i, per), 0.005f); i += per }
    }

    /** Mínimo que se sigue rápido hacia abajo y despacio hacia arriba (ruido del coche). */
    private fun trackFloor(e: Float, up: Float) {
        floor = when {
            floor <= 0f -> e
            e < floor -> floor * 0.7f + e * 0.3f
            else -> floor * (1 - up) + e * up
        }
    }

    /**
     * Un bloque del micrófono (16 kHz) que acaba de leerse a las [nowMs] mientras habla.
     * Devuelve true si alguien está hablando encima de ella.
     */
    @Synchronized fun mic(buf: ShortArray, n: Int, nowMs: Long, rate: Int = 16_000): Boolean {
        val per = rate * FRAME_MS / 1000
        val frames = n / per
        val end = nowMs / FRAME_MS
        for (k in 0 until frames) {
            val e = rms(buf, k * per, per)
            putMic(end - (frames - 1 - k), e)
            // También mientras habla: en los huecos de su voz se ve el ruido de fondo.
            trackFloor(e, 0.002f)
        }
        return analyse(end)
    }

    /** Lo mismo con energías por trama (tests). */
    @Synchronized internal fun micFrame(frame: Long, e: Float) { putMic(frame, e) }
    @Synchronized internal fun refFrame(frame: Long, e: Float) { putRef(frame, e); refFrames++ }
    @Synchronized internal fun setFloor(e: Float) { floor = e }

    @Synchronized internal fun analyse(end: Long): Boolean {
        lastMicFrame = end
        // Cada medio segundo se revisa el retraso y la ganancia.
        if (++blocks % 5 == 1 || lag < 0) learn(end)
        if (lag < 0 || gain <= 0f || refFrames < 10) return false
        // Lo oído frente a lo previsto (su eco + el ruido) en los últimos 0,3 s.
        var m = 0f; var p = 0f; var n = 0
        for (f in end - HIST + 1..end) {
            val i = idx(f)
            if (micTag[i] != f) continue
            m += micE[i]; p += gain * tail(f - lag) + floor; n++
        }
        if (n < HIST * 2 / 3) return false
        val hit = m >= over * p && m - p >= MIN_EXCESS * n
        ratio = if (p > 0f) m / p else 0f
        // Esas tramas no sirven para aprender la ganancia del eco.
        if (hit) for (f in end - HIST + 1..end) voice[idx(f)] = true
        return hit
    }

    /** Umbral en uso: sube si se ha callado varias veces por nada (radio, ruido) y baja al acertar. */
    @Volatile var over = OVER; private set
    private var falseRun = 0

    /** Se calló por una «voz encima» y nadie dijo nada. */
    fun falseAlarm() { if (++falseRun >= 2) over = (over * 1.25f).coerceAtMost(OVER * 2f) }

    /** Se calló y de verdad le estaban hablando. */
    fun trueAlarm() { falseRun = 0; over = maxOf(OVER, over / 1.25f) }

    /** Última relación oído / previsto (Diagnóstico). */
    @Volatile var ratio = 0f; private set

    /** Eco previsto de la trama [r] de lo que sonó: lo que sonaba entonces y la cola de lo anterior. */
    private fun tail(r: Long): Float {
        var best = ref(r + 1) * 0.5f
        var w = 1f
        for (k in 0..TAIL_FRAMES) { best = maxOf(best, ref(r - k) * w); w *= TAIL }
        return best
    }

    private fun learn(end: Long) {
        val start = end - WIN + 1
        // ¿Hay bastante voz suya en la ventana para aprender?
        var active = 0
        for (f in start - MAX_LAG..end) if (ref(f) > 0f) active++
        if (active < 40) return
        var bestLag = -1
        var bestC = -1.0
        for (l in 0..MAX_LAG) {
            val c = correlation(start, end, l)
            if (c > bestC) { bestC = c; bestLag = l }
        }
        corr = bestC
        if (bestLag < 0 || bestC < MIN_CORR) return
        lag = if (lag >= 0 && kotlin.math.abs(bestLag - lag) <= 1) lag else bestLag
        // Ganancia: mediana de micro/salida en sus tramas fuertes que no son voz encima.
        var maxRef = 0f
        for (f in start..end) maxRef = maxOf(maxRef, ref(f - lag))
        if (maxRef <= 0f) return
        val ratios = ArrayList<Float>()
        for (f in start..end) {
            val i = idx(f)
            if (micTag[i] != f || voice[i]) continue
            val r = ref(f - lag)
            if (r >= 0.3f * maxRef) ratios += ((micE[i] - floor).coerceAtLeast(0f)) / r
        }
        if (ratios.size < 10) return
        ratios.sort()
        val g = ratios[ratios.size / 2]
        gain = if (gain <= 0f) g else gain * 0.6f + g * 0.4f
    }

    /** Correlación (de los logaritmos) entre micro y salida con el retraso [l]. */
    private fun correlation(start: Long, end: Long, l: Int): Double {
        var n = 0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (f in start..end) {
            val i = idx(f)
            if (micTag[i] != f) continue
            val x = log10(micE[i] + 1.0)
            val y = log10(ref(f - l) + 1.0)
            n++; sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y
        }
        if (n < 30) return -1.0
        val cov = sxy - sx * sy / n
        val vx = sxx - sx * sx / n
        val vy = syy - sy * sy / n
        if (vx <= 1e-9 || vy <= 1e-9) return -1.0
        return cov / sqrt(vx * vy)
    }

    private fun idx(f: Long) = ((f % CAP + CAP) % CAP).toInt()
    private fun ref(f: Long): Float { val i = idx(f); return if (refTag[i] == f) refE[i] else 0f }
    private fun putRef(f: Long, e: Float) { val i = idx(f); refTag[i] = f; refE[i] = e }
    private fun putMic(f: Long, e: Float) { val i = idx(f); micTag[i] = f; micE[i] = e; voice[i] = false }

    private fun rms(buf: ShortArray, from: Int, n: Int): Float {
        var s = 0.0
        for (i in from until from + n) { val v = buf[i].toDouble(); s += v * v }
        return sqrt(s / n).toFloat()
    }

    /** Estado para Diagnóstico. */
    fun describe(): String =
        if (!ready) "eco: aprendiendo (correlación ${"%.2f".format(corr)})"
        else "eco: retraso ${lag * FRAME_MS} ms, ganancia ${"%.2f".format(gain)}, ruido ${floor.toInt()}, correlación ${"%.2f".format(corr)}, " +
            "oído/previsto ${"%.1f".format(ratio)} (umbral ${"%.1f".format(over)})"
}
