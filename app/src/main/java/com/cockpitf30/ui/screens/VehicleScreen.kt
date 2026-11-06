package com.cockpitf30.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cockpitf30.R
import com.cockpitf30.ui.components.TopBar
import com.cockpitf30.ui.components.TriangleBackground
import com.cockpitf30.ui.components.glassPanel
import com.cockpitf30.ui.components.PanelTitle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import com.cockpitf30.ui.theme.Cockpit
import com.cockpitf30.ui.theme.numeric
import com.cockpitf30.vehicle.Doors
import com.cockpitf30.vehicle.VehicleRepository
import com.cockpitf30.vehicle.VehicleState

/** Proporciones de la escena car_top_cutout (576x906, morro arriba). */
private const val SCENE_ASPECT = 576f / 906f
private const val BODY_LEFT = 0.255f
private const val BODY_RIGHT = 0.715f
private const val FRONT_AXLE = 0.28f
private const val REAR_AXLE = 0.71f
/** Las capas de puertas abiertas son más anchas que la escena: 48 px de margen a cada lado. */
private const val LAYER_PAD = 48f / 576f

/**
 * Coche visto desde arriba (morro arriba), recortado sobre el fondo de triángulos y centrado en su
 * malla. A los lados, la presión de cada rueda en paneles de cristal unidos a su rueda con una
 * línea de luz; más hacia fuera, autonomía con nivel de depósito, temperatura exterior, puertas y
 * aceite. Lo abierto se muestra abierto y velado en rojo sobre la imagen. Sin datos se muestra "--".
 */
@Composable
fun VehicleScreen() {
    val state by VehicleRepository.state.collectAsStateWithLifecycle()
    val status by VehicleRepository.status.collectAsStateWithLifecycle()

    TriangleBackground(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Vehículo") {
            Text(
                if (status.connected) status.sourceName else "Sin datos · ${status.sourceName}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (status.connected) Cockpit.TextDim else Cockpit.Alert,
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = 16.dp)) {
            val h = maxHeight
            val w = h * SCENE_ASPECT
            val left = (maxWidth - w) / 2
            fun x(f: Float) = left + w * f
            fun y(f: Float) = h * f

            val t = state.tyres
            val chipW = 230.dp
            val chipH = 112.dp
            val gap = 120.dp
            val leftChipX = x(BODY_LEFT) - gap - chipW
            val rightChipX = x(BODY_RIGHT) + gap
            val wheels = listOf(
                Triple(t.frontLeft, Offset(0f, FRONT_AXLE), true),
                Triple(t.rearLeft, Offset(0f, REAR_AXLE), true),
                Triple(t.frontRight, Offset(1f, FRONT_AXLE), false),
                Triple(t.rearRight, Offset(1f, REAR_AXLE), false),
            )

            // Reflejo de suelo bajo el coche, en la malla del fondo.
            Canvas(Modifier.offset(x = left - w * 0.3f).size(w * 1.6f, h)) {
                drawOval(
                    Brush.radialGradient(listOf(Cockpit.BlueLight.copy(alpha = 0.22f), Color.Transparent), Offset(size.width / 2, size.height / 2), size.width / 2),
                    Offset(0f, size.height * 0.08f), Size(size.width, size.height * 0.84f),
                )
            }
            Image(painterResource(R.drawable.car_top_cutout), null, Modifier.offset(x = left).size(w, h))

            // Capó, maletero y puertas abiertos: imágenes veladas en rojo encima del coche.
            Box(Modifier.offset(x = left - w * LAYER_PAD).size(w * (1 + 2 * LAYER_PAD), h)) {
                val d = state.doors
                OpenLayer(d.hood, R.drawable.car_top_hood)
                OpenLayer(d.trunk, R.drawable.car_top_trunk)
                OpenLayer(d.driver, R.drawable.car_top_door_fl)
                OpenLayer(d.passenger, R.drawable.car_top_door_fr)
                OpenLayer(d.rearLeft, R.drawable.car_top_door_rl)
                OpenLayer(d.rearRight, R.drawable.car_top_door_rr)
            }

            // Líneas de luz de cada panel a su rueda, con un punto brillante sobre la rueda.
            Canvas(Modifier.fillMaxSize()) {
                wheels.forEach { (v, pos, isLeft) ->
                    val c = tyreColor(v)
                    val wy = y(pos.y).toPx()
                    val wx = (if (isLeft) x(BODY_LEFT) + 6.dp else x(BODY_RIGHT) - 6.dp).toPx()
                    val cx = (if (isLeft) leftChipX + chipW else rightChipX).toPx()
                    val elbow = (if (isLeft) leftChipX + chipW + 36.dp else rightChipX - 36.dp).toPx()
                    val start = Offset(cx, wy)
                    drawLine(Brush.horizontalGradient(listOf(c.copy(alpha = 0.15f), c.copy(alpha = 0.8f)), startX = cx, endX = wx), start, Offset(wx, wy), 1.5.dp.toPx())
                    drawCircle(c.copy(alpha = 0.6f), 3.dp.toPx(), Offset(elbow, wy))
                    drawCircle(c.copy(alpha = 0.25f), 16.dp.toPx(), Offset(wx, wy))
                    drawCircle(c.copy(alpha = 0.5f), 9.dp.toPx(), Offset(wx, wy), style = Stroke(1.5.dp.toPx()))
                    drawCircle(Color.White, 3.5.dp.toPx(), Offset(wx, wy))
                }
            }

            Tyre("Delantera izq.", t.frontLeft, Modifier.offset(leftChipX, y(FRONT_AXLE) - chipH / 2).size(chipW, chipH), Alignment.End)
            Tyre("Trasera izq.", t.rearLeft, Modifier.offset(leftChipX, y(REAR_AXLE) - chipH / 2).size(chipW, chipH), Alignment.End)
            Tyre("Delantera der.", t.frontRight, Modifier.offset(rightChipX, y(FRONT_AXLE) - chipH / 2).size(chipW, chipH), Alignment.Start)
            Tyre("Trasera der.", t.rearRight, Modifier.offset(rightChipX, y(REAR_AXLE) - chipH / 2).size(chipW, chipH), Alignment.Start)

            // Columnas exteriores: autonomía y exterior a la izquierda, puertas y aceite a la derecha.
            val side = 330.dp
            val outer = 56.dp
            Column(
                Modifier.offset(x = outer).width(side).fillMaxHeight().padding(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                Panel("Autonomía", state.rangeKm?.let { "$it" } ?: "--", "km", Cockpit.BlueLight) {
                    FuelBar(state.fuelLevel)
                }
                Panel("Exterior", state.outsideTempC?.let { "%.0f".format(it) } ?: "--", "°C", Color(0xFF7FD3FF))
            }
            Column(
                Modifier.offset(x = maxWidth - outer - side).width(side).fillMaxHeight().padding(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                Panel("Puertas", doorsText(state), null, doorsColor(state), valueColor = doorsColor(state)) {
                    val open = openItems(state.doors)
                    if (open.isEmpty()) {
                        Text(if (state.doors.driver == null) "Sin datos del CAN" else "Capó y maletero cerrados", style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim)
                    }
                    open.forEach { Text(it, style = MaterialTheme.typography.labelLarge, color = Cockpit.Alert, modifier = Modifier.padding(top = 4.dp)) }
                }
                Panel("Aceite", state.oilTempC?.let { "$it" } ?: "--", "°C", Color(0xFFFFB74D))
            }
        }
    }
    }
}

/** Presión razonable en un 320d: en verde entre 1,9 y 3,2 bar, en rojo fuera de ese margen. */
private fun tyreColor(v: Float?) = when {
    v == null -> Cockpit.TextDim
    v < 1.9f || v > 3.2f -> Cockpit.Alert
    else -> Cockpit.Ok
}

/** Panel de una rueda: rótulo, presión grande y un punto de estado del lado de la rueda. */
@Composable
private fun Tyre(label: String, v: Float?, modifier: Modifier, align: Alignment.Horizontal) {
    val c = tyreColor(v)
    Column(
        modifier.glassPanel(tint = c).padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = align,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (align == Alignment.Start) StatusDot(c)
            Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim, modifier = Modifier.padding(horizontal = 8.dp))
            if (align == Alignment.End) StatusDot(c)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(bar(v), style = numeric(52, FontWeight.Medium), color = Cockpit.Text)
            Text(" bar", style = numeric(20), color = Cockpit.TextDim, modifier = Modifier.padding(bottom = 9.dp))
        }
    }
}

@Composable
private fun StatusDot(c: Color) {
    Canvas(Modifier.size(12.dp)) {
        drawCircle(c.copy(alpha = 0.3f), size.minDimension / 2)
        drawCircle(c, size.minDimension / 4)
    }
}

/** Panel de dato grande con acento de color y contenido extra opcional debajo. */
@Composable
private fun Panel(
    label: String, value: String, unit: String?, accent: Color,
    valueColor: Color = Cockpit.Text,
    extra: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().glassPanel(tint = accent).padding(horizontal = 24.dp, vertical = 18.dp)) {
        PanelTitle(label, accent = accent)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
            Text(value, style = numeric(48, FontWeight.Medium), color = valueColor, maxLines = 1)
            if (unit != null) Text(" $unit", style = numeric(20), color = Cockpit.TextDim, modifier = Modifier.padding(bottom = 8.dp))
        }
        extra()
    }
}

/** Nivel del depósito en una barra segmentada; ámbar por debajo de un cuarto. */
@Composable
private fun FuelBar(level: Float?) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.weight(1f).height(10.dp)) {
            val n = 12
            val gap = 4.dp.toPx()
            val segW = (size.width - gap * (n - 1)) / n
            val lit = level?.let { (it.coerceIn(0f, 1f) * n + 0.5f).toInt() } ?: 0
            val low = (level ?: 1f) < 0.25f
            for (i in 0 until n) {
                val on = i < lit
                val col = when {
                    !on -> Color.White.copy(alpha = 0.10f)
                    low -> Color(0xFFFFB300)
                    else -> lerp(Cockpit.Blue, Cockpit.BlueLight, i / (n - 1f))
                }
                drawRoundRect(col, Offset(i * (segW + gap), 0f), Size(segW, size.height), CornerRadius(2.dp.toPx()))
            }
        }
        Text(level?.let { " ${(it * 100).toInt()} %" } ?: " --", style = numeric(18), color = Cockpit.TextDim, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun bar(v: Float?) = v?.let { "%.1f".format(it) } ?: "--"

private fun doorsText(s: VehicleState) = when {
    s.doors.anyOpen -> "Abierto"
    s.doors.driver == null -> "--"
    else -> "Cerradas"
}

private fun doorsColor(s: VehicleState) = when {
    s.doors.anyOpen -> Cockpit.Alert
    s.doors.driver == null -> Cockpit.TextDim
    else -> Cockpit.Ok
}

private fun openItems(d: Doors) = buildList {
    if (d.driver == true) add("PUERTA DEL. IZQ.")
    if (d.passenger == true) add("PUERTA DEL. DER.")
    if (d.rearLeft == true) add("PUERTA TRAS. IZQ.")
    if (d.rearRight == true) add("PUERTA TRAS. DER.")
    if (d.hood == true) add("CAPÓ ABIERTO")
    if (d.trunk == true) add("MALETERO ABIERTO")
}

/** Una pieza abierta (puerta, capó o maletero) que aparece y desaparece con un fundido. */
@Composable
private fun OpenLayer(open: Boolean?, @DrawableRes res: Int) {
    val alpha by animateFloatAsState(if (open == true) 1f else 0f, tween(350), label = "puerta")
    if (alpha > 0f) {
        Image(painterResource(res), null, Modifier.fillMaxSize().alpha(alpha), contentScale = ContentScale.FillBounds)
    }
}
