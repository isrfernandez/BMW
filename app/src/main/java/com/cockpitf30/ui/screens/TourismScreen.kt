package com.cockpitf30.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cockpitf30.nav.AlertCategory
import com.cockpitf30.nav.FoodLodging
import com.cockpitf30.nav.GeoPoint
import com.cockpitf30.nav.RouteAlerts
import com.cockpitf30.nav.RoutePois
import com.cockpitf30.nav.RouteTracker
import com.cockpitf30.nav.StayKind
import com.cockpitf30.nav.Wiki
import com.cockpitf30.nav.WikiSummary
import com.cockpitf30.ui.components.FocusTile
import com.cockpitf30.ui.components.InitialFocus
import com.cockpitf30.ui.components.PanelTitle
import com.cockpitf30.ui.components.TopBar
import com.cockpitf30.ui.components.TriangleBackground
import com.cockpitf30.ui.components.alertColor
import com.cockpitf30.ui.components.glassPanel
import com.cockpitf30.ui.theme.Cockpit
import com.cockpitf30.ui.theme.numeric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

/** Una fila de la lista: un sitio para ver, comer o dormir, ya medido desde el coche. */
data class TourItem(
    val id: String,
    val name: String,
    val what: String,
    val detail: String?,
    val meters: Double,
    val relBearing: Float,
    val lat: Double,
    val lon: Double,
    /** Popularidad: nº de Wikipedias (sitios) o puntos de la ficha (comer y dormir). */
    val popularity: Int,
    val popularityText: String?,
    val poi: com.cockpitf30.nav.Poi? = null,
    val phone: String? = null,
)

private enum class TourTab(val label: String) { SEE("Para ver"), EAT("Comer"), SLEEP("Dormir") }
private enum class TourSort(val label: String) { BEST("Recomendados"), NEAR("Más cerca"), POPULAR("Más populares") }

/**
 * Turismo: lo más emblemático de la zona (monumentos, miradores, museos con artículo en
 * Wikipedia), dónde comer y dónde dormir (hoteles, hostales y campings), por cercanía y
 * popularidad. Al tocar uno se ve su ficha (con el resumen y la foto de Wikipedia en los sitios) y
 * se puede ir con Google Maps.
 */
@Composable
fun TourismScreen() {
    val context = LocalContext.current
    val pois by RoutePois.state.collectAsStateWithLifecycle()
    val stays by FoodLodging.state.collectAsStateWithLifecycle()
    val route by RouteTracker.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(TourTab.SEE) }
    var sort by remember { mutableStateOf(TourSort.BEST) }
    var selected by remember { mutableStateOf<TourItem?>(null) }
    var fameVersion by remember { mutableIntStateOf(0) }
    val first = remember { FocusRequester() }
    InitialFocus(first)
    val pos = route.position ?: pois.position

    // Comer y dormir se descarga al abrir (si no estaba ya), y la popularidad de los sitios.
    LaunchedEffect(pos != null) {
        // Al abrir, y luego cada 5 s por si falla (los reintentos se van espaciando solos).
        pos?.let { FoodLodging.maybeFetch(null, it, force = FoodLodging.state.value.stays.isEmpty()) }
        while (true) {
            kotlinx.coroutines.delay(5000)
            (RouteTracker.state.value.position ?: RoutePois.state.value.position)?.let { FoodLodging.maybeFetch(null, it) }
        }
    }
    LaunchedEffect(pois.pois.size) {
        val ids = pois.pois.filter { it.category == AlertCategory.TOURISM && it.kind != "place" }.mapNotNull { it.wikidata }
        if (ids.isNotEmpty()) { withContext(Dispatchers.IO) { Wiki.loadFame(ids) }; fameVersion++ }
    }

    val items = remember(tab, sort, pois, stays, pos?.lat?.let { (it * 200).toInt() }, pos?.lon?.let { (it * 200).toInt() }, fameVersion) {
        buildItems(tab, sort, pos, route.bearing, pois.pois, stays.stays)
    }
    LaunchedEffect(tab) { selected = null }

    TriangleBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Turismo") {
                Text(
                    when {
                        pos == null -> "Sin posición GPS todavía"
                        tab == TourTab.SEE && pois.loading -> "Descargando sitios de interés…"
                        tab != TourTab.SEE && stays.loading -> "Buscando dónde comer y dormir…"
                        tab != TourTab.SEE && stays.error != null && stays.stays.isEmpty() -> "Sin conexión (${stays.error})"
                        else -> "${items.size} ${if (tab == TourTab.SEE) "sitios en 12 km" else if (tab == TourTab.EAT) "sitios en 3 km" else "alojamientos en 15 km"} · OpenStreetMap"
                    },
                    style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim,
                )
            }
            Row(
                Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(Modifier.weight(1.25f).fillMaxHeight().glassPanel(16.dp).padding(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TourTab.entries.forEach { t ->
                            FocusTile(onClick = { tab = t }, selected = t == tab, modifier = if (t == TourTab.SEE) Modifier.focusRequester(first) else Modifier) {
                                Text(t.label, Modifier.padding(horizontal = 18.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        TourSort.entries.forEach { s ->
                            FocusTile(onClick = { sort = s }, selected = s == sort) {
                                Text(s.label, Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (items.isEmpty()) {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            val failed = tab != TourTab.SEE && !stays.loading && stays.error != null && stays.stays.isEmpty()
                            Text(
                                when {
                                    pos == null -> "Esperando la posición GPS de la radio."
                                    tab == TourTab.SEE && pois.loading -> "Buscando…"
                                    failed -> "No he podido descargar dónde comer y dormir."
                                    tab != TourTab.SEE && (stays.loading || stays.updatedAt == 0L) -> "Buscando…"
                                    else -> "No hay nada de esto por aquí cerca."
                                },
                                style = MaterialTheme.typography.bodyLarge, color = Cockpit.TextDim,
                            )
                            if (tab != TourTab.SEE && pos != null && stays.stays.isEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(FoodLodging.note, style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                if (failed) {
                                    Spacer(Modifier.height(12.dp))
                                    FocusTile(onClick = { FoodLodging.retry(pos) }) {
                                        Text("Reintentar", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                            }
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(items, key = { it.id }) { item ->
                                TourRow(item, item.id == selected?.id) { selected = item }
                            }
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight().glassPanel(16.dp).padding(18.dp)) {
                    TourDetail(selected ?: items.firstOrNull(), tab, context)
                }
            }
        }
    }
}

private fun buildItems(
    tab: TourTab, sort: TourSort, pos: GeoPoint?, bearing: Float,
    pois: List<com.cockpitf30.nav.Poi>, stays: List<com.cockpitf30.nav.Stay>,
): List<TourItem> {
    if (pos == null) return emptyList()
    fun rel(lat: Double, lon: Double) = RoutePois.relative(RouteTracker.bearingBetween(pos, GeoPoint(lat, lon)), bearing)
    val list = when (tab) {
        TourTab.SEE -> pois.filter { it.category == AlertCategory.TOURISM && it.kind != "place" }.map { p ->
            val fame = Wiki.fame(p.wikidata)
            TourItem(
                p.id, p.name ?: p.what, p.what, null, RouteTracker.distance(pos, p.point), rel(p.lat, p.lon), p.lat, p.lon,
                fame ?: 0, fame?.takeIf { it > 0 }?.let { "En $it ${if (it == 1) "Wikipedia" else "Wikipedias"}" }, poi = p,
            )
        }
        TourTab.EAT, TourTab.SLEEP -> {
            val kinds = if (tab == TourTab.EAT) setOf(StayKind.EAT, StayKind.DRINK) else setOf(StayKind.SLEEP, StayKind.CAMP)
            stays.filter { it.kind in kinds }.map { s ->
                TourItem(
                    s.id, s.name, s.what, s.detail, RouteTracker.distance(pos, s.point), rel(s.lat, s.lon), s.lat, s.lon,
                    s.score, null, phone = s.phone,
                )
            }
        }
    }
    val perPoint = if (tab == TourTab.SEE) 1000.0 else 400.0
    // En los sitios la popularidad (nº de Wikipedias) se aplana: de 40 en adelante cuenta igual.
    fun points(i: TourItem) = if (tab == TourTab.SEE) minOf(i.popularity, 40) / 4 else i.popularity
    return when (sort) {
        TourSort.NEAR -> list.sortedBy { it.meters }
        TourSort.POPULAR -> list.sortedWith(compareByDescending<TourItem> { it.popularity }.thenBy { it.meters })
        TourSort.BEST -> list.sortedBy { FoodLodging.rank(it.meters, points(it), perPoint) }
    }.take(60)
}

@Composable
private fun TourRow(item: TourItem, selected: Boolean, onClick: () -> Unit) {
    FocusTile(onClick = onClick, selected = selected, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(item.what, item.detail, item.popularityText).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Stars(item)
            Column(Modifier.width(110.dp), horizontalAlignment = Alignment.End) {
                Text(distance(item.meters), style = numeric(24, FontWeight.Medium))
                Text(arrow(item.relBearing), style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim)
            }
        }
    }
}

/** Popularidad en tres puntos (●●○), comparable dentro de cada lista. */
@Composable
private fun Stars(item: TourItem) {
    val n = when {
        item.poi != null -> when { item.popularity >= 30 -> 3; item.popularity >= 10 -> 2; item.popularity >= 3 -> 1; else -> 0 }
        else -> when { item.popularity >= 6 -> 3; item.popularity >= 4 -> 2; item.popularity >= 2 -> 1; else -> 0 }
    }
    Text("●".repeat(n) + "○".repeat(3 - n), Modifier.padding(horizontal = 12.dp), color = Cockpit.CopperLight, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun TourDetail(item: TourItem?, tab: TourTab, context: Context) {
    val accent = alertColor(if (tab == TourTab.SEE) AlertCategory.TOURISM else AlertCategory.FOOD)
    PanelTitle(if (tab == TourTab.SEE) "Sitio" else if (tab == TourTab.EAT) "Para comer" else "Para dormir", accent = accent)
    if (item == null) {
        Spacer(Modifier.height(16.dp))
        Text("Elige un sitio de la lista.", style = MaterialTheme.typography.bodyLarge, color = Cockpit.TextDim)
        return
    }
    var wiki by remember(item.id) { mutableStateOf<WikiSummary?>(item.poi?.let { Wiki.cached(it) }) }
    LaunchedEffect(item.id) { item.poi?.let { p -> if (wiki == null) wiki = withContext(Dispatchers.IO) { Wiki.load(p) } } }
    val image = remember(wiki) { wiki?.image?.let { b -> runCatching { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull() } }
    Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            image?.let {
                Image(it, null, Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                Spacer(Modifier.height(12.dp))
            }
            Text(item.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(item.what, item.detail, "${distance(item.meters)} ${RouteAlerts.side(item.relBearing).second}").joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge, color = Cockpit.TextDim,
            )
            item.popularityText?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Cockpit.CopperLight) }
            item.phone?.let { Text("Tel. $it", style = MaterialTheme.typography.bodyLarge, color = Cockpit.TextDim) }
            Spacer(Modifier.height(10.dp))
            val text = wiki?.extract?.let { Wiki.firstSentences(it, 520) }
            if (text != null) Text(text, style = MaterialTheme.typography.bodyLarge)
            else if (item.poi != null) Text("Buscando en Wikipedia…", style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim)
        }
        Spacer(Modifier.height(12.dp))
        FocusTile(onClick = { goTo(context, item.lat, item.lon) }, modifier = Modifier.fillMaxWidth()) {
            Text("Llévame con Google Maps", Modifier.fillMaxWidth().padding(vertical = 14.dp).align(Alignment.Center), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

private fun goTo(context: Context, lat: Double, lon: Double) {
    val q = "%.6f,%.6f".format(Locale.US, lat, lon)
    val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q")).setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(nav) }.onFailure {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$q?q=$q")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

private fun distance(m: Double) = if (m < 1000) "${maxOf(50, (m / 50).toInt() * 50)} m" else "%.1f km".format(Locale("es", "ES"), m / 1000)

private fun arrow(rel: Float) = when {
    abs(rel) <= 25 -> "↑ delante"
    abs(rel) >= 150 -> "↓ detrás"
    rel > 0 -> "→ derecha"
    else -> "← izquierda"
}
