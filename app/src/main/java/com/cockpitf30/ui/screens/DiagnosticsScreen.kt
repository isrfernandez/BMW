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
import com.cockpitf30.vehicle.source.CarPropertySource
import com.cockpitf30.vehicle.source.SerialSource
import com.cockpitf30.ui.theme.DESIGN_HEIGHT_DP
import com.cockpitf30.ui.theme.DESIGN_WIDTH_DP
import com.cockpitf30.ui.theme.appliedBandsPx
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
    var carProbe by remember { mutableStateOf<List<String>>(emptyList()) }
    var dataProbe by remember { mutableStateOf<List<String>>(emptyList()) }
    var dexActions by remember { mutableStateOf<List<String>>(emptyList()) }
    val callbacks by com.cockpitf30.vehicle.probe.CarDataProbe.callbackLog.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf<String?>(null) }
    val radioValues by com.cockpitf30.vehicle.source.RadioCarData.values.collectAsStateWithLifecycle()
    val radioChanges by com.cockpitf30.vehicle.source.RadioCarData.changes.collectAsStateWithLifecycle()
    val vehicle by VehicleRepository.state.collectAsStateWithLifecycle()
    var canbusProbe by remember { mutableStateOf<List<String>>(emptyList()) }
    var showAllKeys by remember { mutableStateOf(false) }
    var askSpeed by remember { mutableStateOf(settings.canAskSpeed) }
    var testRunning by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

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
                dexActions = com.cockpitf30.vehicle.probe.CarServiceProbe.dexActions(context)
            }
            busy = null
        }
    }

    fun exploreCarService() {
        busy = "Explorando el servicio del coche…"
        scope.launch {
            carProbe = withContext(Dispatchers.IO) {
                runCatching { com.cockpitf30.vehicle.probe.CarServiceProbe.run(context) }.getOrElse { listOf("Error: $it") }
            }
            busy = null
        }
    }

    fun readCarData() {
        busy = "Leyendo datos con CarDataManager…"
        scope.launch {
            dataProbe = withContext(Dispatchers.IO) {
                runCatching { com.cockpitf30.vehicle.probe.CarDataProbe.explore(context) }.getOrElse { listOf("Error: $it") }
            }
            busy = null
        }
    }

    fun exploreCanbus() {
        busy = "Explorando CarCanbus…"
        scope.launch {
            canbusProbe = withContext(Dispatchers.IO) {
                runCatching { com.cockpitf30.vehicle.probe.CanbusProbe.explore(context) }.getOrElse { listOf("Error: $it") }
            }
            busy = null
        }
    }

    // Lo de la radio va al principio: el informe completo pasa de 150 KB y al compartir se recorta.
    fun report() = buildReport(context, status, probes, packages, props, input, raw, bytes, broadcasts, sysApps, services, jars, carProbe, dataProbe, callbacks)
        .replaceFirst("\n== Puertos", radioReport(radioValues, radioChanges, canbusProbe, vehicle) + "\n== Puertos")
    val automotive = remember { CarPropertySource.isAvailable(context) }

    /**
     * Todo de una vez: busca las apps del coche, escucha sus broadcasts, explora el servicio,
     * lee el almacén, se apunta a los avisos y a CarCanbus, deja [TEST_SECONDS] para abrir puertas
     * y girar el volante, y guarda informe + APKs en un zip en Descargas.
     */
    fun fullTest() {
        if (testRunning) return
        testRunning = true
        testResult = null
        scope.launch {
            fun step(text: String) { busy = text }
            if (settings.sourceMode != SourceMode.RADIO && settings.sourceMode != SourceMode.AUTO) {
                mode = SourceMode.AUTO
                settings.sourceMode = SourceMode.AUTO
                VehicleService.restart(context)
            }
            step("1/6 Buscando apps del coche…")
            withContext(Dispatchers.IO) {
                packages = SystemProbe.scan(context)
                props = SystemProbe.properties()
                sysApps = SystemProbe.systemApps(context)
                services = SystemProbe.systemServices()
                jars = SystemProbe.frameworkJars()
                dexActions = com.cockpitf30.vehicle.probe.CarServiceProbe.dexActions(context)
            }
            BroadcastSniffer.start(context, (packages.orEmpty().flatMap { it.actions } + dexActions).distinct())
            step("2/6 Explorando el servicio del coche…")
            carProbe = withContext(Dispatchers.IO) { runCatching { com.cockpitf30.vehicle.probe.CarServiceProbe.run(context) }.getOrElse { listOf("Error: $it") } }
            step("3/6 Leyendo el almacén de datos…")
            dataProbe = withContext(Dispatchers.IO) { runCatching { com.cockpitf30.vehicle.probe.CarDataProbe.explore(context) }.getOrElse { listOf("Error: $it") } }
            withContext(Dispatchers.IO) { runCatching { com.cockpitf30.vehicle.probe.CarDataProbe.startCallbacks(context) } }
            step("4/6 Conectando con CarCanbus…")
            canbusProbe = withContext(Dispatchers.IO) { runCatching { com.cockpitf30.vehicle.probe.CanbusProbe.explore(context) }.getOrElse { listOf("Error: $it") } }
            for (left in TEST_SECONDS downTo 1) {
                step("5/6 Ahora: abre y cierra cada puerta, el maletero y el capó, gira el volante y acelera un poco… $left s")
                kotlinx.coroutines.delay(1000)
            }
            step("6/6 Guardando el zip (informe + apps del coche)…")
            val r = withContext(Dispatchers.IO) {
                runCatching { com.cockpitf30.vehicle.probe.CanbusProbe.writeTestZip(context, report()) }.getOrElse { "Error: ${it.message}" }
            }
            testResult = r
            busy = null
            testRunning = false
            android.widget.Toast.makeText(context, r.take(200), android.widget.Toast.LENGTH_LONG).show()
        }
    }

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
                Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    DiagStat("Versión", installedVersion(context), Cockpit.BlueLight)
                    DiagStat("Fuente", status.sourceName, if (status.connected) Cockpit.Ok else Cockpit.Alert)
                    DiagStat("Tramas", "${status.framesReceived}", Color(0xFF7FD3FF))
                    DiagStat("Bytes leídos", "${status.bytesReceived}", Color(0xFFFFB74D))
                }
            }
            item {
                DiagCard("Prueba completa") {
                    Info("1. Arranca el motor. 2. Pulsa el botón. 3. Cuando lo pida, abre y cierra cada puerta, el maletero y el capó, gira el volante y acelera un poco. " +
                        "Al acabar (unos 2 minutos) queda un zip en Descargas: pásamelo tal cual.", Cockpit.TextDim)
                    ChipRow(listOf(if (testRunning) "Prueba en marcha…" else "Prueba completa"), if (testRunning) 0 else -1, Modifier.focusRequester(first)) { fullTest() }
                    busy?.let { Info(it, Cockpit.BlueLight) }
                    testResult?.let { Info("Guardado: $it", if (it.startsWith("Error")) Cockpit.Alert else Cockpit.Ok) }
                }
            }
            item {
                DiagCard("Sensores y modo Night") {
                    val sensors = remember { com.cockpitf30.vehicle.telemetry.Telemetry.sensorList(context) }
                    sensors.forEach { Info(it) }
                    val lights = radioValues["state.main.headlight_state"]
                    Info("Faros (state.main.headlight_state): ${lights ?: "?"} · oscuro: ${if (com.cockpitf30.ui.theme.DriveModes.dark) "sí" else "no"} (${com.cockpitf30.ui.theme.NightDetector.reason})", Cockpit.BlueLight)
                }
            }
            item {
                DiagCard("Estado") {
                    Info("Versión instalada: ${installedVersion(context)}", Cockpit.BlueLight)
                    Info("Fuente: ${status.sourceName} · ${if (status.connected) "conectada" else "desconectada"}")
                    Info("Bytes leídos: ${status.bytesReceived} · tramas: ${status.framesReceived} · checksum erróneo: ${status.badChecksums}")
                    status.lastError?.let { Info("Último error: $it", Cockpit.Alert) }
                    Info("Android Automotive (CarPropertyManager): ${if (automotive) "sí" else "no"}")
                    busy?.let { Info(it, Cockpit.BlueLight) }
                    ChipRow(listOf("Compartir informe", "Guardar informe en Descargas"), -1) {
                        if (it == 0) shareReport(context, report()) else saveReport(context, report())
                    }
                }
            }
            item {
                DiagCard("Datos del coche (la propia radio)") {
                    if (status.sourceName != "Radio (CAN)") Info("La fuente activa es «${status.sourceName}». Elige «Radio CAN» o «Auto» en Fuente de datos para leer el coche.", Cockpit.Alert)
                    Info("Leído: ${vehicleSummary(vehicle)}")
                    val frames = com.cockpitf30.vehicle.source.CanBoxData.summary()
                    if (frames.isEmpty()) Info("Caja CAN: todavía no ha llegado ninguna trama", Cockpit.TextDim)
                    else {
                        Info("Caja CAN: ${canBoxSummary(radioValues)}")
                        frames.forEach { Mono(it) }
                        val canChanges = com.cockpitf30.vehicle.source.CanBoxData.changes()
                        Info("Cambios en las tramas (abre las puertas de una en una, con unos segundos entre cada una):", Cockpit.TextDim)
                        canChanges.take(30).forEach { Mono(it) }
                    }
                    ChipRow(listOf("Pedir velocidad y rpm a la caja: ${if (askSpeed) "sí" else "no"}"), if (askSpeed) 0 else -1) {
                        askSpeed = !askSpeed
                        settings.canAskSpeed = askSpeed
                    }
                    Info("Claves en la radio: ${radioValues.size}. Abre y cierra cada puerta, el maletero y el capó, arranca y gira el volante: aquí abajo sale qué clave cambia.", Cockpit.TextDim)
                    ChipRow(
                        listOf(if (showAllKeys) "Ocultar claves" else "Ver todas las claves (${radioValues.size})", "Explorar CarCanbus"),
                        if (showAllKeys) 0 else -1,
                    ) {
                        when (it) {
                            0 -> showAllKeys = !showAllKeys
                            else -> exploreCanbus()
                        }
                    }
                    if (showAllKeys) radioValues.toSortedMap().forEach { (k, v) -> Mono("$k = $v") }
                    if (radioChanges.isEmpty()) Info("Sin cambios todavía", Cockpit.TextDim)
                    radioChanges.take(40).forEach { Mono(it) }
                    if (canbusProbe.isNotEmpty()) {
                        Info("CarCanbus (${canbusProbe.size} líneas, todo en el informe):", Cockpit.TextDim)
                        canbusProbe.filter { " = " in it || it.startsWith("--") || "conectado" in it }.take(40).forEach { Mono(it) }
                    }
                }
            }
            item {
                DiagCard("Ruleta iDrive: enseñar botones") {
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
            }
            item {
                DiagCard("Pantalla de inicio (lanzador)") {
                    val isHome = remember { isDefaultHome(context) }
                    Info(
                        if (isHome) "Cockpit F30 es ahora la pantalla de inicio de la radio. Para volver a la de serie u otra, elige aquí."
                        else "La pantalla de inicio de la radio es otra app. Puedes poner Cockpit F30 desde aquí.",
                    )
                    ChipRow(listOf("Elegir pantalla de inicio", "Abrir la de serie (BMW)"), -1) {
                        when (it) {
                            0 -> chooseHome(context)
                            else -> openStockLauncher(context)
                        }
                    }
                }
            }
            item {
                DiagCard("Fuente de datos") {
                    ChipRow(SourceMode.entries.map { it.label }, SourceMode.entries.indexOf(mode)) {
                        mode = SourceMode.entries[it]
                        apply()
                    }
                }
            }
            item {
                DiagCard("Puerto serie") {
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
            }
            item {
                DiagCard("Apps del CAN / MCU en la radio") {
                    Info("Si la radio reparte los datos del coche por broadcast, se pueden escuchar sin root.", Cockpit.TextDim)
                    val actions = (packages.orEmpty().flatMap { it.actions } + dexActions).distinct()
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
                    ChipRow(listOf("Explorar el servicio del coche (car / car_data)"), -1) { exploreCarService() }
                    ChipRow(listOf("Leer datos (CarDataManager)", "Escuchar avisos del servicio"), -1) {
                        if (it == 0) readCarData()
                        else scope.launch {
                            val r = withContext(Dispatchers.IO) { com.cockpitf30.vehicle.probe.CarDataProbe.startCallbacks(context) }
                            android.widget.Toast.makeText(context, r.take(120), android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                    if (dataProbe.isNotEmpty()) {
                        dataProbe.filter { it.contains(") = ") || it.startsWith("--") || it.startsWith("DataType") }.take(40).forEach { Mono(it) }
                        Mono("(${dataProbe.size} líneas en el informe)")
                    }
                    if (carProbe.isNotEmpty()) {
                        carProbe.take(40).forEach { Mono(it) }
                        if (carProbe.size > 40) Mono("… y ${carProbe.size - 40} líneas más (en el informe)")
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
            }
            item {
                DiagCard("Avisos del servicio del coche") {
                    if (callbacks.isEmpty()) Info("Ninguno (pulsa «Escuchar avisos del servicio» y abre una puerta)")
                    callbacks.take(60).forEach { Mono(it) }
                }
            }
            item {
                DiagCard("Broadcasts recibidos (abre una puerta, arranca…)") {
                    if (broadcasts.isEmpty()) Info(if (listening > 0) "Ninguno todavía" else "Parado")
                    broadcasts.forEach { Mono(it) }
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
                // Nivel de humor del asistente, como el robot TARS. También por voz: «Hola BMW, humor al 60 por ciento».
                val assistant = com.cockpitf30.assistant.VoiceAssistant
                LaunchedEffect(Unit) { assistant.loadHumor(context) }
                val humor by assistant.humor.collectAsStateWithLifecycle()
                DiagCard("Humor del asistente: $humor %") {
                    ChipRow(listOf("− 5 %", "+ 5 %"), -1) { assistant.setHumor(context, humor + if (it == 0) -5 else 5) }
                    val levels = listOf(0, 25, 50, 75, 90, 100)
                    ChipRow(levels.map { "$it %" }, levels.indexOf(humor)) { assistant.setHumor(context, levels[it]) }
                    Info("0 % responde seco y sin bromas; 100 % siempre con una broma. Por voz: «Hola BMW, humor al 60 por ciento», «sube el humor», «baja el humor» o «¿qué humor tienes?».", Cockpit.TextDim)
                }
            }
            item {
                // Conversación seguida, memoria y modo copiloto (charla cada cierto tiempo contra la fatiga).
                val assistant = com.cockpitf30.assistant.VoiceAssistant
                var follow by remember { mutableStateOf(settings.assistantFollowUp) }
                var copilot by remember { mutableStateOf(settings.copilotEnabled) }
                var minutes by remember { mutableStateOf(settings.copilotMinutes) }
                var name by remember { mutableStateOf(settings.assistantUserName) }
                DiagCard("Conversación y copiloto") {
                    ChipRow(listOf("Sigue escuchando tras responder", "Solo con «Hola BMW»"), if (follow) 0 else 1) {
                        follow = it == 0
                        settings.assistantFollowUp = follow
                    }
                    var sens by remember { mutableStateOf(settings.wakeSensitivity) }
                    ChipRow(listOf("«Hola BMW»: sensibilidad baja", "Normal", "Alta"), 2 - sens) {
                        sens = 2 - it
                        settings.wakeSensitivity = sens
                    }
                    ChipRow(listOf("Copiloto activado", "Copiloto desactivado", "Probar ahora"), if (copilot) 0 else 1) {
                        if (it == 2) assistant.copilotNow(context)
                        else {
                            copilot = it == 0
                            settings.copilotEnabled = copilot
                        }
                    }
                    val options = listOf(5, 10, 15, 20, 30)
                    ChipRow(options.map { "Cada $it min" }, options.indexOf(minutes)) {
                        minutes = options[it]
                        settings.copilotMinutes = minutes
                    }
                    ChipRow(listOf(name?.let { "Te llamas $it" } ?: "Sin nombre", "Olvidar nombre y frases"), 0) {
                        if (it == 1) {
                            assistant.forgetMemory(context)
                            name = null
                        }
                    }
                    Info("Tras contestar sigue escuchando unos segundos: puedes decir «otra», «¿y mañana?», «llévame», «repite» o «¿de qué hablábamos?» sin repetir «Hola BMW». " +
                        "El copiloto habla solo con el coche en marcha (en demo, cada 2 minutos): descansos, autonomía, mal tiempo, destino o una pregunta. Por voz: «activa / desactiva el copiloto», «cállate un rato».", Cockpit.TextDim)
                }
            }
            item {
                // Funciones de conducción: resumen al parar, flecha de cambio, briefing y luz ambiente.
                var summaryOn by remember { mutableStateOf(settings.tripSummary) }
                var shiftOn by remember { mutableStateOf(settings.shiftIndicator) }
                var briefingOn by remember { mutableStateOf(settings.briefing) }
                var glowOn by remember { mutableStateOf(settings.ambientLight) }
                DiagCard("Conducción: resumen, cambio de marcha, briefing y luz ambiente") {
                    ChipRow(listOf("Resumen al apagar el motor", "Sin resumen", "Ver ahora"), if (summaryOn) 0 else 1) {
                        if (it == 2) com.cockpitf30.vehicle.trip.TripComputer.showSummary(System.currentTimeMillis())
                        else { summaryOn = it == 0; settings.tripSummary = summaryOn }
                    }
                    ChipRow(listOf("Flecha de cambio de marcha", "Sin flecha"), if (shiftOn) 0 else 1) {
                        shiftOn = it == 0; settings.shiftIndicator = shiftOn
                    }
                    ChipRow(listOf("Briefing al arrancar", "Sin briefing", "Probar ahora"), if (briefingOn) 0 else 1) {
                        if (it == 2) com.cockpitf30.assistant.StartupBriefing.speak(context)
                        else { briefingOn = it == 0; settings.briefing = briefingOn }
                    }
                    ChipRow(listOf("Luz ambiente", "Sin luz ambiente"), if (glowOn) 0 else 1) {
                        glowOn = it == 0; settings.ambientLight = glowOn
                    }
                    Info("La flecha y la luz ambiente se aplican al volver a abrir la pantalla. El briefing se dice al arrancar si han pasado más de 45 minutos desde el anterior.", Cockpit.TextDim)
                }
            }
            item {
                // Avisos en ruta: radares, peligros, combustible, áreas de descanso y sitios turísticos.
                val pois by com.cockpitf30.nav.RoutePois.state.collectAsStateWithLifecycle()
                val alertScope = androidx.compose.runtime.rememberCoroutineScope()
                var alertsOn by remember { mutableStateOf(settings.routeAlerts) }
                var cats by remember { mutableStateOf(com.cockpitf30.nav.AlertCategory.entries.filter { settings.alertCategory(it) }.toSet()) }
                DiagCard("Avisos en ruta y puntos de interés") {
                    ChipRow(listOf("Activados", "Desactivados", "Probar ahora"), if (alertsOn) 0 else 1) {
                        if (it == 2) com.cockpitf30.nav.RouteAlerts.test(alertScope)
                        else {
                            alertsOn = it == 0
                            settings.routeAlerts = alertsOn
                            if (!alertsOn) com.cockpitf30.nav.RouteAlerts.hide()
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        com.cockpitf30.nav.AlertCategory.entries.forEach { c ->
                            val on = c in cats
                            FocusTile(onClick = {
                                cats = if (on) cats - c else cats + c
                                settings.setAlertCategory(c, !on)
                            }, selected = on && alertsOn) {
                                Text((if (on) "✓ " else "✗ ") + c.label, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyLarge,
                                    color = if (on) Cockpit.Text else Cockpit.TextDim)
                            }
                        }
                    }
                    Info(
                        when {
                            pois.loading -> "Descargando puntos de interés…"
                            pois.error != null && pois.pois.isEmpty() -> "Puntos de interés: sin conexión (${pois.error})"
                            else -> "${pois.pois.size} puntos en 12 km (OpenStreetMap) · ${pois.near.size} a menos de 5 km"
                        }, Cockpit.TextDim,
                    )
                    Info("El asistente avisa de radares, peligros señalizados y pasos a nivel, áreas de descanso (tras hora y media al volante), " +
                        "gasolinera más cercana con poca autonomía y monumentos, miradores o pueblos con artículo en Wikipedia (con foto en una ventanita). " +
                        "Por voz: «desactiva los avisos», «quita los avisos turísticos», «activa los avisos de radares», «¿qué hay por aquí?», «háblame de eso».", Cockpit.TextDim)
                }
            }
            item {
                // Asistente «Hola BMW»: activar, voz en alto y estado del micrófono y del modelo.
                val voice by com.cockpitf30.assistant.VoiceAssistant.state.collectAsStateWithLifecycle()
                var voiceOn by remember { mutableStateOf(settings.assistantEnabled) }
                var voiceTalks by remember { mutableStateOf(settings.assistantSpeaks) }
                DiagCard("Asistente de voz «Hola BMW»") {
                    ChipRow(listOf("Activado", "Desactivado"), if (voiceOn) 0 else 1) {
                        voiceOn = it == 0
                        com.cockpitf30.assistant.VoiceAssistant.setEnabled(context, voiceOn)
                    }
                    ChipRow(listOf("Responde en voz alta", "Solo en pantalla"), if (voiceTalks) 0 else 1) {
                        voiceTalks = it == 0
                        settings.assistantSpeaks = voiceTalks
                    }
                    // Voz al estilo TARS: hombre adulto, grave y pausada; humor ajustable como en la película.
                    var robot by remember { mutableStateOf(settings.assistantRobotVoice) }
                    var voiceName by remember { mutableStateOf(settings.assistantVoiceName) }
                    val assistant = com.cockpitf30.assistant.VoiceAssistant
                    ChipRow(listOf("Voz estilo TARS", "Voz de la radio", "Probar voz"), if (robot) 0 else 1) {
                        if (it == 2) assistant.testVoice(context)
                        else {
                            robot = it == 0
                            settings.assistantRobotVoice = robot
                            assistant.applyVoice()
                        }
                    }
                    if (robot) {
                        val strengths = com.cockpitf30.assistant.RobotVoice.Strength.entries
                        var strength by remember { mutableStateOf(settings.assistantRobotStrength) }
                        ChipRow(strengths.map { it.label }, strengths.indexOf(strength)) {
                            strength = strengths[it]
                            settings.assistantRobotStrength = strength
                            assistant.applyVoice()
                            assistant.testVoice(context)
                        }
                    }
                    val voices = remember(voice.phase, voiceName) { assistant.spanishVoices() }
                    if (voices.isNotEmpty()) {
                        // El motor no siempre dice qué voz es de hombre: «Cambiar voz» las va probando.
                        ChipRow(listOf("Voz automática", "Cambiar voz ›"), if (voiceName == null) 0 else 1) {
                            if (it == 0) {
                                voiceName = null
                                settings.assistantVoiceName = null
                                assistant.applyVoice(null)
                                assistant.testVoice(context)
                            } else {
                                voiceName = assistant.nextVoice(context)
                            }
                        }
                    }
                    val ttsStatus by assistant.ttsStatus.collectAsStateWithLifecycle()
                    Info("Motor de voz: ${ttsStatus.text}" + (assistant.ttsEngine?.let { " · $it" } ?: "") + (assistant.ttsLastError?.let { " · $it" } ?: ""),
                        if (ttsStatus == com.cockpitf30.assistant.VoiceAssistant.TtsStatus.READY) Cockpit.TextDim else Cockpit.Alert)
                    when (ttsStatus) {
                        com.cockpitf30.assistant.VoiceAssistant.TtsStatus.NO_ENGINE ->
                            ChipRow(listOf("Instalar «Servicios de voz de Google»", "Volver a probar"), -1) {
                                if (it == 0) assistant.installVoiceEngine(context) else assistant.restartTts(context)
                            }
                        com.cockpitf30.assistant.VoiceAssistant.TtsStatus.NO_SPANISH ->
                            ChipRow(listOf("Descargar voz española", "Volver a probar"), -1) {
                                if (it == 0) assistant.installSpanishVoice(context) else assistant.restartTts(context)
                            }
                        else -> Unit
                    }
                    Info("Voz en uso: ${assistant.currentVoice() ?: "—"} · ${voices.size} voces en español", Cockpit.TextDim)
                    Info("Estado: ${voice.phase} · micrófono ${"%.0f".format(voice.level * 100)} %" + (voice.error?.let { " · $it" } ?: ""))
                    Info("Modelo ${com.cockpitf30.assistant.VoiceModel.NAME}: " + if (com.cockpitf30.assistant.VoiceModel.isReady(context)) "instalado" else "no instalado")
                    voice.lastText?.let { Mono("Último reconocido: «$it»") }
                    voice.heard?.let { Mono("Oído: «$it»") }
                    voice.reply?.let { Mono("Respuesta: $it") }
                    Info("Reconocedor de Google: " + if (android.speech.SpeechRecognizer.isRecognitionAvailable(context)) "disponible" else "no disponible", Cockpit.TextDim)
                }
            }
            item {
                DiagCard("Batería") {
                    ChipRow(
                        listOf(if (batteryOk) "Excluida de la optimización ✓" else "Pedir exclusión de optimización"),
                        if (batteryOk) 0 else -1,
                    ) {
                        BatteryOptimization.request(context)
                    }
                }
            }
            item {
                DiagCard("Tramas recibidas (más reciente arriba)") {
                    if (raw.isEmpty()) Info("Ninguna todavía")
                    raw.forEach { Mono(it) }
                }
            }
            item {
                DiagCard("Bytes en crudo del puerto (aunque no sean tramas Raise)") {
                    if (bytes.isEmpty()) Info("Ninguno todavía")
                    bytes.forEach { Mono(it) }
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

private const val TEST_SECONDS = 60

private val BAUDS = listOf(19200, 38400, 57600, 115200)

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
    carProbe: List<String>,
    dataProbe: List<String>,
    callbacks: List<String>,
): String = buildString {
    val dm = context.resources.displayMetrics
    appendLine("Cockpit F30 ${installedVersion(context)} · informe de diagnóstico · ${java.time.LocalDateTime.now()}")
    appendLine("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · ${android.os.Build.DISPLAY}")
    appendLine("Pantalla ${dm.widthPixels}x${dm.heightPixels} ${dm.densityDpi} dpi")
    appendLine()
    appendLine("== Sensores")
    com.cockpitf30.vehicle.telemetry.Telemetry.sensorList(context).forEach { appendLine(it) }
    appendLine("Modo ${com.cockpitf30.ui.theme.DriveModes.current} (elegido ${com.cockpitf30.ui.theme.DriveModes.selected}) · oscuro=${com.cockpitf30.ui.theme.DriveModes.dark} (${com.cockpitf30.ui.theme.NightDetector.reason})")
    com.cockpitf30.vehicle.telemetry.Telemetry.state.value.let { t ->
        appendLine("Telemetría: G ${t.gSource} long=${"%.2f".format(t.longG)} lat=${"%.2f".format(t.latG)} calibrado=${t.calibrated} · altitud ${t.altitudeM} (${t.altSource}) pendiente ${t.slopePct} · CV ${t.powerCv} Nm ${t.torqueNm} · lux ${t.lux}")
    }
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
    section("Servicio del coche", carProbe.ifEmpty { listOf("(no se exploró: pulsa «Explorar el servicio del coche»)") })
    section("CarDataManager", dataProbe.ifEmpty { listOf("(no se pulsó «Leer datos»)") })
    section("Avisos del servicio", callbacks)
    section("Servicios del sistema", services)
    section("Jars del framework", jars)
    section("Apps del sistema", sysApps)
    section("Broadcasts", broadcasts)
    section("Eventos de entrada", input)
    section("Tramas Raise", raw)
    section("Bytes en crudo", bytes)
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

private fun radioReport(values: Map<String, String>, changes: List<String>, canbus: List<String>, vehicle: com.cockpitf30.vehicle.VehicleState): String = buildString {
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
    appendLine()
    appendLine("== CarCanbus")
    if (canbus.isEmpty()) appendLine("(no se pulsó «Explorar CarCanbus»)")
    canbus.forEach(::appendLine)
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

/** Versión de la app tal como está instalada (nombre y código de versión del paquete). */
internal fun installedVersion(context: android.content.Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    "${info.versionName} (build $code)"
}.getOrDefault("desconocida")

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
