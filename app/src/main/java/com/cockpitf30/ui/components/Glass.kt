package com.cockpitf30.ui.components

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp

/**
 * Capa de cristal que se pinta ENCIMA del contenido de un botón: brillo en la mitad superior,
 * un reflejo diagonal, filo de luz arriba y un borde biselado (claro arriba a la izquierda,
 * del color [tint] abajo a la derecha). Con [shade] oscurece la parte baja para que se lea el rótulo.
 */
fun DrawScope.glassOverlay(tint: Color, corner: Float = 12.dp.toPx(), shade: Float = 0f) {
    val w = size.width
    val h = size.height
    val rr = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, w, h, CornerRadius(corner))) }
    clipPath(rr) {
        if (shade > 0f) {
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = shade)), startY = h * 0.45f, endY = h))
        }
        // Tinte muy leve del color de la función: el cristal no es neutro.
        drawRect(Brush.linearGradient(listOf(tint.copy(alpha = 0.10f), Color.Transparent, tint.copy(alpha = 0.06f)), Offset(0f, 0f), Offset(w, h)))
        // Brillo de la mitad superior, como la curva de un vidrio.
        drawRect(
            Brush.verticalGradient(0f to Color.White.copy(alpha = 0.16f), 0.42f to Color.White.copy(alpha = 0.04f), 0.43f to Color.Transparent, endY = h),
            size = Size(w, h * 0.43f),
        )
        // Reflejo diagonal.
        val sheen = Path().apply {
            moveTo(w * 0.52f, 0f); lineTo(w * 0.70f, 0f); lineTo(w * 0.34f, h); lineTo(w * 0.16f, h); close()
        }
        drawPath(sheen, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.09f), Color.White.copy(alpha = 0.0f)), endY = h))
        // Filo de luz justo debajo del borde superior.
        val inset = 1.5.dp.toPx()
        drawLine(
            Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent)),
            Offset(corner, inset), Offset(w - corner, inset), 1.dp.toPx(),
        )
    }
    // Borde biselado.
    val stroke = 1.3.dp.toPx()
    drawRoundRect(
        Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.60f), 0.35f to Color.White.copy(alpha = 0.10f),
            0.7f to Color.White.copy(alpha = 0.05f), 1f to tint.copy(alpha = 0.55f),
            start = Offset(0f, 0f), end = Offset(w, h),
        ),
        Offset(stroke / 2, stroke / 2), Size(w - stroke, h - stroke), CornerRadius(corner), style = Stroke(stroke),
    )
}
