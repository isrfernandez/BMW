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
    }
}
