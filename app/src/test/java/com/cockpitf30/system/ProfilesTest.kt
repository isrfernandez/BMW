package com.cockpitf30.system

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cockpitf30.assistant.Command
import com.cockpitf30.assistant.VoiceCommands
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfilesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = ctx.getSharedPreferences("cockpit", Context.MODE_PRIVATE)

    @Test fun switchKeepsEachDriverSettings() {
        prefs.edit().clear().putInt("assistant_humor", 40).putLong("odometer_m", 123L).commit()
        assertEquals(ProfileSwitch.NAMED, Profiles.switchTo(ctx, "Israel"))
        assertEquals(40, prefs.getInt("assistant_humor", -1))
        assertEquals(ProfileSwitch.CREATED, Profiles.switchTo(ctx, "Luis"))
        // Luis empieza con los ajustes de fábrica, pero el coche es el mismo.
        assertEquals(-1, prefs.getInt("assistant_humor", -1))
        assertEquals(123L, prefs.getLong("odometer_m", 0))
        assertEquals("Luis", prefs.getString("assistant_user_name", null))
        prefs.edit().putInt("assistant_humor", 90).commit()
        assertEquals(ProfileSwitch.SWITCHED, Profiles.switchTo(ctx, "israel"))
        assertEquals(40, prefs.getInt("assistant_humor", -1))
        assertEquals("Israel", prefs.getString("assistant_user_name", null))
        assertEquals(ProfileSwitch.SWITCHED, Profiles.switchTo(ctx, "Luís"))
        assertEquals(90, prefs.getInt("assistant_humor", -1))
        assertEquals(ProfileSwitch.SAME, Profiles.switchTo(ctx, "luis"))
        assertEquals(2, Profiles.list(ctx).size)
        assertEquals("Luis", Profiles.current(ctx).name)
    }

    @Test fun voiceSwitch() {
        assertEquals(Command.SetName("Luis"), VoiceCommands.parse("soy luis"))
        assertEquals(Command.SetName("Luis"), VoiceCommands.parse("hola soy luis"))
        assertEquals(Command.SetName("Ana Maria"), VoiceCommands.parse("cambia de conductor a ana maria"))
        assertEquals(false, VoiceCommands.parse("soy de madrid") is Command.SetName)
        assertEquals(Command.DeleteUser("Luis"), VoiceCommands.parse("borra el usuario luis"))
        assertEquals(Command.DeleteUser("Luis"), VoiceCommands.parse("elimina el perfil de luis"))
        assertEquals(Command.DeleteUser("Luis"), VoiceCommands.parse("borra a luis"))
        assertEquals(Command.ListUsers, VoiceCommands.parse("que usuarios hay"))
        assertEquals(Command.DeleteUser("Capi"), VoiceCommands.parse("borra al capi"))
    }

    @Test fun summaryAndDelete() {
        prefs.edit().clear().commit()
        Profiles.switchTo(ctx, "Israel")
        prefs.edit().putInt("assistant_humor", 30).putBoolean("alert_tourism", false).commit()
        Profiles.switchTo(ctx, "Luis")
        val israel = Profiles.find(ctx, "israel")!!
        val s = Profiles.summary(Profiles.values(ctx, israel.id)).toMap()
        assertEquals("30 %", s["Humor"])
        assertEquals("Todos menos turismo", s["Avisos en ruta"])
        assertEquals("75 %", Profiles.summary(Profiles.values(ctx, Profiles.current(ctx).id)).toMap()["Humor"])
        assertEquals(false, Profiles.delete(ctx, Profiles.current(ctx).id))
        // Israel fue el primero: es el Capi y no se puede borrar.
        assertEquals(true, israel.capi)
        assertEquals(false, Profiles.delete(ctx, israel.id))
        Profiles.switchTo(ctx, "Israel")
        assertEquals(true, Profiles.delete(ctx, Profiles.find(ctx, "luis")!!.id))
        assertEquals(1, Profiles.list(ctx).size)
    }

    @Test fun capiPinAndSelfDelete() {
        prefs.edit().clear().commit()
        Profiles.switchTo(ctx, "Israel")
        Profiles.switchTo(ctx, "Luis")
        // Luis no es el Capi: ni PIN ni borrado.
        assertEquals(false, Profiles.setPin(ctx, "1234"))
        assertEquals(CapiDelete.NOT_CAPI, Profiles.deleteCapi(ctx, "1234"))
        Profiles.switchTo(ctx, "Israel")
        assertEquals(CapiDelete.NO_PIN, Profiles.deleteCapi(ctx, "1234"))
        assertEquals(false, Profiles.setPin(ctx, "12"))
        assertEquals(true, Profiles.setPin(ctx, "2468"))
        assertEquals(false, Profiles.setPin(ctx, "1111", oldPin = "0000"))
        assertEquals(CapiDelete.WRONG_PIN, Profiles.deleteCapi(ctx, "1357"))
        assertEquals(CapiDelete.OK, Profiles.deleteCapi(ctx, "2468"))
        val now = Profiles.list(ctx)
        assertEquals(listOf("Luis"), now.map { it.name })
        assertEquals(true, now.single().capi)
        assertEquals("Luis", Profiles.current(ctx).name)
    }

    @Test fun pinLocksAfterFails() {
        prefs.edit().clear().commit()
        Profiles.switchTo(ctx, "Israel")
        Profiles.setPin(ctx, "2468")
        repeat(Profiles.MAX_FAILS - 1) { assertEquals(CapiDelete.WRONG_PIN, Profiles.deleteCapi(ctx, "0000")) }
        assertEquals(CapiDelete.LOCKED, Profiles.deleteCapi(ctx, "0000"))
        assertEquals(CapiDelete.LOCKED, Profiles.deleteCapi(ctx, "2468"))
    }

    @Test fun firstUserIsCapiAndAloneStartsOver() {
        prefs.edit().clear().commit()
        Profiles.switchTo(ctx, "Ana")
        assertEquals(true, Profiles.current(ctx).capi)
        Profiles.setPin(ctx, "9999")
        assertEquals(CapiDelete.OK, Profiles.deleteCapi(ctx, "9999"))
        assertEquals("", Profiles.current(ctx).name)
        assertEquals(ProfileSwitch.NAMED, Profiles.switchTo(ctx, "Pepe"))
        assertEquals(true, Profiles.current(ctx).capi)
    }
}
