package com.cockpitf30.nav

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Dónde se quedó el coche. */
data class Parked(val lat: Double, val lon: Double, val at: Long, val accuracyM: Float? = null) {
    /** Enlace que abre el punto en Google Maps (en el móvil, con o sin la app). */
    val mapsUrl: String get() = "https://www.google.com/maps/search/?api=1&query=%.6f,%.6f".format(Locale.US, lat, lon)
    fun time(): String = SimpleDateFormat("HH:mm", Locale("es", "ES")).format(Date(at))
    fun day(): String = SimpleDateFormat("d 'de' MMMM", Locale("es", "ES")).format(Date(at))
    fun message(): String = "Mi coche está aparcado aquí (${day()}, ${time()}): $mapsUrl"
}

/**
 * La ubicación del coche aparcado: se guarda en cuanto el coche lleva 10 s parado con posición
 * buena del GPS (así queda guardada aunque la radio se apague al quitar el contacto), y se puede
 * enseñar como código QR para el móvil o mandar por correo, WhatsApp… con lo que tenga la radio.
 * El sitio es del coche; el correo al que mandarlo, de cada conductor.
 */
object ParkingSpot {
    private const val KEY = "parking_spot"
    const val KEY_EMAIL = "parking_email"
    private const val STILL_MS = 10_000L
    private const val MOVED_M = 25.0
    private const val MAX_ACCURACY_M = 60f

    private val _spot = MutableStateFlow<Parked?>(null)
    val spot: StateFlow<Parked?> = _spot.asStateFlow()

    /** Se enseña la ventana con el QR. */
    val showing = MutableStateFlow(false)

    private var stillSince = 0L
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val s = prefs(ctx).getString(KEY, null) ?: return
        runCatching {
            val p = s.split(',')
            _spot.value = Parked(p[0].toDouble(), p[1].toDouble(), p[2].toLong(), p.getOrNull(3)?.toFloatOrNull())
        }
    }

    /** Cada segundo desde el servicio del coche. */
    fun tick(ctx: Context, route: RouteState, speedKmh: Float?, now: Long) {
        load(ctx)
        val pos = route.position
        val fix = route.fix
        val good = pos != null && route.live && !route.estimated && fix != null && (fix.accuracyM ?: 0f) <= MAX_ACCURACY_M
        if (!good || (speedKmh ?: 0f) >= 3f) { stillSince = 0L; return }
        if (stillSince == 0L) { stillSince = now; return }
        if (now - stillSince < STILL_MS) return
        val old = _spot.value
        if (old != null && RouteTracker.distance(GeoPoint(old.lat, old.lon), pos!!) < MOVED_M) return
        save(ctx, Parked(pos!!.lat, pos.lon, now, fix!!.accuracyM))
    }

    fun save(ctx: Context, p: Parked) {
        _spot.value = p
        prefs(ctx).edit().putString(KEY, "${p.lat},${p.lon},${p.at},${p.accuracyM ?: ""}").apply()
    }

    fun email(ctx: Context): String? = prefs(ctx).getString(KEY_EMAIL, null)?.takeIf { it.isNotBlank() }
    fun setEmail(ctx: Context, v: String?) { prefs(ctx).edit().putString(KEY_EMAIL, v?.trim()).apply() }

    fun validEmail(v: String) = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(v.trim())

    /** Abre el menú de compartir de Android con el enlace. false si no hay ubicación o ninguna app. */
    fun share(ctx: Context): Boolean {
        val p = _spot.value ?: return false
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Ubicación del coche")
            .putExtra(Intent.EXTRA_TEXT, p.message())
        val chooser = Intent.createChooser(send, "Enviar la ubicación del coche").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(chooser) }.isSuccess && send.resolveActivity(ctx.packageManager) != null
    }

    /** Abre la app de correo con el mensaje ya escrito para el correo guardado. */
    fun sendEmail(ctx: Context): Boolean {
        val p = _spot.value ?: return false
        val to = email(ctx) ?: return false
        val uri = Uri.parse("mailto:" + Uri.encode(to) + "?subject=" + Uri.encode("Ubicación del coche") + "&body=" + Uri.encode(p.message()))
        val i = Intent(Intent.ACTION_SENDTO, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i.resolveActivity(ctx.packageManager) == null) return false
        return runCatching { ctx.startActivity(i) }.isSuccess
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cockpit", Context.MODE_PRIVATE)
}
