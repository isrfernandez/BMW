package com.cockpitf30.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CloudTtsTest {
    @Test fun requestUsesSpanishChirpVoiceAndWav() {
        val j = JSONObject(CloudTts.request("¿Te llevo a casa?", "Charon", 1.1f))
        assertEquals("¿Te llevo a casa?", j.getJSONObject("input").getString("text"))
        assertEquals("es-ES-Chirp3-HD-Charon", j.getJSONObject("voice").getString("name"))
        assertEquals("LINEAR16", j.getJSONObject("audioConfig").getString("audioEncoding"))
        assertEquals(1.1, j.getJSONObject("audioConfig").getDouble("speakingRate"), 0.01)
    }
}
