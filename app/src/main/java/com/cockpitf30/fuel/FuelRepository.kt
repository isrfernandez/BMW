package com.cockpitf30.fuel

import android.content.Context
import com.cockpitf30.nav.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos
import kotlin.math.sin

data class FuelState(
    val stations: List<FuelStation> = emptyList(),
    /** Fecha de los precios según el Ministerio. */
    val dataDate: String? = null,
    val updatedAtMillis: Long = 0,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Estaciones de servicio de España con sus precios: datos abiertos del Ministerio (gratis, sin
 * clave). El listado completo (~12 000 estaciones) se descarga como mucho cada 12 horas y se guarda
 * en disco en un formato compacto, así la tarjeta funciona sin conexión con los últimos datos.
 */
object FuelRepository {
    private const val URL_ALL =
        "https://sedeaplicaciones.minetur.gob.es/ServiciosRESTCarburantes/PreciosCarburantes/EstacionesTerrestres/"
    private const val MAX_AGE_MS = 12 * 60 * 60 * 1000L

    private val _state = MutableStateFlow(FuelState())
    val state: StateFlow<FuelState> = _state.asStateFlow()
    private val lock = Mutex()
    private var cacheLoaded = false

    private fun cacheFile(context: Context) = File(context.filesDir, "gasolineras.tsv")
    private fun metaFile(context: Context) = File(context.filesDir, "gasolineras.meta")

    /** Carga la caché si hace falta y descarga el listado si es viejo (o si [force]). */
    suspend fun refresh(context: Context, force: Boolean = false) = lock.withLock {
        loadCache(context)
        val s = _state.value
        if (!force && s.stations.isNotEmpty() && System.currentTimeMillis() - s.updatedAtMillis < MAX_AGE_MS) return@withLock
        _state.update { it.copy(loading = true, error = null) }
        runCatching {
            withContext(Dispatchers.IO) {
                val json = httpGet(URL_ALL)
                val list = FuelStations.parse(json)
                if (list.isEmpty()) error("Listado vacío")
                val date = FuelStations.date(json)
                val now = System.currentTimeMillis()
                runCatching {
                    cacheFile(context).writeText(FuelStations.encode(list))
                    metaFile(context).writeText("$now\n${date.orEmpty()}")
                }
                FuelState(list, date, now)
            }
        }.onSuccess { _state.value = it }
            .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) } }
    }

    private suspend fun loadCache(context: Context) {
        if (cacheLoaded) return
        cacheLoaded = true
        withContext(Dispatchers.IO) {
            runCatching {
                val list = FuelStations.decode(cacheFile(context).readText())
                val meta = metaFile(context).readText().lines()
                _state.value = FuelState(list, meta.getOrNull(1)?.ifBlank { null }, meta[0].toLong())
            }
        }
    }

    /**
     * Gasolineras del modo Demo: si ya hay precios reales del Ministerio cerca de [center] (el
     * recorrido de ejemplo es en Madrid), esas; si no (sin Internet), las de [demoStations].
     */
    fun demoList(real: List<FuelStation>, center: GeoPoint): List<FuelStation> {
        val near = FuelStations.nearest(real, center, limit = 3)
        return if (near.size >= 3 && near.last().meters < 8_000) real else demoStations(center)
    }

    /** Modo Demo sin datos reales: unas estaciones inventadas alrededor de [center] (Madrid). */
    fun demoStations(center: GeoPoint): List<FuelStation> {
        val brands = listOf("REPSOL", "CEPSA", "BP", "GALP", "SHELL", "PLENOIL", "BALLENOIL", "PETRONOR", "REPSOL", "CEPSA", "BP", "GALP")
        val streets = listOf(
            "Paseo de la Castellana 89", "Calle de Serrano 120", "Calle de Velázquez 98", "Calle de Joaquín Costa 12", "Calle de Bravo Murillo 210",
            "Avenida de América 33", "Calle de Alcalá 300", "Calle de Arturo Soria 150", "Paseo de las Delicias 61", "Calle de Príncipe de Vergara 250",
            "Avenida de la Albufera 120", "Carretera de Castilla 5",
        )
        return brands.mapIndexed { i, b ->
            // Espiral: la primera a ~600 m, las demás cada vez más lejos.
            val r = 600.0 + i * i * 260.0
            val a = i * 2.4
            val dLat = r * cos(a) / 111_320.0
            val dLon = r * sin(a) / (111_320.0 * cos(Math.toRadians(center.lat)))
            FuelStation(
                "demo$i", b, streets[i], "Madrid", center.lat + dLat, center.lon + dLon, "L-D: 24H",
                1.489 + (i % 5) * 0.02, 1.559 + (i % 4) * 0.03,
            )
        }
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        // El listado completo pesa ~10 MB (Android lo pide comprimido con gzip, ~1,5 MB).
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "CockpitF30/0.9 (Android head unit; personal use)")
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
