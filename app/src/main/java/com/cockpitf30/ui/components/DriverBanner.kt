package com.cockpitf30.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cockpitf30.assistant.VoiceAssistant
import com.cockpitf30.system.DriverId
import com.cockpitf30.system.Profiles
import com.cockpitf30.ui.theme.Cockpit
import kotlinx.coroutines.delay

/** Al arrancar: quién conduce y cómo lo ha sabido, con «Cambiar» (abre Ajustes › Usuarios). */
@Composable
fun DriverBanner(modifier: Modifier = Modifier) {
    val g by DriverId.greeting.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    LaunchedEffect(g?.at) { if (g != null) { delay(14_000); DriverId.greeting.value = null } }
    AnimatedVisibility(g != null, modifier, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
        val s = g ?: return@AnimatedVisibility
        Row(
            Modifier.glassPanel(18.dp, Cockpit.BlueLight, smoke = 0.9f).padding(start = 26.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Column {
                Text("Conduce ${s.name}" + if (s.capi) " · Zeus" else "", style = MaterialTheme.typography.titleMedium, color = Cockpit.Text)
                Text(when (s.how) { DriverId.How.LAST -> "Último conductor"; DriverId.How.GUEST -> "Modo invitado, ajustes de fábrica"; else -> "Reconocido ${s.how.text}" }, style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim)
            }
            FocusTile(onClick = { VoiceAssistant.askWho(ctx) }) {
                Text("Cambiar", Modifier.padding(horizontal = 20.dp, vertical = 10.dp), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
