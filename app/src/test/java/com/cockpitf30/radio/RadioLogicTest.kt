package com.cockpitf30.radio

import com.cockpitf30.assistant.Command
import com.cockpitf30.assistant.VoiceCommands
import com.cockpitf30.ui.screens.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioLogicTest {
    @Test
    fun formatsFm() {
        assertEquals("102.3", RadioLogic.formatFm(10230))
        assertEquals("87.5", RadioLogic.formatFm(8750))
        assertEquals("98.05", RadioLogic.formatFm(9805))
    }

    @Test
    fun decodesVendorCompositeFrequency() {
        // banda 0 (FM), índice 3, 102,3 MHz
        assertEquals(0 to 10230, RadioLogic.decodeComposite((0 shl 24) or (3 shl 16) or 10230))
        assertEquals(2 to 999, RadioLogic.decodeComposite((2 shl 24) or 999))
    }

    @Test
    fun stepsWrapAroundTheBand() {
        assertEquals(10240, RadioLogic.stepFm(10230, 1))
        assertEquals(RadioLogic.FM_MIN, RadioLogic.stepFm(10800, 1))
        assertEquals(RadioLogic.FM_MAX, RadioLogic.stepFm(8750, -1))
    }

    @Test
    fun parsesVendorSavedList() {
        val b = mapOf("count" to 3, "freq0" to 9050, "name0" to "RNE", "freq1" to 10230, "name1" to null, "freq2" to 120)
        assertEquals(listOf(FmStation(9050, "RNE"), FmStation(10230, "")), RadioLogic.parseSavedList { b[it] })
    }

    @Test
    fun picksTheLeanestDecentVariantPerStation() {
        val json = """[
          {"stationuuid":"a","name":"Cadena SER","url_resolved":"http://ser/128.mp3","codec":"MP3","bitrate":128,"lastcheckok":1},
          {"stationuuid":"b","name":"Los40","url_resolved":"http://40/aac","codec":"AAC","bitrate":64,"lastcheckok":1},
          {"stationuuid":"c","name":"CADENA SER ","url_resolved":"http://ser/48.aac","codec":"AAC+","bitrate":48,"lastcheckok":1},
          {"stationuuid":"d","name":"Rota","url_resolved":"http://x","codec":"MP3","bitrate":128,"lastcheckok":0},
          {"stationuuid":"e","name":"Cadena SER","url_resolved":"http://ser/16","codec":"AAC","bitrate":16,"lastcheckok":1}
        ]"""
        val list = RadioLogic.pickStations(json)
        assertEquals(listOf("c", "b"), list.map { it.id })
        assertEquals(21, list.first().mbPerHour)
    }

    @Test
    fun favoritesRoundTrip() {
        val s = listOf(StreamStation("1", "Onda Cero", "http://oc", "AAC", 48))
        assertEquals(s, RadioLogic.favoritesFromJson(RadioLogic.favoritesToJson(s)))
        assertEquals(listOf(FmStation(9050, "RNE")), RadioLogic.fmFromJson(RadioLogic.fmToJson(listOf(FmStation(9050, "RNE")))))
    }

    @Test
    fun matchesSpokenNames() {
        val list = listOf("Cadena SER", "LOS40", "Radio Clásica")
        assertEquals("LOS40", RadioLogic.matchName("los 40", list) { it })
        assertEquals("Radio Clásica", RadioLogic.matchName("clasica", list) { it })
        assertNull(RadioLogic.matchName("onda cero", list) { it })
    }

    @Test
    fun voiceCommands() {
        assertEquals(Command.RadioPlay(null), VoiceCommands.parse("pon la radio"))
        assertEquals(Command.RadioPlay("cadena ser"), VoiceCommands.parse("pon la radio cadena ser"))
        assertEquals(Command.RadioPlay("los 40"), VoiceCommands.parse("pon la emisora los 40"))
        assertEquals(Command.RadioPlay("102 3"), VoiceCommands.parse("sintoniza el 102.3"))
        assertEquals(Command.RadioStop, VoiceCommands.parse("apaga la radio"))
        assertEquals(Command.RadioPlay("cadena cien"), VoiceCommands.parse("pon en la radio cadena cien"))
        assertEquals(Command.RadioPlay("los cuarenta principales"), VoiceCommands.parse("pon la radio los cuarenta principales"))
        assertEquals(Command.RadioNext(1), VoiceCommands.parse("siguiente emisora"))
        assertEquals(Command.Open(Screen.RADIO), VoiceCommands.parse("abre la radio"))
    }

    @Test
    fun volumeAlwaysComesBackToTheBase() {
        assertEquals(0.7f, RadioLogic.effectiveVolume(70, AssistantActivity.IDLE, false), 1e-4f)
        // Escuchando gana a cualquier otra bajada; hablando y Maps, la más fuerte.
        assertEquals(0.7f * RadioLogic.DUCK_LISTENING, RadioLogic.effectiveVolume(70, AssistantActivity.LISTENING, true), 1e-4f)
        assertEquals(0.7f * RadioLogic.DUCK_SPEAKING, RadioLogic.effectiveVolume(70, AssistantActivity.SPEAKING, true), 1e-4f)
        assertEquals(0.7f * RadioLogic.DUCK_NAV, RadioLogic.effectiveVolume(70, AssistantActivity.IDLE, true), 1e-4f)
        assertEquals(0.7f, RadioLogic.effectiveVolume(70, AssistantActivity.IDLE, false), 1e-4f)
        assertEquals(1f, RadioLogic.effectiveVolume(150, AssistantActivity.IDLE, false), 1e-4f)
    }

    @Test
    fun volumeByVoice() {
        assertEquals(Command.RadioVolume(delta = 10), VoiceCommands.parse("sube la radio"))
        assertEquals(Command.RadioVolume(delta = -10), VoiceCommands.parse("baja la radio"))
        assertEquals(Command.RadioVolume(set = 40), VoiceCommands.parse("pon el volumen de la radio al 40"))
        assertEquals(Command.RadioVolume(set = 30), VoiceCommands.parse("volumen de la radio treinta"))
    }

    @Test
    fun spokenNumbersBecomeDigits() {
        assertEquals("cadena 100", RadioLogic.digitize("cadena cien"))
        assertEquals("los 40 principales", RadioLogic.digitize("los cuarenta principales"))
        assertEquals("98.3", RadioLogic.digitize("noventa y ocho coma tres"))
        assertEquals("102.5", RadioLogic.digitize("ciento dos punto cinco"))
        assertEquals("cadena ser", RadioLogic.digitize("Cadena SER"))
    }

    @Test
    fun matchesStationsSaidWithWords() {
        val list = listOf("Cadena SER", "LOS40", "Cadena 100", "Cadena Dial")
        assertEquals("Cadena 100", RadioLogic.matchName("cadena cien", list) { it })
        assertEquals("LOS40", RadioLogic.matchName("los cuarenta principales", list) { it })
        assertEquals("LOS40", RadioLogic.matchName("los cuarenta", list) { it })
        assertEquals("Cadena SER", RadioLogic.matchName("cadena ser", list) { it })
        assertEquals("Los 40 Principales", RadioLogic.matchName("los cuarenta", listOf("Los 40 Principales")) { it })
    }

    @Test
    fun searchVariantsGetShorter() {
        assertEquals(listOf("los 40 principales", "los40principales", "los 40", "los40"), RadioLogic.searchVariants("los cuarenta principales"))
        assertEquals(listOf("cadena 100", "cadena100", "cadena"), RadioLogic.searchVariants("cadena cien"))
    }
}
