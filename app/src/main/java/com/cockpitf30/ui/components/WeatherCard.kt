package com.cockpitf30.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cockpitf30.ui.theme.Cockpit
import com.cockpitf30.ui.theme.numeric
import com.cockpitf30.weather.WeatherKind
import com.cockpitf30.weather.WeatherState
import com.cockpitf30.weather.wmoCondition
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private val ES = Locale("es", "ES")

/** Día abreviado en español: "HOY", "LUN", "MAR"... */
fun dayLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String =
    if (date == today) "HOY" else date.dayOfWeek.getDisplayName(TextStyle.SHORT, ES).trimEnd('.').uppercase(ES)

/**
 * Widget del tiempo para la tarjeta del Inicio: icono grande, temperatura actual, mín~máx,
 * descripción, localidad y los próximos 3 días. Sin datos muestra "--".
 */
@Composable
fun WeatherCardContent(state: WeatherState, modifier: Modifier = Modifier, animated: Boolean = false, compact: Boolean = false) {
    val f = state.forecast
    val condition = f?.let { wmoCondition(it.code) }
    val today = f?.days?.firstOrNull()
    if (compact) {
        // Versión baja para la fila del Inicio: temperatura y estado a la izquierda, lugar y próximos días a la derecha.
        Row(modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
            Column(Modifier.width(120.dp)) {
                Spacer(Modifier.weight(1f))
                Text(f?.let { "${it.tempC}°" } ?: "--°", style = numeric(44, FontWeight.Medium), color = Cockpit.Text)
                Text(condition?.text ?: "Sin datos", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(state.placeName ?: "Elige localidad", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(today?.let { "${it.minC}~${it.maxC}°" } ?: "--~--°", style = numeric(16), color = Cockpit.TextDim)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val next = f?.days?.drop(1)?.take(3).orEmpty()
                    (0 until 3).forEach { i ->
                        val d = next.getOrNull(i)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(d?.let { dayLabel(it.date) } ?: "--", style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim)
                            WeatherIcon(d?.let { wmoCondition(it.code).kind } ?: WeatherKind.CLOUDY, true, Modifier.size(28.dp))
                            Text(d?.let { "${it.minC}/${it.maxC}°" } ?: "--/--", style = numeric(14, FontWeight.Medium), maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
        return
    }
    Row(modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Column(Modifier.width(150.dp)) {
            // Con la escena animada detrás, el icono ya lo dibuja ella; aquí solo se reserva el sitio.
            if (animated) Spacer(Modifier.size(84.dp))
            else WeatherIcon(condition?.kind ?: WeatherKind.CLOUDY, f?.isDay ?: true, Modifier.size(84.dp))
            Spacer(Modifier.weight(1f))
            Text(f?.let { "${it.tempC}°" } ?: "--°", style = numeric(52, FontWeight.Medium), color = Cockpit.Text)
            Text(today?.let { "${it.minC}~${it.maxC}°" } ?: "--~--°", style = numeric(18), color = Cockpit.TextDim)
            Text(condition?.text ?: "Sin datos", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(
                state.placeName ?: "Elige localidad",
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(updatedText(state), style = MaterialTheme.typography.labelMedium, color = if (state.error != null && f == null) Cockpit.Alert else Cockpit.TextDim, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val next = f?.days?.drop(1)?.take(3).orEmpty()
                (0 until 3).forEach { i ->
                    val d = next.getOrNull(i)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(d?.let { dayLabel(it.date) } ?: "--", style = MaterialTheme.typography.labelLarge, color = Cockpit.TextDim)
                        Spacer(Modifier.height(4.dp))
                        WeatherIcon(d?.let { wmoCondition(it.code).kind } ?: WeatherKind.CLOUDY, true, Modifier.size(38.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(d?.let { "${it.minC}/${it.maxC}°" } ?: "--/--", style = numeric(16, FontWeight.Medium), maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

fun updatedText(state: WeatherState): String = when {
    state.loading && state.forecast == null -> "Actualizando…"
    state.updatedAtMillis != null -> "Act. " + java.time.Instant.ofEpochMilli(state.updatedAtMillis)
        .atZone(java.time.ZoneId.systemDefault()).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }
    state.error != null -> state.error
    else -> "Toca para elegir"
}
