package com.cockpitf30.assistant

import com.cockpitf30.ui.screens.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ConversationTest {

    private fun Conversation.say(text: String, now: Long): Command = resolve(text, now).also { record(text, it, "ok", now) }

    @Test
    fun newConversationCommands() {
        assertEquals(Command.Joke, VoiceCommands.parse("cuéntame un chiste"))
        assertEquals(Command.Repeat, VoiceCommands.parse("¿qué has dicho?"))
        assertEquals(Command.Thanks, VoiceCommands.parse("muchas gracias"))
        assertEquals(Command.Yes, VoiceCommands.parse("sí"))
        assertEquals(Command.Another, VoiceCommands.parse("otra"))
        assertEquals(Command.GoThere, VoiceCommands.parse("llévame"))
        assertEquals(Command.GoThere, VoiceCommands.parse("vamos allí"))
        assertEquals(Command.Price, VoiceCommands.parse("¿cuánto cuesta?"))
        assertEquals(Command.WeatherDay(1), VoiceCommands.parse("¿va a llover mañana?"))
        assertEquals(Command.WeatherDay(2), VoiceCommands.parse("qué tiempo hará pasado mañana"))
        assertEquals(Command.SetName("Israel"), VoiceCommands.parse("me llamo Israel"))
        assertEquals(Command.AskName, VoiceCommands.parse("¿cómo me llamo?"))
        assertEquals(Command.Recap, VoiceCommands.parse("¿de qué hablábamos?"))
        assertEquals(Command.Chat("howareyou"), VoiceCommands.parse("¿qué tal estás?"))
        assertEquals(Command.Chat("tired"), VoiceCommands.parse("estoy cansado"))
        assertEquals(Command.Chat("fine"), VoiceCommands.parse("bien"))
        assertEquals(Command.Copilot(true), VoiceCommands.parse("activa el modo copiloto"))
        assertEquals(Command.Copilot(false), VoiceCommands.parse("desactiva el copiloto"))
        assertEquals(Command.Quiet, VoiceCommands.parse("cállate un rato"))
        // Lo de antes sigue igual.
        assertEquals(Command.Info(InfoTopic.WEATHER), VoiceCommands.parse("¿Qué tiempo hace?"))
        assertEquals(Command.Navigate("plaza mayor de madrid"), VoiceCommands.parse("llévame a la plaza mayor de madrid"))
        assertEquals(Command.Cancel, VoiceCommands.parse("no"))
        assertEquals(Command.Info(InfoTopic.FUEL_NEAR), VoiceCommands.parse("busca la gasolinera más cercana"))
        assertEquals(Command.Navigate("restaurante"), VoiceCommands.parse("búscame un restaurante"))
        assertTrue(VoiceCommands.parse("busca la") is Command.Unknown)
    }

    @Test
    fun followUpsUseTheLastTopic() {
        val c = Conversation()
        c.say("qué tiempo hace", 0)
        assertEquals(Command.WeatherDay(1), c.say("¿y mañana?", 10_000))
        assertEquals(Command.WeatherDay(2), c.say("otro", 20_000))

        c.say("cuéntame un chiste", 30_000)
        assertEquals(Command.Joke, c.say("otro", 40_000))
    }

    @Test
    fun stationsFollowUps() {
        val c = Conversation()
        c.setStations(5)
        c.say("gasolinera más cercana", 0)
        assertEquals(Command.Station(1, StationAction.DESCRIBE), c.resolve("la siguiente", 5_000))
        c.setStations(5, 1)
        c.record("la siguiente", Command.Station(1, StationAction.DESCRIBE), "ok", 5_000)
        assertEquals(Command.Station(1, StationAction.PRICE), c.resolve("¿cuánto cuesta?", 8_000))
        assertEquals(Command.Station(1, StationAction.GO), c.resolve("llévame", 9_000))
    }

    @Test
    fun answersToQuestions() {
        val c = Conversation()
        c.ask(Pending.Confirm(Command.Joke), 0)
        assertEquals(Command.Joke, c.resolve("sí", 2_000))
        // La pregunta se contesta una vez.
        assertEquals(Command.Yes, c.resolve("sí", 3_000))

        c.ask(Pending.Confirm(Command.Joke), 10_000)
        assertEquals(Command.No, c.resolve("no", 11_000))

        c.ask(Pending.ChooseContact(listOf("Manuel García", "Manuel Pérez")), 20_000)
        assertEquals(Command.Call("manuel perez"), c.resolve("el segundo", 21_000))
        c.ask(Pending.ChooseContact(listOf("Manuel García", "Manuel Pérez")), 30_000)
        assertEquals(Command.Call("manuel garcia"), c.resolve("Manuel García", 31_000))
    }

    @Test
    fun contextExpires() {
        val c = Conversation()
        c.say("cuéntame un chiste", 0)
        assertEquals(Command.Another, c.resolve("otro", Conversation.CONTEXT_MS + 1))
        c.ask(Pending.Confirm(Command.Joke), 0)
        assertEquals(Command.Yes, c.resolve("sí", Conversation.CONTEXT_MS + 1))
    }

    @Test
    fun recapAndRepeat() {
        val c = Conversation()
        assertNull(c.recap(0))
        c.say("qué tiempo hace", 0)
        c.say("cuéntame un chiste", 1_000)
        c.say("abre las gasolineras", 2_000)
        assertEquals("Hemos hablado de el tiempo, chistes y la pantalla de gasolineras", c.recap(3_000))
        assertEquals("ok", c.lastReply)
        assertTrue(c.askedRecently(Command.Info(InfoTopic.WEATHER), 30_000))
        assertEquals(Command.Info(InfoTopic.FUEL_NEAR), c.resolve("¿y las gasolineras?", 4_000).let { if (it is Command.Open && it.screen == Screen.FUEL) Command.Info(InfoTopic.FUEL_NEAR) else it })
    }

    @Test
    fun linesDoNotRepeatUntilExhausted() {
        val r = RecentLines(100)
        val lines = (1..10).map { "frase $it" }
        val picked = (1..10).map { r.choose(lines, Random(it))!! }
        assertEquals(10, picked.toSet().size)
        // Agotadas: vuelve la más antigua.
        assertEquals(picked.first(), r.choose(lines))
        val saved = r.save()
        val r2 = RecentLines(100).apply { load(saved) }
        assertTrue(r2.contains("frase 3"))
    }

    @Test
    fun jokesVaryAndFillNames() {
        Personality.recent.clear()
        val jokes = (1..20).map { Personality.joke() }
        assertEquals(20, jokes.toSet().size)
        Personality.userName = null
        assertNull(Personality.fill("Hola, {name}"))
        Personality.userName = "Israel"
        assertEquals("Hola, Israel", Personality.fill("Hola, {name}"))
        Personality.userName = null
        val a = Personality.spice("Vale", 100, "open.fuel")
        val b = Personality.spice("Vale", 100, "open.fuel")
        assertNotEquals(a, b)
    }
}
