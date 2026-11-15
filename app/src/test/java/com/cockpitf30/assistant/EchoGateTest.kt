package com.cockpitf30.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoGateTest {
    private val echo = List(30) { i -> if (i % 3 == 0) 0.05f else 0.20f } // sílabas y pausas de su voz

    @Test fun ownEchoIsNotACommand() {
        assertFalse(EchoGate.voiceOverEcho(echo + List(6) { 0.21f }).first)
    }

    @Test fun driverTalkingOverIsACommand() {
        assertTrue(EchoGate.voiceOverEcho(echo + List(6) { 0.40f }).first)
    }
}
