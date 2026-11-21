package com.cockpitf30.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PersonalityTest {

    @Test
    fun noLineRepeatsUntilThePoolIsExhausted() {
        val recent = RecentLines()
        val pool = (1..50).map { "Frase $it" }
        val r = Random(7)
        val first = List(pool.size) { recent.choose(pool, r)!! }
        assertEquals(pool.toSet(), first.toSet())
    }

    @Test
    fun afterExhaustingNeverRepeatsRecentOnes() {
        val recent = RecentLines()
        val pool = (1..30).map { "Chiste $it" }
        val r = Random(3)
        val said = List(300) { recent.choose(pool, r)!! }
        // Tras la primera vuelta, una frase no vuelve antes de que salgan al menos dos tercios del montón.
        for (i in pool.size until said.size) {
            val prev = said.subList(maxOf(0, i - pool.size * 2 / 3 + 1), i)
            assertTrue("«${said[i]}» repetida en $i", said[i] !in prev)
        }
    }

    @Test
    fun memorySurvivesSaveAndLoad() {
        val a = RecentLines()
        val pool = (1..10).map { "L$it" }
        val said = List(9) { a.choose(pool)!! }
        val b = RecentLines().apply { load(a.save()) }
        assertEquals(9, b.usedCount(pool))
        assertEquals((pool - said.toSet()).single(), b.choose(pool))
    }

    @Test
    fun oldSavedFormatStillLoads() {
        val b = RecentLines().apply { load(listOf("L1", "L2").joinToString("\u0001")) }
        assertTrue(b.contains("L1") && b.contains("L2"))
    }

    @Test
    fun twoInARowNeverRepeatInATinyPool() {
        val recent = RecentLines()
        val pool = listOf("A", "B")
        var last = ""
        repeat(20) { val x = recent.choose(pool)!!; assertNotEquals(last, x); last = x }
    }

    @Test
    fun repertoireIsLargeAndWithoutDuplicates() {
        assertTrue(Lines.JOKES.size >= 150)
        assertTrue(Lines.FACTS.size >= 50)
        assertEquals(Lines.JOKES.size, Lines.JOKES.toSet().size)
        assertEquals(Lines.FACTS.size, Lines.FACTS.toSet().size)
        assertEquals(Lines.GENERIC.size, Lines.GENERIC.toSet().size)
    }

    @Test
    fun factIsAskedByVoice() {
        assertEquals(Command.Chat("fact"), VoiceCommands.parse("cuéntame una curiosidad"))
        assertEquals(Command.Chat("fact"), VoiceCommands.parse("dime un dato curioso"))
        assertEquals(Command.Chat("fact"), VoiceCommands.parse("cuéntame algo"))
    }
}
