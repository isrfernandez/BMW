package com.cockpitf30.nav

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Resumen de un artículo de Wikipedia, con su foto pequeña si la tiene. */
data class WikiSummary(
    val title: String,
    val extract: String,
    /** JPEG/PNG de la miniatura (unos 320 px), ya descargada. */
    val image: ByteArray? = null,
)

/**
 * Resúmenes de Wikipedia para los puntos turísticos: con la etiqueta «wikipedia» de OSM, o con la
 * de Wikidata se busca el artículo en español. Gratis y sin clave. Se guardan en memoria.
 */
object Wiki {
    private const val UA = "CockpitF30/1.0 (Android head unit)"
    private val cache = HashMap<String, WikiSummary?>()

    @Synchronized fun cached(p: Poi): WikiSummary? = cache[p.id]
    @Synchronized fun isCached(p: Poi) = cache.containsKey(p.id)

    /** Descarga (bloqueante, llamar en IO) el resumen del punto; null si no hay artículo. */
    fun load(p: Poi): WikiSummary? {
        if (isCached(p)) return cached(p)
        val s = runCatching {
            val (lang, title) = article(p) ?: return@runCatching null
            summary(lang, title)
        }.getOrNull()
        synchronized(this) { cache[p.id] = s }
        return s
    }

    /** («es», «Alcázar de Segovia») a partir de las etiquetas del punto. */
    private fun article(p: Poi): Pair<String, String>? {
        p.wikipedia?.let { parseTag(it) }?.let { if (it.first == "es") return it }
        p.wikidata?.let { id -> esTitle(id)?.let { return "es" to it } }
        return p.wikipedia?.let { parseTag(it) }
    }

    /** «es:Alcázar de Segovia» → (es, Alcázar de Segovia). */
    fun parseTag(tag: String): Pair<String, String>? {
        val i = tag.indexOf(':')
        if (i !in 2..3) return null
        val title = tag.substring(i + 1).trim()
        return if (title.isEmpty()) null else tag.substring(0, i).lowercase() to title
    }

    private fun esTitle(qid: String): String? {
        val json = get("https://www.wikidata.org/w/api.php?action=wbgetentities&props=sitelinks&sitefilter=eswiki&format=json&ids=" + URLEncoder.encode(qid, "UTF-8"))
            ?: return null
        return JSONObject(json).optJSONObject("entities")?.optJSONObject(qid)?.optJSONObject("sitelinks")?.optJSONObject("eswiki")?.optString("title")?.ifBlank { null }
    }

    private fun summary(lang: String, title: String): WikiSummary? {
        val path = URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20").replace("%2F", "/")
        val json = get("https://$lang.wikipedia.org/api/rest_v1/page/summary/$path") ?: return null
        return parseSummary(json)?.let { (t, text, img) ->
            WikiSummary(t, text, img?.let { url -> runCatching { bytes(url) }.getOrNull() })
        }
    }

    /** (título, texto, url de la miniatura) de la respuesta REST «page/summary». */
    fun parseSummary(json: String): Triple<String, String, String?>? {
        val o = JSONObject(json)
        if (o.optString("type") == "disambiguation") return null
        val extract = o.optString("extract").trim().ifEmpty { return null }
        val img = o.optJSONObject("thumbnail")?.optString("source")?.ifBlank { null }
        return Triple(o.optString("title"), extract, img)
    }

    /**
     * Las primeras frases de [text] sin pasar de [max] letras (para decirlo en voz alta). Siempre
     * acaba en un final de frase: si la primera ya es muy larga se corta en la última coma o punto y
     * coma y se cierra con punto. Antes se cortaba en una palabra cualquiera con «…», y en voz alta
     * sonaba a que el asistente se callaba a media explicación.
     */
    fun firstSentences(text: String, max: Int = 300): String {
        // Quita lo que va entre paréntesis (pronunciación, fechas…), que suena mal leído.
        val clean = text.replace(Regex("\\s*\\([^)]*\\)"), "").replace(Regex("\\s*\\[[^\\]]*\\]"), "")
            .replace(Regex("\\s+"), " ").replace(" ,", ",").trim()
        val sentences = Regex("(?<=[.!?])\\s+(?=[A-ZÁÉÍÓÚÑ¿¡])").split(clean)
        val sb = StringBuilder()
        for (s in sentences) {
            if (sb.isNotEmpty() && sb.length + s.length + 1 > max) break
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(s)
        }
        if (sb.length <= max + 60) return close(sb.toString())
        // Una sola frase larguísima: hasta la última pausa natural antes del límite.
        val head = sb.substring(0, max + 60)
        val cut = maxOf(head.lastIndexOf(", "), head.lastIndexOf("; "), head.lastIndexOf(": "))
        val part = if (cut >= max / 3) head.substring(0, cut) else head.substringBeforeLast(' ')
        return close(part.trimEnd(',', ';', ':', ' ', '-', '—'))
    }

    private fun close(s: String) = if (s.isEmpty() || s.last() in ".!?…") s else "$s."

    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000; c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json")
        return try {
            if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun bytes(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000; c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", UA)
        return try {
            if (c.responseCode !in 200..299) null else c.inputStream.use { it.readBytes() }.takeIf { it.size < 2_000_000 }
        } finally {
            c.disconnect()
        }
    }
}
