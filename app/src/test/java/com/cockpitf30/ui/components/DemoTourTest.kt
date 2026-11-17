package com.cockpitf30.ui.components

import com.cockpitf30.assistant.Command
import com.cockpitf30.assistant.InfoTopic
import com.cockpitf30.assistant.VoiceCommands
import com.cockpitf30.ui.screens.Screen
import com.cockpitf30.ui.screens.SettingsSection
import com.cockpitf30.vehicle.source.DemoDrive
import com.cockpitf30.vehicle.telemetry.DragResult
import com.cockpitf30.vehicle.telemetry.DragTest
import com.cockpitf30.vehicle.telemetry.DragTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoTourTest {

    @Test
    fun wholeTourFitsInFourMinutes() {
        assertTrue("dura ${DemoTour.totalSeconds} s", DemoTour.totalSeconds <= DemoTour.MAX_SECONDS)
    }

    @Test
    fun everyScreenAndSettingsSectionIsShown() {
        val screens = DemoTour.steps.map { it.screen }.toSet()
        // Vehículo abre el iDrive original: no es una pantalla de la app.
        Screen.entries.filter { it != Screen.VEHICLE }.forEach { assertTrue("falta $it", it in screens) }
        val sections = DemoTour.steps.mapNotNull { it.section }.toSet()
        assertEquals(SettingsSection.entries.toSet(), sections)
        // Las tres vistas del centro del cuadro.
        assertEquals(setOf(-1, 0, 1), DemoTour.steps.mapNotNull { it.clusterPage }.toSet())
    }

    @Test
    fun demoQuestionsAreUnderstood() {
        assertEquals(Command.Info(InfoTopic.RANGE), VoiceCommands.parse(DemoTour.ASK_RANGE))
        assertEquals(Command.Info(InfoTopic.FUEL_NEAR), VoiceCommands.parse(DemoTour.ASK_FUEL))
    }

    @Test
    fun launchSetsAllThreeMarks() {
        // Como en la demo: parado 1,5 s y la salida a fondo, con el límite de aceleración de DemoSource.
        val marks = ArrayList<DragResult>()
        val timer = DragTimer { marks += it }
        var v = 0f
        var t = 0f
        while (t < 1.5f) { timer.feed(t, 0f); t += 0.05f }
        var s = 0f
        while (s <= DemoTour.LAUNCH_SECONDS + 1f) {
            v = DemoDrive.approach(v, DemoTour.launchKmh(s))
            timer.feed(t, v)
            t += 0.05f; s += 0.05f
        }
        val byTest = marks.associateBy { it.test }
        assertEquals(DragTest.entries.toSet(), byTest.keys)
        assertEquals(7.2f, byTest.getValue(DragTest.ZERO_100).seconds, 0.3f)
        assertTrue(byTest.getValue(DragTest.QUARTER_400).seconds in 14f..17.5f)
    }
}
