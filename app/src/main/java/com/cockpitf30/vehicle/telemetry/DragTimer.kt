package com.cockpitf30.vehicle.telemetry

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Pruebas que mide el cronómetro. */
enum class DragTest(val label: String) {
    ZERO_100("0–100 km/h"),
    MID_60_120("60–120 km/h"),
    QUARTER_400("0–400 m"),
}

/** Una marca: segundos, velocidad al final (para los 400 m) y cuándo se hizo. */
data class DragResult(val test: DragTest, val seconds: Float, val endKmh: Float, val at: Long)

/** Prueba en marcha para enseñarla en directo. */
data class DragRun(val test: DragTest, val elapsed: Float, val kmh: Float, val meters: Float)

data class DragState(
    val running: List<DragRun> = emptyList(),
    val last: Map<DragTest, DragResult> = emptyMap(),
    val best: Map<DragTest, DragResult> = emptyMap(),
    val history: List<DragResult> = emptyList(),
    /** Parado y listo: la siguiente salida se cronometra. */
    val armed: Boolean = false,
    /** Marca recién hecha (para resaltarla unos segundos). */
    val fresh: DragResult? = null,
)

/**
 * Cronómetro de aceleración automático: al salir desde parado mide 0–100 km/h y 0–400 m; en
 * marcha, cada vez que se pasa de 60 km/h acelerando mide hasta 120. Los cruces se interpolan
 * entre muestras, así que con la velocidad de la caja CAN (10 por segundo) la precisión ronda la
 * décima. Se anula si se levanta el pie (la velocidad cae 3 km/h del máximo) o tarda demasiado.
 */
class DragTimer(private val onResult: (DragResult) -> Unit = {}) {
    private class Active(val test: DragTest, val t0: Float, var meters: Float = 0f, var maxKmh: Float = 0f)

    private val active = ArrayList<Active>()
    private var stillSince: Float? = null
    var armed = false
        private set
    private var lastT: Float? = null
    private var lastKmh: Float? = null

    val running: List<DragRun>
        get() = active.map { DragRun(it.test, (lastT ?: it.t0) - it.t0, lastKmh ?: 0f, it.meters) }

    /** Nueva muestra: [t] en segundos (reloj monótono) y velocidad en km/h. */
    fun feed(t: Float, kmh: Float) {
        val pt = lastT
        val pv = lastKmh
        lastT = t; lastKmh = kmh
        if (pt == null || pv == null || t <= pt) return
        val dt = t - pt

        // Armado: 1 s parado.
        if (kmh < 0.5f) {
            if (stillSince == null) stillSince = t
            if (t - (stillSince ?: t) >= 1f) armed = true
            active.removeAll { it.test != DragTest.MID_60_120 }
        } else stillSince = null

        // Salida desde parado: el cronómetro arranca cuando se pasa de 0,5 km/h.
        if (armed && pv < 0.5f && kmh >= 0.5f) {
            val t0 = cross(pt, pv, t, kmh, 0.5f)
            active += Active(DragTest.ZERO_100, t0)
            active += Active(DragTest.QUARTER_400, t0)
            armed = false
        }
        // 60–120: al cruzar 60 subiendo.
        if (pv < 60f && kmh >= 60f && active.none { it.test == DragTest.MID_60_120 }) {
            active += Active(DragTest.MID_60_120, cross(pt, pv, t, kmh, 60f), maxKmh = 60f)
        }

        val done = ArrayList<Active>()
        for (a in active) {
            a.meters += (pv + kmh) / 2f / 3.6f * dt
            a.maxKmh = maxOf(a.maxKmh, kmh)
            val elapsed = t - a.t0
            val limit = if (a.test == DragTest.MID_60_120) 40f else 30f
            if (kmh < a.maxKmh - 3f || elapsed > limit) { done += a; continue }
            when (a.test) {
                DragTest.ZERO_100 -> if (kmh >= 100f) { finish(a, cross(pt, pv, t, kmh, 100f), 100f); done += a }
                DragTest.MID_60_120 -> if (kmh >= 120f) { finish(a, cross(pt, pv, t, kmh, 120f), 120f); done += a }
                DragTest.QUARTER_400 -> if (a.meters >= 400f) {
                    // Instante en que se llegó a 400 m dentro de este intervalo.
                    val stepM = (pv + kmh) / 2f / 3.6f * dt
                    val before = a.meters - stepM
                    val f = if (stepM > 0) ((400f - before) / stepM).coerceIn(0f, 1f) else 1f
                    finish(a, pt + dt * f, pv + (kmh - pv) * f)
                    done += a
                }
            }
        }
        active.removeAll(done.toSet())
    }

    private fun finish(a: Active, tEnd: Float, endKmh: Float) {
        onResult(DragResult(a.test, tEnd - a.t0, endKmh, System.currentTimeMillis()))
    }

    /** Instante (interpolado) en que la velocidad pasó por [target] entre dos muestras. */
    private fun cross(t0: Float, v0: Float, t1: Float, v1: Float, target: Float): Float =
        if (v1 == v0) t1 else t0 + (t1 - t0) * ((target - v0) / (v1 - v0)).coerceIn(0f, 1f)
}

/** Marcas guardadas y el cronómetro que corre con la telemetría. */
object DragTimes {
    private const val HISTORY = 30
    private val _state = MutableStateFlow(DragState())
    val state: StateFlow<DragState> = _state.asStateFlow()
    private var context: Context? = null
    private val timer = DragTimer(::record)
    private var freshUntil = 0L

    fun load(ctx: Context) {
        context = ctx.applicationContext
        val json = ctx.getSharedPreferences("cockpit", Context.MODE_PRIVATE).getString(KEY, null) ?: return
        runCatching {
            val arr = JSONArray(json)
            val hist = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DragResult(DragTest.valueOf(o.getString("t")), o.getDouble("s").toFloat(), o.getDouble("v").toFloat(), o.getLong("at"))
            }
            _state.value = summarize(hist)
        }
    }

    fun feed(t: Float, kmh: Float) {
        timer.feed(t, kmh)
        val s = _state.value
        val fresh = s.fresh?.takeIf { System.currentTimeMillis() < freshUntil }
        val running = timer.running
        if (running != s.running || timer.armed != s.armed || fresh != s.fresh) {
            _state.value = s.copy(running = running, armed = timer.armed, fresh = fresh)
        }
    }

    private fun record(r: DragResult) {
        freshUntil = System.currentTimeMillis() + 15_000
        val hist = (listOf(r) + _state.value.history).take(HISTORY)
        _state.value = summarize(hist, keepBest = _state.value.best).copy(fresh = r, running = timer.running, armed = timer.armed)
        save(hist, _state.value.best)
    }

    fun clear() {
        _state.value = DragState()
        context?.getSharedPreferences("cockpit", Context.MODE_PRIVATE)?.edit()?.remove(KEY)?.remove(KEY_BEST)?.apply()
    }

    private fun summarize(hist: List<DragResult>, keepBest: Map<DragTest, DragResult>? = null): DragState {
        val best = HashMap<DragTest, DragResult>()
        (keepBest ?: loadBest()).forEach { (k, v) -> best[k] = v }
        hist.forEach { r -> if (best[r.test]?.let { r.seconds < it.seconds } != false) best[r.test] = r }
        val last = hist.groupBy { it.test }.mapValues { it.value.first() }
        return DragState(last = last, best = best, history = hist)
    }

    private fun save(hist: List<DragResult>, best: Map<DragTest, DragResult>) {
        val ctx = context ?: return
        fun enc(list: Collection<DragResult>) = JSONArray().apply {
            list.forEach { put(JSONObject().put("t", it.test.name).put("s", it.seconds.toDouble()).put("v", it.endKmh.toDouble()).put("at", it.at)) }
        }.toString()
        ctx.getSharedPreferences("cockpit", Context.MODE_PRIVATE).edit().putString(KEY, enc(hist)).putString(KEY_BEST, enc(best.values)).apply()
    }

    private fun loadBest(): Map<DragTest, DragResult> {
        val json = context?.getSharedPreferences("cockpit", Context.MODE_PRIVATE)?.getString(KEY_BEST, null) ?: return emptyMap()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).associate { i ->
                val o = arr.getJSONObject(i)
                val r = DragResult(DragTest.valueOf(o.getString("t")), o.getDouble("s").toFloat(), o.getDouble("v").toFloat(), o.getLong("at"))
                r.test to r
            }
        }.getOrDefault(emptyMap())
    }

    fun preview(s: DragState) { _state.value = s }

    private const val KEY = "drag_history"
    private const val KEY_BEST = "drag_best"
}
