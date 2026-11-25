package com.cockpitf30.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationVoiceTest {
    @Test
    fun `ubicacion sola ensena el QR`() {
        assertEquals(Command.Parking(null), VoiceCommands.parse("ubicación"))
        assertEquals(Command.Parking(null), VoiceCommands.parse("mi ubicación"))
        assertEquals(Command.Parking(null), VoiceCommands.parse("enséñame la ubicación"))
    }

    @Test
    fun `compartir ubicacion abre compartir`() {
        assertEquals(Command.Parking(ParkingSend.SHARE), VoiceCommands.parse("compartir ubicación"))
        assertEquals(Command.Parking(ParkingSend.SHARE), VoiceCommands.parse("comparte la ubicación"))
        assertEquals(Command.Parking(ParkingSend.SHARE), VoiceCommands.parse("manda la ubicación"))
    }

    @Test
    fun `cortes fuertes mientras habla`() {
        assertEquals(setOf("cancela"), VoiceCommands.stopKinds("cancelar"))
        assertEquals(setOf("detente"), VoiceCommands.stopKinds("detente"))
    }
}

class UsersVoiceTest {
    @Test
    fun `usuarios abre la lista`() {
        for (p in listOf("usuarios", "abre usuarios", "los usuarios", "ajustes de usuarios", "qué usuarios hay", "cambiar de conductor", "conductores"))
            assertEquals(p, Command.ListUsers, VoiceCommands.parse(p))
    }
}
