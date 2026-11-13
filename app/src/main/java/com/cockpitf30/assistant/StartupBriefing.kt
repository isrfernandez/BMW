package com.cockpitf30.assistant

import android.content.Context
import com.cockpitf30.nav.RouteTracker
import com.cockpitf30.nav.SpeedCameras
import com.cockpitf30.system.AppSettings
import com.cockpitf30.ui.screens.serviceStatuses
import com.cockpitf30.vehicle.Maintenance
import com.cockpitf30.vehicle.VehicleRepository
import com.cockpitf30.vehicle.trip.TripComputer
import com.cockpitf30.vehicle.trip.TripHistory
import com.cockpitf30.weather.WeatherRepository
import com.cockpitf30.weather.wmoCondition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime

/** Dispara el saludo al arrancar con lo que se sepa en ese momento. */
object StartupBriefing {
    /** Espera máxima a que lleguen el tiempo y los radares. */
    private const val WAIT_MS = 25_000L
    private const val ROUTE_CAMERA_KM = 0.4
    private const val NEAR_KM = 5.0

    /** ¿Toca briefing al arrancar? (activado y no dicho hace poco) */
    fun due(ctx: Context): Boolean {
        val s = AppSettings(ctx)
        return s.briefing && System.currentTimeMillis() - s.lastBriefingAt >= Briefing.GAP_MS
    }

    /** Al arrancar: solo si está activado y no se dijo hace poco. */
    suspend fun maybeRun(ctx: Context) {
        val s = AppSettings(ctx)
        val now = System.currentTimeMillis()
        if (!s.briefing || now - s.lastBriefingAt < Briefing.GAP_MS) return
        // Mientras se prepara, los avisos en ruta (turismo…) esperan: el briefing va primero.
        com.cockpitf30.nav.RouteAlerts.holdUntil = now + WAIT_MS + 5_000
        withTimeoutOrNull(WAIT_MS) {
            WeatherRepository.state.first { it.forecast != null }
            SpeedCameras.state.first { it.cameras.isNotEmpty() }
        }
        speak(ctx)
    }

    /** Lo dice ya (botón «Probar ahora» de Ajustes). */
    fun speak(ctx: Context) {
        val s = AppSettings(ctx)
        val text = Briefing.text(input(ctx))
        VoiceAssistant.announce(text)
        s.lastBriefingAt = System.currentTimeMillis()
        // Y después, mientras lo dice (unos 75 ms por letra), tampoco.
        com.cockpitf30.nav.RouteAlerts.holdUntil = System.currentTimeMillis() + 2_000 + text.length * 80L
    }

    fun input(ctx: Context): BriefingInput {
        val s = AppSettings(ctx)
        val fc = WeatherRepository.state.value.forecast
        val nowLt = LocalDateTime.now()
        val rainLater = fc?.hours?.filter { it.time.isAfter(nowLt) && it.time.isBefore(nowLt.plusHours(6)) }?.any { (it.precipProb ?: 0) >= 50 } == true
        val pos = RouteTracker.state.value.position
            ?: WeatherRepository.lastKnownLocation(ctx)?.let { com.cockpitf30.nav.GeoPoint(it.latitude, it.longitude) }
        val cams = SpeedCameras.state.value.cameras
        var onRoute: Int? = null
        var routeKm: Double? = null
        var near = 0
        if (pos != null) {
            near = cams.count { TripHistory.km(pos.lat, pos.lon, it.lat, it.lon) <= NEAR_KM }
            TripHistory.usualDestination(TripComputer.state.value.history, pos.lat, pos.lon)?.let { (dLat, dLon) ->
                routeKm = TripHistory.km(pos.lat, pos.lon, dLat, dLon)
                // Solo se conocen los radares cargados alrededor; si el destino queda lejos se cuentan los del tramo cargado.
                onRoute = cams.count { TripHistory.kmToSegment(it.lat, it.lon, pos.lat, pos.lon, dLat, dLon) <= ROUTE_CAMERA_KM }
            }
        }
        return BriefingInput(
            hour = nowLt.hour,
            name = s.assistantUserName?.takeIf { it.isNotBlank() },
            tempC = fc?.tempC,
            weather = fc?.let { wmoCondition(it.code).text },
            rainLater = rainLater,
            rangeKm = VehicleRepository.state.value.rangeKm,
            camerasOnRoute = onRoute,
            routeKm = routeKm,
            camerasNear = near,
            service = Maintenance.spoken(serviceStatuses(s)),
        )
    }

}
