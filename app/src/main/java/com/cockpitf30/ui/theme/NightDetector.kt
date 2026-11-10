package com.cockpitf30.ui.theme

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.ZonedDateTime
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/**
 * Decide si está oscuro para poner el modo Night solo. Mira, por este orden:
 * - los faros: si la caja CAN dice que van encendidos (túnel o anochecer con luces en AUTO);
 * - el sensor de luz de la radio, si tiene;
 * - la altura del sol en la posición del coche (sin internet: cálculo astronómico).
 * Para salir de Night espera un poco, así no parpadea entre farolas o a la salida de un túnel corto.
 */
object NightDetector {
    /** Sol más bajo que esto (grados): ya es de noche a efectos de pantalla (crepúsculo civil). */
    private const val SUN_DARK_DEG = -3.0
    private const val LUX_DARK = 8f
    private const val LUX_LIGHT = 30f
    private const val LEAVE_MS = 20_000L

    var reason by mutableStateOf("—")
        private set

    private var lastDarkAt = 0L
    private var luxDark = false

    /**
     * [headlights] estado de los faros (null = la caja no lo dice), [lux] luz ambiente si hay sensor,
     * [lat]/[lon] posición o null. Devuelve si está oscuro y lo deja en [DriveModes.dark].
     */
    fun update(headlights: Boolean?, lux: Float?, lat: Double?, lon: Double?, useHeadlights: Boolean, now: ZonedDateTime = ZonedDateTime.now()): Boolean {
        if (lux != null) luxDark = if (luxDark) lux < LUX_LIGHT else lux < LUX_DARK
        val sunDark = if (lat != null && lon != null) sunElevation(lat, lon, now) < SUN_DARK_DEG
        else now.hour >= 21 || now.hour < 7
        val why = when {
            useHeadlights && headlights == true -> "faros encendidos"
            lux != null && luxDark -> "poca luz (%.0f lux)".format(lux)
            sunDark -> if (lat != null) "sol bajo el horizonte" else "hora (sin posición)"
            else -> null
        }
        val t = SystemClock.elapsedRealtime()
        if (why != null) lastDarkAt = t
        val dark = why != null || (DriveModes.dark && t - lastDarkAt < LEAVE_MS)
        reason = why ?: if (dark) "saliendo de la oscuridad" else "de día"
        DriveModes.dark = dark
        return dark
    }

    /** Altura del sol sobre el horizonte en grados (fórmula aproximada de la NOAA, ±0,5°). */
    fun sunElevation(lat: Double, lon: Double, time: ZonedDateTime): Double {
        val utc = time.withZoneSameInstant(java.time.ZoneOffset.UTC)
        val day = utc.dayOfYear
        val hour = utc.hour + utc.minute / 60.0 + utc.second / 3600.0
        val g = 2 * Math.PI / 365 * (day - 1 + (hour - 12) / 24)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) + 0.000907 * sin(2 * g) -
            0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val trueSolarMin = hour * 60 + eqTime + 4 * lon
        val ha = Math.toRadians(trueSolarMin / 4 - 180)
        val la = Math.toRadians(lat)
        val cosZen = sin(la) * sin(decl) + cos(la) * cos(decl) * cos(ha)
        // cos(cenit) = sen(altura).
        return Math.toDegrees(asin(cosZen.coerceIn(-1.0, 1.0)))
    }
}
