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
                LoopVideo(R.raw.athos_core, 1920f / 480f, playing = false, Modifier.fillMaxSize(), focus = CRYSTAL_FOCUS, anchor = Offset(0.5f, RING_Y))
            }
        }
        rule.waitForIdle()
        val tv = textureViews(root!!.rootView).single()
        // Cubre el alto (434) manteniendo 4:1 → 1736 de ancho; el cristal (x 0,505) al centro (755).
        assertEquals(434, tv.height)
        assertEquals(1736, tv.width)
        val loc = IntArray(2).also { tv.getLocationInWindow(it) }
        val crystalX = loc[0] + tv.width * CRYSTAL_FOCUS.x
        assertTrue("cristal en x=$crystalX", kotlin.math.abs(crystalX - 755f) < 3f)
        assertTrue(loc[0] <= 0 && loc[0] + tv.width >= 1510)
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
