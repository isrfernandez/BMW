package com.cockpitf30.fuel

import com.cockpitf30.nav.GeoPoint
import com.cockpitf30.nav.RouteTracker
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/**
 * Una estación de servicio del listado del Ministerio. [brand] es el rótulo (la marca que se ve
 * en la marquesina: REPSOL, CEPSA, BALLENOIL…); el listado no trae otro nombre de la estación,
 * así que se identifica por la dirección y el municipio.
 */
data class FuelStation(
    val id: String,
    val brand: String,
    val address: String,
    val town: String,
    val lat: Double,
    val lon: Double,
    val hours: String,
    /** €/l; null si la estación no lo vende. */
    val diesel: Double?,
    val gasoline95: Double?,
) {
    val point get() = GeoPoint(lat, lon)
}

/** Una estación con su distancia en línea recta a la posición actual. */
data class NearStation(val station: FuelStation, val meters: Double)

object FuelStations {
    /**
     * Las [limit] estaciones más cercanas a [here], de la más cercana a la más lejana. Primero se
     * descartan las que están fuera de un recuadro de ±[boxDeg] grados (unos 55 km), que es lo
     * que hace que recorrer las ~12 000 de España sea instantáneo; si no queda ninguna, se mira todo.
     */
    fun nearest(stations: List<FuelStation>, here: GeoPoint, limit: Int = 40, boxDeg: Double = 0.5): List<NearStation> {
        fun near(list: List<FuelStation>) = list.map { NearStation(it, RouteTracker.distance(here, it.point)) }
            .sortedBy { it.meters }.take(limit)
        val inBox = stations.filter { kotlin.math.abs(it.lat - here.lat) < boxDeg && kotlin.math.abs(it.lon - here.lon) < boxDeg }
        return if (inBox.isNotEmpty()) near(inBox) else near(stations)
    }

    /**
     * Lee la respuesta de EstacionesTerrestres del Ministerio (precios con coma decimal). Los
     * nombres de los campos llevan tildes y espacios; se buscan sin tildes ni mayúsculas para
     * no depender de cómo vengan codificados.
     */
    fun parse(json: String): List<FuelStation> {
        val root = JSONObject(json)
        val list = root.optJSONArray("ListaEESSPrecio") ?: error("Respuesta sin ListaEESSPrecio")
        val out = ArrayList<FuelStation>(list.length())
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val f = HashMap<String, String>()
            for (k in o.keys()) f[norm(k)] = o.optString(k).trim()
            val lat = number(f["latitud"]) ?: continue
            val lon = number(f["longitud (wgs84)"] ?: f.entries.firstOrNull { it.key.startsWith("longitud") }?.value) ?: continue
            out += FuelStation(
                id = f["ideess"].orEmpty(),
                brand = f["rotulo"].orEmpty().ifBlank { "Estación" },
                address = f["direccion"].orEmpty(),
                town = (f["municipio"] ?: f["localidad"]).orEmpty(),
                lat = lat,
                lon = lon,
                hours = f["horario"].orEmpty(),
                diesel = number(f["precio gasoleo a"]),
                gasoline95 = number(f["precio gasolina 95 e5"]),
            )
        }
        return out
    }

    /** Fecha del listado ("05/11/2026 21:15:30"), tal cual la manda el Ministerio. */
    fun date(json: String): String? = runCatching { JSONObject(json).optString("Fecha").ifBlank { null } }.getOrNull()

    private fun number(s: String?): Double? = s?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it != 0.0 }

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace("\\p{M}".toRegex(), "").lowercase(Locale.ROOT).trim()

    /** Formato compacto para la caché en disco: una estación por línea, campos separados por tabuladores. */
    fun encode(list: List<FuelStation>): String = buildString {
        for (s in list) {
            listOf(s.id, s.brand, s.address, s.town, s.lat, s.lon, s.hours, s.diesel ?: "", s.gasoline95 ?: "")
                .joinTo(this, "\t") { it.toString().replace('\t', ' ').replace('\n', ' ') }
            append('\n')
        }
    }

    fun decode(text: String): List<FuelStation> = text.lineSequence().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size < 9) return@mapNotNull null
        FuelStation(
            p[0], p[1], p[2], p[3], p[4].toDoubleOrNull() ?: return@mapNotNull null, p[5].toDoubleOrNull() ?: return@mapNotNull null,
            p[6], p[7].toDoubleOrNull(), p[8].toDoubleOrNull(),
        )
    }.toList()

    /** "850 m", "1,2 km", "14 km". */
    fun formatDistance(meters: Double): String = when {
        meters < 1000 -> "${(meters / 10).toInt() * 10} m"
        meters < 10_000 -> String.format(Locale("es", "ES"), "%.1f km", meters / 1000)
        else -> "${(meters / 1000).toInt()} km"
    }

    /** "1,459" (€/l con tres decimales, como en las gasolineras). */
    fun formatPrice(p: Double): String = String.format(Locale("es", "ES"), "%.3f", p)
}
