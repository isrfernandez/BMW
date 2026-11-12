package com.cockpitf30.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cockpitf30.R
import com.cockpitf30.ui.theme.Cockpit
import com.cockpitf30.ui.theme.DriveModes
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Fondo de triángulos facetados (imagen del usuario) para las pantallas secundarias: la foto a
 * pantalla completa, más oscura arriba (barra de título) y en los bordes para que el cristal y el
 * texto se lean, dejando la malla del centro a la vista.
 */
@Composable
fun TriangleBackground(modifier: Modifier = Modifier, dim: Float = 0.12f, content: @Composable BoxScope.() -> Unit) {
    if (Cockpit.Light) {
        // Diurno: la malla oscura se ve como una mancha al sol. Fondo claro liso con un degradado
        // muy suave y los paneles blancos encima.
        Box(
            modifier.background(Brush.verticalGradient(listOf(Color(0xFFF4F6F9), Cockpit.Background, Color(0xFFDDE3EA)))),
        ) { content() }
        return
    }
    Box(modifier.background(Cockpit.Background)) {
        Image(
            com.cockpitf30.ui.components.cachedPainter(R.drawable.bg_triangles), null,
            Modifier.fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // Modos Sport y Night: la malla toma el color del modo.
                    DriveModes.palette.tint?.let { drawRect(it, alpha = 0.9f, blendMode = BlendMode.Hue) }
                },
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(Color.Black.copy(alpha = dim + DriveModes.palette.dim))
                drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 0.14f to Color.Transparent, 0.85f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.35f)))
                drawRect(
                    Brush.radialGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.30f)),
                        Offset(size.width / 2, size.height * 0.55f), size.maxDimension * 0.62f,
                    ),
                )
            },
        )
        content()
    }
}

/**
 * Panel de cristal ahumado para poner datos sobre el fondo de triángulos: relleno oscuro
 * translúcido, brillo suave arriba, filo de luz en el borde superior y borde biselado con un
 * toque del color [tint].
 */
fun Modifier.glassPanel(corner: Dp = 18.dp, tint: Color = Cockpit.BlueLight, smoke: Float = 0.48f): Modifier =
    if (Cockpit.Light) lightPanel(corner, tint) else drawBehind {
        val r = CornerRadius(corner.toPx())
        drawRoundRect(Color(0xFF050B14).copy(alpha = smoke), cornerRadius = r)
        drawRoundRect(
            Brush.verticalGradient(0f to Color.White.copy(alpha = 0.10f), 0.5f to Color.White.copy(alpha = 0.025f), 1f to tint.copy(alpha = 0.05f)),
            cornerRadius = r,
        )
    }.drawWithContent {
        drawContent()
        val c = corner.toPx()
        val w = size.width
        val h = size.height
        val stroke = 1.2.dp.toPx()
        drawRoundRect(
            Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.42f), 0.3f to Color.White.copy(alpha = 0.10f),
                0.75f to Color.White.copy(alpha = 0.04f), 1f to tint.copy(alpha = 0.45f),
                start = Offset(0f, 0f), end = Offset(w, h),
            ),
            Offset(stroke / 2, stroke / 2), Size(w - stroke, h - stroke), CornerRadius(c), style = Stroke(stroke),
        )
        drawLine(
            Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.45f), Color.Transparent)),
            Offset(c, stroke * 1.6f), Offset(w - c, stroke * 1.6f), 1.dp.toPx(),
        )
    }

/**
 * Panel del tema diurno: tarjeta blanca opaca con sombra corta y borde gris, y una barrita del
 * color del acento arriba. Sin transparencias: al sol es lo que mejor se lee.
 */
private fun Modifier.lightPanel(corner: Dp, tint: Color): Modifier = drawBehind {
    val r = CornerRadius(corner.toPx())
    val off = 2.dp.toPx()
    drawRoundRect(Color(0xFF0B1320).copy(alpha = 0.10f), Offset(0f, off), Size(size.width, size.height), r)
    drawRoundRect(Color.White, cornerRadius = r)
    drawRoundRect(Brush.verticalGradient(0f to tint.copy(alpha = 0.06f), 0.3f to Color.Transparent), cornerRadius = r)
}.drawWithContent {
    drawContent()
    val stroke = 1.2.dp.toPx()
    drawRoundRect(
        Color(0xFF0B1320).copy(alpha = 0.16f), Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke),
        CornerRadius(corner.toPx() - stroke / 2), style = Stroke(stroke),
    )
}

/** Rótulo de panel: barrita de acento en degradado y texto en versalitas espaciadas. */
@Composable
fun PanelTitle(text: String, modifier: Modifier = Modifier, accent: Color = Cockpit.BlueLight, trailing: @Composable () -> Unit = {}) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(4.dp).height(18.dp)
                .background(Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.25f))), androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = Cockpit.TextDim, maxLines = 1)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}
