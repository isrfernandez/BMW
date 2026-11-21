package com.cockpitf30.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cockpitf30.ui.components.TripSummaryOverlay
import com.cockpitf30.ui.theme.CockpitTheme
import com.cockpitf30.vehicle.trip.Trip
import com.cockpitf30.vehicle.trip.TripComputer
import com.cockpitf30.vehicle.trip.TripsState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w1920dp-h720dp-land-mdpi")
class SummaryButtonTest {
    @get:Rule val rule = createComposeRule()

    @Test fun verResumen() {
        val now = System.currentTimeMillis()
        val f = TripComputer::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(TripComputer) as MutableStateFlow<TripsState>).value =
            TripsState(null, Trip(startMillis = now - 3_600_000, lastMillis = now - 600_000, lastMoveMillis = now - 600_000, finished = true, distanceM = 32_000.0, movingMillis = 2_400_000, maxSpeedKmh = 120))
        rule.setContent {
            CockpitTheme {
                Box(Modifier.fillMaxSize()) {
                    SettingsScreen(initialSection = SettingsSection.DRIVING)
                    TripSummaryOverlay()
                }
            }
        }
        rule.onNodeWithText("Ver").performScrollTo().performClick()
        rule.mainClock.advanceTimeBy(1000)
        println("summary=" + TripComputer.summary.value)
        rule.onAllNodesWithText("ÚLTIMO TRAYECTO").assertCountEquals(1)
    }
}
