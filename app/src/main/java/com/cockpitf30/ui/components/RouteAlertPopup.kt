package com.cockpitf30.ui.components

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cockpitf30.nav.AlertCategory
import com.cockpitf30.nav.RouteAlert
import com.cockpitf30.nav.RouteAlerts
import com.cockpitf30.ui.theme.Cockpit
import kotlinx.coroutines.delay

/** Color de cada tipo de aviso. */
fun alertColor(c: AlertCategory): Color = when (c) {
    AlertCategory.RADAR, AlertCategory.SAFETY -> Color(0xFFFFB020)
    AlertCategory.FUEL -> Color(0xFFFF7A45)
    AlertCategory.REST -> Color(0xFF3DDC97)
    AlertCategory.TOURISM -> Color(0xFF7FD3FF)
}

/**
 * Ventanita del último aviso en ruta (arriba a la derecha, sobre cualquier pantalla): foto y
 * resumen de Wikipedia en los sitios turísticos, o el peligro / la gasolinera. Se oculta sola a
 * los 20 s o al tocarla.
 */
@Composable
fun RouteAlertPopup(modifier: Modifier = Modifier) {
    val alert by RouteAlerts.current.collectAsStateWithLifecycle()
    LaunchedEffect(alert) {
        val a = alert ?: return@LaunchedEffect
        delay((a.at + 20_000L - System.currentTimeMillis()).coerceAtLeast(0))
        if (RouteAlerts.current.value === a) RouteAlerts.hide()
    }
    AnimatedVisibility(
        visible = alert != null,
        modifier = modifier,
        enter = fadeIn() + slideInHorizontally { it / 3 },
        exit = fadeOut() + slideOutHorizontally { it / 3 },
    ) {
        // Se queda con el último aviso mientras se va ocultando.
        val last = remember { arrayOfNulls<RouteAlert>(1) }
        alert?.let { last[0] = it }
        last[0]?.let { RouteAlertCard(it, Modifier.clickable { RouteAlerts.hide() }) }
    }
}

@Composable
fun RouteAlertCard(a: RouteAlert, modifier: Modifier = Modifier) {
    val accent = alertColor(a.category)
    val image: ImageBitmap? = remember(a.id, a.image) {
        a.image?.let { b -> runCatching { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull() }
    }
    Column(
        modifier.width(440.dp).glassPanel(16.dp, tint = accent, smoke = 0.82f).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (image != null) {
            Image(
                image, a.title, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(10.dp)),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(accent))
            Text(a.category.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = accent)
        }
        Text(a.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(a.subtitle, style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        a.text?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Cockpit.Text, maxLines = if (image != null) 4 else 6, overflow = TextOverflow.Ellipsis)
        }
    }
}
