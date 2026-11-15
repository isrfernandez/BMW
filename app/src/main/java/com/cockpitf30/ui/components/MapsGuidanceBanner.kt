package com.cockpitf30.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cockpitf30.nav.MapsGuidance
import com.cockpitf30.nav.MapsGuidanceState
import com.cockpitf30.ui.screens.openMaps
import com.cockpitf30.ui.theme.Cockpit
import com.cockpitf30.ui.theme.numeric

/**
 * Siguiente indicación de Google Maps arriba del cockpit mientras se navega (leída de su
 * notificación): así se puede volver a la app sin perder el próximo giro. Tocar vuelve a Maps.
 */
@Composable
fun MapsGuidanceBanner(modifier: Modifier = Modifier) {
    val g by MapsGuidanceState.current.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    AnimatedVisibility(g != null, modifier, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
        val last = remember { arrayOfNulls<MapsGuidance>(1) }
        val shown = g?.also { last[0] = it } ?: last[0] ?: return@AnimatedVisibility
        MapsGuidanceRow(shown, Modifier.clickable { openMaps(ctx) })
    }
}

@Composable
fun MapsGuidanceRow(g: MapsGuidance, modifier: Modifier = Modifier) {
    Row(
        modifier.widthIn(max = 900.dp).glassPanel(24.dp, CardTheme.NAV.to, smoke = 0.9f).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        g.arrow?.let { Image(remember(it) { it.asImageBitmap() }, null, Modifier.size(52.dp)) }
        Column(Modifier.widthIn(max = 560.dp)) {
            Text(g.title, style = numeric(28, FontWeight.SemiBold), color = Cockpit.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            g.text?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Cockpit.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        g.eta?.let { Text(it, style = numeric(18, FontWeight.Medium), color = CardTheme.NAV.to, maxLines = 2) }
    }
}
