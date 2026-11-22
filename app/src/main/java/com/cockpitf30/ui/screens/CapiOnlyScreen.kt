package com.cockpitf30.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cockpitf30.ui.components.TopBar
import com.cockpitf30.ui.components.TriangleBackground
import com.cockpitf30.ui.theme.Cockpit

/** Diagnóstico y modo demo cuando no conduce el Capi: halo rojo y un aviso. */
@Composable
fun CapiOnlyScreen() {
    LaunchedEffect(Unit) { com.cockpitf30.ui.components.AmbientLight.alarm(3_000) }
    TriangleBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Diagnóstico")
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Acceso solo para el Capi", style = MaterialTheme.typography.headlineMedium, color = Cockpit.Alert)
                    Text(
                        "Diagnóstico y modo demo están reservados al Capi. Si eres tú, di «soy» y tu nombre para cambiar de conductor.",
                        style = MaterialTheme.typography.bodyLarge, color = Cockpit.TextDim, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
