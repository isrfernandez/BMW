package com.cockpitf30.ui.screens

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cockpitf30.nav.SpeedCameras
import com.cockpitf30.system.AppSettings
import com.cockpitf30.ui.components.FocusTile
import com.cockpitf30.ui.components.InitialFocus
import com.cockpitf30.ui.components.PanelTitle
import com.cockpitf30.ui.components.RadarAlert
import com.cockpitf30.ui.components.RadarScope
import com.cockpitf30.ui.components.SpeedSign
import com.cockpitf30.ui.components.TopBar
import com.cockpitf30.ui.components.TriangleBackground
import com.cockpitf30.ui.components.WeatherIcon
import com.cockpitf30.ui.components.glassPanel
import com.cockpitf30.ui.components.radarDistance
import com.cockpitf30.ui.theme.Cockpit
import com.cockpitf30.ui.theme.numeric
import com.cockpitf30.weather.Place
import com.cockpitf30.weather.RoutePoint
import com.cockpitf30.weather.RouteWeather
import com.cockpitf30.weather.WeatherRepository
import com.cockpitf30.weather.wmoCondition
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Radares y ruta: radar de barrido con los radares fijos cercanos (OpenStreetMap) y el tiempo a lo
 * largo del camino, hacia donde se va o hasta el destino elegido.
 */
@Composable
fun RouteScreen() {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val cams by SpeedCameras.state.collectAsStateWithLifecycle()
    val route by RouteWeather.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var voice by remember { mutableStateOf(settings.radarVoice) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    InitialFocus(first)
    LaunchedEffect(Unit) { RouteWeather.refresh(context) }

    fun search() {
        if (query.isBlank()) return
        message = "Buscando…"
        scope.launch {
            runCatching { WeatherRepository.search(query) }
                .onSuccess { results = it; message = if (it.isEmpty()) "Sin resultados" else null }
                .onFailure { message = "Error: ${it.message}" }
        }
    }
    fun choose(place: Place?) {
        results = emptyList(); query = ""; message = null
        scope.launch { RouteWeather.setDestination(context, place) }
    }

    TriangleBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Radares y ruta") {
                Text(
                    when {
                        cams.loading -> "Descargando radares…"
                        cams.error != null && cams.cameras.isEmpty() -> "Radares: sin conexión (${cams.error})"
                        else -> "${cams.cameras.size} radares fijos en 40 km · OpenStreetMap"
                    },
                    style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim,
                )
            }
            Row(
                Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Radar.
                Column(Modifier.width(620.dp).fillMaxHeight().glassPanel(16.dp).padding(16.dp)) {
                    PanelTitle("Radares cercanos", accent = if (cams.ahead != null) RadarAlert else Cockpit.BlueLight)
                    Row(Modifier.fillMaxSize().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadarScope(cams, Modifier.size(380.dp))
                        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val ahead = cams.ahead
                            if (ahead != null) {
                                Text("RADAR DELANTE", style = MaterialTheme.typography.labelLarge, color = RadarAlert)
                                Text(radarDistance(ahead.meters), style = numeric(44, FontWeight.Medium))
                                ahead.camera.maxspeed?.let { SpeedSign(it, 72.dp) }
                                ahead.camera.kind?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim) }
                            } else {
                                Text("SIN RADAR DELANTE", style = MaterialTheme.typography.labelLarge, color = Cockpit.TextDim)
                                val n = cams.near.firstOrNull()
                                Text(
                                    n?.let { "El más cercano a ${radarDistance(it.meters)}" } ?: "Ninguno a menos de 3 km",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            FocusTile(
                                onClick = { voice = !voice; settings.radarVoice = voice },
                                modifier = Modifier.fillMaxWidth().height(56.dp).focusRequester(first),
                            ) {
                                Text(
                                    if (voice) "Aviso por voz: sí" else "Aviso por voz: no",
                                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.align(Alignment.Center),
                                )
                            }
                        }
                    }
                }
                // Tiempo en ruta.
                Column(Modifier.weight(1f).fillMaxHeight().glassPanel(16.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PanelTitle("Tiempo en ruta", trailing = {
                        Text(
                            route.destination?.let { "Hasta ${it.name}" } ?: "Hacia donde vas (10, 25 y 50 km)",
                            style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim,
                        )
                    })
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FocusTile(onClick = { choose(null) }, selected = route.destination == null, modifier = Modifier.width(220.dp).height(64.dp)) {
                            Text("Sin destino", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center))
                        }
                        OutlinedTextField(
                            value = query, onValueChange = { query = it },
                            placeholder = { Text("Destino: ciudad o pueblo…") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleMedium,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { search() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Cockpit.BlueLight, unfocusedBorderColor = Cockpit.GlassBorder,
                                focusedTextColor = Cockpit.Text, unfocusedTextColor = Cockpit.Text, cursorColor = Cockpit.BlueLight,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        FocusTile(onClick = ::search, modifier = Modifier.size(64.dp)) {
                            Icon(Icons.Filled.Search, null, Modifier.align(Alignment.Center).size(30.dp), tint = Cockpit.Text)
                        }
                    }
                    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim) }
                    if (results.isNotEmpty()) {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                            items(results) { place ->
                                FocusTile(onClick = { choose(place) }, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                                    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.Center) {
                                        Text(place.name, style = MaterialTheme.typography.titleMedium)
                                        Text(place.subtitle, style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim)
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            route.summary ?: if (route.loading) "Consultando el tiempo…" else route.error?.let { "Sin datos ($it)" } ?: "",
                            style = MaterialTheme.typography.headlineSmall,
                            color = if (route.summary?.startsWith("Sin") == false) Color(0xFF7FD3FF) else Cockpit.Text,
                        )
                        LazyRow(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(route.points) { p -> RoutePointCard(p, Modifier.width(220.dp).fillMaxHeight()) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutePointCard(p: RoutePoint, modifier: Modifier) {
    val wet = (p.precipProb ?: 0) >= 50 || (p.precipMm ?: 0f) >= 0.3f
    val fog = (p.visibilityM ?: 99_999f) < 1000f
    Column(
        modifier.glassPanel(14.dp, tint = if (wet || fog) Color(0xFF7FD3FF) else Cockpit.BlueLight).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(p.label ?: "En ${p.km} km", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (p.minutes < 60) "dentro de ${p.minutes} min" else "dentro de %d h %02d min".format(p.minutes / 60, p.minutes % 60), style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim)
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            WeatherIcon(wmoCondition(p.code).kind, true, Modifier.size(78.dp))
        }
        Text(p.tempC?.let { "$it°" } ?: "--°", style = numeric(30, FontWeight.Medium))
        Text(wmoCondition(p.code).text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Lluvia ${p.precipProb?.let { "$it %" } ?: "--"}", style = MaterialTheme.typography.bodyMedium, color = if (wet) Color(0xFF7FD3FF) else Cockpit.TextDim)
        Text(
            "Visibilidad " + (p.visibilityM?.let { if (it >= 10_000) "buena" else if (it >= 1000) "%.0f km".format(Locale("es"), it / 1000) else "${it.toInt()} m" } ?: "--"),
            style = MaterialTheme.typography.bodyMedium, color = if (fog) Color(0xFFFFC94D) else Cockpit.TextDim,
        )
    }
}
