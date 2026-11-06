package com.cockpitf30.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cockpitf30.fuel.FuelRepository
import com.cockpitf30.fuel.FuelState
import com.cockpitf30.fuel.FuelStation
import com.cockpitf30.fuel.FuelStations
import com.cockpitf30.fuel.NearStation
import com.cockpitf30.nav.GeoPoint
import com.cockpitf30.nav.MapTiles
import com.cockpitf30.nav.RouteTracker
import com.cockpitf30.ui.components.FocusTile
import com.cockpitf30.ui.components.FuelMarker
import com.cockpitf30.ui.components.InitialFocus
import com.cockpitf30.ui.components.StationMark
import com.cockpitf30.ui.components.TopBar
import com.cockpitf30.ui.components.drawStationMap
import com.cockpitf30.ui.components.fitView
import com.cockpitf30.ui.components.TriangleBackground
import com.cockpitf30.ui.theme.Cockpit
import com.cockpitf30.ui.theme.numeric
import java.util.Locale
import kotlin.math.roundToInt

/** Gasolineras cercanas a la posición actual (o las de ejemplo en modo Demo). */
class FuelView(
    val near: List<NearStation>,
    val position: GeoPoint?,
    val bearing: Float,
    val demo: Boolean,
    val state: FuelState,
)

/** Centro del recorrido de ejemplo del modo Demo (Múnich). */
private val DEMO_CENTER = GeoPoint(48.1575, 11.5840)

@Composable
fun rememberFuelView(): FuelView {
    val route by RouteTracker.state.collectAsStateWithLifecycle()
    val fuel by FuelRepository.state.collectAsStateWithLifecycle()
    val pos = route.position
    val demo = pos != null && !route.live
    // Se recalcula cada ~50 m, no con cada lectura del GPS.
    val key = pos?.let { (it.lat * 2000).roundToInt() to (it.lon * 2000).roundToInt() }
    val near = remember(key, fuel.stations, demo) {
        when {
            pos == null -> emptyList()
            demo -> FuelStations.nearest(FuelRepository.demoStations(DEMO_CENTER), pos)
            else -> FuelStations.nearest(fuel.stations, pos)
        }
    }
    return FuelView(near, pos, route.bearing, demo, fuel)
}

private val Panel = RoundedCornerShape(16.dp)

/**
 * Gasolineras: mapa con la más cercana como destino y la lista del resto ordenadas por distancia.
 * Elegir otra en la lista la pone de destino; "Iniciar ruta" abre la navegación de Google Maps.
 */
@Composable
fun FuelScreen() {
    val view = rememberFuelView()
    val context = LocalContext.current
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = view.near.firstOrNull { it.station.id == selectedId } ?: view.near.firstOrNull()
    val go = remember { FocusRequester() }
    InitialFocus(go)

    TriangleBackground(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Gasolineras") {
            Text(statusText(view), style = MaterialTheme.typography.bodyMedium, color = if (view.demo) Cockpit.Alert else Cockpit.TextDim)
        }
        Row(
            Modifier.weight(1f).fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(Modifier.weight(1f).fillMaxHeight().clip(Panel).border(1.dp, Cockpit.GlassBorder, Panel)) {
                StationMapView(view, selected, Modifier.fillMaxSize())
                if (selected != null) {
                    DestinationPanel(selected, selected === view.near.firstOrNull(), Modifier.align(Alignment.TopStart).padding(18.dp), go) { navigateTo(context, selected.station) }
                } else {
                    EmptyMessage(view, Modifier.align(Alignment.Center))
                }
            }
            StationList(view.near, selected?.station?.id, Modifier.width(600.dp).fillMaxHeight()) { selectedId = it.station.id }
        }
    }
    }
}

private fun statusText(view: FuelView): String = when {
    view.demo -> "Demo · estaciones de ejemplo"
    view.state.loading && view.state.stations.isEmpty() -> "Descargando estaciones…"
    view.state.dataDate != null -> "Precios del Ministerio · ${view.state.dataDate}"
    view.state.error != null -> "Sin datos: ${view.state.error}"
    else -> ""
}

@Composable
private fun StationMapView(view: FuelView, selected: NearStation?, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    val phase by rememberInfiniteTransition(label = "fuelmap").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "phase",
    )
    val tiles by MapTiles.version.collectAsStateWithLifecycle()
    val numberStyle = numeric(18, FontWeight.Bold)
    val labelStyle = numeric(13, FontWeight.SemiBold)
    BoxWithConstraints(modifier) {
        val w = maxWidth.value
        val h = maxHeight.value
        val car = view.position
        val dest = selected?.station?.point
        // Coche, destino y las más cercanas a la vista, en la parte del mapa que no tapa el panel del destino.
        val panelDp = 480f
        val (center, zoom) = remember(car?.let { (it.lat * 4000).roundToInt() to (it.lon * 4000).roundToInt() }, dest, view.near, w, h) {
            val pts = listOfNotNull(car, dest) + view.near.take(5).map { it.station.point }
            if (pts.isEmpty()) return@remember GeoPoint(40.4168, -3.7038) to 6f
            val (c, z) = fitView(pts, w - panelDp, h)
            // El centro del encuadre va en el centro de la zona libre: se desplaza el mapa medio panel a la izquierda.
            val metersPerDp = com.cockpitf30.nav.TileMath.metersPerPixel(c.lat, 0) / Math.pow(2.0, z.toDouble())
            val shiftDeg = (panelDp / 2 * metersPerDp) / (111_320.0 * kotlin.math.cos(Math.toRadians(c.lat)))
            GeoPoint(c.lat, c.lon - shiftDeg) to z
        }
        val marks = remember(view.near, selected) {
            view.near.mapIndexed { i, n ->
                StationMark(n.station.point, i + 1, n.station.id == selected?.station?.id, n.station.brand)
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            tiles // se vuelve a dibujar cuando llega una tesela
            drawStationMap(
                Rect(0f, 0f, size.width, size.height), center, zoom, car, view.bearing, marks, tm, numberStyle, labelStyle, phase,
            )
        }
    }
}

@Composable
private fun DestinationPanel(near: NearStation, nearest: Boolean, modifier: Modifier, focus: FocusRequester, onGo: () -> Unit) {
    val s = near.station
    Column(
        modifier.width(440.dp).clip(Panel).background(Cockpit.Background.copy(alpha = 0.82f))
            .border(1.dp, Cockpit.GlassBorder, Panel).padding(20.dp),
    ) {
        Text(if (nearest) "DESTINO · LA MÁS CERCANA" else "DESTINO", style = MaterialTheme.typography.labelMedium, color = FuelMarker)
        Spacer(Modifier.height(6.dp))
        Text(s.brand, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(place(s), style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(FuelStations.formatDistance(near.meters), style = numeric(44, FontWeight.Medium))
            Text("  en línea recta", style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim, modifier = Modifier.padding(bottom = 10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Price("Gasóleo A", s.diesel)
            Price("Gasolina 95", s.gasoline95)
        }
        if (s.hours.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text("Horario  ${s.hours}", style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(14.dp))
        FocusTile(onClick = onGo, modifier = Modifier.fillMaxWidth().height(56.dp).focusRequester(focus)) {
            Box(Modifier.fillMaxSize().background(Cockpit.Blue.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
                Text("Iniciar ruta en Google Maps", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun Price(label: String, price: Double?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(price?.let(FuelStations::formatPrice) ?: "--", style = numeric(26, FontWeight.Medium))
            Text(" €/l", style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
private fun StationList(near: List<NearStation>, selectedId: String?, modifier: Modifier, onPick: (NearStation) -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(selectedId) {
        val i = near.indexOfFirst { it.station.id == selectedId }
        if (i >= 0 && i !in list.layoutInfo.visibleItemsInfo.map { it.index }) list.animateScrollToItem(i)
    }
    LazyColumn(modifier, state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(near, key = { _, n -> n.station.id }) { i, n ->
            val isSelected = n.station.id == selectedId
            FocusTile(onClick = { onPick(n) }, selected = isSelected, modifier = Modifier.fillMaxWidth().height(86.dp)) {
                Row(
                    Modifier.fillMaxSize()
                        .background(if (isSelected) FuelMarker.copy(alpha = 0.12f) else Color.Transparent)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape)
                            .background(if (isSelected) FuelMarker else Cockpit.SurfaceHigh)
                            .border(1.5.dp, FuelMarker.copy(alpha = 0.8f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text("${i + 1}", style = numeric(16, FontWeight.Bold)) }
                    Column(Modifier.weight(1f)) {
                        Text(n.station.brand, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(place(n.station), style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(FuelStations.formatDistance(n.meters), style = numeric(26, FontWeight.Medium))
                        Text(
                            n.station.diesel?.let { "Gasóleo ${FuelStations.formatPrice(it)} €" } ?: "Sin gasóleo",
                            style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyMessage(view: FuelView, modifier: Modifier) {
    val text = when {
        view.position == null -> "Esperando la ubicación GPS…"
        view.state.loading -> "Descargando las estaciones del Ministerio…"
        view.state.error != null -> "No se han podido descargar las gasolineras (${view.state.error})"
        else -> "No hay gasolineras cerca"
    }
    Text(text, modifier.padding(24.dp), style = MaterialTheme.typography.titleMedium, color = Cockpit.TextDim)
}

/** "Avenida de Castilla 26 · Alcobendas", con la dirección en mayúsculas del Ministerio suavizada. */
internal fun place(s: FuelStation): String {
    val addr = s.address.lowercase(Locale("es", "ES")).split(' ').joinToString(" ") { w ->
        if (w.length > 2) w.replaceFirstChar { it.titlecase(Locale("es", "ES")) } else w
    }
    return listOf(addr, s.town).filter { it.isNotBlank() }.joinToString(" · ")
}

/** Abre la navegación de Google Maps hacia la gasolinera; sin Maps, cualquier app de mapas. */
internal fun navigateTo(context: Context, s: FuelStation) {
    val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=%.6f,%.6f".format(Locale.US, s.lat, s.lon))).setPackage(GOOGLE_MAPS)
    if (nav.resolveActivity(context.packageManager) != null) {
        launch(context, nav)
    } else {
        val label = Uri.encode(s.brand)
        launch(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:%.6f,%.6f?q=%.6f,%.6f($label)".format(Locale.US, s.lat, s.lon, s.lat, s.lon))))
    }
}
