package com.cockpitf30.assistant

import com.cockpitf30.ui.screens.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandsTest {

    @Test
    fun wakeWordInItsSpokenForms() {
        assertEquals("", VoiceCommands.afterWakeWord("Hola BMW"))
        assertEquals("", VoiceCommands.afterWakeWord("hola be eme uve doble"))
        assertEquals("", VoiceCommands.afterWakeWord("hola beme uve doble"))
        assertEquals("", VoiceCommands.afterWakeWord("ola me uve doble"))
        assertEquals("", VoiceCommands.afterWakeWord("hola be eme doble ve"))
        assertEquals("abre las gasolineras", VoiceCommands.afterWakeWord("hola be eme uve doble abre las gasolineras"))
        assertEquals("que tiempo hace", VoiceCommands.afterWakeWord("¡Hola, BMW! ¿Qué tiempo hace?"))
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
        assertEquals(Command.Cancel, VoiceCommands.parse("nada"))
        assertEquals(Command.Help, VoiceCommands.parse("qué puedes hacer"))
        assertEquals(Command.Unknown("cuentame un chiste"), VoiceCommands.parse("cuéntame un chiste"))
    }
}
