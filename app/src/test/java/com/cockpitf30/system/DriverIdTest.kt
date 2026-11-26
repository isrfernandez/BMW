package com.cockpitf30.system

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DriverIdTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = ctx.getSharedPreferences("cockpit", Context.MODE_PRIVATE)

    private fun v(vararg x: Float) = x

    @Test fun voiceMath() {
        assertEquals(1f, VoicePrint.cosine(v(1f, 2f, 3f), v(2f, 4f, 6f)), 1e-5f)
        assertEquals(0f, VoicePrint.cosine(v(1f, 0f), v(0f, 1f)), 1e-5f)
        val avg = VoicePrint.average(listOf(v(2f, 0f), v(0f, 3f)))
        assertEquals(0.5f, avg[0], 1e-5f); assertEquals(0.5f, avg[1], 1e-5f)
        val prints = mapOf("isra" to v(1f, 0f, 0f), "luis" to v(0f, 1f, 0f))
        assertEquals("isra", VoicePrint.best(v(0.9f, 0.2f, 0.1f), prints)?.first)
        // Parecido a los dos: no se decide.
        assertNull(VoicePrint.best(v(1f, 1f, 0f), prints))
        assertNull(VoicePrint.best(v(0f, 0f, 1f), prints))
    }

    @Test fun vectorNeedsEnoughSpeech() {
        val spk = JSONArray(listOf(0.1, 0.2, 0.3))
        assertNull(VoicePrint.vector(JSONObject().put("spk", spk).put("spk_frames", 20)))
        assertEquals(3, VoicePrint.vector(JSONObject().put("spk", spk).put("spk_frames", 120))!!.vector.size)
    }

    @Test fun enrollAndIdentify() {
        prefs.edit().clear().commit()
        Profiles.switchTo(ctx, "Isra")
        val isra = Profiles.current(ctx)
        VoicePrint.startEnroll(isra)
        val h = VoicePrint.Heard(v(1f, 0.1f, 0f), 100, 0L)
        repeat(VoicePrint.SAMPLES - 1) { assertTrue(VoicePrint.enroll(ctx, h) > 0) }
        assertEquals(0, VoicePrint.enroll(ctx, h))
        assertTrue(VoicePrint.has(ctx, isra.id))
        assertEquals("Isra", VoicePrint.identify(ctx, h)?.first?.name)
        assertTrue(VoicePrint.score(ctx, isra.id, VoicePrint.Heard(v(0f, 0f, 1f), 100, 0L))!! < VoicePrint.MISMATCH)
    }

    @Test fun wifiAndNames() {
        prefs.edit().clear().commit()
        assertEquals("iPhone de Isra", DriverId.clean("\"iPhone de Isra\""))
        assertNull(DriverId.clean("<unknown ssid>"))
        Profiles.switchTo(ctx, "Capi")
        val capi = Profiles.current(ctx)
        assertEquals("Capi", Profiles.callName(capi))
        assertTrue(Profiles.rename(ctx, "Isra"))
        assertEquals("Isra", Profiles.current(ctx).name)
        // El rol Zeus no cambia el nombre: le llama como se llame su perfil.
        assertEquals("Isra", Profiles.callName(Profiles.current(ctx)))
        assertTrue(Profiles.current(ctx).capi)
        Profiles.switchTo(ctx, "Luis")
        assertEquals("Luis", Profiles.callName(Profiles.current(ctx)))
        DriverId.setWifi(ctx, capi.id, "iPhone de Isra")
        assertEquals("Isra", DriverId.byWifi(ctx, listOf("MOVISTAR_1234", "iPhone de Isra"))?.name)
        assertNull(DriverId.byWifi(ctx, listOf("MOVISTAR_1234")))
    }
}
