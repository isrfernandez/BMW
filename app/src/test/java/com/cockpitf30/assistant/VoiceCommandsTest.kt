package com.cockpitf30.assistant

import com.cockpitf30.ui.screens.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandsTest {

    @Test
    fun wakeWordInItsSpokenForms() {
        assertEquals("", VoiceCommands.afterWakeWord("Hola Athos"))
        assertEquals("", VoiceCommands.afterWakeWord("hola atos"))
        assertEquals("", VoiceCommands.afterWakeWord("hola a tos"))
        assertEquals("", VoiceCommands.afterWakeWord("ola atos"))
        assertEquals("", VoiceCommands.afterWakeWord("oye Athos"))
        assertEquals("abre las gasolineras", VoiceCommands.afterWakeWord("hola a tos abre las gasolineras"))
        assertEquals("que tiempo hace", VoiceCommands.afterWakeWord("¡Hola, Athos! ¿Qué tiempo hace?"))
    }

    @Test
    fun wakeGrammarPhrasesAreAccepted() {
        Regex("\"([^\"]+)\"").findAll(VoiceCommands.WAKE_GRAMMAR).map { it.groupValues[1] }.filter { it != "[unk]" }.forEach {
            assertEquals(it, "", VoiceCommands.afterWakeWord(it))
        }
    }

    @Test
    fun leftoverBrandIsStrippedFromTheCommand() {
        assertEquals("abre las gasolineras", VoiceCommands.stripWakeWords("atos abre las gasolineras"))
        assertEquals("abre las gasolineras", VoiceCommands.stripWakeWords("hola a tos abre las gasolineras"))
        assertEquals("", VoiceCommands.stripWakeWords("athos"))
        assertEquals("a casa", VoiceCommands.stripWakeWords("a casa"))
        assertEquals("que tiempo hace", VoiceCommands.stripWakeWords("qué tiempo hace"))
    }

    @Test
    fun noWakeWordWithoutTheBrand() {
        assertNull(VoiceCommands.afterWakeWord("hola qué tal"))
        assertNull(VoiceCommands.afterWakeWord("abre las gasolineras"))
        assertNull(VoiceCommands.afterWakeWord("hola me voy a casa"))
        assertNull(VoiceCommands.afterWakeWord(""))
    }

    @Test
    fun opensScreens() {
        assertEquals(Command.Open(Screen.FUEL), VoiceCommands.parse("abre las gasolineras"))
        assertEquals(Command.Open(Screen.VEHICLE), VoiceCommands.parse("muestra el vehículo"))
        assertEquals(Command.Open(Screen.TRIP), VoiceCommands.parse("datos de viaje"))
        assertEquals(Command.Open(Screen.WEATHER), VoiceCommands.parse("el tiempo"))
        assertEquals(Command.Open(Screen.PHONE), VoiceCommands.parse("teléfono"))
        assertEquals(Command.Open(Screen.APPS), VoiceCommands.parse("abre las aplicaciones"))
        assertEquals(Command.Open(Screen.HOME), VoiceCommands.parse("vuelve al inicio"))
        assertEquals(Command.Music, VoiceCommands.parse("pon música"))
        assertEquals(Command.Maps, VoiceCommands.parse("abre el mapa"))
    }

    @Test
    fun answersQuestions() {
        assertEquals(Command.Info(InfoTopic.WEATHER), VoiceCommands.parse("¿Qué tiempo hace?"))
        assertEquals(Command.Info(InfoTopic.WEATHER), VoiceCommands.parse("va a llover"))
        assertEquals(Command.Info(InfoTopic.RANGE), VoiceCommands.parse("cuánta autonomía me queda"))
        assertEquals(Command.Info(InfoTopic.RANGE), VoiceCommands.parse("cuanto gasoil tengo"))
        assertEquals(Command.Info(InfoTopic.FUEL_NEAR), VoiceCommands.parse("dónde está la gasolinera más cercana"))
        assertEquals(Command.Info(InfoTopic.TYRES), VoiceCommands.parse("qué presión tienen los neumáticos"))
        assertEquals(Command.Info(InfoTopic.TIME), VoiceCommands.parse("qué hora es"))
        assertEquals(Command.Info(InfoTopic.DOORS), VoiceCommands.parse("hay alguna puerta abierta"))
    }

    @Test
    fun navigationAndGoogle() {
        assertEquals(Command.Navigate("plaza mayor de madrid"), VoiceCommands.parse("llévame a la plaza mayor de madrid"))
        assertEquals(Command.Info(InfoTopic.FUEL_NEAR), VoiceCommands.parse("llévame a la gasolinera más cercana"))
        assertEquals(Command.AskGoogle("cuanto mide la torre eiffel"), VoiceCommands.parse("pregunta a Google cuánto mide la torre Eiffel"))
        assertEquals(Command.AskGoogle(null), VoiceCommands.parse("abre Google"))
        // Antes acababa en restaurantes cercanos («cenar»).
        assertEquals(Command.AskGoogle("ideas para cenar"), VoiceCommands.parse("pregúntale a Google ideas para cenar"))
        assertEquals(Command.AskGoogle("donde comer bien en madrid"), VoiceCommands.parse("busca en google dónde comer bien en Madrid"))
        assertEquals(Command.Cancel, VoiceCommands.parse("nada"))
        assertEquals(Command.Help, VoiceCommands.parse("qué puedes hacer"))
        assertEquals(Command.Joke, VoiceCommands.parse("cuéntame un chiste"))
    }

    @Test
    fun callContact() {
        assertEquals(Command.Call("manuel"), VoiceCommands.parse("llama a Manuel"))
        assertEquals(Command.Call("manuel garcia"), VoiceCommands.parse("Llama a Manuel García"))
        assertEquals(Command.Call("mama"), VoiceCommands.parse("llamar a mamá"))
        assertEquals(Command.Open(Screen.PHONE), VoiceCommands.parse("teléfono"))
    }

    @Test
    fun driveModes() {
        assertEquals(Command.Mode(com.cockpitf30.ui.theme.DriveMode.SPORT), VoiceCommands.parse("modo sport"))
        assertEquals(Command.Mode(com.cockpitf30.ui.theme.DriveMode.NIGHT), VoiceCommands.parse("pon el modo noche"))
        assertEquals(Command.Daylight(com.cockpitf30.ui.theme.DayTheme.ON), VoiceCommands.parse("pon el modo diurno"))
        assertEquals(Command.Daylight(com.cockpitf30.ui.theme.DayTheme.OFF), VoiceCommands.parse("quita el modo diurno"))
        assertEquals(Command.Daylight(com.cockpitf30.ui.theme.DayTheme.OFF), VoiceCommands.parse("modo oscuro"))
        assertEquals(Command.Open(Screen.PERFORMANCE), VoiceCommands.parse("abre rendimiento"))
    }

    @Test
    fun bargeInWords() {
        assertTrue(VoiceCommands.isStopWord("basta"))
        assertTrue(VoiceCommands.isStopWord("Cállate"))
        assertTrue(!VoiceCommands.isStopWord("para descansar"))
        assertEquals(VoiceCommands.Barge.STOP, VoiceCommands.bargeAction("para"))
        assertEquals(VoiceCommands.Barge.STOP, VoiceCommands.bargeAction("Detente"))
        assertEquals(VoiceCommands.Barge.STOP, VoiceCommands.bargeAction("cancela"))
        assertEquals(VoiceCommands.Barge.PAUSE, VoiceCommands.bargeAction("pausa"))
        assertEquals(VoiceCommands.Barge.HOLD, VoiceCommands.bargeAction("un momento"))
        // «a ver», «oye» y «perdona» salen en sus propias frases: ya no cortan.
        assertNull(VoiceCommands.bargeAction("a ver"))
        assertNull(VoiceCommands.bargeAction("oye"))
        assertEquals(VoiceCommands.Barge.RESUME, VoiceCommands.bargeAction("continúa"))
        assertEquals(Command.Resume, VoiceCommands.parse("sigue"))
        assertEquals("para", VoiceCommands.bargePhraseAtEnd("[unk] [unk] para"))
        assertEquals("un momento", VoiceCommands.bargePhraseAtEnd("[unk] un momento"))
        assertEquals("hola atos", VoiceCommands.bargePhraseAtEnd("hola atos"))
        assertNull(VoiceCommands.bargePhraseAtEnd("[unk] [unk]"))
        // Las palabras de relleno absorben su propia voz y no son órdenes.
        assertTrue(VoiceCommands.BARGE_GRAMMAR.contains("\"kilómetros\""))
        assertNull(VoiceCommands.bargePhraseAtEnd("gira a la derecha"))
        assertEquals("para", VoiceCommands.bargePhraseAtEnd("vale para"))
        assertEquals(Command.Pause, VoiceCommands.parse("pausa"))
        assertEquals(Command.Cancel, VoiceCommands.parse("para"))
        assertTrue(VoiceCommands.BARGE_GRAMMAR.contains("\"un momento\""))
        assertTrue(VoiceCommands.BARGE_GRAMMAR.contains("\"hola atos\""))
        assertTrue(VoiceCommands.BARGE_GRAMMAR.contains("\"basta\""))
    }

    @Test
    fun mapsGuidanceQuestions() {
        for (t in listOf("¿cuál es el siguiente giro?", "¿cuánto falta?", "¿a qué hora llego?", "próxima indicación", "¿por dónde sigo?")) {
            assertEquals(t, Command.Info(InfoTopic.GUIDANCE), VoiceCommands.parse(t))
        }
        // Lo de antes sigue igual.
        assertEquals(Command.Info(InfoTopic.TIME), VoiceCommands.parse("¿qué hora es?"))
        assertEquals(Command.Info(InfoTopic.RANGE), VoiceCommands.parse("¿cuánto queda de autonomía?"))
    }

    @Test
    fun closeMapsEndsNavigation() {
        listOf("cierra el mapa", "cierra google maps", "cierra maps", "quita la navegación", "cancela la ruta", "termina la navegación", "sal de google maps")
            .forEach { assertEquals(it, Command.CloseMaps, VoiceCommands.parse(it)) }
        assertEquals(Command.Maps, VoiceCommands.parse("abre el mapa"))
        assertEquals(Command.Cancel, VoiceCommands.parse("cancela"))
    }

    @Test
    fun cancelHeardInItsManyFormsStopsTheAssistant() {
        listOf("cancela", "cancele", "cancelo", "cansela", "cancelar", "cancélalo", "vale cancela").forEach {
            val hit = VoiceCommands.bargePhraseAtEnd(it) ?: VoiceCommands.fuzzyStop(it)
            assertEquals(it, VoiceCommands.Barge.STOP, hit?.let(VoiceCommands::bargeAction))
        }
        assertNull(VoiceCommands.fuzzyStop("el castillo de alcalá"))
    }
}
