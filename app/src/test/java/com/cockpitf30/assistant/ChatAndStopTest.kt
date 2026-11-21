package com.cockpitf30.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAndStopTest {

    @Test
    fun strongStopsAnywhereInTranscript() {
        // El eco de la explicación sigue detrás de la orden: antes solo valía al final.
        assertTrue("cancela" in VoiceCommands.stopKinds("la muralla del siglo cancele y fue construida por"))
        assertTrue("cancela" in VoiceCommands.stopKinds("cancelar"))
        assertTrue("detente" in VoiceCommands.stopKinds("el castillo de tente la torre"))
        assertTrue("detente" in VoiceCommands.stopKinds("detente"))
        assertTrue("silencio" in VoiceCommands.stopKinds("silencio por favor"))
        assertTrue("callate" in VoiceCommands.stopKinds("cállate ya"))
        assertTrue("deja de hablar" in VoiceCommands.stopKinds("deja de hablar"))
        assertTrue(VoiceCommands.stopKinds("el castillo de alcalá fue construido para la defensa").isEmpty())
        assertTrue(VoiceCommands.stopKinds("la catedral tiene una torre de estilo gótico").isEmpty())
        // Las formas canónicas cortan y se callan.
        for (k in listOf("cancela", "detente", "silencio", "basta", "callate", "stop", "para ya", "deja de hablar", "no sigas", "vale ya", "suficiente", "no me interesa"))
            assertEquals(k, VoiceCommands.Barge.STOP, VoiceCommands.bargeAction(k))
    }

    @Test
    fun talkAndMoods() {
        assertEquals(Command.Chat("talkative"), VoiceCommands.parse("hoy no estás muy hablador..."))
        assertEquals(Command.Chat("talkative"), VoiceCommands.parse("estás muy callado hoy"))
        assertEquals(Command.Chat("talkative"), VoiceCommands.parse("dame conversación"))
        assertEquals(Command.Chat("chatty"), VoiceCommands.parse("hablas demasiado"))
        assertEquals(Command.Chat("sad"), VoiceCommands.parse("estoy un poco triste"))
        assertEquals(Command.Chat("riddle"), VoiceCommands.parse("cuéntame una adivinanza"))
        assertEquals(Command.Chat("saying"), VoiceCommands.parse("dime un refrán"))
        assertEquals(Command.Chat("question"), VoiceCommands.parse("pregúntame algo"))
        assertEquals(Command.Chat("alive"), VoiceCommands.parse("¿estás vivo?"))
        assertEquals(Command.Chat("traffic"), VoiceCommands.parse("vaya atasco"))
        assertEquals(Command.Chat("laugh"), VoiceCommands.parse("jajaja"))
        assertEquals(Command.Chat("aboutme"), VoiceCommands.parse("hoy estás muy raro"))
        assertEquals(Command.Cancel, VoiceCommands.parse("detente"))
        assertEquals(Command.Cancel, VoiceCommands.parse("no sigas"))
        assertTrue(VoiceCommands.isRealQuestion("¿cuándo se construyó la torre eiffel?"))
        assertFalse(VoiceCommands.isRealQuestion("hoy estás muy raro"))
        // Lo de antes no cambia.
        assertEquals(Command.Joke, VoiceCommands.parse("cuéntame un chiste"))
        assertEquals(Command.Chat("howareyou"), VoiceCommands.parse("¿qué tal estás?"))
    }

    @Test
    fun riddleRoundTrip() {
        val c = Conversation()
        val r = LinesExtra.RIDDLES.first { it.answer == "el reloj" }
        c.ask(Pending.Guess(r), 0)
        assertEquals(Command.RiddleAnswer(r, RiddleOutcome.RIGHT), c.resolve("un reloj", 1))
        c.ask(Pending.Guess(r), 0)
        assertEquals(Command.RiddleAnswer(r, RiddleOutcome.GIVE_UP), c.resolve("me rindo", 1))
        c.ask(Pending.Guess(r), 0)
        assertEquals(Command.RiddleAnswer(r, RiddleOutcome.WRONG), c.resolve("una mesa", 1))
        c.ask(Pending.Guess(r), 0)
        // Una orden de verdad sigue siendo una orden.
        assertEquals(Command.Joke, c.resolve("cuéntame un chiste", 1))
    }

    @Test
    fun answersToAthosQuestionsAreCommented() {
        val c = Conversation()
        c.ask(Pending.Reply, 0)
        assertEquals(Command.Chat("chat.answer"), c.resolve("pues la playa de cádiz", 1))
        c.ask(Pending.Reply, 0)
        assertEquals(Command.Chat("fine"), c.resolve("bien", 1))
    }

    @Test
    fun linesArePlentifulAndUnique() {
        assertTrue(Lines.JOKES.size >= 250)
        assertTrue(Lines.FACTS.size >= 140)
        assertEquals(Lines.JOKES.size, Lines.JOKES.toSet().size)
        assertEquals(Lines.FACTS.size, Lines.FACTS.toSet().size)
        assertEquals(LinesExtra.SAYINGS.size, LinesExtra.SAYINGS.toSet().size)
        assertEquals(LinesExtra.RIDDLES.size, LinesExtra.RIDDLES.map { it.question }.toSet().size)
        for (r in LinesExtra.RIDDLES) assertTrue(r.question, r.matches(r.answer))
        for (k in listOf("talkative", "chatty", "sad", "riddle.right", "riddle.wrong", "riddle.giveup", "chat.answer", "question", "aboutme"))
            assertTrue(k, Lines.CHAT[k].orEmpty().isNotEmpty())
        for (k in listOf("offer.copilot", "offer.fact", "offer.quiet", "offer.joke", "offer.chat", "offer.riddle", "unknown.question"))
            assertTrue(k, Lines.PLAIN[k].orEmpty().isNotEmpty())
        assertEquals(Copilot.IDLE.size, Copilot.IDLE.map { it.id }.toSet().size)
    }
}
