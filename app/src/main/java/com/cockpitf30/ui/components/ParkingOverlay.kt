package com.cockpitf30.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cockpitf30.nav.Parked
import com.cockpitf30.nav.ParkingSpot
import com.cockpitf30.ui.theme.Cockpit
import com.cockpitf30.ui.theme.numeric
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import java.util.Locale

/** Código QR en blanco y negro, un píxel por módulo (se escala sin suavizar). */
fun qrBitmap(text: String): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
    val w = m.width; val h = m.height
    val px = IntArray(w * h) { if (m[it % w, it / w]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Dónde está el coche: QR para escanear con el móvil, y enviar por las apps de la radio. */
@Composable
fun BoxScope.ParkingOverlay() {
    val showing by ParkingSpot.showing.collectAsStateWithLifecycle()
    val spot by ParkingSpot.spot.collectAsStateWithLifecycle()
    AnimatedVisibility(showing, Modifier.matchParentSize(), enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
        BackHandler { ParkingSpot.showing.value = false }
        LaunchedEffect(Unit) { delay(90_000); ParkingSpot.showing.value = false }
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f))
                .pointerInput(Unit) { detectTapGestures { ParkingSpot.showing.value = false } },
            contentAlignment = Alignment.Center,
        ) { ParkingPanel(spot) }
    }
}

@Composable
internal fun ParkingPanel(spot: Parked?) {
    val ctx = LocalContext.current
    val close = remember { FocusRequester() }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { repeat(10) { if (runCatching { close.requestFocus() }.isSuccess) return@LaunchedEffect; delay(50) } }
    val email = remember { ParkingSpot.email(ctx) }
    Column(
        Modifier.width(1100.dp).height(540.dp).glassPanel(28.dp, Cockpit.BlueLight, smoke = 0.92f)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = 40.dp, vertical = 30.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("UBICACIÓN DEL COCHE", style = MaterialTheme.typography.labelLarge, color = Cockpit.TextDim)
                Spacer(Modifier.height(6.dp))
                AthosStripe()
            }
            FocusTile(onClick = { ParkingSpot.showing.value = false }, modifier = Modifier.focusRequester(close)) {
                Text("Cerrar", Modifier.padding(horizontal = 26.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.height(22.dp))
        if (spot == null) {
            Text(
                "Aún no tengo guardado dónde está el coche. Lo guardo solo cuando llevas unos segundos parado y el GPS tiene buena señal.",
                style = MaterialTheme.typography.titleMedium, color = Cockpit.Text,
            )
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            val qr = remember(spot) { qrBitmap(spot.mapsUrl) }
            Image(
                qr, "Código QR con la ubicación",
                Modifier.size(360.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(10.dp),
                filterQuality = FilterQuality.None,
            )
            Column(Modifier.weight(1f)) {
                Text("Escanéalo con la cámara del móvil y se abre en el mapa.", style = MaterialTheme.typography.titleLarge, color = Cockpit.Text)
                Spacer(Modifier.height(18.dp))
                Text("Aparcado el ${spot.day()} a las ${spot.time()}", style = MaterialTheme.typography.titleMedium, color = Cockpit.TextDim)
                Spacer(Modifier.height(6.dp))
                Text(
                    "%.5f, %.5f".format(Locale.US, spot.lat, spot.lon),
                    style = numeric(22), color = Cockpit.TextDim,
                )
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FocusTile(onClick = { if (!ParkingSpot.share(ctx)) note = "No hay ninguna app para compartir en la radio." }) {
                        Text("Compartir", Modifier.padding(horizontal = 26.dp, vertical = 14.dp), style = MaterialTheme.typography.titleMedium)
                    }
                    if (email != null) FocusTile(onClick = { if (!ParkingSpot.sendEmail(ctx)) note = "No hay app de correo en la radio. Usa el QR." }) {
                        Text("Enviar a $email", Modifier.padding(horizontal = 26.dp, vertical = 14.dp), style = MaterialTheme.typography.titleMedium)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    note ?: if (email == null) "Puedes guardar un correo en Ajustes, Conducción." else "Se abre la app de correo con el mensaje ya escrito.",
                    style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim,
                )
            }
        }
    }
}
