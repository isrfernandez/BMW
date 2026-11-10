package com.cockpitf30.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cockpitf30.ui.components.FocusTile
import com.cockpitf30.ui.components.InitialFocus
import com.cockpitf30.ui.components.TopBar
import com.cockpitf30.ui.components.TriangleBackground
import com.cockpitf30.ui.components.glassPanel
import com.cockpitf30.ui.components.PanelTitle
import com.cockpitf30.ui.theme.Cockpit
import com.cockpitf30.ui.theme.numeric
import com.cockpitf30.vehicle.trip.Trip
import com.cockpitf30.vehicle.trip.TripComputer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Datos de viaje: hora de inicio y fin, duración, kilómetros, consumo y velocidades, con la
 * gráfica de velocidad del viaje y un resumen del último viaje terminado.
 */
@Composable
fun TripScreen() {
    val trips by TripComputer.state.collectAsStateWithLifecycle()
    // Sin viaje en curso se muestra el último, para que la pantalla nunca esté vacía.
    val trip = trips.current ?: trips.last
    val ongoing = trips.current != null
    val first = remember { FocusRequester() }
    InitialFocus(first)

    TriangleBackground(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Datos de viaje") {
            Text(
                when {
                    trips.demo -> "Demo"
                    ongoing -> "Viaje en curso"
                    trip != null -> "Último viaje"
                    else -> "Sin viajes"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (ongoing) Cockpit.Ok else Cockpit.TextDim,
            )
        }
        Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Timeline(trip, ongoing, Modifier.fillMaxWidth().height(132.dp))
            Row(Modifier.fillMaxWidth().height(132.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val cons = trip?.avgConsumption(trips.tankLiters)
                Stat("Distancia", trip?.let { km(it.distanceKm) } ?: "--", "km", accent = Color(0xFF4FA3FF))
                Stat(
                    "Consumo medio", cons?.let { "%.1f".format(Locale("es"), it) } ?: "--", "l/100 km",
                    note = when {
                        trip == null -> null
                        trip.consumptionFromCar -> "Ordenador de a bordo"
                        cons != null -> "Estimado por el depósito"
                        else -> "Necesita más km o datos del CAN"
                    },
                    accent = Color(0xFFFFB74D),
                )
                Stat("Velocidad media", trip?.avgSpeedKmh?.toString() ?: "--", "km/h", accent = Color(0xFF7FD3FF))
                Stat("Velocidad máx.", trip?.takeIf { it.maxSpeedKmh > 0 }?.maxSpeedKmh?.toString() ?: "--", "km/h", accent = Color(0xFFFF6B6B))
                Stat("En movimiento", trip?.let { duration(it.movingMillis) } ?: "--", null, accent = Cockpit.Ok)
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SpeedChart(trip, Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(440.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (ongoing) {
                        LastTrip(trips.last, trips.tankLiters, Modifier.fillMaxWidth().weight(1f))
                    } else {
                        Hint(Modifier.fillMaxWidth().weight(1f))
                    }
                    FocusTile(
                        onClick = TripComputer::resetTrip,
                        modifier = Modifier.fillMaxWidth().height(72.dp).focusRequester(first),
                    ) {
                        Text(
                            if (ongoing) "Terminar viaje y empezar otro" else "Empezará al mover el coche",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (ongoing) Cockpit.Text else Cockpit.TextDim,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }
        }
    }
}
}

/** Inicio a la izquierda, fin a la derecha y la duración sobre una línea que los une. */
@Composable
private fun Timeline(trip: Trip?, ongoing: Boolean, modifier: Modifier) {
    Row(modifier.panel().padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        TimePoint("Inicio", trip?.let { clock(it.startMillis) } ?: "--:--", trip?.let { day(it.startMillis) }, Alignment.Start)
        Column(Modifier.weight(1f).padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("DURACIÓN", style = MaterialTheme.typography.labelLarge, color = Cockpit.TextDim)
            Text(trip?.let { duration(it.durationMillis) } ?: "--", style = numeric(46, FontWeight.Medium), color = Cockpit.Text)
            Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                val y = size.height / 2
                val r = 6.dp.toPx()
                val end = Offset(size.width - r, y)
                drawLine(Brush.horizontalGradient(listOf(Cockpit.Blue, Cockpit.BlueLight)), Offset(r, y), end, 3.dp.toPx(), StrokeCap.Round)
                drawCircle(Cockpit.BlueLight, r, Offset(r, y))
                if (ongoing) {
                    // Fin abierto: punto hueco con halo, el viaje sigue.
                    drawCircle(Cockpit.BlueLight.copy(alpha = 0.3f), r * 2, end)
                    drawCircle(Cockpit.Background, r, end)
                    drawCircle(Cockpit.BlueLight, r, end, style = Stroke(2.dp.toPx()))
                } else {
                    drawCircle(Cockpit.BlueLight, r, end)
                }
            }
        }
        TimePoint(
            "Fin", if (ongoing) "En curso" else trip?.let { clock(it.endMillis) } ?: "--:--",
            trip?.takeIf { !ongoing }?.let { day(it.endMillis) }, Alignment.End,
            color = if (ongoing) Cockpit.Ok else Cockpit.Text,
        )
    }
}

@Composable
private fun TimePoint(label: String, value: String, sub: String?, align: Alignment.Horizontal, color: Color = Cockpit.Text) {
    Column(Modifier.width(260.dp), horizontalAlignment = align) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelLarge, color = Cockpit.TextDim)
        Text(value, style = numeric(46, FontWeight.Medium), color = color, maxLines = 1)
        Text(sub ?: " ", style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim)
    }
}

@Composable
private fun RowScope.Stat(label: String, value: String, unit: String?, note: String? = null, accent: Color = Cockpit.BlueLight) {
    Column(Modifier.weight(1f).fillMaxHeight().panel(accent).padding(horizontal = 22.dp, vertical = 14.dp), verticalArrangement = Arrangement.Center) {
        PanelTitle(label, accent = accent)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = numeric(44, FontWeight.Medium), color = Cockpit.Text, maxLines = 1)
            if (unit != null) Text(" $unit", style = numeric(20), color = Cockpit.TextDim, modifier = Modifier.padding(bottom = 7.dp), maxLines = 1)
        }
        if (note != null) Text(note, style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim, maxLines = 1)
    }
}

/** Velocidad media de cada tramo del viaje, con relleno degradado y líneas guía cada 50 km/h. */
@Composable
private fun SpeedChart(trip: Trip?, modifier: Modifier) {
    Column(modifier.panel().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelTitle("Velocidad durante el viaje", Modifier.weight(1f)) {
                Text("km/h", style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim)
            }
        }
        Spacer(Modifier.height(10.dp))
        val samples = trip?.speedSamples.orEmpty()
        if (samples.size < 2) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("La gráfica aparece tras unos minutos de viaje", style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim)
            }
            return@Column
        }
        val top = ((samples.max() + 49) / 50 * 50).coerceAtLeast(100)
        Row(Modifier.fillMaxSize()) {
            // Escala a la izquierda.
            Column(Modifier.fillMaxHeight().width(44.dp), verticalArrangement = Arrangement.SpaceBetween) {
                listOf(top, top / 2, 0).forEach { Text("$it", style = numeric(15), color = Cockpit.TextDim) }
            }
            // Margen vertical de media línea de texto para que cada cifra quede a la altura de su guía.
            Canvas(Modifier.weight(1f).fillMaxHeight().padding(vertical = 10.dp)) {
                val w = size.width
                val h = size.height
                val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
                for (v in 0..top step 50) {
                    val y = h * (1f - v / top.toFloat())
                    drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, y), Offset(w, y), 1.dp.toPx(), pathEffect = dash)
                }
                val pts = samples.mapIndexed { i, v -> Offset(w * i / (samples.size - 1), h * (1f - v / top.toFloat())) }
                val line = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until pts.size) {
                        val a = pts[i - 1]
                        val b = pts[i]
                        val mx = (a.x + b.x) / 2
                        cubicTo(mx, a.y, mx, b.y, b.x, b.y)
                    }
                }
                val area = Path().apply { addPath(line); lineTo(w, h); lineTo(0f, h); close() }
                drawPath(area, Brush.verticalGradient(listOf(Cockpit.BlueLight.copy(alpha = 0.35f), Color.Transparent)))
                drawPath(line, Cockpit.BlueLight.copy(alpha = 0.25f), style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
                drawPath(line, Brush.horizontalGradient(listOf(Cockpit.Blue, Cockpit.BlueLight)), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                drawCircle(Color.White, 5.dp.toPx(), pts.last())
            }
        }
    }
}

/** Sin viaje en curso: explica cuándo empieza el siguiente. */
@Composable
private fun Hint(modifier: Modifier) {
    Column(modifier.panel().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelTitle("Próximo viaje", accent = Cockpit.Ok)
        Text(
            "Empieza solo al mover el coche y termina tras 10 minutos parado o con el contacto quitado.",
            style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim,
        )
    }
}

/** Resumen compacto del viaje anterior. */
@Composable
private fun LastTrip(trip: Trip?, tank: Float, modifier: Modifier) {
    Column(modifier.panel().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelTitle("Último viaje", accent = Cockpit.Ok)
        if (trip == null) {
            Text("Todavía no hay un viaje anterior", style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim)
            return@Column
        }
        Text(
            "${day(trip.startMillis)} · ${clock(trip.startMillis)} – ${clock(trip.endMillis)}",
            style = numeric(22, FontWeight.Medium), color = Cockpit.Text,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Mini(km(trip.distanceKm), "km")
            Mini(duration(trip.durationMillis), null)
            Mini(trip.avgConsumption(tank)?.let { "%.1f".format(Locale("es"), it) } ?: "--", "l/100")
        }
    }
}

@Composable
private fun Mini(value: String, unit: String?) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = numeric(30, FontWeight.Medium), color = Cockpit.Text)
        if (unit != null) Text(" $unit", style = numeric(16), color = Cockpit.TextDim, modifier = Modifier.padding(bottom = 4.dp))
    }
}

/** Recuadro de cristal ahumado sobre el fondo de triángulos. */
private fun Modifier.panel(tint: Color = Cockpit.BlueLight) = glassPanel(16.dp, tint)

internal fun km(v: Double) = if (v < 100) "%.1f".format(Locale("es"), v) else "%.0f".format(Locale("es"), v)

/** "1 h 05 min", "12 min" o "40 s". */
internal fun duration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    return when {
        h > 0 -> "%d h %02d min".format(h, m)
        m > 0 -> "$m min"
        else -> "${s % 60} s"
    }
}

/** Duración compacta "1:05" (horas:minutos) para la tarjeta del Inicio. */
internal fun hm(ms: Long): String = (ms / 60_000).let { "%d:%02d".format(it / 60, it % 60) }

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale("es"))

private fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(CLOCK)

private fun day(ms: Long): String {
    val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (d) {
        today -> "Hoy"
        today.minusDays(1) -> "Ayer"
        else -> d.format(DAY)
    }
}
