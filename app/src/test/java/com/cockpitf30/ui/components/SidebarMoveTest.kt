package com.cockpitf30.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cockpitf30.ui.components.SideArt
import com.cockpitf30.ui.components.SideItem
import com.cockpitf30.ui.components.Sidebar
import com.cockpitf30.ui.theme.CockpitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w1920dp-h720dp-land-mdpi")
/** Coger un botón de la barra lateral (pulsación larga) y arrastrarlo no debe romper la composición (fallo de 0.9.110–0.9.111). */
class SidebarMoveTest {
    @get:Rule val rule = createComposeRule()

    private var navClicks = 0

    @Test fun longPressAndDrag() {
        rule.setContent {
            CockpitTheme {
                Row(Modifier.fillMaxSize()) {
                    Sidebar(listOf(
                        SideItem("Home", SideArt.HOME, true) {}, SideItem("Navegación", SideArt.NAV, false) { navClicks++ },
                        SideItem("Rendimiento", SideArt.PERF, false) {}, SideItem("Música", SideArt.MUSIC, false) {},
                        SideItem("Apps", SideArt.APPS, false) {}, SideItem("Ajustes", SideArt.SETTINGS, false) {},
                    ))
                }
            }
        }
        rule.onNodeWithContentDescription("Música").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Navegación").performTouchInput {
            down(center); advanceEventTime(1000); moveBy(androidx.compose.ui.geometry.Offset(0f, 150f)); moveBy(androidx.compose.ui.geometry.Offset(0f, 150f)); up()
        }
        rule.waitForIdle()
        // Y de verdad cambia de puesto (en 0.9.119 el arrastre se cancelaba y no se movía nada).
        val order = com.cockpitf30.system.AppSettings(androidx.test.core.app.ApplicationProvider.getApplicationContext()).sidebarOrder
        org.junit.Assert.assertTrue("orden: $order", order.indexOf("NAV") > order.indexOf("PERF"))
        // Y un toque después abre su pantalla a la primera (en 0.9.120 había que pulsar dos veces).
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS)
        rule.onNodeWithContentDescription("Navegación").performClick()
        rule.waitForIdle()
        org.junit.Assert.assertEquals(1, navClicks)
    }
}
