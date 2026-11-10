package com.cockpitf30.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.cockpitf30.system.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Previsión del tiempo con Open-Meteo (gratis, sin clave). Usa el lugar elegido o, si no hay,
 * la ubicación del sistema (LocationManager, sin Google Play Services). Guarda la última
 * respuesta para mostrarla sin conexión.
 */
object WeatherRepository {
    private val _state = MutableStateFlow(WeatherState())
    val state: StateFlow<WeatherState> = _state.asStateFlow()
    private var cacheLoaded = false

    fun loadCache(context: Context) {
        if (cacheLoaded) return
        cacheLoaded = true
        val (json, place, time) = AppSettings(context).weatherCache ?: return
        runCatching { parseForecast(json) }.onSuccess { f ->
            _state.update { it.copy(forecast = f, placeName = place, updatedAtMillis = time) }
        }
    }

    suspend fun refresh(context: Context) {
        loadCache(context)
        _state.update { it.copy(loading = true, error = null) }
        val settings = AppSettings(context)
        val result = runCatching {
            val place = settings.weatherPlace ?: currentPlace(context)
                ?: error(if (hasLocationPermission(context)) "Ubicación no disponible" else "Sin permiso de ubicación")
            val json = httpGet(
                "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
                    "&current=temperature_2m,weather_code,is_day" +
                    "&hourly=temperature_2m,apparent_temperature,weather_code,is_day,precipitation_probability,precipitation," +
                    "wind_speed_10m,wind_direction_10m,wind_gusts_10m,relative_humidity_2m" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,precipitation_sum," +
                    "precipitation_probability_max,wind_speed_10m_max&timezone=auto&forecast_days=7",
            )
            val forecast = parseForecast(json)
            val now = System.currentTimeMillis()
            settings.weatherCache = Triple(json, place.name, now)
            settings.addRecentPlace(place)
            Triple(forecast, place.name, now)
        }
        result.onSuccess { (f, name, time) ->
            _state.value = WeatherState(forecast = f, placeName = name, updatedAtMillis = time)
        }.onFailure { e ->
            _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
        }
    }

    /** Busca localidades por nombre (geocoding de Open-Meteo, en español). */
    suspend fun search(query: String): List<Place> {
        if (query.isBlank()) return emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return parsePlaces(httpGet("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=10&language=es&format=json"))
    }

    fun hasLocationPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Ubicación actual como lugar, con nombre por geocodificación inversa o coordenadas redondeadas. */
    private suspend fun currentPlace(context: Context): Place? {
        val location = currentLocation(context) ?: return null
        val name = reverseGeocode(context, location)
            ?: "%.2f, %.2f".format(Locale.US, location.latitude, location.longitude)
        return Place(name, null, null, location.latitude, location.longitude)
    }

    /** Última posición conocida del sistema (sin esperar a una nueva), o null. */
    @SuppressLint("MissingPermission")
    fun lastKnownLocation(context: Context): Location? {
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        return enabledProviders(lm).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    private fun enabledProviders(lm: LocationManager) =
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(context: Context): Location? {
        lastKnownLocation(context)?.let { return it }
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = enabledProviders(lm)
        // Sin posición guardada: una petición única, con tiempo límite.
        val provider = providers.firstOrNull() ?: return null
        return withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                val signal = androidx.core.os.CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(lm, provider, signal, Executors.newSingleThreadExecutor()) { loc ->
                    if (cont.isActive) cont.resume(loc)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun reverseGeocode(context: Context, location: Location): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            Geocoder(context, Locale("es", "ES")).getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
        }.getOrNull()
    }

    private suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
