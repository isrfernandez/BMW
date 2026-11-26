package com.cockpitf30.nav

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar

/** Dónde está casa: guardada a mano («esta es mi casa») o aprendida de dónde duerme el coche. */
data class HomeSpot(val lat: Double, val lon: Double, val manual: Boolean)

/**
 * Llegar a casa: cuando el coche, tras haber estado lejos, entra despacio a menos de [NEAR_M] de casa,
 * el asistente dice «Ya estamos en casa» (u otra frase). Una vez por llegada.
 *
 * Casa se guarda por voz o en Ajustes; si nunca se ha guardado, se aprende sola: el sitio donde el
 * coche se queda aparcado de noche ([NIGHTS] noches a menos de [SAME_M]).
 */
object HomeArrival {
    private const val KEY = "home_spot"
    private const val KEY_NIGHTS = "home_nights"
    const val NEAR_M = 150.0
    /** Hay que haber estado así de lejos para que la vuelta cuente como llegada. */
    const val AWAY_M = 800.0
    private const val SAME_M = 200.0
    const val NIGHTS = 3
    private const val MAX_ACCURACY_M = 80f

    private val _home = MutableStateFlow<HomeSpot?>(null)
    val home: StateFlow<HomeSpot?> = _home.asStateFlow()

    private var loaded = false
    /** Ha estado lejos de casa desde la última llegada (al arrancar en casa no se saluda). */
    private var away = false
    private var lastNightAt = 0L

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        prefs(ctx).getString(KEY, null)?.let { s ->
            runCatching { val p = s.split(','); _home.value = HomeSpot(p[0].toDouble(), p[1].toDouble(), p.getOrNull(2) == "1") }
        }
    }

    fun set(ctx: Context, lat: Double, lon: Double, manual: Boolean = true) {
        load(ctx)
        _home.value = HomeSpot(lat, lon, manual)
        prefs(ctx).edit().putString(KEY, "$lat,$lon,${if (manual) 1 else 0}").apply()
        away = false
    }

    fun clear(ctx: Context) {
        load(ctx)
        _home.value = null
        prefs(ctx).edit().remove(KEY).remove(KEY_NIGHTS).apply()
    }

    /** «Esta es mi casa»: la posición actual. false si aún no hay GPS bueno. */
    fun setHere(ctx: Context): Boolean {
        val r = RouteTracker.state.value
        val pos = r.position ?: return false
        if (!r.live || r.estimated || (r.fix?.accuracyM ?: 0f) > MAX_ACCURACY_M) return false
        set(ctx, pos.lat, pos.lon, manual = true)
        return true
    }

    /**
     * Cada segundo. Devuelve true justo al llegar a casa (para decir la frase); el resto, false.
     */
    fun tick(ctx: Context, route: RouteState, speedKmh: Float?, now: Long): Boolean {
        load(ctx)
        val pos = route.position ?: return false
        val fix = route.fix
        if (!route.live || route.estimated || fix == null || (fix.accuracyM ?: 0f) > MAX_ACCURACY_M) return false
        val h = _home.value
        val speed = speedKmh ?: 0f
        if (h == null) { learn(ctx, pos, speed, now); return false }
        val d = RouteTracker.distance(GeoPoint(h.lat, h.lon), pos)
        if (d > AWAY_M) away = true
        if (away && d < NEAR_M && speed < 25f) { away = false; return true }
        if (!h.manual) learn(ctx, pos, speed, now)
        return false
    }

    /** Aprender casa: dónde se queda parado de noche (entre las 21 y las 6), una vez por noche. */
    private fun learn(ctx: Context, pos: GeoPoint, speed: Float, now: Long) {
        if (speed >= 3f || now - lastNightAt < 6 * 3600_000L) return
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        if (hour in 6..20) return
        lastNightAt = now
        val p = prefs(ctx)
        val nights = (p.getString(KEY_NIGHTS, "") ?: "").split(';').filter { it.isNotBlank() }
            .mapNotNull { s -> s.split(',').takeIf { it.size >= 2 }?.let { GeoPoint(it[0].toDouble(), it[1].toDouble()) } }
            .plus(pos).takeLast(10)
        p.edit().putString(KEY_NIGHTS, nights.joinToString(";") { "${it.lat},${it.lon}" }).apply()
        val home = homeFromNights(nights) ?: return
        val cur = _home.value
        if (cur == null || (!cur.manual && RouteTracker.distance(GeoPoint(cur.lat, cur.lon), home) > SAME_M)) set(ctx, home.lat, home.lon, manual = false)
    }

    /** El sitio con al menos [NIGHTS] noches a menos de [SAME_M]: su centro. Puro, para tests. */
    fun homeFromNights(nights: List<GeoPoint>): GeoPoint? {
        val best = nights.map { a -> nights.filter { RouteTracker.distance(a, it) < SAME_M } }.maxByOrNull { it.size } ?: return null
        if (best.size < NIGHTS) return null
        return GeoPoint(best.sumOf { it.lat } / best.size, best.sumOf { it.lon } / best.size)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cockpit", Context.MODE_PRIVATE)
}
