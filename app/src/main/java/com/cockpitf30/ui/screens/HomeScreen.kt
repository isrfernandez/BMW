package com.cockpitf30.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.util.Locale
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import com.cockpitf30.system.AppSettings
import com.cockpitf30.ui.components.WeatherCardContent
import com.cockpitf30.ui.components.WeatherScene
import com.cockpitf30.weather.wmoCondition
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.cockpitf30.weather.WeatherRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cockpitf30.ui.components.CardArt
import com.cockpitf30.ui.components.AssistantCardBody
import com.cockpitf30.assistant.VoiceAssistant
import com.cockpitf30.ui.components.CardTheme
import com.cockpitf30.ui.components.ClusterStrip
import com.cockpitf30.ui.components.glassBackground
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import com.cockpitf30.ui.components.glassOverlay
import com.cockpitf30.ui.components.FocusTile
import com.cockpitf30.ui.components.InitialFocus
import com.cockpitf30.ui.theme.Cockpit
import com.cockpitf30.vehicle.VehicleRepository
import com.cockpitf30.vehicle.VehicleState
import com.cockpitf30.vehicle.trip.TripComputer
import com.cockpitf30.fuel.FuelStations
import com.cockpitf30.ui.theme.numeric
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/** Tarjeta de la parrilla panorámica: un resumen rápido de una función. */
private class Card(
    val id: String,
    val title: String,
    val subtitle: String,
    val theme: CardTheme,
    val action: (Context) -> Unit,
    val bodyAlignment: Alignment = Alignment.Center,
    val body: (@Composable () -> Unit)? = null,
)

/**
 * Modo mover de las tarjetas. La pulsación larga del iDrive llega por [longPress]; mientras
 * [moving] no es null, el giro de la ruleta mueve esa tarjeta en vez del foco ([step]).
 */
object Reorder {
    val moving = MutableStateFlow<String?>(null)
    val longPress = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    var step: ((Int) -> Unit)? = null
}

private val CARD_WIDTH = 300.dp
/** Alto del cuadro de instrumentos: 3/4 de los 720 dp de la pantalla. */
private val CLUSTER_HEIGHT = 540.dp
private val CARD_GAP = 16.dp

@Composable
fun HomeScreen(onOpen: (Screen) -> Unit) {
    // Los datos del coche (10 lecturas por segundo) se leen solo dentro del cuadro ([LiveCluster]):
    // leídos aquí, cada lectura recomponía también todas las tarjetas.
    val weather by WeatherRepository.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val cards = remember {
        listOf(
            // Antes "Música" (ya está en la barra lateral): ahora el asistente de voz «Hola BMW».
            Card("assistant", "Hola BMW", "Asistente de voz", CardTheme.ASSIST, { VoiceAssistant.tap(it) }),
            // Antes "Navegación" (repetida en la barra lateral): ahora la gasolinera más cercana.
            Card("fuel", "Gasolinera", "La más cercana", CardTheme.FUEL, { onOpen(Screen.FUEL) }),
            // Modo de conducción: cada pulsación pasa al siguiente (Normal → Sport → Night).
            // Radares cercanos (barrido) y el tiempo en ruta.
            Card("radar", "Radares", "", CardTheme.RADAR, { onOpen(Screen.ROUTE) }),
            Card("mode", "Modo", "", CardTheme.MODE, { com.cockpitf30.ui.theme.DriveModes.cycle(it) }),
            // Radio: FM por la antena si la unidad tiene sintonizador; si no, por Internet.
            Card("radio", "Radio", "", CardTheme.RADIO, { onOpen(Screen.RADIO) }),
            Card("trip", "Datos de viaje", "Duración, km y consumo", CardTheme.TRIP, { onOpen(Screen.TRIP) }, Alignment.BottomStart) { TripBody() },
            Card("weather", "Tiempo", "", CardTheme.WEATHER, { onOpen(Screen.WEATHER) }),
            Card("vehicle", "Vehículo", "iDrive original del coche", CardTheme.VEHICLE, { onOpen(Screen.VEHICLE) }),
            Card("phone", "Teléfono", "Marcador y contactos", CardTheme.PHONE, { onOpen(Screen.PHONE) }),
            Card("service", "Mantenimiento", "Aceite, neumáticos e ITV", CardTheme.SERVICE, { onOpen(Screen.MAINTENANCE) }, Alignment.BottomStart) { ServiceBody() },
            Card("diag", "Diagnóstico", "Información técnica y depuración", CardTheme.DIAG, { onOpen(Screen.DIAGNOSTICS) }),
        ).associateBy { it.id }
    }
    // Quien tenía guardado el orden con "nav" o "music" encuentra la gasolinera o el asistente en el mismo sitio.
    var order by remember {
        mutableStateOf(CardOrder.merge(settings.cardOrder.map { when (it) { "nav" -> "fuel"; "music" -> "assistant"; else -> it } }, cards.keys.toList()))
    }
    val fuel = rememberFuelView()
    val moving by Reorder.moving.collectAsStateWithLifecycle()
    var beforeMove by remember { mutableStateOf(order) }
    var focusedId by remember { mutableStateOf<String?>(null) }
    var suppressClick by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun startMove(id: String) {
        beforeMove = order
        Reorder.moving.value = id
    }
    fun step(dir: Int) {
        val id = Reorder.moving.value ?: return
        val from = order.indexOf(id)
        order = CardOrder.move(order, from, from + dir)
        val to = order.indexOf(id)
        scope.launch { listState.animateScrollToItem((to - 1).coerceAtLeast(0)) }
    }
    fun finish() {
        settings.cardOrder = order
        Reorder.moving.value = null
    }
    fun cancel() {
        order = beforeMove
        Reorder.moving.value = null
    }
    DisposableEffect(Unit) {
        // Durante la transición entre pantallas conviven dos: cada una solo borra su propio manejador.
        val mine: (Int) -> Unit = ::step
        Reorder.step = mine
        onDispose {
            if (Reorder.step === mine) Reorder.step = null
            if (Reorder.moving.value != null) cancel()
        }
    }
    LaunchedEffect(Unit) { Reorder.longPress.collect { focusedId?.let(::startMove) } }
    BackHandler(enabled = moving != null) { cancel() }

    val firstCard = remember { FocusRequester() }
    InitialFocus(firstCard)

    Column(Modifier.fillMaxSize().padding(top = 6.dp, bottom = 8.dp)) {
        // El cuadro (mapa e indicadores) ocupa tres cuartos de la altura (540 de 720 dp); las tarjetas, el resto.
        LiveCluster(Modifier.fillMaxWidth().height(CLUSTER_HEIGHT).padding(horizontal = 12.dp))
        Spacer(Modifier.height(2.dp))
        LazyRow(
            Modifier.weight(1f).fillMaxWidth().onPreviewKeyEvent { e ->
                // En modo mover, el joystick izquierda/derecha también mueve la tarjeta.
                if (moving == null || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { step(-1); true }
                    Key.DirectionRight -> { step(1); true }
                    else -> false
                }
            },
            state = listState,
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
        ) {
            itemsIndexed(order, key = { _, id -> id }) { i, id ->
                val card = cards.getValue(id)
                val isMoving = moving == id
                val wobble = if (isMoving) {
                    rememberInfiniteTransition(label = "wobble").animateFloat(
                        -0.8f, 0.8f, infiniteRepeatable(tween(140), RepeatMode.Reverse), label = "rot",
                    ).value
                } else 0f
                val stepPx = with(LocalDensity.current) { (CARD_WIDTH + CARD_GAP).toPx() }
                var mod = Modifier.width(CARD_WIDTH).fillMaxHeight()
                    .then(if (isMoving) Modifier.zIndex(1f) else Modifier.animateItem())
                    .graphicsLayer {
                        translationX = if (isMoving) dragX else 0f
                        rotationZ = wobble
                        val s = if (isMoving) 1.05f else 1f
                        scaleX = s
                        scaleY = s
                    }
                    .onFocusChanged { if (it.isFocused) focusedId = id }
                    .pointerInput(id) {
                        // Táctil: pulsación larga y arrastrar; al soltar se guarda el orden.
                        detectDragGesturesAfterLongPress(
                            onDragStart = { suppressClick = true; startMove(id) },
                            onDrag = { change, amount ->
                                change.consume()
                                dragX += amount.x
                                while (dragX > stepPx / 2 && order.indexOf(id) < order.lastIndex) { step(1); dragX -= stepPx }
                                while (dragX < -stepPx / 2 && order.indexOf(id) > 0) { step(-1); dragX += stepPx }
                            },
                            onDragEnd = { dragX = 0f; finish() },
                            onDragCancel = { dragX = 0f; finish() },
                        )
                    }
                if (i == 0) mod = mod.focusRequester(firstCard)
                FocusTile(
                    onClick = {
                        when {
                            suppressClick -> suppressClick = false
                            isMoving -> finish()
                            moving == null -> card.action(context)
                        }
                    },
                    selected = isMoving,
                    modifier = mod,
                ) {
                    Box(
                        Modifier.fillMaxSize()
                            .drawBehind { glassBackground(card.theme) }
                            // Placa de cristal por encima de la ilustración y del texto.
                            .drawWithContent { drawContent(); glassOverlay(card.theme.to) },
                    ) {
                        if (card.id == "weather") {
                            weather.forecast?.let { f ->
                                val c = wmoCondition(f.code)
                                WeatherScene(c.kind, f.isDay, f.code, Modifier.matchParentSize(), maxParticles = 40, sunAt = Offset(0.17f, 0.24f))
                                // Velo para que el texto se lea sobre la animación.
                                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.55f)))))
                            }
                            WeatherCardContent(weather, animated = weather.forecast != null, compact = true)
                        } else if (card.id == "assistant") {
                            Box(Modifier.matchParentSize().graphicsLayer {
                                scaleX = 0.66f; scaleY = 0.66f
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
                            }) { CardArt(card.theme) }
                            AssistantCardBody()
                        } else if (card.id == "radar") {
                            RadarCardBody()
                        } else if (card.id == "radio") {
                            RadioCardBody()
                        } else if (card.id == "mode") {
                            val mode = com.cockpitf30.ui.theme.DriveModes.current
                            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
                                Text("MODO DE CONDUCCIÓN", style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim)
                                Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
                                    ModeFace(mode, active = true, lit = true, glow = { 0.6f }, hint = "OK: siguiente modo")
                                }
                            }
                        } else if (card.id == "fuel") {
                            Box(Modifier.matchParentSize().graphicsLayer {
                                scaleX = 0.66f; scaleY = 0.66f
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
                            }) { CardArt(card.theme) }
                            FuelCardBody(fuel)
                        } else {
                            // Tarjetas bajas: la ilustración se reduce hacia la esquina superior derecha.
                            Box(Modifier.matchParentSize().graphicsLayer {
                                scaleX = 0.66f; scaleY = 0.66f
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
                            }) { CardArt(card.theme) }
                            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
                                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = card.bodyAlignment) {
                                    card.body?.invoke()
                                }
                                Text(card.title, style = MaterialTheme.typography.headlineSmall)
                                Text(card.subtitle, style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim)
                            }
                        }
                        if (isMoving) MoveHint(Modifier.align(Alignment.TopCenter))
                    }
                }
            }
        }
    }
}

/** Tarjeta de radio: fuente (antena o Internet), lo que suena y si está sonando. */
@Composable
private fun RadioCardBody() {
    val st by com.cockpitf30.radio.Radio.state.collectAsStateWithLifecycle()
    val fm = st.source == com.cockpitf30.radio.RadioSource.FM
    val c = sourceColor(st.source)
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(if (st.playing) c else Cockpit.TextDim, androidx.compose.foundation.shape.RoundedCornerShape(5.dp)))
            Text("  " + st.source.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = c)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            if (fm) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(com.cockpitf30.radio.RadioLogic.formatFm(st.fmFreq), style = numeric(44, FontWeight.Medium))
                    Text(" MHz", style = numeric(18), color = Cockpit.TextDim, modifier = Modifier.padding(bottom = 8.dp))
                }
            } else {
                Text(st.station?.name ?: "Elige emisora", style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Text("Radio", style = MaterialTheme.typography.headlineSmall)
        Text(
            (if (st.playing) (if (fm) st.fmRds else st.nowPlaying) ?: "Sonando" else "Parada") + if (st.ducked) " · bajada" else "",
            style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** El cuadro con los datos del coche en vivo: solo él se recompone con cada lectura. */
@Composable
private fun LiveCluster(modifier: Modifier) {
    val state by VehicleRepository.state.collectAsStateWithLifecycle()
    val status by VehicleRepository.status.collectAsStateWithLifecycle()
    ClusterStrip(state, status, modifier)
}

/**
 * Tarjeta de radares: barrido a la derecha y, a la izquierda, el aviso (distancia y límite) o
 * cuántos hay cerca y el resumen del tiempo en ruta.
 */
@Composable
private fun RadarCardBody() {
    val cams by com.cockpitf30.nav.SpeedCameras.state.collectAsStateWithLifecycle()
    val route by com.cockpitf30.weather.RouteWeather.state.collectAsStateWithLifecycle()
    val ahead = cams.ahead
    Row(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text(if (ahead != null) "RADAR DELANTE" else "RADARES", style = MaterialTheme.typography.labelMedium, color = if (ahead != null) com.cockpitf30.ui.components.RadarAlert else Cockpit.TextDim)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
                if (ahead != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(com.cockpitf30.ui.components.radarDistance(ahead.meters), style = numeric(30, FontWeight.Medium), maxLines = 1)
                        ahead.camera.maxspeed?.let { Spacer(Modifier.width(8.dp)); com.cockpitf30.ui.components.SpeedSign(it, 40.dp) }
                    }
                }
            }
            Text(
                if (ahead == null) cams.near.firstOrNull()?.let { "A ${com.cockpitf30.ui.components.radarDistance(it.meters)}" } ?: "Sin radares" else (ahead.camera.kind ?: "Radar fijo"),
                style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(route.summary ?: "Tiempo en ruta", style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        com.cockpitf30.ui.components.RadarScope(cams, Modifier.size(104.dp), rangeM = 2000f, rings = listOf(1000f, 2000f), labels = false)
    }
}

/** Indicación del modo mover sobre la tarjeta. */
@Composable
private fun MoveHint(modifier: Modifier = Modifier) {
    Row(
        modifier.padding(top = 10.dp).background(Cockpit.Blue.copy(alpha = 0.9f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("◀  Gira o arrastra  ▶   OK para soltar", style = MaterialTheme.typography.labelLarge, color = Cockpit.Text)
    }
}

/**
 * Tarjeta de la gasolinera más cercana: distancia en grande, marca (rótulo) y dirección.
 * Sin ubicación o sin datos, dice qué falta.
 */
@Composable
private fun FuelCardBody(view: FuelView) {
    val n = view.near.firstOrNull()
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
        Text("GASOLINERA MÁS CERCANA", style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
            if (n != null) {
                Text(FuelStations.formatDistance(n.meters), style = numeric(34, FontWeight.Medium), maxLines = 1)
            }
        }
        if (n != null) {
            Text(n.station.brand, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(place(n.station), style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Text("Gasolineras", style = MaterialTheme.typography.headlineSmall)
            Text(
                when {
                    view.position == null -> "Esperando ubicación"
                    view.state.loading -> "Descargando estaciones…"
                    view.state.error != null -> "Sin datos (${view.state.error})"
                    else -> "Ninguna cerca"
                },
                style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Kilómetros y duración del viaje en curso (o del último), con ancho fijo para que no salten. */
@Composable
private fun TripBody() {
    val trips by TripComputer.state.collectAsStateWithLifecycle()
    val trip = trips.current ?: trips.last
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
        Metric(trip?.let { km(it.distanceKm) } ?: "--", "km", 96.dp)
        Metric(trip?.let { hm(it.durationMillis) } ?: "--", if (trips.current != null) "h · en curso" else "h · último", 150.dp)
    }
}

/** Lo más urgente del mantenimiento, con su color. */
@Composable
private fun ServiceBody() {
    val context = LocalContext.current
    val st = remember { com.cockpitf30.vehicle.Maintenance.mostUrgent(com.cockpitf30.ui.screens.serviceStatuses(AppSettings(context))) }
    Column {
        Text(st?.item?.label ?: "Sin datos", style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim, maxLines = 1)
        Text(st?.text ?: "Pon los km", style = MaterialTheme.typography.titleLarge, maxLines = 1,
            color = st?.let { com.cockpitf30.ui.screens.serviceColor(it.level) } ?: Cockpit.TextDim)
    }
}

@Composable
private fun Metric(value: String, unit: String, width: Dp) {
    // Cifra y unidad en la misma línea: la tarjeta es baja.
    Row(Modifier.width(width), verticalAlignment = Alignment.Bottom) {
        Text(value, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.weight(1f))
        Text(" $unit", style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim, maxLines = 1, modifier = Modifier.padding(bottom = 4.dp))
    }
}

internal const val GOOGLE_MAPS = "com.google.android.apps.maps"

/**
 * Abre la app Google Maps instalada (no la web) centrada en la posición actual. Sin posición
 * conocida, o si ya está navegando, la abre normal (Maps se centra solo en la ubicación). Si no está instalada,
 * cualquier app de mapas con un aviso.
 */
internal fun openMaps(context: Context) {
    val pm = context.packageManager
    val maps = pm.getLaunchIntentForPackage(GOOGLE_MAPS)
    if (maps == null) {
        Toast.makeText(context, "Google Maps no está instalado", Toast.LENGTH_SHORT).show()
        launch(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")))
        return
    }
    val here = WeatherRepository.lastKnownLocation(context)
    // Con una ruta en marcha, abrir Maps con un «geo:» es pedirle otra cosa, y pregunta
    // «¿Continuar con la navegación?». Se abre tal cual estaba, sin intent nuevo.
    val navigating = com.cockpitf30.nav.MapsGuidanceState.current.value != null
    val intent = if (here != null && !navigating) {
        Intent(Intent.ACTION_VIEW, Uri.parse("geo:%.6f,%.6f?z=16".format(Locale.US, here.latitude, here.longitude)))
            .setPackage(GOOGLE_MAPS)
    } else {
        maps
    }
    // Pantalla dividida: Maps en la otra mitad, si el sistema lo permite (si no, lo ignora).
    if (com.cockpitf30.system.AppSettings(context).splitScreenMaps) {
        intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
    }
    launch(context, intent)
}

/**
 * Pasa la pantalla al iDrive original del coche, igual que el botón del lanzador de la radio:
 * la actividad OsuInfoActivity de CarCanbus manda a la caja CAN la orden de enseñar el vídeo del
 * iDrive y se cierra. Allí, con la ruleta, está «Estado del vehículo» (neumáticos, puertas…).
 * Para volver, el botón de inicio o atrás de la radio.
 */
internal fun openOriginalBmw(context: Context) {
    val candidates = listOf(
        "android.car.app.canbus" to "android.car.app.canbus.instance.osuwy.activity.OsuInfoActivity",
        "android.car.app.video_original" to "android.car.app.video_original.OriginalMain",
        "android.car.app.canbus" to "android.car.app.canbus.activity.MainActivity",
    )
    for ((pkg, cls) in candidates) {
        try {
            context.startActivity(Intent().setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: Exception) {
        }
    }
    Toast.makeText(context, "No encuentro la app del coche original", Toast.LENGTH_SHORT).show()
}

internal fun openMusic(context: Context) = launch(
    context,
    context.packageManager.getLaunchIntentForPackage("com.spotify.music")
        ?: Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC),
)

internal fun launch(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

enum class Screen { HOME, VEHICLE, TRIP, APPS, DIAGNOSTICS, SETTINGS, WEATHER, PHONE, FUEL, PERFORMANCE, ROUTE, MAINTENANCE, RADIO }
