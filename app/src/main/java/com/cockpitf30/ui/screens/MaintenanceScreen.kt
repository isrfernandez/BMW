package com.cockpitf30.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cockpitf30.system.AppSettings
import com.cockpitf30.ui.components.FocusTile
import com.cockpitf30.ui.components.InitialFocus
import com.cockpitf30.ui.components.PanelTitle
import com.cockpitf30.ui.components.TopBar
import com.cockpitf30.ui.components.TriangleBackground
import com.cockpitf30.ui.components.glassPanel
import com.cockpitf30.ui.theme.Cockpit
import com.cockpitf30.ui.theme.numeric
import com.cockpitf30.vehicle.Maintenance
import com.cockpitf30.vehicle.ServiceItem
import com.cockpitf30.vehicle.ServiceLevel
import com.cockpitf30.vehicle.ServiceRecord
import com.cockpitf30.vehicle.ServiceStatus
import com.cockpitf30.vehicle.trip.TripComputer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val es = Locale("es", "ES")
private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", es)

fun serviceColor(level: ServiceLevel): Color = when (level) {
    ServiceLevel.OK -> Color(0xFF3DDC97)
    ServiceLevel.SOON -> Color(0xFFFFC94D)
    ServiceLevel.DUE -> Color(0xFFFF5A46)
    ServiceLevel.UNKNOWN -> Color(0xFF8A96A6)
}.let {
    // En diurno los tonos claros (verde, ámbar) no se leen sobre blanco ni junto al dibujo verde.
    if (Cockpit.Light) androidx.compose.ui.graphics.lerp(it, Color.Black, 0.45f) else it
}

/** Estado de todas las tareas con los ajustes guardados. */
fun serviceStatuses(settings: AppSettings, now: Long = System.currentTimeMillis()): List<ServiceStatus> {
    val odo = TripComputer.odometerKm()
    return ServiceItem.entries.map { Maintenance.status(it, settings.serviceRecord(it), odo, settings.itvDate, now, everyKm = settings.serviceEveryKm(it)) }
}

/**
 * Mantenimiento: cuentakilómetros (la caja CAN no manda el del coche: se pone una vez y la app
 * suma lo recorrido), y aceite, neumáticos e ITV con lo que queda por km y por fecha.
 */
@Composable
fun MaintenanceScreen() {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    var version by remember { mutableIntStateOf(0) }
    val statuses = remember(version) { serviceStatuses(settings) }
    val odo = remember(version) { TripComputer.odometerKm() }
    val first = remember { FocusRequester() }
    InitialFocus(first)
    fun changed() { version++ }

    TriangleBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Mantenimiento") {
                val worst = Maintenance.mostUrgent(statuses)
                Text(
                    when (worst?.level) {
                        ServiceLevel.DUE -> "Hay tareas vencidas"
                        ServiceLevel.SOON -> "Algo toca pronto"
                        ServiceLevel.OK -> "Todo al día"
                        else -> "Configura los datos"
                    },
                    style = MaterialTheme.typography.bodyMedium, color = worst?.let { serviceColor(it.level) } ?: Cockpit.TextDim,
                )
            }
            Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Cuentakilómetros: tocar la cifra abre la edición; al guardar se cierra.
                var editing by remember { mutableStateOf(odo == null) }
                val field = remember { FocusRequester() }
                Row(Modifier.fillMaxWidth().height(120.dp).glassPanel(16.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    FocusTile(onClick = { editing = true }, modifier = Modifier.width(430.dp).focusRequester(first)) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                            PanelTitle("Kilómetros del coche")
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(odo?.let { "%,d".format(es, it).replace(',', '.') } ?: "Sin poner", style = numeric(46, FontWeight.Medium), maxLines = 1)
                                if (odo != null) Text(" km", style = numeric(20), color = Cockpit.TextDim, modifier = Modifier.padding(bottom = 8.dp))
                            }
                        }
                    }
                    if (editing) {
                        Spacer(Modifier.weight(1f))
                        var typed by remember { mutableStateOf(odo?.toString() ?: "") }
                        val parsed = typed.toLongOrNull()?.takeIf { it in 1..2_000_000 }
                        fun save() { if (parsed != null) { TripComputer.setOdometerKm(parsed); editing = false; changed() } }
                        androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
                        OutlinedTextField(
                            value = typed, onValueChange = { v -> typed = v.filter(Char::isDigit).take(7) }, singleLine = true,
                            placeholder = { Text("Km del coche", style = numeric(26)) },
                            textStyle = numeric(30, FontWeight.Medium),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { save() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Cockpit.BlueLight, unfocusedBorderColor = Cockpit.TextDim,
                                focusedTextColor = Cockpit.Text, unfocusedTextColor = Cockpit.Text, cursorColor = Cockpit.BlueLight,
                            ),
                            modifier = Modifier.width(260.dp).focusRequester(field),
                        )
                        FocusTile(onClick = { save() }, selected = parsed != null, modifier = Modifier.padding(start = 12.dp)) {
                            Text("Guardar", Modifier.padding(horizontal = 26.dp, vertical = 16.dp), style = MaterialTheme.typography.titleMedium)
                        }
                        if (odo != null) FocusTile(onClick = { editing = false }, modifier = Modifier.padding(start = 12.dp)) {
                            Text("Cancelar", Modifier.padding(horizontal = 22.dp, vertical = 16.dp), style = MaterialTheme.typography.titleMedium)
                        }
                    } else {
                        Text(
                            "Si no coincide con el cuadro del coche, ajústalo de 100 en 100; la app suma lo que recorres.",
                            style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim, modifier = Modifier.weight(1f).padding(horizontal = 16.dp), maxLines = 2,
                        )
                        listOf(-100L, 100L).forEach { step ->
                            FocusTile(onClick = { odo?.let { TripComputer.setOdometerKm((it + step).coerceAtLeast(0)); changed() } }, modifier = Modifier.padding(start = 12.dp)) {
                                Text(if (step > 0) "+100" else "−100", Modifier.padding(horizontal = 26.dp, vertical = 16.dp), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    statuses.forEach { st ->
                        ServiceCard(st, Modifier.weight(1f).fillMaxHeight(), settings, odo) { changed() }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServiceCard(st: ServiceStatus, modifier: Modifier, settings: AppSettings, odo: Long?, onChange: () -> Unit) {
    val color = serviceColor(st.level)
    Column(modifier.glassPanel(16.dp, color).padding(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PanelTitle(st.item.label, accent = color, trailing = {
            Text(
                when (st.level) { ServiceLevel.OK -> "AL DÍA"; ServiceLevel.SOON -> "PRONTO"; ServiceLevel.DUE -> "VENCIDO"; ServiceLevel.UNKNOWN -> "SIN DATOS" },
                style = MaterialTheme.typography.labelLarge, color = color,
            )
        })
        Text(st.text, style = numeric(30, FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
        ServiceBar(st, Modifier.fillMaxWidth().height(18.dp))
        Text(st.item.hint, style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim, maxLines = 2)
        if (st.item == ServiceItem.ITV) {
            val date = settings.itvDate
            Text(date?.let { "Próxima: ${it.format(DATE)}" } ?: "Sin fecha", style = MaterialTheme.typography.bodyLarge)
        } else {
            settings.serviceEveryKm(st.item)?.let { every ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Cada ${"%,d".format(es, every).replace(',', '.')} km", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    FocusTile(onClick = { settings.setServiceEveryKm(st.item, every - 1_000); onChange() }) {
                        Text("−1.000", Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
                    }
                    FocusTile(onClick = { settings.setServiceEveryKm(st.item, every + 1_000); onChange() }) {
                        Text("+1.000", Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            val r = settings.serviceRecord(st.item)
            Text(
                r?.let { listOfNotNull(it.doneAtMillis?.let { at -> "Hecho el " + java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(DATE) }, it.doneAtKm?.let { km -> "a los ${"%,d".format(es, km).replace(',', '.')} km" }).joinToString(" ") } ?: "Sin registrar",
                style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.weight(1f))
        if (st.item == ServiceItem.ITV) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallButton("−1 mes") { settings.itvDate = (settings.itvDate ?: LocalDate.now().plusYears(1)).minusMonths(1); onChange() }
                SmallButton("+1 mes") { settings.itvDate = (settings.itvDate ?: LocalDate.now()).plusMonths(1); onChange() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallButton("Pasada hoy (+${settings.itvYears} año${if (settings.itvYears > 1) "s" else ""})") {
                    settings.itvDate = LocalDate.now().plusYears(settings.itvYears.toLong()); onChange()
                }
                SmallButton(if (settings.itvYears == 1) "Cada 2 años" else "Cada año") { settings.itvYears = if (settings.itvYears == 1) 2 else 1; onChange() }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallButton("Hecho hoy") {
                    settings.setServiceRecord(st.item, ServiceRecord(odo, System.currentTimeMillis())); onChange()
                }
                if (settings.serviceRecord(st.item) != null) SmallButton("Borrar") { settings.setServiceRecord(st.item, null); onChange() }
            }
        }
    }
}

@Composable
private fun RowScope.SmallButton(label: String, onClick: () -> Unit) {
    FocusTile(onClick = onClick, modifier = Modifier.weight(1f)) {
        Text(label, Modifier.align(Alignment.Center).padding(horizontal = 12.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}

/** Barra de lo consumido del intervalo, del verde al rojo, con brillo en la punta. */
@Composable
private fun ServiceBar(st: ServiceStatus, modifier: Modifier) {
    val color = serviceColor(st.level)
    Canvas(modifier) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Cockpit.Ink.copy(alpha = 0.08f), cornerRadius = r)
        val f = st.used.coerceIn(0f, 1f)
        if (f > 0f && st.level != ServiceLevel.UNKNOWN) {
            val w = size.width * f
            drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF3DDC97), Color(0xFFFFC94D), Color(0xFFFF5A46)), endX = size.width), size = Size(w, size.height), cornerRadius = r)
            drawCircle(color.copy(alpha = 0.45f), size.height, Offset(w - size.height / 2, size.height / 2), blendMode = BlendMode.Plus)
        }
    }
}
