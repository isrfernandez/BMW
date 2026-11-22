package com.cockpitf30.ui.screens

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cockpitf30.assistant.RobotVoice
import com.cockpitf30.assistant.StartupBriefing
import com.cockpitf30.assistant.VoiceAssistant
import com.cockpitf30.input.IDriveInput
import com.cockpitf30.input.KnobFunction
import com.cockpitf30.nav.AlertCategory
import com.cockpitf30.nav.RouteAlerts
import com.cockpitf30.nav.RoutePois
import com.cockpitf30.radio.Radio
import com.cockpitf30.radio.RadioSource
import com.cockpitf30.system.AppSettings
import com.cockpitf30.system.BatteryOptimization
import com.cockpitf30.ui.components.FocusTile
import com.cockpitf30.ui.components.InitialFocus
import com.cockpitf30.ui.components.PanelTitle
import com.cockpitf30.ui.components.TopBar
import com.cockpitf30.ui.components.TriangleBackground
import com.cockpitf30.ui.components.glassPanel
import com.cockpitf30.ui.theme.Cockpit
import com.cockpitf30.ui.theme.DriveMode
import com.cockpitf30.ui.theme.DriveModes
import com.cockpitf30.ui.theme.DayTheme
import com.cockpitf30.ui.theme.TextSize
import com.cockpitf30.ui.theme.NightDetector
import com.cockpitf30.ui.theme.numeric
import com.cockpitf30.vehicle.trip.TripComputer

/** Secciones de Ajustes, en el orden de la columna de la izquierda. */
enum class SettingsSection(val title: String, val hint: String) {
    DISPLAY("Pantalla e iluminación", "Modo diurno, tamaño de letra, Night y luz ambiente"),
    ASSISTANT("Asistente de voz", "«Hola Athoox», voz y personalidad"),
    USERS("Usuarios", "Conductores, sus preferencias y borrar"),
    DRIVING("Conducción", "Copiloto, briefing, resumen y cambio"),
    ALERTS("Avisos en ruta", "Radares, peligros, descanso, turismo, comer y dormir"),
    RADIO("Radio", "Fuente, volumen y atenuación"),
    MAPS("Junto a Google Maps", "Mini cockpit, voz e indicaciones"),
    SYSTEM("Sistema", "Inicio, botones del iDrive y batería"),
}

/**
 * Ajustes de la app, por secciones: a la izquierda la lista de secciones y a la derecha sus
 * opciones, cada una con su explicación. Lo técnico (fuente de datos, puertos, tramas) está en
 * Diagnóstico.
 */
@Composable
fun SettingsScreen(onOpen: (Screen) -> Unit = {}, initialSection: SettingsSection = SettingsSection.DISPLAY) {
    val context = LocalContext.current
    var section by rememberSaveable { mutableStateOf(com.cockpitf30.ui.components.DemoTour.settingsSection ?: initialSection) }
    // El modo demo pasa por todas las secciones.
    val demoSection = com.cockpitf30.ui.components.DemoTour.settingsSection
    androidx.compose.runtime.LaunchedEffect(demoSection) { demoSection?.let { section = it } }
    val first = remember { FocusRequester() }
    InitialFocus(first)

    TriangleBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Ajustes") {
                Text("v${installedVersion(context)}", style = MaterialTheme.typography.labelLarge, color = Cockpit.TextDim)
            }
            Row(
                Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Column(Modifier.width(400.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsSection.entries.forEachIndexed { i, s ->
                        SectionTab(
                            s, s == section,
                            Modifier.fillMaxWidth().weight(1f).let { if (i == 0) it.focusRequester(first) else it },
                        ) { section = s }
                    }
                }
                // Cada sección empieza arriba del todo.
                val profileVersion by VoiceAssistant.profileVersion.collectAsStateWithLifecycle()
                key(section, profileVersion) {
                    // Modo demo: las filas se resaltan por turnos enseñando el cambio, sin guardarlo.
                    val demoRows = remember { DemoRows() }
                    LaunchedEffect(demoSection) {
                        if (demoSection == null) return@LaunchedEffect
                        kotlinx.coroutines.delay(450)
                        val n = demoRows.count
                        if (n == 0) return@LaunchedEffect
                        val total = (com.cockpitf30.ui.components.DemoTour.step?.seconds ?: 5) * 1000L - 900L
                        val per = (total / n).coerceIn(220L, 700L)
                        try {
                            for (i in 0 until n) {
                                com.cockpitf30.ui.components.DemoTour.settingsFlip = i
                                kotlinx.coroutines.delay(per)
                            }
                        } finally {
                            com.cockpitf30.ui.components.DemoTour.settingsFlip = -1
                        }
                    }
                    androidx.compose.runtime.CompositionLocalProvider(LocalDemoRows provides demoRows.takeIf { demoSection != null }) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(section.title, style = numeric(30, FontWeight.Medium), color = Cockpit.Text)
                        when (section) {
                            SettingsSection.DISPLAY -> DisplaySettings()
                            SettingsSection.ASSISTANT -> AssistantSettings()
                            SettingsSection.USERS -> UserSettings()
                            SettingsSection.DRIVING -> DrivingSettings(onOpen)
                            SettingsSection.ALERTS -> AlertSettings()
                            SettingsSection.RADIO -> RadioSettings(onOpen)
                            SettingsSection.MAPS -> MapsSettings()
                            SettingsSection.SYSTEM -> SystemSettings(onOpen)
                        }
                    }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Secciones

@Composable
private fun DisplaySettings() {
    val ctx = LocalContext.current
    val settings = remember { AppSettings(ctx) }
    SettingGroup("Modo de conducción") {
        ChoiceRow(
            "Colores de la pantalla",
            "Normal azul, Sport rojo y Night ámbar. También desde Rendimiento o por voz («modo sport»).",
            DriveMode.entries.map { it.label }, DriveModes.selected.ordinal,
        ) { DriveModes.select(ctx, DriveMode.entries[it]) }
        ChoiceRow(
            "Night automático",
            "Con Normal elegido, pasa a Night cuando oscurece. Ahora: ${NightDetector.reason}",
            listOf("No", "Al anochecer", "Anochecer y faros"),
            when {
                !DriveModes.autoNight -> 0
                DriveModes.nightWithHeadlights -> 2
                else -> 1
            },
        ) {
            DriveModes.setAutoNight(ctx, it > 0)
            if (it > 0) DriveModes.setNightWithHeadlights(ctx, it == 2)
        }
    }
    SettingGroup("Legibilidad") {
        ChoiceRow(
            "Modo diurno",
            "Fondos claros y letra oscura para leer a pleno sol. Automático: de día sí, al oscurecer pasa a Night. También por voz («modo diurno»).",
            DayTheme.entries.map { it.label }, DriveModes.dayTheme.ordinal,
        ) { DriveModes.setDayTheme(ctx, DayTheme.entries[it]) }
        ChoiceRow(
            "Tamaño de letra",
            "Agranda todos los textos de la app para leerlos conduciendo.",
            TextSize.entries.map { it.label }, DriveModes.textSize.ordinal,
        ) { DriveModes.setTextSize(ctx, TextSize.entries[it]) }
    }
    SettingGroup("Iluminación") {
        val glow = com.cockpitf30.ui.components.AmbientLight.enabled(ctx)
        ToggleRow("Luz ambiente", "Halo de color en los bordes de la pantalla, como la luz ambiente del coche.", glow) {
            com.cockpitf30.ui.components.AmbientLight.set(ctx, it)
        }
    }
}

@Composable
private fun AssistantSettings() {
    val ctx = LocalContext.current
    val settings = remember { AppSettings(ctx) }
    val assistant = VoiceAssistant
    val voice by assistant.state.collectAsStateWithLifecycle()

    SettingGroup("«Hola Athoox»") {
        var on by remember { mutableStateOf(settings.assistantEnabled) }
        ToggleRow("Asistente de voz", "Escucha «Hola Athoox» para pedir el tiempo, navegar, llamar, poner la radio…", on) {
            on = it; assistant.setEnabled(ctx, it)
        }
        var talks by remember { mutableStateOf(settings.assistantSpeaks) }
        ToggleRow("Responde en voz alta", "Si lo quitas, las respuestas solo salen en pantalla.", talks, enabled = on) {
            talks = it; settings.assistantSpeaks = it
        }
        if (talks) {
            var vol by remember { mutableStateOf(settings.assistantVolume) }
            StepperRow(
                "Volumen del asistente",
                "Su voz respecto al volumen general, para equilibrarla con Maps y la radio. Más de 100 % solo con la voz TARS.",
                "$vol %",
                onMinus = { vol = (vol - 10).coerceAtLeast(10); settings.assistantVolume = vol; assistant.testVolume(ctx) },
                onPlus = { vol = (vol + 10).coerceAtMost(150); settings.assistantVolume = vol; assistant.testVolume(ctx) },
            )
        }
        var follow by remember { mutableStateOf(settings.assistantFollowUp) }
        ToggleRow("Sigue escuchando tras responder", "Puedes decir «otra», «¿y mañana?» o «repite» sin volver a decir «Hola Athoox».", follow, enabled = on) {
            follow = it; settings.assistantFollowUp = it
        }
        if (follow) {
            var secs by remember { mutableStateOf(settings.assistantFollowSeconds) }
            val opts = listOf(3, 5, 8, 12)
            ChoiceRow("Tiempo de escucha tras responder", "Lo que espera tu respuesta antes de volver a «Hola Athoox». Si empiezas a hablar, espera a que acabes.", opts.map { "$it s" }, opts.indexOf(secs).let { if (it < 0) 1 else it }) {
                secs = opts[it]; settings.assistantFollowSeconds = secs
            }
        }
        var withGoogle by remember { mutableStateOf(settings.commandsWithGoogle) }
        val hasGoogle = remember { assistant.googleAvailable() }
        ChoiceRow(
            "Entender las órdenes con",
            if (hasGoogle) "Google entiende el habla normal mucho mejor (necesita conexión o el idioma descargado). «Hola Athoox» lo sigue oyendo el reconocedor sin conexión."
            else "Esta radio no tiene el reconocedor de Google: se usa el de sin conexión.",
            listOf("Google", "Sin conexión"), if (withGoogle && hasGoogle) 0 else 1,
        ) {
            withGoogle = it == 0; settings.commandsWithGoogle = withGoogle
        }
        var sens by remember { mutableStateOf(settings.wakeSensitivity) }
        ChoiceRow("Sensibilidad al oír «Hola Athoox»", "Alta lo oye mejor con ruido, pero salta más veces sin querer.", listOf("Baja", "Normal", "Alta"), 2 - sens) {
            sens = 2 - it; settings.wakeSensitivity = sens
        }
    }

    SettingGroup("Voz") {
        var robot by remember { mutableStateOf(settings.assistantRobotVoice) }
        ChoiceRow("Tipo de voz", "TARS: grave y pausada, con efecto robot. Radio: la voz normal del sistema.", listOf("Estilo TARS", "Voz de la radio"), if (robot) 0 else 1) {
            robot = it == 0
            settings.assistantRobotVoice = robot
            assistant.applyVoice()
        }
        if (robot) {
            val strengths = RobotVoice.Strength.entries
            var strength by remember { mutableStateOf(settings.assistantRobotStrength) }
            ChoiceRow("Efecto robot", null, strengths.map { it.label }, strengths.indexOf(strength)) {
                strength = strengths[it]
                settings.assistantRobotStrength = strength
                assistant.applyVoice()
                assistant.testVoice(ctx)
            }
        }
        var voiceName by remember { mutableStateOf(settings.assistantVoiceName) }
        val voices = remember(voice.phase, voiceName) { assistant.spanishVoices() }
        if (voices.isNotEmpty()) {
            // El motor no siempre dice qué voz es de hombre: «Cambiar voz» las va probando.
            ChoiceRow(
                "Voz",
                "En uso: ${assistant.currentVoice() ?: "—"} (${voices.size} voces en español)",
                listOf("Automática", "Cambiar voz ›"), if (voiceName == null) 0 else 1,
            ) {
                if (it == 0) {
                    voiceName = null
                    settings.assistantVoiceName = null
                    assistant.applyVoice(null)
                    assistant.testVoice(ctx)
                } else {
                    voiceName = assistant.nextVoice(ctx)
                }
            }
        }
        ActionRow("Probar la voz", "Dice una frase con la voz elegida.", "Probar") { assistant.testVoice(ctx) }
        val tts by assistant.ttsStatus.collectAsStateWithLifecycle()
        when (tts) {
            VoiceAssistant.TtsStatus.NO_ENGINE -> ActionRow("Falta el motor de voz", "La radio no tiene motor de voz: instala «Servicios de voz de Google».", "Instalar") {
                assistant.installVoiceEngine(ctx)
            }
            VoiceAssistant.TtsStatus.NO_SPANISH -> ActionRow("Falta la voz en español", "El motor de voz no tiene español descargado.", "Descargar") {
                assistant.installSpanishVoice(ctx)
            }
            else -> Unit
        }
    }

    SettingGroup("Personalidad") {
        LaunchedEffect(Unit) { assistant.loadHumor(ctx) }
        val humor by assistant.humor.collectAsStateWithLifecycle()
        StepperRow("Humor", "0 % responde seco; 100 % siempre con una broma. Por voz: «humor al 60 por ciento».", "$humor %",
            onMinus = { assistant.setHumor(ctx, humor - 5) }, onPlus = { assistant.setHumor(ctx, humor + 5) })
        val levels = listOf(0, 25, 50, 75, 100)
        ChoiceRow("Niveles rápidos", null, levels.map { "$it %" }, levels.indexOf(humor)) { assistant.setHumor(ctx, levels[it]) }
        // Conductores: cada uno con sus ajustes; Athoox recuerda el último. Por voz: «soy Luis».
        val profiles = remember { com.cockpitf30.system.Profiles.list(ctx) }
        val current = remember { com.cockpitf30.system.Profiles.current(ctx) }
        if (profiles.size > 1 || current.name.isNotBlank()) {
            ChoiceRow(
                "Conductor", "Cada uno con sus ajustes. Por voz: «soy Luis» (si no existe, crea su perfil).",
                profiles.map { it.name.ifBlank { "Sin nombre" } }, profiles.indexOfFirst { it.id == current.id },
            ) { i -> profiles[i].name.takeIf { it.isNotBlank() }?.let { assistant.selectUser(ctx, it) } }
        }
        var name by remember { mutableStateOf(settings.assistantUserName) }
        ActionRow(
            "Memoria",
            (name?.let { "Sabe que te llamas $it." } ?: "Aún no sabe tu nombre: díselo con «me llamo…».") + " Olvidar borra también las frases recientes.",
            "Olvidar",
        ) {
            assistant.forgetMemory(ctx)
            name = null
        }
    }
}

/**
 * Usuarios: cada conductor con sus preferencias guardadas; se puede pasar a otro o borrarlo (el
 * borrado pide confirmar con un segundo toque). Por voz: «soy Luis», «borra el usuario Luis»,
 * «¿qué usuarios hay?».
 */
@Composable
private fun UserSettings() {
    val ctx = LocalContext.current
    val profiles = remember { com.cockpitf30.system.Profiles.list(ctx) }
    val current = remember { com.cockpitf30.system.Profiles.current(ctx) }
    SettingGroup("Conductores") {
        Labels(
            "Cada conductor tiene sus propios ajustes y Athoox recuerda quién condujo el último.",
            "Por voz: «Hola Athoox, soy Luis» cambia de usuario (si no existe, crea su perfil), «borra el usuario Luis» y «¿qué usuarios hay?».",
            true, Modifier.fillMaxWidth(),
        )
    }
    (listOf(current) + profiles.filter { it.id != current.id }).forEach { p ->
        val isCurrent = p.id == current.id
        val title = p.name.ifBlank { "Sin nombre" } + (if (p.capi) " · Capi" else "") + if (isCurrent) " · conduce ahora" else ""
        SettingGroup(title) {
            val prefs = remember(p.id) { com.cockpitf30.system.Profiles.summary(com.cockpitf30.system.Profiles.values(ctx, p.id)) }
            prefs.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(k, style = MaterialTheme.typography.bodyLarge, color = Cockpit.TextDim, modifier = Modifier.width(300.dp))
                    Text(v, style = MaterialTheme.typography.bodyLarge, color = Cockpit.Text)
                }
            }
            if (p.capi && isCurrent) {
                CapiControls(ctx)
            } else if (p.capi) {
                if (p.name.isNotBlank()) ActionRow("Usar este perfil", "Carga sus ajustes, como decir «soy ${p.name}».", "Usar") {
                    VoiceAssistant.selectUser(ctx, p.name)
                }
                ActionRow("Borrar usuario", "Cuenta protegida: solo el propio Capi puede borrarla, con su PIN.", "Borrar") {
                    VoiceAssistant.refuseCapiDelete(ctx)
                }
            } else if (!isCurrent) {
                if (p.name.isNotBlank()) ActionRow("Usar este perfil", "Carga sus ajustes, como decir «soy ${p.name}».", "Usar") {
                    VoiceAssistant.selectUser(ctx, p.name)
                }
                var confirm by remember(p.id) { mutableStateOf(false) }
                ActionRow(
                    "Borrar usuario",
                    if (confirm) "Se borrarán sus ajustes. Pulsa otra vez para confirmar." else "Borra el perfil y todos sus ajustes. Los viajes y el mantenimiento del coche no se tocan.",
                    if (confirm) "Confirmar" else "Borrar",
                ) { if (confirm) VoiceAssistant.deleteUserFromSettings(ctx, p.id) else confirm = true }
            } else if (profiles.size > 1) {
                Labels("Para borrar este perfil, cambia antes a otro conductor.", null, false, Modifier.fillMaxWidth())
            }
        }
    }
}

/** El Capi conduciendo: su PIN y el borrado de su propia cuenta. */
@Composable
private fun CapiControls(ctx: android.content.Context) {
    var dialog by remember { mutableStateOf<String?>(null) }
    val hasPin = remember(dialog) { com.cockpitf30.system.Profiles.hasPin(ctx) }
    val scope = rememberCoroutineScope()
    var keyLabel by remember { mutableStateOf(com.cockpitf30.system.PhoneKey.registered(ctx)) }
    var keyNote by remember { mutableStateOf<String?>(null) }
    // Segundo factor: el móvil del Capi conectado por Bluetooth.
    ActionRow(
        "Móvil llave",
        keyNote ?: (keyLabel?.let { "$it. Para borrar tu cuenta tiene que estar conectado, además del PIN." }
            ?: "Ninguno. Conecta tu móvil por Bluetooth y pulsa Registrar: será la llave para borrar tu cuenta."),
        if (keyLabel == null) "Registrar" else "Cambiar",
    ) {
        scope.launch {
            val phones = com.cockpitf30.system.PhoneKey.connected(ctx)
            val first = phones.firstOrNull()
            keyNote = if (first != null && com.cockpitf30.system.PhoneKey.register(ctx, first)) {
                keyLabel = first.label; null
            } else "No veo ningún móvil conectado. Si el teléfono está conectado y aun así no sale, esta radio no deja verlo: entonces vale solo el PIN."
        }
    }
    ActionRow(
        "PIN del Capi",
        if (hasPin) "Protege tu cuenta: hace falta para borrarla. Se teclea aquí, nunca en voz alta." else "Aún no tienes PIN. Sin él nadie puede borrar tu cuenta, ni siquiera tú.",
        if (hasPin) "Cambiar" else "Crear",
    ) { dialog = "pin" }
    ActionRow("Borrar mi cuenta", "Pide tu PIN y tu móvil llave conectado. El rol de Capi pasa al conductor más antiguo, o al primero que se presente si no queda nadie.", "Borrar") {
        if (!hasPin) { dialog = "pin"; return@ActionRow }
        scope.launch {
            if (com.cockpitf30.system.PhoneKey.present(ctx)) { keyNote = null; dialog = "delete" }
            else {
                com.cockpitf30.ui.components.AmbientLight.alarm(3_000)
                keyNote = "Para borrar tu cuenta conecta antes tu móvil llave ($keyLabel) por Bluetooth."
            }
        }
    }
    when (dialog) {
        "pin" -> PinDialog(
            title = if (hasPin) "Cambiar el PIN" else "Crear el PIN del Capi",
            fields = if (hasPin) listOf("PIN actual", "PIN nuevo (4 a 8 cifras)", "Repite el PIN nuevo") else listOf("PIN (4 a 8 cifras)", "Repite el PIN"),
            onDismiss = { dialog = null },
        ) { v ->
            val (old, new, again) = if (hasPin) Triple(v[0], v[1], v[2]) else Triple(null, v[0], v[1])
            when {
                !com.cockpitf30.system.Profiles.validPin(new) -> "El PIN tiene que tener de 4 a 8 cifras."
                new != again -> "Los dos PIN no coinciden."
                hasPin && com.cockpitf30.system.Profiles.lockLeftMs(ctx) > 0 -> "Demasiados intentos. Espera unos minutos."
                !com.cockpitf30.system.Profiles.setPin(ctx, new, old) -> "El PIN actual no es correcto."
                else -> {
                    dialog = null
                    // Si aún no hay llave, el móvil conectado ahora se queda como llave.
                    if (keyLabel == null) scope.launch {
                        com.cockpitf30.system.PhoneKey.connected(ctx).firstOrNull()?.let { if (com.cockpitf30.system.PhoneKey.register(ctx, it)) keyLabel = it.label }
                    }
                    null
                }
            }
        }
        "delete" -> PinDialog(
            title = "Borrar la cuenta del Capi",
            fields = listOf("Tu PIN"),
            onDismiss = { dialog = null },
        ) { v ->
            when (com.cockpitf30.system.Profiles.deleteCapi(ctx, v[0])) {
                com.cockpitf30.system.CapiDelete.OK -> { dialog = null; VoiceAssistant.capiDeleted(ctx); null }
                com.cockpitf30.system.CapiDelete.LOCKED -> {
                    com.cockpitf30.ui.components.AmbientLight.alarm()
                    "Demasiados intentos. Bloqueado ${(com.cockpitf30.system.Profiles.lockLeftMs(ctx) / 60_000 + 1)} min."
                }
                com.cockpitf30.system.CapiDelete.WRONG_PIN -> { com.cockpitf30.ui.components.AmbientLight.alarm(2_500); "PIN incorrecto." }
                com.cockpitf30.system.CapiDelete.NO_PIN -> "Crea antes tu PIN."
                com.cockpitf30.system.CapiDelete.NOT_CAPI -> "Solo el Capi puede hacer esto."
            }
        }
    }
}

/** Diálogo con una casilla de texto. [onConfirm] devuelve el error o null si vale. */
@Composable
private fun TextDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> String?) {
    var value by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = value, onValueChange = { value = it.take(80) }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email),
                )
                error?.let { Text(it, color = Cockpit.Alert, style = MaterialTheme.typography.labelLarge) }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { error = onConfirm(value) }) { Text("Guardar") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Diálogo con [fields] casillas de PIN (cifras ocultas). [onConfirm] devuelve el error o null si vale. */
@Composable
private fun PinDialog(title: String, fields: List<String>, onDismiss: () -> Unit, onConfirm: (List<String>) -> String?) {
    val values = remember { mutableStateListOf(*Array(fields.size) { "" }) }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                fields.forEachIndexed { i, label ->
                    androidx.compose.material3.OutlinedTextField(
                        value = values[i],
                        onValueChange = { v -> values[i] = v.filter { it.isDigit() }.take(8) },
                        label = { Text(label) },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                    )
                }
                error?.let { Text(it, color = Cockpit.Alert, style = MaterialTheme.typography.labelLarge) }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { error = onConfirm(values.toList()) }) { Text("Aceptar") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun DrivingSettings(onOpen: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { AppSettings(ctx) }
    SettingGroup("Copiloto") {
        var copilot by remember { mutableStateOf(settings.copilotEnabled) }
        ToggleRow("Copiloto", "Con el coche en marcha te habla de vez en cuando contra la fatiga: descansos, autonomía, tiempo o destino.", copilot) {
            copilot = it; settings.copilotEnabled = it
        }
        var minutes by remember { mutableStateOf(settings.copilotMinutes) }
        val options = listOf(5, 10, 15, 20, 30)
        ChoiceRow("Habla cada", null, options.map { "$it min" }, options.indexOf(minutes)) {
            minutes = options[it]; settings.copilotMinutes = minutes
        }
        ActionRow("Probar el copiloto", null, "Probar") { VoiceAssistant.copilotNow(ctx) }
    }
    SettingGroup("Al arrancar y al parar") {
        var sound by remember { mutableStateOf(settings.startSound) }
        ToggleRow("Iniciar con sonido", "El sonido de Athoox en la pantalla de arranque.", sound) {
            sound = it; settings.startSound = it
        }
        ActionRow("Ver la intro", "La presentación de Athoox otra vez, con su sonido, y vuelta al Inicio como al arrancar.", "Ver ›") {
            com.cockpitf30.ui.components.Startup.replay.value++
        }
        var briefing by remember { mutableStateOf(settings.briefing) }
        ToggleRow("Briefing al arrancar", "Saludo con el tiempo, los radares cercanos y la autonomía, si han pasado más de 45 minutos desde el anterior.", briefing) {
            briefing = it; settings.briefing = it
        }
        ActionRow("Escuchar el briefing", null, "Probar") { StartupBriefing.speak(ctx) }
        var summary by remember { mutableStateOf(settings.tripSummary) }
        ToggleRow("Resumen al apagar el motor", "Km, tiempo, consumo y nota del viaje.", summary) {
            summary = it; settings.tripSummary = it
        }
        var noTrip by remember { mutableStateOf(false) }
        ActionRow("Ver el resumen", if (noTrip) "Aún no hay ningún trayecto guardado: sale en cuanto hagas el primero." else "El del trayecto en curso o el último.", "Ver") {
            noTrip = !TripComputer.showSummary(System.currentTimeMillis())
        }
    }
    SettingGroup("Coche aparcado") {
        val spot by com.cockpitf30.nav.ParkingSpot.spot.collectAsStateWithLifecycle()
        ActionRow(
            "Dónde está el coche",
            spot?.let { "Guardado el ${it.day()} a las ${it.time()}. Sale como QR para el móvil y se puede compartir." }
                ?: "Se guarda solo al llevar unos segundos parado con buena señal de GPS.",
            "Ver QR",
        ) { com.cockpitf30.nav.ParkingSpot.showing.value = true }
        var email by remember { mutableStateOf(com.cockpitf30.nav.ParkingSpot.email(ctx)) }
        var editing by remember { mutableStateOf(false) }
        ActionRow("Correo para la ubicación", email ?: "Ninguno. Con un correo guardado, «Enviar» abre el correo ya escrito.", if (email == null) "Añadir" else "Cambiar") { editing = true }
        if (editing) TextDialog("Correo para la ubicación del coche", email.orEmpty(), onDismiss = { editing = false }) { v ->
            when {
                v.isBlank() -> { com.cockpitf30.nav.ParkingSpot.setEmail(ctx, null); email = null; editing = false; null }
                !com.cockpitf30.nav.ParkingSpot.validEmail(v) -> "Ese correo no parece válido."
                else -> { com.cockpitf30.nav.ParkingSpot.setEmail(ctx, v); email = v.trim(); editing = false; null }
            }
        }
    }
    SettingGroup("Cuadro") {
        var shift by remember { mutableStateOf(settings.shiftIndicator) }
        ToggleRow("Flecha de cambio de marcha", "Indica cuándo subir o bajar marcha para gastar menos. Se aplica al volver a abrir la pantalla.", shift) {
            shift = it; settings.shiftIndicator = it
        }
    }
    SettingGroup("Mantenimiento") {
        ActionRow("Aceite, neumáticos e ITV", "Kilómetros del coche, intervalos y fechas de revisión.", "Abrir ›") { onOpen(Screen.MAINTENANCE) }
    }
}

@Composable
private fun AlertSettings() {
    val ctx = LocalContext.current
    val settings = remember { AppSettings(ctx) }
    val scope = rememberCoroutineScope()
    val pois by RoutePois.state.collectAsStateWithLifecycle()
    var on by remember { mutableStateOf(settings.routeAlerts) }
    var cats by remember { mutableStateOf(AlertCategory.entries.filter { settings.alertCategory(it) }.toSet()) }
    SettingGroup("General") {
        ToggleRow("Avisos en ruta", "El asistente avisa mientras conduces. Por voz: «desactiva los avisos».", on) {
            on = it
            settings.routeAlerts = it
            if (!it) RouteAlerts.hide()
        }
        ActionRow(
            "Probar un aviso",
            when {
                pois.loading -> "Descargando puntos de interés…"
                pois.error != null && pois.pois.isEmpty() -> "Sin conexión con OpenStreetMap ahora mismo."
                else -> "${pois.pois.size} puntos de interés en 12 km · ${pois.near.size} a menos de 5 km."
            },
            "Probar",
        ) { RouteAlerts.test(scope) }
    }
    SettingGroup("Qué avisar") {
        AlertCategory.entries.forEach { c ->
            ToggleRow(c.label, alertHint(c), c in cats, enabled = on) { v ->
                cats = if (v) cats + c else cats - c
                settings.setAlertCategory(c, v)
            }
        }
    }
}

private fun alertHint(c: AlertCategory): String = when (c) {
    AlertCategory.RADAR -> "«Radar a 400 metros» con su límite."
    AlertCategory.SAFETY -> "Peligros señalizados y pasos a nivel."
    AlertCategory.FUEL -> "La gasolinera más barata a tu alcance cuando queda poca autonomía."
    AlertCategory.REST -> "Áreas de descanso tras hora y media al volante."
    AlertCategory.TOURISM -> "Monumentos, miradores y pueblos con artículo en Wikipedia, con foto."
    AlertCategory.FOOD -> "Restaurantes a la hora de comer o cenar, y hoteles y campings por la noche o tras tres horas al volante."
    AlertCategory.WIND -> "Viento de proa, de través o de popa según el rumbo, en términos náuticos, cuando sopla con fuerza."
    AlertCategory.ROAD -> "Cuestas y viento que se notan en el esfuerzo, el ritmo del viaje y los túneles o zonas sin cobertura."
}

@Composable
private fun RadioSettings(onOpen: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { AppSettings(ctx) }
    val radio by Radio.state.collectAsStateWithLifecycle()
    SettingGroup("Sonido") {
        val fm = radio.tunerAvailable == true
        ChoiceRow(
            "Fuente",
            if (fm) "FM usa la antena del coche (sin datos). Internet usa datos móviles."
            else "Esta radio no tiene sintonizador FM accesible: solo Internet.",
            RadioSource.entries.map { it.label }, RadioSource.entries.indexOf(radio.source),
        ) { Radio.selectSource(RadioSource.entries[it]) }
        StepperRow("Volumen de la radio", "Independiente del volumen general.", "${radio.volume} %",
            onMinus = { Radio.changeVolume(-5) }, onPlus = { Radio.changeVolume(5) })
        var autoPlay by remember { mutableStateOf(settings.radioAutoPlay) }
        ToggleRow("Poner la última emisora al arrancar", "Vuelve a sonar la última que escuchaste, FM o Internet, salvo que ya suene otra música.", autoPlay) {
            autoPlay = it; settings.radioAutoPlay = it
        }
        var duck by remember { mutableStateOf(settings.radioDuckAssistant) }
        ToggleRow("Bajar la radio cuando habla el asistente", "También mientras te escucha, para que te entienda.", duck) {
            duck = it; settings.radioDuckAssistant = it
        }
        var duckNav by remember { mutableStateOf(settings.radioDuckNav) }
        ToggleRow("Bajar la radio con las indicaciones de Google Maps", "Deja Maps como navegador de la radio y baja la FM mientras habla.", duckNav) {
            duckNav = it; settings.radioDuckNav = it
            if (it) Thread({ com.cockpitf30.radio.NavDuck.claim(ctx) }, "navegador-radio").start()
        }
        ActionRow("Emisoras y favoritas", null, "Abrir Radio ›") { onOpen(Screen.RADIO) }
    }
}

/**
 * Con Google Maps en pantalla completa: Android no deja meter Maps dentro de la app, así que
 * la app se queda «al lado»: mini cockpit flotante encima, «Hola Athos» sigue escuchando y las
 * indicaciones de Maps se ven en el cockpit al volver.
 */
@Composable
private fun MapsSettings() {
    val ctx = LocalContext.current
    val settings = remember { AppSettings(ctx) }
    // Los permisos se dan en pantallas del sistema: al volver se miran otra vez.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val resumed by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val canOverlay = remember(resumed) { com.cockpitf30.overlay.MiniCockpit.canShow(ctx) }
    val hasNotif = remember(resumed) { com.cockpitf30.nav.MapsGuidanceState.hasAccess(ctx) }

    SettingGroup("Encima de Maps") {
        var mini by remember { mutableStateOf(settings.miniCockpit) }
        ToggleRow(
            "Mini cockpit flotante",
            "Barra con velocidad, marcha, rpm, autonomía, radares y el asistente encima de Maps y de otras apps. Se arrastra a donde moleste menos; tocar la velocidad la pliega.",
            mini && canOverlay,
        ) {
            mini = it; settings.miniCockpit = it
            if (it && !canOverlay) com.cockpitf30.overlay.MiniCockpit.requestPermission(ctx)
        }
        if (mini && !canOverlay) {
            ActionRow("Falta el permiso «Mostrar sobre otras apps»", "Sin él no se puede pintar encima de Maps.", "Dar permiso ›") {
                com.cockpitf30.overlay.MiniCockpit.requestPermission(ctx)
            }
        }
        var listen by remember { mutableStateOf(settings.listenOutsideApp) }
        ToggleRow(
            "«Hola Athoox» también con Maps delante",
            "El micrófono sigue abierto fuera de la app. Las preguntas se contestan sin quitar el mapa; «abre la radio» o «vuelve al inicio» traen la app.",
            listen,
        ) {
            listen = it; settings.listenOutsideApp = it
            com.cockpitf30.service.VehicleService.start(ctx)
        }
    }

    SettingGroup("Indicaciones de Maps en la app") {
        Hint("Mientras Maps navega, el próximo giro y la llegada salen arriba del cockpit, y puedes preguntar «¿cuál es el siguiente giro?» o «¿cuánto falta?». Se leen de la notificación de Maps.")
        if (hasNotif) {
            ActionRow("Acceso a notificaciones: concedido", "Para quitarlo, en los ajustes de Android.", "Abrir ›") {
                com.cockpitf30.nav.MapsGuidanceState.requestAccess(ctx)
            }
        } else {
            ActionRow("Acceso a notificaciones", "En la lista que se abre, activa Athoox.", "Dar acceso ›") {
                if (!com.cockpitf30.nav.MapsGuidanceState.requestAccess(ctx)) {
                    android.widget.Toast.makeText(ctx, "Esta radio no tiene esa pantalla de ajustes", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    SettingGroup("Pantalla dividida (experimental)") {
        Hint("Android no deja meter Google Maps dentro de otra app: solo el sistema puede incrustar ventanas ajenas. Lo más parecido es la pantalla dividida, si la radio la permite.")
        var split by remember { mutableStateOf(settings.splitScreenMaps) }
        ToggleRow(
            "Abrir Maps al lado del cockpit",
            "Pide a Android abrir Maps en la otra mitad de la pantalla. Si la radio no tiene pantalla dividida, Maps se abre como siempre (y queda el mini cockpit).",
            split,
        ) { split = it; settings.splitScreenMaps = it }
    }
}

@Composable
private fun SystemSettings(onOpen: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { AppSettings(ctx) }
    SettingGroup("Pantalla de inicio") {
        val isHome = remember { isDefaultHome(ctx) }
        ActionRow(
            "Lanzador de la radio",
            if (isHome) "Athoox es la pantalla de inicio." else "La pantalla de inicio es otra app.",
            "Elegir",
        ) { chooseHome(ctx) }
        ActionRow("Lanzador de serie (BMW)", "Abre el de fábrica sin cambiar el de inicio.", "Abrir") { openStockLauncher(ctx) }
    }
    SettingGroup("Botones del iDrive") {
        val learning by IDriveInput.learning.collectAsStateWithLifecycle()
        var learned by remember { mutableStateOf(IDriveInput.learned) }
        // Cuando se aprende una tecla, el mapa cambia fuera de Compose: lo releemos.
        LaunchedEffect(learning) { learned = IDriveInput.learned }
        ChoiceRow(
            "Enseñar un botón",
            if (learning != null) "Pulsa ahora en el iDrive el botón para «${learning!!.label}»… (toca otra vez para cancelar)"
            else "Elige una función y pulsa en el iDrive el botón que quieras para ella.",
            KnobFunction.entries.map { it.label }, KnobFunction.entries.indexOf(learning),
        ) {
            val f = KnobFunction.entries[it]
            IDriveInput.startLearning(if (learning == f) null else f)
        }
        if (learned.isEmpty()) Hint("Sin botones aprendidos: se usan la ruleta y las flechas estándar.")
        else {
            learned.forEach { (from, to) -> Hint("${KeyEvent.keyCodeToString(from)} → ${KnobFunction.ofKeyCode(to)?.label ?: KeyEvent.keyCodeToString(to)}") }
            ActionRow("Borrar botones aprendidos", null, "Borrar") {
                IDriveInput.learned = emptyMap()
                settings.knobKeys = emptyMap()
                learned = emptyMap()
            }
        }
    }
    SettingGroup("Batería") {
        var ok by remember { mutableStateOf(BatteryOptimization.isIgnoring(ctx)) }
        // Al volver del diálogo de Android refrescamos el estado.
        LifecycleResumeEffect(Unit) {
            ok = BatteryOptimization.isIgnoring(ctx)
            onPauseOrDispose {}
        }
        ActionRow(
            "Funcionar en segundo plano",
            if (ok) "Android no cierra la app para ahorrar batería ✓" else "Android puede cerrar la app para ahorrar batería.",
            if (ok) "Hecho" else "Permitir",
        ) { BatteryOptimization.request(ctx) }
    }
    SettingGroup("Acerca de") {
        if (com.cockpitf30.system.Profiles.current(ctx).capi) {
            ActionRow("Athoox ${installedVersion(ctx)}", "Información técnica, datos del CAN, informe para depurar y modo demo.", "Diagnóstico ›") {
                onOpen(Screen.DIAGNOSTICS)
            }
        } else Labels("Athoox ${installedVersion(ctx)}", "Diagnóstico y modo demo: solo para el Capi.", true, Modifier.fillMaxWidth())
    }
}

// ---------------------------------------------------------------- Piezas

/** Botón de sección de la columna izquierda. */
@Composable
private fun SectionTab(s: SettingsSection, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FocusTile(onClick = onClick, selected = selected, modifier = modifier) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            // Barra de acento a la izquierda de la sección abierta.
            Canvas(Modifier.width(4.dp).fillMaxHeight(0.5f)) {
                if (selected) drawRoundRect(Cockpit.BlueLight, cornerRadius = CornerRadius(2.dp.toPx()))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, style = MaterialTheme.typography.titleMedium, color = if (selected || focused) Cockpit.Text else Cockpit.Text.copy(alpha = 0.85f), maxLines = 1)
                Text(s.hint, style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = if (selected) Cockpit.BlueLight else Cockpit.TextDim)
        }
    }
}

/** Bloque de cristal con su rótulo que agrupa opciones relacionadas. */
@Composable
private fun SettingGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().glassPanel(16.dp).padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PanelTitle(title, Modifier.padding(bottom = 2.dp))
        content()
    }
}

@Composable
private fun Labels(title: String, subtitle: String?, enabled: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Cockpit.Text else Cockpit.TextDim)
        subtitle?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim) }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim, modifier = Modifier.padding(horizontal = 4.dp))
}

/** Opción de sí/no: toda la fila es el botón y a la derecha lleva un interruptor. */
@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val (demo, demoMod) = demoFlip()
    FocusTile(onClick = { onChange(!checked) }, selected = demo, modifier = demoMod.fillMaxWidth().heightIn(min = 68.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Labels(title, subtitle, enabled, Modifier.weight(1f))
            Spacer(Modifier.width(20.dp))
            Switch(checked != demo, enabled)
        }
    }
}

/** Filas de la sección en el orden en que se pintan, para que el modo demo las recorra. */
private class DemoRows { var count = 0; fun next() = count++ }
private val LocalDemoRows = androidx.compose.runtime.compositionLocalOf<DemoRows?> { null }

/**
 * ¿Le toca a esta fila enseñar su cambio en el modo demo? Devuelve también el modificador que la
 * trae a la vista (las secciones largas se desplazan solas) y la resalta.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun demoFlip(): Pair<Boolean, Modifier> {
    val rows = LocalDemoRows.current ?: return false to Modifier
    val index = remember { rows.next() }
    val on = com.cockpitf30.ui.components.DemoTour.settingsFlip == index
    val req = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    LaunchedEffect(on) { if (on) runCatching { req.bringIntoView() } }
    val glow by animateFloatAsState(if (on) 1f else 0f, label = "demoRow")
    return on to Modifier
        .bringIntoViewRequester(req)
        .background(Cockpit.Blue.copy(alpha = 0.16f * glow), androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
}

/** Interruptor dibujado: pista azul y bola a la derecha cuando está encendido. */
@Composable
private fun Switch(on: Boolean, enabled: Boolean) {
    val t by animateFloatAsState(if (on) 1f else 0f, label = "switch")
    val accent = Cockpit.Blue
    Canvas(Modifier.size(width = 66.dp, height = 36.dp)) {
        val h = size.height
        val dim = if (enabled) 1f else 0.45f
        drawRoundRect(lerp(Cockpit.Track, accent, t).copy(alpha = dim), cornerRadius = CornerRadius(h / 2))
        val r = h / 2 - 4.dp.toPx()
        val cx = h / 2 + (size.width - h) * t
        drawCircle(Color.White.copy(alpha = 0.95f * dim), r, Offset(cx, h / 2))
    }
}

/** Opción con varias posibilidades: texto arriba y botones debajo, el elegido resaltado. */
@Composable
private fun ChoiceRow(title: String, subtitle: String?, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val (demo, demoMod) = demoFlip()
    // En el modo demo se ve elegida la opción siguiente (no se guarda).
    @Suppress("NAME_SHADOWING")
    val selected = if (demo && options.isNotEmpty()) (selected + 1).mod(options.size) else selected
    Column(demoMod.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Labels(title, subtitle, true, Modifier)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEachIndexed { i, label ->
                FocusTile(onClick = { onSelect(i) }, selected = i == selected) {
                    Text(
                        (if (i == selected) "✓ " else "") + label,
                        Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (i == selected) Cockpit.Text else Cockpit.Text.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}

/** Opción que hace algo (probar, abrir, borrar): texto a la izquierda y botón a la derecha. */
@Composable
private fun ActionRow(title: String, subtitle: String?, button: String, onClick: () -> Unit) {
    val (demo, demoMod) = demoFlip()
    Row(demoMod.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Labels(title, subtitle, true, Modifier.weight(1f))
        Spacer(Modifier.width(20.dp))
        FocusTile(onClick = onClick, selected = demo) {
            Text(button, Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Valor con − y +. */
@Composable
private fun StepperRow(title: String, subtitle: String?, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    val (demo, demoMod) = demoFlip()
    Row(demoMod.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Labels(title, subtitle, true, Modifier.weight(1f))
        Spacer(Modifier.width(20.dp))
        FocusTile(onClick = onMinus, selected = demo) {
            Text("−", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
        }
        Text(value, Modifier.width(110.dp), style = numeric(28, FontWeight.Medium), color = Cockpit.Text, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        FocusTile(onClick = onPlus) {
            Text("+", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
        }
    }
}

// ---------------------------------------------------------------- Lanzador

/** ¿Es Cockpit la app de inicio (HOME) por defecto? */
private fun isDefaultHome(context: android.content.Context): Boolean {
    val home = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
    return context.packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == context.packageName
}

/**
 * Abre el ajuste de Android para elegir la app de inicio. Si la radio no lo tiene, los de apps
 * por defecto, y como último recurso la ficha de Cockpit (allí «Borrar valores predeterminados»).
 */
private fun chooseHome(context: android.content.Context) {
    val tries = listOf(
        android.content.Intent(android.provider.Settings.ACTION_HOME_SETTINGS),
        android.content.Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + context.packageName)),
    )
    for (i in tries) {
        if (runCatching { context.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
    android.widget.Toast.makeText(context, "La radio no deja abrir el ajuste de pantalla de inicio", android.widget.Toast.LENGTH_LONG).show()
}

/** Abre el lanzador BMW de serie de la radio (o cualquier otro lanzador instalado que no sea Cockpit). */
private fun openStockLauncher(context: android.content.Context) {
    val pm = context.packageManager
    val home = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
    val others = pm.queryIntentActivities(home, 0).map { it.activityInfo }.filter { it.packageName != context.packageName && it.packageName != "com.android.settings" }
    val target = others.firstOrNull { it.packageName == "com.bmw1.launcher2" } ?: others.firstOrNull()
    if (target == null) {
        android.widget.Toast.makeText(context, "No hay otro lanzador instalado", android.widget.Toast.LENGTH_LONG).show()
        return
    }
    runCatching {
        context.startActivity(android.content.Intent(home).setClassName(target.packageName, target.name).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
