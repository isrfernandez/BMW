package com.cockpitf30.ui.screens

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
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
import com.cockpitf30.ui.theme.NightDetector
import com.cockpitf30.ui.theme.numeric
import com.cockpitf30.vehicle.trip.TripComputer

/** Secciones de Ajustes, en el orden de la columna de la izquierda. */
enum class SettingsSection(val title: String, val hint: String) {
    DISPLAY("Pantalla e iluminación", "Modo, Night automático y luz ambiente"),
    ASSISTANT("Asistente de voz", "«Hola BMW», voz y personalidad"),
    DRIVING("Conducción", "Copiloto, briefing, resumen y cambio"),
    ALERTS("Avisos en ruta", "Radares, peligros, descanso y turismo"),
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
    var section by rememberSaveable { mutableStateOf(initialSection) }
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
                key(section) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(section.title, style = numeric(30, FontWeight.Medium), color = Cockpit.Text)
                        when (section) {
                            SettingsSection.DISPLAY -> DisplaySettings()
                            SettingsSection.ASSISTANT -> AssistantSettings()
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
    SettingGroup("Iluminación") {
        var glow by remember { mutableStateOf(settings.ambientLight) }
        ToggleRow("Luz ambiente", "Halo de color en los bordes de la pantalla, como la luz ambiente del coche. Se aplica al volver a abrir la pantalla.", glow) {
            glow = it; settings.ambientLight = it
        }
    }
}

@Composable
private fun AssistantSettings() {
    val ctx = LocalContext.current
    val settings = remember { AppSettings(ctx) }
    val assistant = VoiceAssistant
    val voice by assistant.state.collectAsStateWithLifecycle()

    SettingGroup("«Hola BMW»") {
        var on by remember { mutableStateOf(settings.assistantEnabled) }
        ToggleRow("Asistente de voz", "Escucha «Hola BMW» para pedir el tiempo, navegar, llamar, poner la radio…", on) {
            on = it; assistant.setEnabled(ctx, it)
        }
        var talks by remember { mutableStateOf(settings.assistantSpeaks) }
        ToggleRow("Responde en voz alta", "Si lo quitas, las respuestas solo salen en pantalla.", talks, enabled = on) {
            talks = it; settings.assistantSpeaks = it
        }
        var follow by remember { mutableStateOf(settings.assistantFollowUp) }
        ToggleRow("Sigue escuchando tras responder", "Puedes decir «otra», «¿y mañana?» o «repite» sin volver a decir «Hola BMW».", follow, enabled = on) {
            follow = it; settings.assistantFollowUp = it
        }
        var sens by remember { mutableStateOf(settings.wakeSensitivity) }
        ChoiceRow("Sensibilidad al oír «Hola BMW»", "Alta lo oye mejor con ruido, pero salta más veces sin querer.", listOf("Baja", "Normal", "Alta"), 2 - sens) {
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
        var briefing by remember { mutableStateOf(settings.briefing) }
        ToggleRow("Briefing al arrancar", "Saludo con el tiempo, los radares cercanos y la autonomía, si han pasado más de 45 minutos desde el anterior.", briefing) {
            briefing = it; settings.briefing = it
        }
        ActionRow("Escuchar el briefing", null, "Probar") { StartupBriefing.speak(ctx) }
        var summary by remember { mutableStateOf(settings.tripSummary) }
        ToggleRow("Resumen al apagar el motor", "Km, tiempo, consumo y nota del viaje.", summary) {
            summary = it; settings.tripSummary = it
        }
        ActionRow("Ver el resumen", null, "Ver") { TripComputer.showSummary(System.currentTimeMillis()) }
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
        var duck by remember { mutableStateOf(settings.radioDuckAssistant) }
        ToggleRow("Bajar la radio cuando habla el asistente", "También mientras te escucha, para que te entienda.", duck) {
            duck = it; settings.radioDuckAssistant = it
        }
        ActionRow("Emisoras y favoritas", null, "Abrir Radio ›") { onOpen(Screen.RADIO) }
    }
}

/**
 * Con Google Maps en pantalla completa: Android no deja meter Maps dentro de la app, así que
 * la app se queda «al lado»: mini cockpit flotante encima, «Hola BMW» sigue escuchando y las
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
            "«Hola BMW» también con Maps delante",
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
            ActionRow("Acceso a notificaciones", "En la lista que se abre, activa Cockpit F30.", "Dar acceso ›") {
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
            if (isHome) "Cockpit F30 es la pantalla de inicio." else "La pantalla de inicio es otra app.",
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
        ActionRow("Cockpit F30 ${installedVersion(ctx)}", "Información técnica, datos del CAN e informe para depurar.", "Diagnóstico ›") {
            onOpen(Screen.DIAGNOSTICS)
        }
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
    FocusTile(onClick = { onChange(!checked) }, selected = false, modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Labels(title, subtitle, enabled, Modifier.weight(1f))
            Spacer(Modifier.width(20.dp))
            Switch(checked, enabled)
        }
    }
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Labels(title, subtitle, true, Modifier.weight(1f))
        Spacer(Modifier.width(20.dp))
        FocusTile(onClick = onClick) {
            Text(button, Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Valor con − y +. */
@Composable
private fun StepperRow(title: String, subtitle: String?, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Labels(title, subtitle, true, Modifier.weight(1f))
        Spacer(Modifier.width(20.dp))
        FocusTile(onClick = onMinus) {
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
