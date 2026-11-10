package com.cockpitf30.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import android.content.pm.PackageManager
import androidx.compose.runtime.DisposableEffect
import androidx.core.content.ContextCompat
import com.cockpitf30.assistant.VoiceAssistant
import com.cockpitf30.ui.components.AssistantOverlay
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.cockpitf30.input.IDriveInput
import com.cockpitf30.input.NavAction
import com.cockpitf30.service.VehicleService
import com.cockpitf30.system.AppSettings
import com.cockpitf30.system.BatteryOptimization
import com.cockpitf30.ui.components.DefaultFocus
import com.cockpitf30.ui.components.SideArt
import com.cockpitf30.ui.components.SideItem
import com.cockpitf30.ui.components.Sidebar
import com.cockpitf30.ui.screens.AppsScreen
import com.cockpitf30.ui.screens.DiagnosticsScreen
import com.cockpitf30.ui.screens.HomeScreen
import com.cockpitf30.ui.screens.Screen
import com.cockpitf30.ui.screens.TripScreen
import com.cockpitf30.ui.screens.Reorder
import com.cockpitf30.ui.screens.WeatherScreen
import com.cockpitf30.ui.screens.PhoneScreen
import com.cockpitf30.ui.screens.FuelScreen
import com.cockpitf30.fuel.FuelRepository
import com.cockpitf30.weather.WeatherRepository
import com.cockpitf30.nav.RouteTracker
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.cockpitf30.ui.screens.openMaps
import com.cockpitf30.ui.screens.openOriginalBmw
import com.cockpitf30.ui.screens.openMusic
import com.cockpitf30.ui.theme.Cockpit
import com.cockpitf30.ui.theme.CockpitTheme
import com.cockpitf30.vehicle.VehicleRepository
import com.cockpitf30.ui.components.ClusterIntro
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class MainActivity : ComponentActivity() {
    private val navActions = MutableSharedFlow<NavAction>(extraBufferCapacity = 16)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        IDriveInput.learned = AppSettings(this).knobKeys
        com.cockpitf30.ui.theme.DriveModes.load(this)
        VehicleService.start(this)
        askBatteryExemptionOnce()
        // Lo pesado, en segundo plano y antes de que haga falta: imágenes grandes y lista de apps.
        com.cockpitf30.system.AppCatalog.load(this)
        Thread({
            com.cockpitf30.ui.components.Images.preload(this)
            com.cockpitf30.ui.components.Images.get(this, com.cockpitf30.R.drawable.spinner_sheet)
            // La pantalla de inicio espera también a la lista de apps (como mucho 5 s).
            val until = System.currentTimeMillis() + 5000
            while (com.cockpitf30.system.AppCatalog.apps.value == null && System.currentTimeMillis() < until) Thread.sleep(50)
            com.cockpitf30.ui.components.Startup.ready.value = true
        }, "precarga").apply { priority = Thread.MIN_PRIORITY + 2 }.start()

        setContent {
            CockpitTheme {
                Box(Modifier.fillMaxSize()) {
                    CockpitRoot(navActions)
                    com.cockpitf30.ui.components.StartupSplash()
                }
            }
        }
    }

    // Tras tocar la pantalla el foco deja de verse; el próximo giro de la ruleta lo volverá a colocar.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) DefaultFocus.needsPlacement = true
        return super.dispatchTouchEvent(ev)
    }

    // Interceptamos aquí, antes que las vistas, para que la ruleta siempre mueva el foco.
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        VehicleRepository.logInput(IDriveInput.describe(event))
        val action = IDriveInput.fromMotion(event) ?: return super.dispatchGenericMotionEvent(event)
        navActions.tryEmit(action)
        return true
    }

    // Falso positivo de lint: sobrescribir dispatchKeyEvent es API pública de Activity.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(raw: KeyEvent): Boolean {
        VehicleRepository.logInput(IDriveInput.describe(raw))
        // Modo aprender (Diagnóstico): la tecla se asigna a la función elegida y no hace nada más.
        IDriveInput.learn(raw)?.let { map ->
            AppSettings(this).knobKeys = map
            swallowKey = raw.keyCode
            return true
        }
        if (swallowKey == raw.keyCode) {
            if (raw.action == KeyEvent.ACTION_UP) swallowKey = null
            return true
        }
        // Teclas propias de la radio que se han enseñado: pasan a ser la tecla estándar.
        val event = IDriveInput.translate(raw)
        if (event.keyCode == KeyEvent.KEYCODE_BACK && raw.keyCode != KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onBackPressedDispatcher.onBackPressed()
            return true
        }
        // Tras una pulsación larga de OK, nos comemos sus repeticiones y el soltar para que no haga clic.
        if (swallowCenter && IDriveInput.isCenter(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_UP) swallowCenter = false
            return true
        }
        // Flechas, TAB, etc.: movemos el foco nosotros (con plan B si no hay nada en esa dirección).
        IDriveInput.navActionFor(event.keyCode, event.isShiftPressed)?.let { action ->
            if (event.action == KeyEvent.ACTION_DOWN) navActions.tryEmit(action)
            return true
        }
        val action = IDriveInput.fromKey(event) ?: return super.dispatchKeyEvent(event)
        if (action == NavAction.LONG_PRESS) swallowCenter = true
        navActions.tryEmit(action)
        return true
    }

    // El asistente solo escucha con la app en pantalla: al salir suelta el micrófono.
    override fun onStart() {
        super.onStart()
        VoiceAssistant.resume(this)
    }

    override fun onStop() {
        VoiceAssistant.pause()
        super.onStop()
    }

    private var swallowKey: Int? = null
    private var swallowCenter = false

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun askBatteryExemptionOnce() {
        val settings = AppSettings(this)
        if (!settings.askedBatteryExemption && !BatteryOptimization.isIgnoring(this)) {
            settings.askedBatteryExemption = true
            BatteryOptimization.request(this)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class) // requestInputMode
@Composable
private fun CockpitRoot(navActions: SharedFlow<NavAction>) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    val inputMode = LocalInputModeManager.current
    LaunchedEffect(navActions) {
        navActions.collect {
            if (it == NavAction.LONG_PRESS) {
                Reorder.longPress.tryEmit(Unit)
                return@collect
            }
            // Tras tocar la pantalla Android entra en modo táctil y nada es enfocable:
            // al girar la ruleta volvemos a modo teclado para que el foco se mueva y se vea.
            if (inputMode.inputMode != InputMode.Keyboard) inputMode.requestInputMode(InputMode.Keyboard)
            if (DefaultFocus.needsPlacement) {
                DefaultFocus.needsPlacement = false
                // Nada tenía el foco: el primer giro solo lo coloca en el elemento por defecto de la pantalla.
                if (Reorder.moving.value == null && DefaultFocus.request()) return@collect
            }
            val (dir, direction) = when (it) {
                NavAction.DOWN -> 1 to FocusDirection.Down
                NavAction.UP -> -1 to FocusDirection.Up
                NavAction.RIGHT -> 1 to FocusDirection.Right
                NavAction.LEFT -> -1 to FocusDirection.Left
                NavAction.NEXT -> 1 to null
                else -> -1 to null
            }
            // En modo mover, la ruleta desplaza la tarjeta en vez del foco.
            val step = Reorder.step
            if (Reorder.moving.value != null && step != null) {
                step(dir)
                return@collect
            }
            // Abajo en el último elemento de la barra lateral (o en una fila sin nada debajo)
            // sigue al siguiente elemento en orden, como el giro de la ruleta.
            val moved = direction?.let { d -> focusManager.moveFocus(d) } ?: false
            if (!moved && !focusManager.moveFocus(if (dir > 0) FocusDirection.Next else FocusDirection.Previous)) {
                // Nada se movió: probablemente el foco se perdió al cambiar de pantalla. Lo recolocamos.
                DefaultFocus.request()
            }
        }
    }

    // Tiempo: refresco cada 30 min y, la primera vez, pedir permiso de ubicación.
    val ctx = LocalContext.current
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        RouteTracker.start(ctx)
        com.cockpitf30.vehicle.telemetry.Telemetry.startGps(ctx)
        scope.launch { WeatherRepository.refresh(ctx) }
        VoiceAssistant.resume(ctx)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        VoiceAssistant.resume(ctx)
    }
    DisposableEffect(Unit) {
        VoiceAssistant.permissionRequester = { micPermission.launch(Manifest.permission.RECORD_AUDIO) }
        onDispose { VoiceAssistant.permissionRequester = null }
    }
    LaunchedEffect(Unit) {
        val settings = AppSettings(ctx)
        WeatherRepository.loadCache(ctx)
        RouteTracker.start(ctx)
        // Ubicación (tiempo, gasolineras) y micrófono (asistente) se piden una vez, en el mismo diálogo.
        val ask = buildList {
            if (!settings.askedLocation && settings.weatherPlace == null && !WeatherRepository.hasLocationPermission(ctx)) {
                settings.askedLocation = true
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            // Agenda y llamadas: para «Hola BMW, llama a …».
            if (!settings.askedContacts) {
                settings.askedContacts = true
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.READ_CONTACTS)
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.CALL_PHONE)
            }
            if (!settings.askedMicrophone && settings.assistantEnabled &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            ) {
                settings.askedMicrophone = true
                add(Manifest.permission.RECORD_AUDIO)
            }
        }
        if (ask.isNotEmpty()) locationPermission.launch(ask.toTypedArray())
        while (true) {
            WeatherRepository.refresh(ctx)
            delay(30 * 60 * 1000L)
        }
    }
    // Gasolineras: el listado del Ministerio se descarga como mucho cada 12 h; si falla (p. ej. sin
    // conexión al arrancar), se reintenta a los 10 minutos.
    LaunchedEffect(Unit) {
        while (true) {
            FuelRepository.refresh(ctx)
            delay(if (FuelRepository.state.value.error != null) 10 * 60 * 1000L else 60 * 60 * 1000L)
        }
    }
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    // Modo Night automático: cada 5 s se mira si está oscuro (faros, sensor de luz o el sol).
    LaunchedEffect(Unit) {
        while (true) {
            val lights = com.cockpitf30.vehicle.source.RadioCarData.values.value["state.main.headlight_state"]?.toIntOrNull()?.let { it != 0 }
            val pos = RouteTracker.state.value.position?.takeIf { RouteTracker.state.value.live }
            val here = pos ?: WeatherRepository.lastKnownLocation(ctx)?.let { com.cockpitf30.nav.GeoPoint(it.latitude, it.longitude) }
            com.cockpitf30.ui.theme.NightDetector.update(
                lights, com.cockpitf30.vehicle.telemetry.Telemetry.state.value.lux, here?.lat, here?.lon,
                useHeadlights = com.cockpitf30.ui.theme.DriveModes.nightWithHeadlights,
            )
            delay(5000)
        }
    }
    // Radares: aviso hablado al acercarse a uno que queda delante. Tiempo en ruta: se recalcula solo.
    DisposableEffect(Unit) {
        com.cockpitf30.nav.SpeedCameras.onAlert = { v ->
            if (AppSettings(ctx).alertOn(com.cockpitf30.nav.AlertCategory.RADAR)) {
                val limit = v.camera.maxspeed?.let { ", límite $it" } ?: ""
                val what = if (v.camera.kind == "Tramo") "Tramo de radar" else "Radar"
                VoiceAssistant.announce("$what a ${com.cockpitf30.ui.components.radarDistance(v.meters).replace(" m", " metros").replace(" km", " kilómetros")}$limit")
            }
        }
        onDispose { com.cockpitf30.nav.SpeedCameras.onAlert = null }
    }
    // Avisos en ruta (peligros, descanso, turismo, combustible): los dice el asistente.
    DisposableEffect(Unit) {
        val deliver: (com.cockpitf30.nav.RouteAlert) -> Boolean = { VoiceAssistant.announceIfFree(it.speech) }
        com.cockpitf30.nav.RouteAlerts.deliver = deliver
        onDispose { if (com.cockpitf30.nav.RouteAlerts.deliver === deliver) com.cockpitf30.nav.RouteAlerts.deliver = null }
    }
    LaunchedEffect(Unit) {
        com.cockpitf30.weather.RouteWeather.load(ctx)
        while (true) {
            com.cockpitf30.weather.RouteWeather.refresh(ctx)
            delay(2 * 60 * 1000L)
        }
    }
    // Al elegir Sport se abre Rendimiento (relojes de potencia y G-meter).
    val selectedMode = com.cockpitf30.ui.theme.DriveModes.selected
    var lastMode by remember { mutableStateOf(selectedMode) }
    LaunchedEffect(selectedMode) {
        if (selectedMode == com.cockpitf30.ui.theme.DriveMode.SPORT && lastMode != selectedMode) screen = Screen.PERFORMANCE
        lastMode = selectedMode
    }

    // Las órdenes de voz abren pantallas como los botones.
    // Vehículo abre el iDrive original del coche (la caja CAN no da puertas ni presiones):
    // allí está «Estado del vehículo» con las ruedas y su presión.
    val go: (Screen) -> Unit = { if (it == Screen.VEHICLE) openOriginalBmw(ctx) else screen = it }
    DisposableEffect(Unit) {
        val nav: (Screen) -> Unit = go
        VoiceAssistant.navigator = nav
        onDispose { if (VoiceAssistant.navigator === nav) VoiceAssistant.navigator = null }
    }

    val context = LocalContext.current
    val sideItems = listOf(
        // Pulsación larga en Home: atajo a Diagnóstico.
        SideItem("Home", SideArt.HOME, screen == Screen.HOME, onLongClick = { screen = Screen.DIAGNOSTICS }) { screen = Screen.HOME },
        SideItem("Navegación", SideArt.NAV, false) { openMaps(context) },
        SideItem("Rendimiento", SideArt.PERF, screen == Screen.PERFORMANCE) { screen = Screen.PERFORMANCE },
        SideItem("Música", SideArt.MUSIC, false) { openMusic(context) },
        SideItem("Apps", SideArt.APPS, screen == Screen.APPS) { screen = Screen.APPS },
        // Ajustes de la app: fuente de datos, puerto serie, batería.
        SideItem("Ajustes", SideArt.SETTINGS, screen == Screen.DIAGNOSTICS) { screen = Screen.DIAGNOSTICS },
    )

    // Al cambiar de modo se rehace la interfaz entera con la paleta nueva.
    androidx.compose.runtime.key(com.cockpitf30.ui.theme.DriveModes.current) {
    Row(
        Modifier
            .fillMaxSize()
            // El degradado ya termina en el color de fondo opaco: no hace falta pintar el fondo dos veces.
            .background(Brush.radialGradient(listOf(Cockpit.Blue.copy(alpha = 0.10f), Cockpit.Background), radius = 1600f)),
    ) {
        Sidebar(sideItems)
        Box(Modifier.weight(1f).fillMaxHeight()) {
        AnimatedContent(
            screen,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "screen",
        ) { current ->
            when (current) {
                Screen.HOME -> HomeScreen(onOpen = go)
                // Vehículo abre el iDrive original (no es una pantalla de la app).
                Screen.VEHICLE -> HomeScreen(onOpen = go)
                Screen.TRIP -> TripScreen()
                Screen.APPS -> AppsScreen()
                Screen.DIAGNOSTICS -> DiagnosticsScreen()
                Screen.WEATHER -> WeatherScreen()
                Screen.PHONE -> PhoneScreen()
                Screen.FUEL -> FuelScreen()
                Screen.PERFORMANCE -> com.cockpitf30.ui.screens.PerformanceScreen()
                Screen.ROUTE -> com.cockpitf30.ui.screens.RouteScreen()
            }
        }
            com.cockpitf30.ui.components.RouteAlertPopup(Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(top = 20.dp, end = 24.dp))
            AssistantOverlay()
        }
    }
    }
}
