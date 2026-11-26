package com.cockpitf30.system

import android.os.Bundle
import com.cockpitf30.assistant.Command
import com.cockpitf30.assistant.VoiceCommands
import com.cockpitf30.ui.screens.Screen
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConnectivityTest {
    @Test
    fun `rayas de Wi-Fi por dBm`() {
        assertEquals(4, Connectivity.level(-50))
        assertEquals(3, Connectivity.level(-60))
        assertEquals(2, Connectivity.level(-75))
        assertEquals(1, Connectivity.level(-85))
        assertEquals(0, Connectivity.level(-95))
    }

    @Test
    fun `lista del Bluetooth de la radio en JSON`() {
        val l = Connectivity.devices("""[{"name":"iPhone de Israel","addr":"AA:BB:CC:DD:EE:01"},{"device_name":"Pixel","mac":"aa:bb:cc:dd:ee:02"}]""")
        assertEquals(listOf("iPhone de Israel", "Pixel"), l.map { it.name })
        assertEquals("AA:BB:CC:DD:EE:01", l[0].addr)
    }

    @Test
    fun `lista del Bluetooth de la radio en texto`() {
        val l = Connectivity.devices("iPhone,AA:BB:CC:DD:EE:01;Galaxy S23,11-22-33-44-55-66")
        assertEquals(listOf("iPhone", "Galaxy S23"), l.map { it.name })
        assertEquals("11:22:33:44:55:66", l[1].addr)
    }

    @Test
    fun `lista del Bluetooth de la radio en Bundle con arrays`() {
        val b = Bundle().apply {
            putStringArray("addr", arrayOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"))
            putStringArray("name", arrayOf("iPhone", ""))
        }
        val l = Connectivity.devices(b)
        assertEquals(2, l.size)
        assertEquals("iPhone", l[0].name)
        assertEquals("AA:BB:CC:DD:EE:02", l[1].name)
    }

    @Test
    fun `vacio o nulo no da aparatos`() {
        assertEquals(0, Connectivity.devices(null).size)
        assertEquals(0, Connectivity.devices("").size)
    }

    @Test
    fun `ordenes de voz abren Conexiones sin quitarle el GPS al mapa`() {
        assertEquals(Command.Open(Screen.CONNECTIONS), VoiceCommands.parse("abre el wifi"))
        assertEquals(Command.Open(Screen.CONNECTIONS), VoiceCommands.parse("bluetooth"))
        assertEquals(Command.Open(Screen.CONNECTIONS), VoiceCommands.parse("qué cobertura tengo"))
        assertEquals(Command.Open(Screen.CONNECTIONS), VoiceCommands.parse("conexiones"))
        assertEquals(Command.Maps, VoiceCommands.parse("abre el gps"))
    }
}
