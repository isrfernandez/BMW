package com.cockpitf30.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.cockpitf30.input.IDriveInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cockpitf30.service.VehicleService
import com.cockpitf30.system.AppSettings
import com.cockpitf30.system.SourceMode
import com.cockpitf30.ui.components.FocusTile
import com.cockpitf30.ui.components.InitialFocus
import com.cockpitf30.ui.components.TopBar
import com.cockpitf30.ui.components.TriangleBackground
import com.cockpitf30.ui.components.glassPanel
import com.cockpitf30.ui.components.PanelTitle
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.cockpitf30.ui.theme.numeric
import com.cockpitf30.ui.theme.Cockpit
import com.cockpitf30.vehicle.VehicleRepository
import com.cockpitf30.ui.theme.DESIGN_HEIGHT_DP
import com.cockpitf30.ui.theme.DESIGN_WIDTH_DP
import com.cockpitf30.ui.theme.appliedBandsPx
import com.cockpitf30.ui.theme.appliedDesignDensity

/**
 * Solo información técnica para depurar: fuente de datos, GPS, lo que lee la radio del coche,
 * qué eventos manda el iDrive y el estado interno del asistente. Lo que elige el conductor
 * (iluminación, voz, avisos…) está en [SettingsScreen].
 */
@Composable
fun DiagnosticsScreen() {
    val context = LocalContext.current
    // Los registros de depuración solo se preparan mientras esta pantalla está abierta.
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.cockpitf30.system.DiagnosticsGate.enter()
        onDispose { com.cockpitf30.system.DiagnosticsGate.leave() }
    }
    val settings = remember { AppSettings(context) }
    val status by VehicleRepository.status.collectAsStateWithLifecycle()
    val input by VehicleRepository.inputLog.collectAsStateWithLifecycle()
    val radioValues by com.cockpitf30.vehicle.source.RadioCarData.values.collectAsStateWithLifecycle()
    val radioChanges by com.cockpitf30.vehicle.source.RadioCarData.changes.collectAsStateWithLifecycle()
    val vehicle by VehicleRepository.state.collectAsStateWithLifecycle()
    val route by com.cockpitf30.nav.RouteTracker.state.collectAsStateWithLifecycle()
    val gpsRetries by com.cockpitf30.nav.RouteTracker.retries.collectAsStateWithLifecycle()
    var showAllKeys by remember { mutableStateOf(false) }
    var showSensors by remember { mutableStateOf(false) }
    var askSpeed by remember { mutableStateOf(settings.canAskSpeed) }
    var mode by remember { mutableStateOf(settings.sourceMode) }
    // El puerto serie no daba nada en esta radio: ya no se ofrece (si alguien lo tenía elegido, se ve marcado igual).
    val modes = remember { SourceMode.entries.filter { it != SourceMode.SERIAL || settings.sourceMode == SourceMode.SERIAL } }

    // Lo de la radio va al principio: el informe completo pasa de 150 KB y al compartir se recorta.
    fun report() = buildReport(context, status, input) + radioReport(radioValues, radioChanges, vehicle)

    val first = remember { FocusRequester() }
    InitialFocus(first)

    // Se refresca cada segundo para ver la antigüedad de la última posición.
    var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(1000); now = android.os.SystemClock.elapsedRealtime() }
    }

    TriangleBackground(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Diagnóstico") {
            Text("v${installedVersion(context)}", style = MaterialTheme.typography.labelLarge, color = Cockpit.BlueLight)
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                val gps = gpsText(route, now)
                Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    DiagStat("Versión", installedVersion(context), Cockpit.BlueLight)
                    DiagStat("Fuente", status.sourceName, if (status.connected) Cockpit.Ok else Cockpit.Alert)
                    DiagStat("GPS", gps.first, if (gps.second) Cockpit.Ok else Cockpit.Alert)
                    DiagStat("Claves radio", "${radioValues.size}", Color(0xFF7FD3FF))
                }
            }
            item {
                DiagCard("Modo demo") {
                    Info("Recorre sola toda la app: cada pantalla, los modos Sport, Night y diurno y las secciones de Ajustes, " +
                        "con datos de demostración (unos ${com.cockpitf30.ui.components.DemoTour.steps.sumOf { it.seconds } / 60 + 1} minutos). " +
                        "Abajo se ve qué es cada pantalla; «Detener» o atrás la para. Al acabar todo vuelve a como estaba.", Cockpit.TextDim)
                    ChipRow(listOf("Empezar demo"), -1, Modifier.focusRequester(first)) { com.cockpitf30.ui.components.DemoTour.start(context) }
                }
            }
            item {
                DiagCard("Estado") {
                    Info("Versión instalada: ${installedVersion(context)}", Cockpit.BlueLight)
                    Info("Fuente: ${status.sourceName} · ${if (status.connected) "conectada" else "desconectada"}")
                    status.lastError?.let { Info("Último error: $it", Cockpit.Alert) }
                    ChipRow(listOf("Compartir informe", "Guardar informe en Descargas"), -1) {
                        if (it == 0) shareReport(context, report()) else saveReport(context, report())
                    }
                }
            }
            item {
                DiagCard("GPS") {
                    val fix = route.fix
                    Info(gpsText(route, now).third, if (gpsText(route, now).second) Cockpit.Text else Cockpit.Alert)
                    fix?.let {
                        Info("Proveedor: ${if (it.fromGps) "GPS" else "red"} · precisión ${it.accuracyM?.let { a -> "%.0f m".format(a) } ?: "?"} · " +
                            "velocidad GPS ${it.speedKmh?.let { v -> "%.0f km/h".format(v) } ?: "?"}", Cockpit.TextDim)
                    }
                    Info("Reintentos automáticos: $gpsRetries (si no llegan posiciones se vuelve a pedir el GPS, cada vez más espaciado, hasta 1 por minuto)", Cockpit.TextDim)
                }
            }
            item {
                DiagCard("Sensores y modo Night") {
                    ChipRow(listOf(if (showSensors) "Ocultar" else "Ver sensores y modo Night"), if (showSensors) 0 else -1) { showSensors = !showSensors }
                    if (showSensors) {
                        val sensors = remember { com.cockpitf30.vehicle.telemetry.Telemetry.sensorList(context) }
                        sensors.forEach { Info(it) }
                        val lights = radioValues["state.main.headlight_state"]
                        Info("Faros (state.main.headlight_state): ${lights ?: "?"} · oscuro: ${if (com.cockpitf30.ui.theme.DriveModes.dark) "sí" else "no"} (${com.cockpitf30.ui.theme.NightDetector.reason})", Cockpit.BlueLight)
                    }
                }
            }
            item {
                DiagCard("Datos del coche (la propia radio)") {
                    if (status.sourceName != "Radio (CAN)") Info("La fuente activa es «${status.sourceName}». Elige «Radio CAN» o «Auto» en Fuente de datos para leer el coche.", Cockpit.Alert)
                    Info("Leído: ${vehicleSummary(vehicle)}")
                    if (com.cockpitf30.vehicle.source.CanBoxData.summary().isEmpty()) Info("Caja CAN: todavía no ha llegado ninguna trama", Cockpit.TextDim)
                    else Info("Caja CAN: ${canBoxSummary(radioValues)}")
                    ChipRow(
                        listOf(
                            "Pedir velocidad y rpm a la caja: ${if (askSpeed) "sí" else "no"}",
                            if (showAllKeys) "Ocultar claves" else "Ver claves y cambios (${radioValues.size})",
                        ),
                        if (askSpeed) 0 else if (showAllKeys) 1 else -1,
                    ) {
                        if (it == 0) {
                            askSpeed = !askSpeed
                            settings.canAskSpeed = askSpeed
                        } else showAllKeys = !showAllKeys
                    }
                    if (showAllKeys) {
                        radioValues.toSortedMap().forEach { (k, v) -> Mono("$k = $v") }
                        Info("Últimos cambios:", Cockpit.TextDim)
                        if (radioChanges.isEmpty()) Info("Sin cambios todavía", Cockpit.TextDim)
                        radioChanges.take(40).forEach { Mono(it) }
                    }
                }
            }
            item {
                DiagCard("Fuente de datos") {
                    ChipRow(modes.map { it.label }, modes.indexOf(mode)) {
                        mode = modes[it]
                        settings.sourceMode = mode
                        VehicleService.restart(context)
                    }
                }
            }
            item {
                // Estado interno del asistente (lo que se elige está en Ajustes › Asistente de voz).
                val assistant = com.cockpitf30.assistant.VoiceAssistant
                val voice by assistant.state.collectAsStateWithLifecycle()
                val ttsStatus by assistant.ttsStatus.collectAsStateWithLifecycle()
                val stateLog by assistant.stateLog.collectAsStateWithLifecycle()
                DiagCard("Asistente de voz (técnico)") {
                    Info("Motor de voz: ${ttsStatus.text}" + (assistant.ttsEngine?.let { " · $it" } ?: "") + (assistant.ttsLastError?.let { " · $it" } ?: ""),
                        if (ttsStatus == com.cockpitf30.assistant.VoiceAssistant.TtsStatus.READY) Cockpit.TextDim else Cockpit.Alert)
                    Info("Voz en uso: ${assistant.currentVoice() ?: "—"} · ${assistant.spanishVoices().size} voces en español", Cockpit.TextDim)
                    Info("Estado: ${voice.phase} · micrófono ${"%.0f".format(voice.level * 100)} %" + (voice.error?.let { " · $it" } ?: ""))
                    Info("Modelo ${com.cockpitf30.assistant.VoiceModel.NAME}: " + if (com.cockpitf30.assistant.VoiceModel.isReady(context)) "instalado" else "no instalado")
                    voice.lastText?.let { Mono("Último reconocido: «$it»") }
                    voice.heard?.let { Mono("Oído: «$it»") }
                    voice.reply?.let { Mono("Respuesta: $it") }
                    assistant.lastFailure?.let { Info("Último fallo: $it", Cockpit.Alert) }
                    assistant.lastCut?.let { Info("Último corte de voz: $it", Cockpit.TextDim) }
                    assistant.lastSpeechEnd?.let { Info("Última frase dicha: $it", Cockpit.TextDim) }
                    Info("Reconocedor de Google: " + if (android.speech.SpeechRecognizer.isRecognitionAvailable(context)) "disponible" else "no disponible", Cockpit.TextDim)
                    if (stateLog.isNotEmpty()) {
                        Info("Registro (lo más nuevo arriba):", Cockpit.TextDim)
                        stateLog.take(15).forEach { Mono(it) }
                    }
                }
            }
            item {
                // Para ajustar la interfaz a radios con otra densidad o tamaño de letra.
                val dm = context.resources.displayMetrics
                val cfg = context.resources.configuration
                DiagCard("Pantalla") {
                    Info("${dm.widthPixels} x ${dm.heightPixels} px · ${dm.densityDpi} dpi · densidad ${"%.2f".format(dm.density)} · letra x${"%.2f".format(cfg.fontScale)}")
                    Info("Escala aplicada: ${"%.3f".format(appliedDesignDensity)} (lienzo ${DESIGN_WIDTH_DP.toInt()}x${DESIGN_HEIGHT_DP.toInt()}) · bandas ${appliedBandsPx.first} px lados, ${appliedBandsPx.second} px arriba/abajo")
                    Info("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                }
            }
            item {
                DiagCard("Eventos de entrada (gira el iDrive y pulsa botones)") {
                    if (input.isEmpty()) Info("Ninguno todavía")
                    input.forEach { Mono(it) }
                }
            }
        }
    }
    }
}

/** Texto corto para la cabecera, si hay posición reciente, y la frase completa para la tarjeta. */
private fun gpsText(route: com.cockpitf30.nav.RouteState, now: Long): Triple<String, Boolean, String> {
    val fix = route.fix
    if (!route.live || fix == null) {
        return Triple(if (route.position != null) "Demo" else "Sin posición", false,
            if (route.position != null) "Sin GPS real: el mapa usa el recorrido de demostración" else "Todavía no ha llegado ninguna posición")
    }
    val age = ((now - fix.elapsedMs) / 1000).coerceAtLeast(0)
    val fresh = age < 30
    return Triple(if (fresh) "Conectado" else "Hace ${age} s", fresh,
        if (fresh) "Recibiendo posiciones (última hace $age s)" else "Sin posiciones nuevas desde hace $age s: se está reintentando")
}

/** Cifra destacada de la cabecera de Diagnóstico. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.DiagStat(label: String, value: String, accent: Color) {
    Column(
        Modifier.weight(1f).fillMaxHeight().glassPanel(16.dp, accent).padding(horizontal = 22.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        PanelTitle(label, accent = accent)
        Text(value, style = numeric(36, FontWeight.Medium), color = Cockpit.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Bloque de cristal con su rótulo: cada sección de Diagnóstico va en uno. */
@Composable
private fun DiagCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().glassPanel(16.dp).padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PanelTitle(title, Modifier.padding(bottom = 6.dp))
        content()
    }
}

@Composable
private fun Info(text: String, color: Color = Cockpit.Text) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = color)
}

@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Cockpit.TextDim)
}

@Composable
private fun ChipRow(labels: List<String>, selected: Int, firstModifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        labels.forEachIndexed { i, label ->
            FocusTile(onClick = { onSelect(i) }, selected = i == selected, modifier = if (i == 0) firstModifier else Modifier) {
                Text(label, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private fun buildReport(
    context: android.content.Context,
    status: com.cockpitf30.vehicle.SourceStatus,
    input: List<String>,
): String = buildString {
    val dm = context.resources.displayMetrics
    appendLine("Cockpit F30 ${installedVersion(context)} · informe de diagnóstico · ${java.time.LocalDateTime.now()}")
    appendLine("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · ${android.os.Build.DISPLAY}")
    appendLine("Pantalla ${dm.widthPixels}x${dm.heightPixels} ${dm.densityDpi} dpi")
    appendLine()
    appendLine("== GPS")
    com.cockpitf30.nav.RouteTracker.state.value.let { r ->
        appendLine(gpsText(r, android.os.SystemClock.elapsedRealtime()).third)
        r.fix?.let { appendLine("proveedor ${if (it.fromGps) "gps" else "red"} · precisión ${it.accuracyM} · velocidad ${it.speedKmh}") }
    }
    appendLine("reintentos ${com.cockpitf30.nav.RouteTracker.retries.value}")
    appendLine()
    appendLine("== Sensores")
    com.cockpitf30.vehicle.telemetry.Telemetry.sensorList(context).forEach { appendLine(it) }
    appendLine("Modo ${com.cockpitf30.ui.theme.DriveModes.current} (elegido ${com.cockpitf30.ui.theme.DriveModes.selected}) · oscuro=${com.cockpitf30.ui.theme.DriveModes.dark} (${com.cockpitf30.ui.theme.NightDetector.reason})")
    com.cockpitf30.vehicle.telemetry.Telemetry.state.value.let { t ->
        appendLine("Telemetría: G ${t.gSource} long=${"%.2f".format(t.longG)} lat=${"%.2f".format(t.latG)} calibrado=${t.calibrated} · altitud ${t.altitudeM} (${t.altSource}) pendiente ${t.slopePct} · CV ${t.powerCv} Nm ${t.torqueNm} · lux ${t.lux}")
    }
    appendLine()
    appendLine("== Fuente")
    appendLine("${status.sourceName} · conectada=${status.connected}")
    status.lastError?.let { appendLine("Último error: $it") }
    appendLine()
    appendLine("== Teclas aprendidas")
    IDriveInput.learned.forEach { (k, v) -> appendLine("$k → $v") }
    appendLine()
    appendLine("== Eventos de entrada")
    input.forEach(::appendLine)
}

private fun vehicleSummary(v: com.cockpitf30.vehicle.VehicleState): String =
    "velocidad ${v.speedKmh ?: "?"} km/h · rpm ${v.rpm ?: "?"} · exterior ${v.outsideTempC ?: "?"} °C · autonomía ${v.rangeKm ?: "?"} km"

/** Lo que dice la caja CAN y no cabe en VehicleState: freno de mano, cinturones, litros, kilometraje. */
private fun canBoxSummary(values: Map<String, String>): String {
    fun yes(k: String, si: String, no: String) = when (values[k]) { "true" -> si; "false" -> no; else -> "?" }
    return "ACC ${yes("can.acc", "sí", "no")} · freno de mano ${yes("can.handbrake", "puesto", "quitado")} · " +
        "cierre ${yes("can.central_lock", "cerrado", "abierto")} · cinturón conductor ${yes("can.belt_driver", "puesto", "sin poner")} · " +
        "combustible ${values["can.fuel_liters"] ?: "?"} · km ${values["can.mileage"] ?: "?"} · autonomía ${values["can.fuel_range"] ?: "?"} · " +
        "d7 (¿temperatura?) ${values["can.d7_raw"] ?: "?"} · d8 ${values["can.d8_raw"] ?: "?"} · velocidad ${values["can.speed"] ?: "?"} · rpm ${values["can.rpm"] ?: "?"}"
}

private fun radioReport(values: Map<String, String>, changes: List<String>, vehicle: com.cockpitf30.vehicle.VehicleState): String = buildString {
    appendLine()
    appendLine("== Sintonizador FM")
    com.cockpitf30.radio.FactoryTuner.lastProbe?.let { appendLine("Comprobación: ${it.reason}") }
    com.cockpitf30.radio.Radio.fmTest.value?.takeIf { !it.running }?.lines?.forEach(::appendLine)
        ?: run { appendLine("(sin «Prueba FM»; últimas órdenes:)"); com.cockpitf30.radio.FactoryTuner.trace.value.forEach(::appendLine) }
    appendLine()
    appendLine("== Datos del coche (radio)")
    appendLine(vehicleSummary(vehicle))
    appendLine()
    appendLine("== Caja CAN (tramas callback.pt.canbus_cmd)")
    appendLine(canBoxSummary(values))
    com.cockpitf30.vehicle.source.CanBoxData.summary().forEach(::appendLine)
    appendLine("-- cambios de contenido (la más nueva arriba)")
    com.cockpitf30.vehicle.source.CanBoxData.changes().forEach(::appendLine)
    appendLine()
    values.toSortedMap().forEach { (k, v) -> appendLine("$k = $v") }
    appendLine()
    appendLine("== Cambios de claves")
    changes.forEach(::appendLine)
}

internal fun shareReport(context: android.content.Context, text: String, subject: String = "Cockpit F30 diagnóstico") {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
        // El texto va en el propio Intent: lo recortamos para no pasar del límite de Binder.
        .putExtra(android.content.Intent.EXTRA_TEXT, text.take(150_000))
    runCatching {
        context.startActivity(android.content.Intent.createChooser(send, "Compartir informe").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { android.widget.Toast.makeText(context, "No hay apps para compartir", android.widget.Toast.LENGTH_SHORT).show() }
}

internal fun saveReport(context: android.content.Context, text: String, prefix: String = "cockpit-diagnostico") {
    val name = "$prefix-${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.txt"
    val where = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore no devolvió destino")
            context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
            "Descargas/$name"
        } else {
            val file = java.io.File(context.getExternalFilesDir(null), name)
            file.writeText(text)
            file.absolutePath
        }
    }
    android.widget.Toast.makeText(
        context,
        where.fold({ "Guardado en $it" }, { "No se pudo guardar: ${it.message}" }),
        android.widget.Toast.LENGTH_LONG,
    ).show()
}

/** Versión de la app tal como está instalada (nombre y código de versión del paquete). */
internal fun installedVersion(context: android.content.Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    "${info.versionName} (build $code)"
}.getOrDefault("desconocida")
