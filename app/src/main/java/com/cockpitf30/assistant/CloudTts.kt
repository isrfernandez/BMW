package com.cockpitf30.assistant

import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Voz en la nube: Google Cloud Text-to-Speech con las voces «Chirp 3 HD» en español, que entonan
 * las preguntas como una persona. Necesita una clave de API de Google Cloud (la del conductor, en
 * Ajustes) y datos; si falla, el asistente sigue con la voz de la radio.
 */
object CloudTts {
    /** Voces es-ES Chirp 3 HD: las cuatro primeras, de hombre. */
    val VOICES = listOf("Charon", "Orus", "Fenrir", "Puck", "Kore", "Aoede", "Leda", "Zephyr")
    val MALE = setOf("Charon", "Orus", "Fenrir", "Puck")
    const val DEFAULT_VOICE = "Charon"
    private const val ENDPOINT = "https://texttospeech.googleapis.com/v1/text:synthesize"

    /** Último error (para Ajustes/Diagnóstico). */
    @Volatile var lastError: String? = null

    fun fullName(voice: String) = "es-ES-Chirp3-HD-$voice"

    /** El cuerpo JSON de la petición. Puro, para tests. */
    fun request(text: String, voice: String, rate: Float): String = JSONObject()
        .put("input", JSONObject().put("text", text))
        .put("voice", JSONObject().put("languageCode", "es-ES").put("name", fullName(voice)))
        .put("audioConfig", JSONObject().put("audioEncoding", "LINEAR16").put("sampleRateHertz", 24000)
            .put("speakingRate", rate.coerceIn(0.25f, 2f).toDouble()))
        .toString()

    /** Genera [text] en un WAV en [out]. false si no hay red, la clave es mala, etc. (bloqueante). */
    fun synthesize(key: String, text: String, voice: String, rate: Float, out: File): Boolean = runCatching {
        val conn = URL("$ENDPOINT?key=$key").openConnection() as HttpURLConnection
        conn.connectTimeout = 3_000
        conn.readTimeout = 6_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.outputStream.use { it.write(request(text, voice, rate).toByteArray()) }
        val code = conn.responseCode
        if (code != 200) {
            val msg = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
            lastError = "HTTP $code" + (msg?.let { runCatching { " " + JSONObject(it).getJSONObject("error").getString("message") }.getOrNull() } ?: "")
            conn.disconnect()
            return false
        }
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        val audio = Base64.decode(JSONObject(body).getString("audioContent"), Base64.DEFAULT)
        out.writeBytes(audio)
        lastError = null
        audio.size > 44
    }.getOrElse { lastError = it.javaClass.simpleName + (it.message?.let { m -> ": $m" } ?: ""); false }
}
