package com.cockpitf30.radio

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** De dónde sale lo que suena. */
enum class RadioSource(val label: String) {
    /** Sintonizador FM de la propia radio (antena del coche, 0 MB). */
    FM("FM · antena"),
    /** Emisora por Internet (datos móviles). */
    INTERNET("Internet"),
}

/** Emisora de Internet (Radio-Browser). [bitrate] en kbps; 0 si no se sabe. */
data class StreamStation(
    val id: String,
    val name: String,
    val url: String,
    val codec: String = "",
    val bitrate: Int = 0,
    val favicon: String? = null,
    val tags: String = "",
    val country: String = "",
) {
    /** Megas por hora aproximados con su tasa (si se sabe). */
    val mbPerHour: Int? get() = bitrate.takeIf { it > 0 }?.let { it * 3600 / 8 / 1000 }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("url", url).put("codec", codec).put("bitrate", bitrate)
        .put("favicon", favicon ?: "").put("tags", tags).put("country", country)

    companion object {
        fun fromJson(o: JSONObject) = StreamStation(
            id = o.optString("id"), name = o.optString("name"), url = o.optString("url"),
            codec = o.optString("codec"), bitrate = o.optInt("bitrate"),
            favicon = o.optString("favicon").takeIf { it.isNotBlank() },
            tags = o.optString("tags"), country = o.optString("country"),
        )
    }
}

/** Emisora FM guardada. [freq] en unidades de 10 kHz, como el sintonizador (10230 = 102,3 MHz). */
data class FmStation(val freq: Int, val name: String = "")

object RadioLogic {
    const val FM_MIN = 8750
    const val FM_MAX = 10800

    /** 10230 -> "102.3" (o "102.35" si no cae en 100 kHz). */
    fun formatFm(freq: Int): String {
        val mhz = freq / 100
        val rest = freq % 100
        return if (rest % 10 == 0) "%d.%d".format(Locale.ROOT, mhz, rest / 10) else "%d.%02d".format(Locale.ROOT, mhz, rest)
    }

    /**
     * La frecuencia "compuesta" del sintonizador del fabricante: byte alto = banda (0 FM, 1 OIRT,
     * 2 AM), siguiente = índice de memoria, 16 bits bajos = frecuencia.
     */
    fun decodeComposite(v: Int): Pair<Int, Int> = ((v ushr 24) and 0xFF) to (v and 0xFFFF)

    /** Pasos de 50 kHz en Europa; se acota a la banda FM. */
    fun stepFm(freq: Int, steps: Int, step: Int = 10): Int {
        val f = freq + steps * step
        return when {
            f > FM_MAX -> FM_MIN
            f < FM_MIN -> FM_MAX
            else -> f
        }
    }

    /** Lista del fabricante en Bundle: count, freq0, name0, freq1, … (la sacamos como mapa plano). */
    fun parseSavedList(get: (String) -> Any?): List<FmStation> {
        val count = (get("count") as? Int) ?: return emptyList()
        return (0 until count.coerceAtMost(100)).mapNotNull { i ->
            val f = (get("freq$i") as? Int) ?: return@mapNotNull null
            // En la lista algunas placas guardan la frecuencia compuesta: nos quedamos con los 16 bits bajos.
            val freq = f and 0xFFFF
            if (freq !in FM_MIN..FM_MAX) null else FmStation(freq, (get("name$i") as? String)?.trim().orEmpty())
        }
    }

    /**
     * De la respuesta de Radio-Browser: una entrada por emisora (por nombre), quedándonos con la
     * variante que menos datos gasta sin bajar de calidad aceptable: AAC/AAC+ de 32 a 64 kbps
     * primero, luego lo más bajo que haya a partir de 32 kbps.
     */
    fun pickStations(json: String, limit: Int = 80): List<StreamStation> {
        val arr = JSONArray(json)
        val all = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }
            val name = o.optString("name").trim()
            if (url.isBlank() || name.isBlank() || o.optInt("lastcheckok", 1) == 0) return@mapNotNull null
            StreamStation(
                id = o.optString("stationuuid"), name = name.replace(Regex("\\s+"), " "), url = url,
                codec = o.optString("codec"), bitrate = o.optInt("bitrate"),
                favicon = o.optString("favicon").takeIf { it.startsWith("http") },
                tags = o.optString("tags"), country = o.optString("countrycode"),
            )
        }
        return all.groupBy { key(it.name) }.values.map { variants -> variants.minByOrNull(::dataCost)!! }
            // Se mantiene el orden de popularidad de la primera aparición.
            .sortedBy { s -> all.indexOfFirst { key(it.name) == key(s.name) } }
            .take(limit)
    }

    /** Menor = mejor: AAC de 32–64 kbps es lo ideal; sin tasa conocida, al final. */
    fun dataCost(s: StreamStation): Int {
        val aac = s.codec.contains("AAC", ignoreCase = true)
        val br = s.bitrate
        return when {
            br == 0 -> 5000
            br < 32 -> 3000 + br // demasiado baja: suena a lata
            aac && br <= 64 -> br
            aac -> 1000 + br
            else -> 2000 + br
        }
    }

    private fun key(name: String) = java.text.Normalizer.normalize(name.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
        .replace(Regex("[^a-z0-9]"), "")

    fun favoritesToJson(list: List<StreamStation>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

    fun favoritesFromJson(json: String?): List<StreamStation> = runCatching {
        val a = JSONArray(json ?: return emptyList())
        (0 until a.length()).map { StreamStation.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun fmToJson(list: List<FmStation>): String = JSONArray().apply { list.forEach { put(JSONObject().put("f", it.freq).put("n", it.name)) } }.toString()

    fun fmFromJson(json: String?): List<FmStation> = runCatching {
        val a = JSONArray(json ?: return emptyList())
        (0 until a.length()).map { a.getJSONObject(it).let { o -> FmStation(o.getInt("f"), o.optString("n")) } }
    }.getOrDefault(emptyList())

    /** Busca una emisora por lo que dijo el conductor («los 40», «cadena ser»). */
    fun <T> matchName(query: String, items: List<T>, name: (T) -> String): T? {
        val q = key(query)
        if (q.isBlank()) return null
        return items.firstOrNull { key(name(it)) == q }
            ?: items.firstOrNull { key(name(it)).startsWith(q) }
            ?: items.firstOrNull { key(name(it)).contains(q) }
    }
}
