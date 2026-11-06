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
    val body: (@Composable (VehicleState) -> Unit)? = null,
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
    val state by VehicleRepository.state.collectAsStateWithLifecycle()
    val status by VehicleRepository.status.collectAsStateWithLifecycle()
    val weather by WeatherRepository.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val cards = remember {
        listOf(
            Card("music", "Música", "Abrir reproductor", CardTheme.MUSIC, ::openMusic),
            // Antes "Navegación" (repetida en la barra lateral): ahora la gasolinera más cercana.
            Card("fuel", "Gasolinera", "La más cercana", CardTheme.FUEL, { onOpen(Screen.FUEL) }),
            Card("trip", "Datos de viaje", "Duración, km y consumo", CardTheme.TRIP, { onOpen(Screen.TRIP) }, Alignment.BottomStart) { TripBody(it) },
            Card("weather", "Tiempo", "", CardTheme.WEATHER, { onOpen(Screen.WEATHER) }),
            Card("vehicle", "Vehículo", "Puertas y neumáticos", CardTheme.VEHICLE, { onOpen(Screen.VEHICLE) }),
            Card("phone", "Teléfono", "Marcador y contactos", CardTheme.PHONE, { onOpen(Screen.PHONE) }),
            Card("diag", "Diagnóstico", "Fuente de datos e iDrive", CardTheme.DIAG, { onOpen(Screen.DIAGNOSTICS) }),
        ).associateBy { it.id }
    }
    // Quien tenía guardado el orden con "nav" encuentra la gasolinera en el mismo sitio.
    var order by remember {
        mutableStateOf(CardOrder.merge(settings.cardOrder.map { if (it == "nav") "fuel" else it }, cards.keys.toList()))
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
        ClusterStrip(state, status, Modifier.fillMaxWidth().height(CLUSTER_HEIGHT).padding(horizontal = 12.dp))
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
                                    card.body?.invoke(state)
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
private fun TripBody(@Suppress("UNUSED_PARAMETER") state: VehicleState) {
    val trips by TripComputer.state.collectAsStateWithLifecycle()
    val trip = trips.current ?: trips.last
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
        Metric(trip?.let { km(it.distanceKm) } ?: "--", "km", 96.dp)
        Metric(trip?.let { hm(it.durationMillis) } ?: "--", if (trips.current != null) "h · en curso" else "h · último", 150.dp)
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
 * conocida, la abre normal (Maps se centra solo en la ubicación). Si no está instalada,
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
    val intent = if (here != null) {
        Intent(Intent.ACTION_VIEW, Uri.parse("geo:%.6f,%.6f?z=16".format(Locale.US, here.latitude, here.longitude)))
            .setPackage(GOOGLE_MAPS)
    } else {
        maps
    }
    launch(context, intent)
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

enum class Screen { HOME, VEHICLE, TRIP, APPS, DIAGNOSTICS, WEATHER, PHONE, FUEL }
