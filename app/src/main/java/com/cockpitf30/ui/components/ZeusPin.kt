package com.cockpitf30.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cockpitf30.assistant.VoiceAssistant
import com.cockpitf30.system.CapiDelete
import com.cockpitf30.system.DriverId
import com.cockpitf30.system.Profiles
import com.cockpitf30.ui.theme.Cockpit
import kotlinx.coroutines.delay

/** Teclado del PIN de Zeus, cuando alguien dice su nombre y la voz no coincide. */
@Composable
fun ZeusPinDialog() {
    val name by DriverId.askPin.collectAsStateWithLifecycle()
    val target = name ?: return
    val ctx = LocalContext.current
    var pin by remember(target) { mutableStateOf("") }
    var error by remember(target) { mutableStateOf<String?>(null) }
    // Si nadie lo pone en un minuto, se cierra solo.
    LaunchedEffect(target) { delay(60_000); DriverId.askPin.value = null }
    AlertDialog(
        onDismissRequest = { DriverId.askPin.value = null },
        title = { Text("PIN de Zeus para entrar como $target") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = pin, onValueChange = { v -> pin = v.filter { it.isDigit() }.take(8) }, singleLine = true,
                    label = { Text("PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                error?.let { Text(it, color = Cockpit.Alert, style = MaterialTheme.typography.labelLarge) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (Profiles.checkPin(ctx, pin)) {
                    null -> { DriverId.askPin.value = null; VoiceAssistant.selectUser(ctx, target, trusted = true) }
                    CapiDelete.LOCKED -> { AmbientLight.alarm(); error = "Demasiados intentos. Bloqueado ${Profiles.lockLeftMs(ctx) / 60_000 + 1} min." }
                    else -> { AmbientLight.alarm(2_500); error = "PIN incorrecto."; pin = "" }
                }
            }) { Text("Entrar") }
        },
        dismissButton = { TextButton(onClick = { DriverId.askPin.value = null }) { Text("Cancelar") } },
    )
}
