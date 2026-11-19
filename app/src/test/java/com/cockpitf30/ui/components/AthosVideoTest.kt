package com.cockpitf30.ui.components

import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.cockpitf30.R
import com.cockpitf30.ui.theme.CockpitTheme
import com.cockpitf30.vehicle.SourceStatus
import com.cockpitf30.vehicle.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** El vídeo de Athos se coloca bien (recorte tipo Crop, centrado en el cristal) y el cuadro y la presentación se montan sin fallar. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w1920dp-h720dp-land-mdpi")
class AthosVideoTest {
    @get:Rule val rule = createComposeRule()

    private fun textureViews(v: View): List<TextureView> = when (v) {
        is TextureView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { textureViews(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun coversPanelAndCentresCrystal() {
        var root: View? = null
        rule.setContent {
            root = LocalView.current
            Box(Modifier.size(1510.dp, 434.dp)) {
                LoopVideo(R.raw.athos_intro, 1920f / 720f, playing = false, Modifier.fillMaxSize(), region = CRYSTAL_REGION, focus = CRYSTAL_FOCUS, anchor = Offset(0.5f, RING_Y))
            }
        }
        rule.waitForIdle()
        val tv = textureViews(root!!.rootView).single()
        // La franja del cristal (480 de 720 líneas, 4:1) cubre el alto (434): vídeo de 1736×651,
        // el rótulo queda por debajo del panel; el cristal (x 0,505) al centro (755).
        assertEquals(651, tv.height)
        assertEquals(1736, tv.width)
        val loc = IntArray(2).also { tv.getLocationInWindow(it) }
        val crystalX = loc[0] + tv.width * CRYSTAL_FOCUS.x
        assertTrue("cristal en x=$crystalX", kotlin.math.abs(crystalX - 755f) < 3f)
        assertTrue(loc[0] <= 0 && loc[0] + tv.width >= 1510)
        // La franja empieza 12 líneas abajo: el vídeo sube unos 11 px y el rótulo (línea 492+) no entra.
        assertTrue("y=${loc[1]}", loc[1] in -13..-9)
        assertTrue(loc[1] + tv.height * 492f / 720f >= 434f - 1f)
    }

    @Test fun clusterAndSplashCompose() {
        rule.setContent {
            CockpitTheme {
                Box(Modifier.fillMaxSize()) {
                    ClusterStrip(VehicleState(), SourceStatus(), Modifier.fillMaxSize())
                    StartupSplash()
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(3_000)
        rule.waitForIdle()
    }
}
