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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.cockpitf30.ui.screens.VehicleScreen
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
        VehicleService.start(this)
        askBatteryExemptionOnce()

        setContent {
            CockpitTheme {
                CockpitRoot(navActions)
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
        scope.launch { WeatherRepository.refresh(ctx) }
    }
    LaunchedEffect(Unit) {
        val settings = AppSettings(ctx)
        WeatherRepository.loadCache(ctx)
        RouteTracker.start(ctx)
        if (!settings.askedLocation && settings.weatherPlace == null && !WeatherRepository.hasLocationPermission(ctx)) {
            settings.askedLocation = true
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        }
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

    // Puerta, capó o maletero abierto: se abre la pantalla Vehículo (vista cenital con cada puerta).
    // Al cerrarse todo se vuelve al Inicio, que ya abre en el mapa. En Diagnóstico no se interrumpe.
    LaunchedEffect(Unit) {
        var autoOpened = false
        VehicleRepository.state.map { it.doors.anyOpen }.distinctUntilChanged().collect { open ->
            if (open && screen != Screen.VEHICLE && screen != Screen.DIAGNOSTICS) {
                screen = Screen.VEHICLE
                autoOpened = true
            } else if (!open && autoOpened) {
                autoOpened = false
                if (screen == Screen.VEHICLE) {
                    ClusterIntro.done = true
                    screen = Screen.HOME
                }
            }
        }
    }

    val context = LocalContext.current
    val sideItems = listOf(
        // Pulsación larga en Home: atajo a Diagnóstico.
        SideItem("Home", SideArt.HOME, screen == Screen.HOME, onLongClick = { screen = Screen.DIAGNOSTICS }) { screen = Screen.HOME },
        SideItem("Navegación", SideArt.NAV, false) { openMaps(context) },
        SideItem("Música", SideArt.MUSIC, false) { openMusic(context) },
        SideItem("Apps", SideArt.APPS, screen == Screen.APPS) { screen = Screen.APPS },
        // Ajustes de la app: fuente de datos, puerto serie, batería.
        SideItem("Ajustes", SideArt.SETTINGS, screen == Screen.DIAGNOSTICS) { screen = Screen.DIAGNOSTICS },
    )

    Row(
        Modifier
            .fillMaxSize()
            .background(Cockpit.Background)
            .background(Brush.radialGradient(listOf(Cockpit.Blue.copy(alpha = 0.10f), Cockpit.Background), radius = 1600f)),
    ) {
        Sidebar(sideItems)
        AnimatedContent(
            screen,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "screen",
        ) { current ->
            when (current) {
                Screen.HOME -> HomeScreen(onOpen = { screen = it })
                Screen.VEHICLE -> VehicleScreen()
                Screen.TRIP -> TripScreen()
                Screen.APPS -> AppsScreen()
                Screen.DIAGNOSTICS -> DiagnosticsScreen()
                Screen.WEATHER -> WeatherScreen()
                Screen.PHONE -> PhoneScreen()
                Screen.FUEL -> FuelScreen()
            }
        }
    }
}
