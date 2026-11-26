package com.cockpitf30.nav

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cockpitf30.assistant.Command
import com.cockpitf30.assistant.ParkingSend
import com.cockpitf30.assistant.VoiceCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ParkingSpotTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun linkAndMessage() {
        val p = Parked(40.4168, -3.70379, 0L)
        assertEquals("https://www.google.com/maps/search/?api=1&query=40.416800,-3.703790", p.mapsUrl)
        assertTrue(p.message().startsWith("Mi coche está aparcado aquí ("))
        assertTrue(p.message().endsWith(p.mapsUrl))
    }

    private fun state(lat: Double, acc: Float = 8f, estimated: Boolean = false) = RouteState(
        position = GeoPoint(lat, -3.7), live = true, estimated = estimated,
        fix = GpsFix(true, acc, 0f, 0L),
    )

    @Test fun savesAfterTenSecondsStill() {
        ctx.getSharedPreferences("cockpit", Context.MODE_PRIVATE).edit().clear().commit()
        ParkingSpot.save(ctx, Parked(40.0, -3.7, 1L))
        // Moviéndose no guarda; parado, a los 10 s sí.
        ParkingSpot.tick(ctx, state(40.01), 50f, 1_000)
        ParkingSpot.tick(ctx, state(40.01), 0f, 2_000)
        ParkingSpot.tick(ctx, state(40.01), 0f, 9_000)
        assertEquals(40.0, ParkingSpot.spot.value!!.lat, 1e-9)
        ParkingSpot.tick(ctx, state(40.01), 0f, 12_500)
        assertEquals(40.01, ParkingSpot.spot.value!!.lat, 1e-9)
        // Con mala señal o a ciegas no se fía.
        ParkingSpot.tick(ctx, state(40.02, acc = 200f), 0f, 13_000)
        ParkingSpot.tick(ctx, state(40.02, acc = 200f), 0f, 30_000)
        ParkingSpot.tick(ctx, state(40.02, estimated = true), 0f, 31_000)
        ParkingSpot.tick(ctx, state(40.02, estimated = true), 0f, 50_000)
        assertEquals(40.01, ParkingSpot.spot.value!!.lat, 1e-9)
    }

    @Test fun email() {
        assertTrue(ParkingSpot.validEmail("luis@ejemplo.es"))
        assertEquals(false, ParkingSpot.validEmail("luis@ejemplo"))
        ParkingSpot.setEmail(ctx, " luis@ejemplo.es ")
        assertEquals("luis@ejemplo.es", ParkingSpot.email(ctx))
        ParkingSpot.setEmail(ctx, null)
        assertNull(ParkingSpot.email(ctx))
    }

    @Test fun voice() {
        assertEquals(Command.Parking(), VoiceCommands.parse("donde he aparcado"))
        assertEquals(Command.Parking(), VoiceCommands.parse("donde esta el coche"))
        assertEquals(Command.Parking(ParkingSend.SHARE), VoiceCommands.parse("envia la ubicacion del coche"))
        assertEquals(Command.Parking(ParkingSend.EMAIL), VoiceCommands.parse("manda por correo la ubicacion del coche"))
        assertEquals(Command.WhoIsCapi, VoiceCommands.parse("quien es el capi"))
        assertEquals(Command.UserPrefs("Luis"), VoiceCommands.parse("que preferencias tiene luis"))
        assertEquals(Command.UserPrefs("Luis"), VoiceCommands.parse("como tiene configurado luis"))
        assertEquals(Command.UserPrefs(null), VoiceCommands.parse("cuales son mis preferencias"))
        assertEquals(false, VoiceCommands.parse("abre ajustes de conduccion") is Command.UserPrefs)
    }
}
