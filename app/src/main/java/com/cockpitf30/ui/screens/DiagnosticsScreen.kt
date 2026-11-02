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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.KeyEvent
import com.cockpitf30.input.IDriveInput
import com.cockpitf30.input.KnobFunction
import com.cockpitf30.vehicle.probe.BroadcastSniffer
import com.cockpitf30.vehicle.probe.PortProbe
import com.cockpitf30.vehicle.probe.SystemProbe
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cockpitf30.service.VehicleService
import com.cockpitf30.system.AppSettings
import com.cockpitf30.system.BatteryOptimization
import com.cockpitf30.system.SourceMode
import com.cockpitf30.ui.components.FocusTile
import com.cockpitf30.ui.components.InitialFocus
import com.cockpitf30.ui.components.TopBar
import com.cockpitf30.ui.components.hexBackground
import com.cockpitf30.ui.theme.Cockpit
import com.cockpitf30.vehicle.VehicleRepository
import com.cockpitf30.vehicle.source.CarPropertySource
import com.cockpitf30.vehicle.source.SerialSource
import com.cockpitf30.ui.theme.DESIGN_HEIGHT_DP
import com.cockpitf30.ui.theme.DESIGN_WIDTH_DP
import com.cockpitf30.ui.theme.appliedDesignDensity

/**
 * Herramientas para descubrir cómo habla esta placa: elegir fuente y puerto, ver tramas
 * en crudo y ver qué eventos manda el iDrive.
 */
@Composable
fun DiagnosticsScreen() {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val status by VehicleRepository.status.collectAsStateWithLifecycle()
    val raw by VehicleRepository.rawLog.collectAsStateWithLifecycle()
    val input by VehicleRepository.inputLog.collectAsStateWithLifecycle()
    val bytes by VehicleRepository.byteLog.collectAsStateWithLifecycle()
    val learning by IDriveInput.learning.collectAsStateWithLifecycle()
    val broadcasts by BroadcastSniffer.log.collectAsStateWithLifecycle()
    val listening by BroadcastSniffer.listening.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var learned by remember { mutableStateOf(IDriveInput.learned) }
    var probes by remember { mutableStateOf<List<String>?>(null) }
    var packages by remember { mutableStateOf<List<SystemProbe.PackageReport>?>(null) }
    var props by remember { mutableStateOf<List<String>>(emptyList()) }
    var sysApps by remember { mutableStateOf<List<String>>(emptyList()) }
    var services by remember { mutableStateOf<List<String>>(emptyList()) }
    var jars by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }

    var mode by remember { mutableStateOf(settings.sourceMode) }
    var path by remember { mutableStateOf(settings.serialPath) }
    var baud by remember { mutableIntStateOf(settings.baudRate) }
    var batteryOk by remember { mutableStateOf(BatteryOptimization.isIgnoring(context)) }
    val ports = remember { SerialSource.candidatePorts() }
    // Cuando se aprende una tecla, el mapa cambia fuera de Compose: lo releemos.
    androidx.compose.runtime.LaunchedEffect(learning) { learned = IDriveInput.learned }

    fun probePorts() {
        busy = "Probando puertos…"
        scope.launch {
            probes = withContext(Dispatchers.IO) {
                val list = PortProbe.candidates()
                if (list.isEmpty()) listOf("No hay puertos (/dev ${if (PortProbe.devListable()) "listable" else "no listable"})")
                else list.map { PortProbe.probe(it) }
            }
            busy = null
        }
    }

    fun scanSystem() {
        busy = "Buscando apps del coche…"
        scope.launch {
            withContext(Dispatchers.IO) {
                packages = SystemProbe.scan(context)
                props = SystemProbe.properties()
                sysApps = SystemProbe.systemApps(context)
                services = SystemProbe.systemServices()
                jars = SystemProbe.frameworkJars()
            }
            busy = null
        }
    }

    fun report() = buildReport(context, status, probes, packages, props, input, raw, bytes, broadcasts, sysApps, services, jars)
    val automotive = remember { CarPropertySource.isAvailable(context) }

    fun apply() {
        settings.sourceMode = mode
        settings.serialPath = path
        settings.baudRate = baud
        VehicleService.restart(context)
    }

    // Al volver del diálogo de batería refrescamos el estado.
    LifecycleResumeEffect(Unit) {
        batteryOk = BatteryOptimization.isIgnoring(context)
        onPauseOrDispose {}
    }

    val first = remember { FocusRequester() }
    InitialFocus(first)

    Column(Modifier.fillMaxSize().hexBackground()) {
        TopBar("Diagnóstico")
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Section("Estado")
                Info("Fuente: ${status.sourceName} · ${if (status.connected) "conectada" else "desconectada"}")
                Info("Bytes leídos: ${status.bytesReceived} · tramas: ${status.framesReceived} · checksum erróneo: ${status.badChecksums}")
                status.lastError?.let { Info("Último error: $it", Cockpit.Alert) }
                Info("Android Automotive (CarPropertyManager): ${if (automotive) "sí" else "no"}")
                busy?.let { Info(it, Cockpit.BlueLight) }
                ChipRow(listOf("Compartir informe", "Guardar informe en Descargas"), -1) {
                    if (it == 0) shareReport(context, report()) else saveReport(context, report())
                }
            }
            item {
                Section("Ruleta iDrive: enseñar botones")
                Info(
                    if (learning != null) "Pulsa ahora en el iDrive el botón para «${learning!!.label}»… (toca de nuevo para cancelar)"
                    else "Toca una función y luego pulsa en el iDrive el botón que quieras para ella.",
                    if (learning != null) Cockpit.BlueLight else Cockpit.Text,
                )
                ChipRow(KnobFunction.entries.map { it.label }, KnobFunction.entries.indexOf(learning)) {
                    val f = KnobFunction.entries[it]
                    IDriveInput.startLearning(if (learning == f) null else f)
                }
                if (learned.isEmpty()) Info("Sin teclas aprendidas: se usan DPAD, ENTER, TAB y la ruleta estándar.", Cockpit.TextDim)
                else {
                    learned.forEach { (from, to) ->
                        Info("${KeyEvent.keyCodeToString(from)} ($from) → ${KnobFunction.ofKeyCode(to)?.label ?: KeyEvent.keyCodeToString(to)}")
                    }
                    ChipRow(listOf("Borrar teclas aprendidas"), -1) {
                        IDriveInput.learned = emptyMap()
                        settings.knobKeys = emptyMap()
                        learned = emptyMap()
                    }
                }
            }
            item {
                Section("Fuente de datos")
                ChipRow(SourceMode.entries.map { it.label }, SourceMode.entries.indexOf(mode), Modifier.focusRequester(first)) {
                    mode = SourceMode.entries[it]
                    apply()
                }
            }
            item {
                Section("Puerto serie")
                if (ports.isEmpty()) Info("No se encontró ningún /dev/ttyMT* ni /dev/ttyS* (/dev ${if (PortProbe.devListable()) "listable" else "no listable"})")
                ChipRow(
                    ports.map { "${it.name} ${if (it.canRead()) "r" else "-"}${if (it.canWrite()) "w" else "-"}" },
                    ports.indexOfFirst { it.absolutePath == path },
                ) {
                    path = ports[it].absolutePath
                    apply()
                }
                ChipRow(BAUDS.map { "$it" }, BAUDS.indexOf(baud)) {
                    baud = BAUDS[it]
                    apply()
                }
                ChipRow(listOf("Probar todos los puertos (2 s cada uno)"), -1) { probePorts() }
                probes?.forEach { Mono(it) }
            }
            item {
                Section("Apps del CAN / MCU en la radio")
                Info("Si la radio reparte los datos del coche por broadcast, se pueden escuchar sin root.", Cockpit.TextDim)
                val actions = packages.orEmpty().flatMap { it.actions }.distinct()
                ChipRow(
                    listOf(
                        "Buscar apps del coche",
                        if (listening > 0) "Dejar de escuchar ($listening acciones)" else "Escuchar sus broadcasts (${actions.size})",
                    ),
                    if (listening > 0) 1 else -1,
                ) {
                    when {
                        it == 0 -> scanSystem()
                        listening > 0 -> BroadcastSniffer.stop(context)
                        else -> BroadcastSniffer.start(context, actions)
                    }
                }
                packages?.let { list ->
                    if (list.isEmpty()) Info("No se encontró ninguna app candidata.")
                    list.forEach { p ->
                        Info("${p.label} · ${p.packageName}${if (p.system) " · sistema" else ""}")
                        Mono("  servicios ${p.services.size}, receptores ${p.receivers.size}, acciones ${p.actions.size}")
                        if (p.providers.isNotEmpty()) Mono("  providers: ${p.providers.joinToString()}")
                        p.actions.take(8).forEach { a -> Mono("  · $a") }
                        if (p.actions.size > 8) Mono("  … y ${p.actions.size - 8} más (en el informe)")
                    }
                }
                if (sysApps.isNotEmpty() || services.isNotEmpty()) {
                    Info("Apps del sistema: ${sysApps.size} · servicios del sistema: ${services.size} · jars del framework: ${jars.size} (todo en el informe)", Cockpit.TextDim)
                    (services + jars).filter(SystemProbe::interesting).take(20).forEach { Mono(it) }
                }
                if (props.isNotEmpty()) {
                    Info("Propiedades del sistema relacionadas:", Cockpit.TextDim)
                    props.take(30).forEach { Mono(it) }
                }
            }
            item { Section("Broadcasts recibidos (abre una puerta, arranca…)") }
            if (broadcasts.isEmpty()) item { Info(if (listening > 0) "Ninguno todavía" else "Parado") }
            items(broadcasts) { Mono(it) }
            item {
                // Para ajustar la interfaz a radios con otra densidad o tamaño de letra.
                val dm = context.resources.displayMetrics
                val cfg = context.resources.configuration
                Section("Pantalla")
                Info("${dm.widthPixels} x ${dm.heightPixels} px · ${dm.densityDpi} dpi · densidad ${"%.2f".format(dm.density)} · letra x${"%.2f".format(cfg.fontScale)}")
                Info("Escala aplicada: ${"%.3f".format(appliedDesignDensity)} (lienzo ${DESIGN_WIDTH_DP.toInt()}x${DESIGN_HEIGHT_DP.toInt()})")
                Info("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            }
            item {
                Section("Batería")
                ChipRow(
                    listOf(if (batteryOk) "Excluida de la optimización ✓" else "Pedir exclusión de optimización"),
                    if (batteryOk) 0 else -1,
                ) {
                    BatteryOptimization.request(context)
                }
            }
            item { Section("Tramas recibidas (más reciente arriba)") }
            if (raw.isEmpty()) item { Info("Ninguna todavía") }
            items(raw) { Mono(it) }
            item { Section("Bytes en crudo del puerto (aunque no sean tramas Raise)") }
            if (bytes.isEmpty()) item { Info("Ninguno todavía") }
            items(bytes) { Mono(it) }
            item { Section("Eventos de entrada (gira el iDrive y pulsa botones)") }
            if (input.isEmpty()) item { Info("Ninguno todavía") }
            items(input) { Mono(it) }
        }
    }
}

private val BAUDS = listOf(19200, 38400, 57600, 115200)

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = Cockpit.BlueLight,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun Info(text: String, color: androidx.compose.ui.graphics.Color = Cockpit.Text) {
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
    probes: List<String>?,
    packages: List<SystemProbe.PackageReport>?,
    props: List<String>,
    input: List<String>,
    raw: List<String>,
    bytes: List<String>,
    broadcasts: List<String>,
    sysApps: List<String>,
    services: List<String>,
    jars: List<String>,
): String = buildString {
    val dm = context.resources.displayMetrics
    appendLine("Cockpit F30 · informe de diagnóstico · ${java.time.LocalDateTime.now()}")
    appendLine("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · ${android.os.Build.DISPLAY}")
    appendLine("Pantalla ${dm.widthPixels}x${dm.heightPixels} ${dm.densityDpi} dpi")
    appendLine()
    appendLine("== Fuente")
    appendLine("${status.sourceName} · conectada=${status.connected} · bytes=${status.bytesReceived} · tramas=${status.framesReceived} · checksum mal=${status.badChecksums}")
    status.lastError?.let { appendLine("Último error: $it") }
    appendLine()
    appendLine("== Puertos")
    PortProbe.candidates().forEach { appendLine(PortProbe.describe(it)) }
    probes?.forEach { appendLine(it) }
    appendLine()
    appendLine("== Teclas aprendidas")
    IDriveInput.learned.forEach { (k, v) -> appendLine("$k → $v") }
    appendLine()
    appendLine("== Apps del CAN / MCU")
    if (packages == null) appendLine("(no se buscó: pulsa «Buscar apps del coche» antes de compartir)")
    packages?.forEach { p ->
        appendLine("${p.label} · ${p.packageName}${if (p.system) " · sistema" else ""}")
        p.services.forEach { appendLine("  servicio $it") }
        p.receivers.forEach { appendLine("  receptor $it") }
        p.providers.forEach { appendLine("  provider $it") }
        p.actions.forEach { appendLine("  acción $it") }
    }
    if (props.isNotEmpty()) {
        appendLine()
        appendLine("== Propiedades")
        props.forEach(::appendLine)
    }
    fun section(title: String, lines: List<String>) {
        appendLine()
        appendLine("== $title")
        lines.forEach(::appendLine)
    }
    section("Servicios del sistema", services)
    section("Jars del framework", jars)
    section("Apps del sistema", sysApps)
    section("Broadcasts", broadcasts)
    section("Eventos de entrada", input)
    section("Tramas Raise", raw)
    section("Bytes en crudo", bytes)
}

private fun shareReport(context: android.content.Context, text: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_SUBJECT, "Cockpit F30 diagnóstico")
        // El texto va en el propio Intent: lo recortamos para no pasar del límite de Binder.
        .putExtra(android.content.Intent.EXTRA_TEXT, text.take(150_000))
    runCatching {
        context.startActivity(android.content.Intent.createChooser(send, "Compartir informe").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { android.widget.Toast.makeText(context, "No hay apps para compartir", android.widget.Toast.LENGTH_SHORT).show() }
}

private fun saveReport(context: android.content.Context, text: String) {
    val name = "cockpit-diagnostico-${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.txt"
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
