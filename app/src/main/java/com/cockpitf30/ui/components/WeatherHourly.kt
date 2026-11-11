package com.cockpitf30.ui.components

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cockpitf30.ui.theme.Cockpit
import com.cockpitf30.ui.theme.numeric
import com.cockpitf30.weather.DayForecast
import com.cockpitf30.weather.HourForecast
import com.cockpitf30.weather.wmoCondition
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private val COLUMN = 112.dp
private val RainBlue = Color(0xFF4FA3FF)

/**
 * Detalle por horas de un día: cabecera con icono animado, mín/máx, sol y lluvia; tira de horas
 * desplazable con la ruleta (hora, icono, % de lluvia y viento con flecha) y, alineada debajo, la
 * gráfica de temperatura con las barras de precipitación.
 */
@Composable
fun HourlyDetail(day: DayForecast, hours: List<HourForecast>, modifier: Modifier = Modifier) {
    val es = Locale("es", "ES")
    val title = if (day.date == LocalDate.now()) "Hoy" else
        day.date.dayOfWeek.getDisplayName(TextStyle.FULL, es).replaceFirstChar { it.uppercase() } + " " + day.date.dayOfMonth
    val c = wmoCondition(day.code)
    Column(modifier) {
        // Cabecera
        Row(Modifier.fillMaxWidth().height(120.dp), verticalAlignment = Alignment.CenterVertically) {
            WeatherIcon(c.kind, true, Modifier.size(110.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.width(360.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 36.sp))
                Text(c.text, style = MaterialTheme.typography.titleMedium, color = Cockpit.TextDim)
            }
            HeaderStat("MÍN / MÁX", "${day.minC}° / ${day.maxC}°")
            HeaderStat("SOL", listOfNotNull(day.sunrise, day.sunset).joinToString("  ") { "%02d:%02d".format(it.hour, it.minute) }.ifEmpty { "--" })
            HeaderStat("LLUVIA", day.precipSumMm?.let { "%.1f mm".format(it) } ?: "--")
            HeaderStat("VIENTO MÁX", day.windMaxKmh?.let { "$it km/h" } ?: "--")
        }
        Spacer(Modifier.height(8.dp))
        if (hours.isEmpty()) {
            Text("Sin previsión por horas. Se cargará en la próxima actualización.", style = MaterialTheme.typography.headlineSmall, color = Cockpit.TextDim)
            return@Column
        }
        val scroll = rememberScrollState()
        val first = remember { FocusRequester() }
        InitialFocus(first)
        Column(Modifier.horizontalScroll(scroll)) {
            Row {
                hours.forEachIndexed { i, h ->
                    // Enfocables para que la ruleta recorra las horas y la tira se desplace sola.
                    FocusTile(onClick = {}, modifier = Modifier.width(COLUMN).height(220.dp).padding(horizontal = 4.dp).then(if (i == 0) Modifier.focusRequester(first) else Modifier)) {
                        HourColumn(h)
                    }
                }
            }
            TemperatureChart(hours, Modifier.width(COLUMN * hours.size).height(190.dp))
        }
    }
}

@Composable
private fun HeaderStat(label: String, value: String) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Cockpit.TextDim)
        Text(value, style = numeric(30, FontWeight.Medium))
    }
}

@Composable
private fun HourColumn(h: HourForecast) {
    Column(Modifier.fillMaxSize().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Text("%02d:00".format(h.time.hour), style = numeric(24, FontWeight.Medium))
        WeatherIcon(wmoCondition(h.code).kind, h.isDay, Modifier.size(52.dp))
        Text(h.precipProb?.let { "$it%" } ?: "--", style = numeric(24, FontWeight.Medium), color = RainBlue)
        WindVane(h.windDir, Modifier.size(30.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${h.windKmh}", style = numeric(24, FontWeight.Medium))
            Text(" km/h", style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

/** Veleta: flecha hacia donde sopla el viento, con un ligero balanceo. */
@Composable
fun WindVane(fromDeg: Int, modifier: Modifier = Modifier) {
    val sway by rememberInfiniteTransition(label = "vane").animateFloat(-6f, 6f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "sway")
    Canvas(modifier.graphicsLayer()) {
        // El viento "de" fromDeg sopla hacia fromDeg + 180.
        rotate(fromDeg + 180f + sway) {
            val w = size.width
            val h = size.height
            val p = Path().apply {
                moveTo(w / 2, 0f); lineTo(w * 0.85f, h * 0.55f); lineTo(w * 0.58f, h * 0.48f)
                lineTo(w * 0.58f, h); lineTo(w * 0.42f, h); lineTo(w * 0.42f, h * 0.48f); lineTo(w * 0.15f, h * 0.55f); close()
            }
            drawPath(p, Brush.verticalGradient(listOf(Color.White, Color(0xFFB8C7DA))))
        }
    }
}

/**
 * Temperatura como línea suave con área en degradado y puntos con su valor; debajo, barras de
 * precipitación (mm) con la probabilidad sombreada detrás. Alineada con las columnas de horas.
 */
@Composable
private fun TemperatureChart(hours: List<HourForecast>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val label = numeric(24, FontWeight.Medium)
    val small = numeric(16)
    Canvas(modifier) {
        val col = size.width / hours.size
        val top = 36.dp.toPx()
        val chartBottom = size.height * 0.62f
        val barTop = chartBottom + 8.dp.toPx()
        val barBottom = size.height - 4.dp.toPx()
        val minT = hours.minOf { it.tempC } - 1
        val maxT = hours.maxOf { it.tempC } + 1
        fun x(i: Int) = col * i + col / 2
        fun y(t: Int) = chartBottom - (t - minT).toFloat() / (maxT - minT).coerceAtLeast(1) * (chartBottom - top)

        // Precipitación: probabilidad sombreada y barra con los mm.
        val maxMm = maxOf(2f, hours.maxOf { it.precipMm })
        hours.forEachIndexed { i, h ->
            val left = col * i + col * 0.2f
            val w = col * 0.6f
            h.precipProb?.let { p ->
                drawRoundRect(RainBlue.copy(alpha = 0.08f + 0.25f * p / 100f), Offset(left, barTop), Size(w, barBottom - barTop), CornerRadius(6f))
            }
            if (h.precipMm > 0f) {
                val bh = (barBottom - barTop) * (h.precipMm / maxMm).coerceIn(0.06f, 1f)
                drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF8FD3FF), RainBlue), startY = barBottom - bh, endY = barBottom), Offset(left, barBottom - bh), Size(w, bh), CornerRadius(6f))
                val t = measurer.measure("%.1f".format(h.precipMm), small)
                drawText(t, Color.White, Offset(x(i) - t.size.width / 2f, barBottom - bh - t.size.height))
            }
        }

        // Curva suave (Catmull-Rom convertida a Bézier) y área bajo ella.
        val pts = hours.indices.map { Offset(x(it), y(hours[it].tempC)) }
        val line = smooth(pts)
        val area = Path().apply { addPath(line); lineTo(pts.last().x, chartBottom); lineTo(pts.first().x, chartBottom); close() }
        drawPath(area, Brush.verticalGradient(listOf(Color(0xFFFFB74D).copy(alpha = 0.45f), Color(0xFFFFB74D).copy(alpha = 0.02f)), startY = top, endY = chartBottom))
        drawPath(line, Brush.horizontalGradient(listOf(Color(0xFFFFD54F), Color(0xFFFF8A65))), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
        pts.forEachIndexed { i, p ->
            drawCircle(Color.White, 5.dp.toPx(), p)
            drawCircle(Color(0xFFFF9E40), 3.dp.toPx(), p)
            val t = measurer.measure("${hours[i].tempC}°", label)
            drawText(t, Color.White, Offset(p.x - t.size.width / 2f, p.y - t.size.height - 6.dp.toPx()))
        }
    }
}

private fun smooth(p: List<Offset>): Path = Path().apply {
    if (p.isEmpty()) return@apply
    moveTo(p[0].x, p[0].y)
    for (i in 0 until p.size - 1) {
        val p0 = p[maxOf(i - 1, 0)]
        val p1 = p[i]
        val p2 = p[i + 1]
        val p3 = p[minOf(i + 2, p.size - 1)]
        cubicTo(
            p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
            p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
            p2.x, p2.y,
        )
    }
}

