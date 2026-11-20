package com.cockpitf30.weather

import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.json.JSONArray

/** Tipo de tiempo, agrupando los códigos WMO que usan Open-Meteo y la mayoría de servicios. */
enum class WeatherKind { CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, STORM }

data class WeatherCondition(val kind: WeatherKind, val text: String)

/** Traduce un código WMO (0–99) a tipo de icono y descripción en español. */
fun wmoCondition(code: Int): WeatherCondition = when (code) {
    0 -> WeatherCondition(WeatherKind.CLEAR, "Despejado")
    1 -> WeatherCondition(WeatherKind.PARTLY_CLOUDY, "Poco nuboso")
    2 -> WeatherCondition(WeatherKind.PARTLY_CLOUDY, "Parcialmente nuboso")
    3 -> WeatherCondition(WeatherKind.CLOUDY, "Cubierto")
    45, 48 -> WeatherCondition(WeatherKind.FOG, "Niebla")
    51, 53, 55 -> WeatherCondition(WeatherKind.DRIZZLE, "Llovizna")
    56, 57 -> WeatherCondition(WeatherKind.DRIZZLE, "Llovizna helada")
    61 -> WeatherCondition(WeatherKind.RAIN, "Lluvia débil")
    63 -> WeatherCondition(WeatherKind.RAIN, "Lluvia")
    65 -> WeatherCondition(WeatherKind.RAIN, "Lluvia fuerte")
    66, 67 -> WeatherCondition(WeatherKind.RAIN, "Lluvia helada")
    71, 73, 75, 77 -> WeatherCondition(WeatherKind.SNOW, "Nieve")
    80, 81 -> WeatherCondition(WeatherKind.RAIN, "Chubascos")
    82 -> WeatherCondition(WeatherKind.RAIN, "Chubascos fuertes")
    85, 86 -> WeatherCondition(WeatherKind.SNOW, "Chubascos de nieve")
    95 -> WeatherCondition(WeatherKind.STORM, "Tormenta")
    96, 99 -> WeatherCondition(WeatherKind.STORM, "Tormenta con granizo")
    else -> WeatherCondition(WeatherKind.CLOUDY, "Desconocido")
}

data class DayForecast(
    val date: LocalDate,
    val code: Int,
    val minC: Int,
    val maxC: Int,
    val sunrise: LocalTime? = null,
    val sunset: LocalTime? = null,
    val precipSumMm: Float? = null,
    val precipProbMax: Int? = null,
    val windMaxKmh: Int? = null,
)

/** Previsión de una hora: temperatura, cielo, lluvia y viento. */
data class HourForecast(
    val time: LocalDateTime,
    val tempC: Int,
    val code: Int,
    val isDay: Boolean,
    /** Probabilidad de precipitación en %, o null si el modelo no la da. */
    val precipProb: Int?,
    val precipMm: Float,
    val windKmh: Int,
    /** De dónde viene el viento, en grados (0 = norte). */
    val windDir: Int,
    val apparentC: Int? = null,
    val gustKmh: Int? = null,
    val humidity: Int? = null,
)

data class Forecast(
    val tempC: Int,
    val code: Int,
    val isDay: Boolean,
    /** El primer día es hoy. */
    val days: List<DayForecast>,
    val hours: List<HourForecast> = emptyList(),
    /** Viento de ahora (de «current»; null en cachés antiguas). */
    val windKmh: Int? = null,
    /** De dónde viene el viento ahora, en grados (0 = norte). */
    val windDir: Int? = null,
    val gustKmh: Int? = null,
) {
    fun hoursOf(date: LocalDate) = hours.filter { it.time.toLocalDate() == date }
}

/** Lugar para el que se pide la previsión. */
data class Place(val name: String, val region: String?, val country: String?, val lat: Double, val lon: Double) {
    val subtitle: String get() = listOfNotNull(region, country).filter { it.isNotBlank() }.joinToString(", ")
}

/** Lo que muestra la interfaz: previsión (posiblemente en caché), lugar y hora de actualización. */
data class WeatherState(
    val forecast: Forecast? = null,
    val placeName: String? = null,
    val updatedAtMillis: Long? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/** Analiza la respuesta de https://api.open-meteo.com/v1/forecast (current + daily). */
fun parseForecast(json: String): Forecast {
    val root = JSONObject(json)
    val current = root.getJSONObject("current")
    val daily = root.getJSONObject("daily")
    val dates = daily.getJSONArray("time")
    val codes = daily.getJSONArray("weather_code")
    val max = daily.getJSONArray("temperature_2m_max")
    val min = daily.getJSONArray("temperature_2m_min")
    val days = (0 until dates.length()).map { i ->
        DayForecast(
            date = LocalDate.parse(dates.getString(i)),
            code = codes.getInt(i),
            minC = Math.round(min.getDouble(i)).toInt(),
            maxC = Math.round(max.getDouble(i)).toInt(),
            sunrise = daily.optJSONArray("sunrise").timeAt(i),
            sunset = daily.optJSONArray("sunset").timeAt(i),
            precipSumMm = daily.optJSONArray("precipitation_sum").doubleAt(i)?.toFloat(),
            precipProbMax = daily.optJSONArray("precipitation_probability_max").doubleAt(i)?.let { Math.round(it).toInt() },
            windMaxKmh = daily.optJSONArray("wind_speed_10m_max").doubleAt(i)?.let { Math.round(it).toInt() },
        )
    }
    return Forecast(
        tempC = Math.round(current.getDouble("temperature_2m")).toInt(),
        code = current.getInt("weather_code"),
        isDay = current.optInt("is_day", 1) == 1,
        days = days,
        hours = root.optJSONObject("hourly")?.let(::parseHours).orEmpty(),
        windKmh = current.optDoubleOrNull("wind_speed_10m")?.let { Math.round(it).toInt() },
        windDir = current.optDoubleOrNull("wind_direction_10m")?.let { Math.round(it).toInt() },
        gustKmh = current.optDoubleOrNull("wind_gusts_10m")?.let { Math.round(it).toInt() },
    )
}

private fun parseHours(h: JSONObject): List<HourForecast> {
    val time = h.getJSONArray("time")
    fun arr(name: String) = h.optJSONArray(name)
    val temp = arr("temperature_2m")
    val code = arr("weather_code")
    val day = arr("is_day")
    val prob = arr("precipitation_probability")
    val mm = arr("precipitation")
    val wind = arr("wind_speed_10m")
    val dir = arr("wind_direction_10m")
    return (0 until time.length()).map { i ->
        HourForecast(
            time = LocalDateTime.parse(time.getString(i)),
            tempC = Math.round(temp?.optDouble(i) ?: 0.0).toInt(),
            code = code?.optInt(i) ?: 0,
            isDay = (day?.optInt(i, 1) ?: 1) == 1,
            precipProb = prob?.takeUnless { it.isNull(i) }?.optInt(i),
            precipMm = (mm?.optDouble(i, 0.0) ?: 0.0).toFloat(),
            windKmh = Math.round(wind?.optDouble(i, 0.0) ?: 0.0).toInt(),
            windDir = dir?.optInt(i) ?: 0,
            apparentC = arr("apparent_temperature").doubleAt(i)?.let { Math.round(it).toInt() },
            gustKmh = arr("wind_gusts_10m").doubleAt(i)?.let { Math.round(it).toInt() },
            humidity = arr("relative_humidity_2m").doubleAt(i)?.let { Math.round(it).toInt() },
        )
    }
}

private fun JSONObject.optDoubleOrNull(name: String): Double? = if (!has(name) || isNull(name)) null else optDouble(name).takeIf { !it.isNaN() }

private fun JSONArray?.doubleAt(i: Int): Double? = if (this == null || i >= length() || isNull(i)) null else optDouble(i)

private fun JSONArray?.timeAt(i: Int): LocalTime? =
    if (this == null || i >= length() || isNull(i)) null else runCatching { LocalDateTime.parse(getString(i)).toLocalTime() }.getOrNull()

/** Analiza la respuesta de https://geocoding-api.open-meteo.com/v1/search. */
fun parsePlaces(json: String): List<Place> {
    val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
    return (0 until results.length()).map { i ->
        val r = results.getJSONObject(i)
        Place(
            name = r.getString("name"),
            region = r.optString("admin1").takeIf { it.isNotBlank() },
            country = r.optString("country").takeIf { it.isNotBlank() },
            lat = r.getDouble("latitude"),
            lon = r.getDouble("longitude"),
        )
    }
}
