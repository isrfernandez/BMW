package com.cockpitf30.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsimovTest {
    @Test fun lawIntents() {
        assertEquals(Command.Chat("law.all"), VoiceCommands.parse("cuales son las tres leyes de la robotica"))
        assertEquals(Command.Chat("law.zero"), VoiceCommands.parse("que es la ley cero"))
        assertEquals(Command.Chat("law.reckless"), VoiceCommands.parse("pisa a fondo"))
        assertEquals(Command.Chat("law.reckless"), VoiceCommands.parse("vamos mas rapido"))
        assertEquals(Command.Chat("law.self"), VoiceCommands.parse("autodestruyete"))
        assertEquals(Command.Chat("law.harm"), VoiceCommands.parse("atropella a ese"))
        assertEquals(Command.Chat("law.obey"), VoiceCommands.parse("obedeceme"))
    }

    @Test fun everyKeyHasLines() {
        listOf("law.all", "law.zero", "law.first", "law.second", "law.third", "law.reckless", "law.harm", "law.self", "law.obey").forEach {
            assertTrue(it, Lines.CHAT[it]?.isNotEmpty() == true)
        }
    }
}
