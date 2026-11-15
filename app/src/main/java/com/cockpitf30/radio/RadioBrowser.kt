package com.cockpitf30.radio

import android.net.Uri
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * Listado de emisoras de Radio-Browser (api.radio-browser.info, gratis y sin clave). Se pide solo
 * el JSON (unos pocos KB); luego el audio va directo de la emisora al reproductor.
 */
object RadioBrowser {
    private const val TAG = "RadioBrowser"
    // Servidores espejo: se prueba el siguiente si uno no contesta.
    private val SERVERS = listOf("de1.api.radio-browser.info", "de2.api.radio-browser.info", "fi1.api.radio-browser.info", "nl1.api.radio-browser.info")

    /** Las más escuchadas de España. */
    fun popular(country: String = "ES", limit: Int = 300): List<StreamStation> =
        RadioLogic.pickStations(get("/json/stations/search?countrycode=$country&hidebroken=true&order=clickcount&reverse=true&limit=$limit"), limit = 80)

    /** Búsqueda por nombre (en España primero; si no hay nada, en todo el mundo). */
    fun search(name: String, country: String = "ES"): List<StreamStation> {
        val q = Uri.encode(name.trim())
        val local = RadioLogic.pickStations(get("/json/stations/search?name=$q&countrycode=$country&hidebroken=true&order=clickcount&reverse=true&limit=60"), 30)
        if (local.isNotEmpty()) return local
        return RadioLogic.pickStations(get("/json/stations/search?name=$q&hidebroken=true&order=clickcount&reverse=true&limit=60"), 30)
    }

    /** Aviso de escucha que pide Radio-Browser para su ranking (no importa si falla). */
    fun click(id: String) {
        if (id.isBlank()) return
        runCatching { get("/json/url/$id") }
    }

    private fun get(path: String): String {
        var last: Exception? = null
        for (host in SERVERS) {
            try {
                val conn = URL("https://$host$path").openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "CockpitF30/0.9")
                try {
                    if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                    return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "$host: ${e.message}")
                last = e
            }
        }
        throw last ?: IllegalStateException("sin servidores")
    }
}
