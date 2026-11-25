package com.cockpitf30.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cockpitf30.system.BtDev
import com.cockpitf30.system.BtSource
import com.cockpitf30.system.BtState
import com.cockpitf30.system.CellState
import com.cockpitf30.system.ConnState
import com.cockpitf30.system.Connectivity
import com.cockpitf30.system.GpsState
import com.cockpitf30.system.WifiNet
import com.cockpitf30.system.WifiState
import com.cockpitf30.ui.components.FocusTile
import com.cockpitf30.ui.components.InitialFocus
import com.cockpitf30.ui.components.PanelTitle
import com.cockpitf30.ui.components.TopBar
import com.cockpitf30.ui.components.TriangleBackground
import com.cockpitf30.ui.components.glassPanel
import com.cockpitf30.ui.theme.Cockpit
import com.cockpitf30.ui.theme.numeric
import java.util.Locale

enum class ConnTab(val label: String, val accent: Color) {
    WIFI("Wi-Fi", Color(0xFF4FA3FF)),
    BLUETOOTH("Bluetooth", Color(0xFF7C8CFF)),
    MOBILE("Datos móviles", Color(0xFF3DDC97)),
    GPS("Ubicación GPS", Color(0xFFFFB238)),
}

/** Color por calidad (0–4 rayas): rojo, ámbar, verde. */
fun levelColor(level: Int): Color = when {
    level >= 3 -> Color(0xFF3DDC97)
    level == 2 -> Color(0xFFFFC94D)
    else -> Color(0xFFFF6B5A)
}

/**
 * Conexiones: Wi-Fi, Bluetooth, datos móviles (SIM 4G) y GPS sin salir de Athoox. A la izquierda,
 * una ficha por conexión con su estado; a la derecha, el detalle de la elegida.
 */
@Composable
fun ConnectionsScreen(initial: ConnTab = ConnTab.WIFI) {
    val context = LocalContext.current
    val st by Connectivity.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(initial) }
    val first = remember { FocusRequester() }
    InitialFocus(first)

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { Connectivity.permissionsChanged(context) }
    DisposableEffect(Unit) {
        Connectivity.start(context)
        onDispose { Connectivity.stop() }
    }
    LaunchedEffect(Unit) {
        val missing = Connectivity.missingPermissions(context)
        if (missing.isNotEmpty()) runCatching { perms.launch(missing.toTypedArray()) }
    }

    ConnectionsContent(st, tab, first) { tab = it }
}

/** El contenido, aparte del estado vivo (para las capturas de prueba). */
@Composable
fun ConnectionsContent(st: ConnState, tab: ConnTab, first: FocusRequester = remember { FocusRequester() }, onTab: (ConnTab) -> Unit) {
    TriangleBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Conexiones") { NetBadge(st) }
            Row(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.width(430.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ConnTab.entries.forEach { t ->
                        TabTile(t, st, t == tab, Modifier.weight(1f).fillMaxWidth().let { if (t == ConnTab.WIFI) it.focusRequester(first) else it }) { onTab(t) }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight().glassPanel(16.dp, tab.accent).padding(horizontal = 24.dp, vertical = 18.dp)) {
                    when (tab) {
                        ConnTab.WIFI -> WifiPanel(st.wifi)
                        ConnTab.BLUETOOTH -> BtPanel(st.bt)
                        ConnTab.MOBILE -> MobilePanel(st.cell, st)
                        ConnTab.GPS -> GpsPanel(st.gps)
                    }
                    st.toast?.let { msg ->
                        Text(
                            msg, style = MaterialTheme.typography.titleMedium, color = Cockpit.OnDark, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)
                                .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(12.dp)).padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NetBadge(st: ConnState) {
    val ok = st.net.internet
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(10.dp).background(if (ok) Cockpit.Ok else Color(0xFFFFC94D), RoundedCornerShape(5.dp)))
        Text(
            if (st.net.via == "Sin conexión") "Sin Internet" else "Internet por ${st.net.via}" + if (ok) "" else " · sin salida",
            style = MaterialTheme.typography.titleMedium, color = if (ok) Cockpit.Text else Color(0xFFFFC94D),
        )
    }
}

// ── Fichas de la izquierda ───────────────────────────────────────────────────

@Composable
private fun TabTile(t: ConnTab, st: ConnState, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val (status, detail, level) = when (t) {
        ConnTab.WIFI -> Triple(
            when { st.wifi.enabled == false -> "Apagado"; st.wifi.ssid != null -> st.wifi.ssid!!; else -> "Sin conectar" },
            st.wifi.rssi?.let { "$it dBm" } ?: if (st.wifi.enabled == true) "${st.wifi.networks.size} redes cerca" else "",
            if (st.wifi.ssid != null) st.wifi.level else -1,
        )
        ConnTab.BLUETOOTH -> Triple(
            when { st.bt.powered == false -> "Apagado"; st.bt.connectedName != null -> st.bt.connectedName!!; st.bt.source == BtSource.NONE -> "No disponible"; else -> "Sin móvil conectado" },
            st.bt.hfp ?: (if (st.bt.paired.isNotEmpty()) "${st.bt.paired.size} emparejados" else ""),
            -1,
        )
        ConnTab.MOBILE -> Triple(
            if (!st.cell.simReady) "SIM: ${st.cell.sim}" else listOfNotNull(st.cell.tech, st.cell.operator).joinToString(" · ").ifEmpty { "Buscando red" },
            (st.cell.rsrp ?: st.cell.dbm)?.let { "$it dBm" } ?: "",
            if (st.cell.simReady) st.cell.level else -1,
        )
        ConnTab.GPS -> Triple(
            when { !st.gps.permission -> "Sin permiso"; !st.gps.enabled -> "Apagado"; st.gps.fixed -> "Posición fija"; else -> "Buscando satélites" },
            if (st.gps.inView > 0) "${st.gps.used} de ${st.gps.inView} satélites" + (st.gps.location?.takeIf { st.gps.fixed && it.hasAccuracy() }?.let { " · ±${it.accuracy.toInt()} m" } ?: "") else "",
            if (st.gps.fixed) gpsLevel(st.gps) else -1,
        )
    }
    FocusTile(onClick = onClick, selected = selected, modifier = modifier) {
        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Canvas(Modifier.size(44.dp)) { glyph(t, if (selected) t.accent else Cockpit.Text) }
            Column(Modifier.weight(1f)) {
                Text(t.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim)
                Text(status, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.labelLarge, color = Cockpit.TextDim, maxLines = 1)
            }
            if (level >= 0) Bars(level, Modifier.size(width = 44.dp, height = 34.dp))
        }
    }
}

private fun gpsLevel(g: GpsState): Int {
    val acc = g.location?.takeIf { it.hasAccuracy() }?.accuracy ?: return 1
    return when { acc <= 8 -> 4; acc <= 15 -> 3; acc <= 40 -> 2; else -> 1 }
}

/** Rayas de cobertura (0–4), coloreadas por calidad. */
@Composable
fun Bars(level: Int, modifier: Modifier, color: Color = levelColor(level)) {
    val track = Cockpit.Track
    Canvas(modifier) {
        val n = 4
        val gap = size.width * 0.08f
        val w = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val h = size.height * (0.3f + 0.7f * (i + 1) / n)
            drawRoundRect(if (i < level) color else track, Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(w * 0.25f))
        }
    }
}

// ── Iconos ───────────────────────────────────────────────────────────────────

private fun DrawScope.glyph(t: ConnTab, c: Color) {
    val w = size.width; val h = size.height
    val s = Stroke(w * 0.08f, cap = StrokeCap.Round)
    when (t) {
        ConnTab.WIFI -> {
            val cx = w / 2; val cy = h * 0.88f
            for (r in listOf(0.82f, 0.56f, 0.30f)) drawArc(c, 225f, 90f, false, Offset(cx - w * r, cy - w * r), Size(w * r * 2, w * r * 2), style = s)
            drawCircle(c, w * 0.07f, Offset(cx, cy - w * 0.02f))
        }
        ConnTab.BLUETOOTH -> {
            val p = Path().apply {
                moveTo(w * 0.28f, h * 0.30f); lineTo(w * 0.70f, h * 0.68f); lineTo(w * 0.50f, h * 0.88f); lineTo(w * 0.50f, h * 0.12f)
                lineTo(w * 0.70f, h * 0.32f); lineTo(w * 0.28f, h * 0.70f)
            }
            drawPath(p, c, style = Stroke(w * 0.08f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
        ConnTab.MOBILE -> {
            // Antena con ondas.
            drawLine(c, Offset(w / 2, h * 0.35f), Offset(w / 2, h * 0.95f), s.width, StrokeCap.Round)
            drawLine(c, Offset(w / 2, h * 0.45f), Offset(w * 0.32f, h * 0.95f), s.width * 0.8f, StrokeCap.Round)
            drawLine(c, Offset(w / 2, h * 0.45f), Offset(w * 0.68f, h * 0.95f), s.width * 0.8f, StrokeCap.Round)
            drawCircle(c, w * 0.07f, Offset(w / 2, h * 0.3f))
            for (r in listOf(0.2f, 0.36f)) {
                drawArc(c, 150f, 60f, false, Offset(w / 2 - w * r, h * 0.3f - w * r), Size(w * r * 2, w * r * 2), style = s)
                drawArc(c, -30f, 60f, false, Offset(w / 2 - w * r, h * 0.3f - w * r), Size(w * r * 2, w * r * 2), style = s)
            }
        }
        ConnTab.GPS -> {
            // Chincheta de ubicación con su punto y la «onda» del suelo.
            val cx = w / 2
            val p = Path().apply {
                moveTo(cx, h * 0.80f)
                cubicTo(w * 0.18f, h * 0.48f, w * 0.20f, h * 0.10f, cx, h * 0.08f)
                cubicTo(w * 0.80f, h * 0.10f, w * 0.82f, h * 0.48f, cx, h * 0.80f)
                close()
            }
            drawPath(p, c, style = Stroke(w * 0.08f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            drawCircle(c, w * 0.10f, Offset(cx, h * 0.34f))
            drawArc(c, 20f, 140f, false, Offset(w * 0.14f, h * 0.74f), Size(w * 0.72f, h * 0.2f), style = Stroke(w * 0.06f, cap = StrokeCap.Round))
        }
    }
}

// ── Piezas comunes ───────────────────────────────────────────────────────────

@Composable
private fun Btn(label: String, accent: Color? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusTile(onClick = onClick, modifier = modifier) {
        Text(label, Modifier.padding(horizontal = 18.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium, color = accent ?: Cockpit.Text, maxLines = 1)
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier, unit: String = "", color: Color = Cockpit.Text) {
    Column(modifier) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = numeric(30, FontWeight.Medium), color = color, maxLines = 1)
            if (unit.isNotEmpty()) Text(" $unit", style = numeric(16), color = Cockpit.TextDim, modifier = Modifier.padding(bottom = 5.dp))
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = Cockpit.TextDim)
}

// ── Wi-Fi ────────────────────────────────────────────────────────────────────

@Composable
private fun WifiPanel(w: WifiState) {
    val ctx = LocalContext.current
    val accent = ConnTab.WIFI.accent
    var asking by remember { mutableStateOf<WifiNet?>(null) }
    var picked by remember { mutableStateOf<WifiNet?>(null) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PanelTitle("Wi-Fi", accent = accent, trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn(if (w.enabled == false) "Encender" else "Apagar", accent) { Connectivity.setWifi(ctx, w.enabled == false) }
                Btn(if (w.scanning) "Buscando…" else "Buscar redes") { Connectivity.scanWifi() }
                Btn("Ajustes Wi-Fi") { Connectivity.openWifiPanel(ctx) }
            }
        })
        if (w.ssid != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Bars(w.level, Modifier.size(width = 64.dp, height = 48.dp))
                Column(Modifier.weight(1f)) {
                    Text("CONECTADO A", style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim)
                    Text(w.ssid, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                w.rssi?.let { Stat("Señal", "$it", unit = "dBm", color = levelColor(w.level)) }
                w.linkMbps?.let { Stat("Velocidad", "$it", unit = "Mb/s") }
                w.freqMhz?.let { Stat("Banda", if (it > 4900) "5" else "2,4", unit = "GHz") }
                w.ip?.let { Stat("IP", it) }
            }
        } else if (w.enabled == false) {
            Text("El Wi-Fi está apagado", style = MaterialTheme.typography.headlineSmall)
            Note("Android 10 no deja a las apps encenderlo: «Encender» abre el interruptor de la radio y vuelves aquí.")
        }
        val pick = picked
        if (pick != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(pick.ssid, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!pick.connected) Btn("Conectar", accent) { picked = null; if (pick.secure) asking = pick else Connectivity.connectWifi(ctx, pick, null) }
                Btn("Olvidar") { picked = null; Connectivity.forgetWifi(ctx, pick) }
                Btn("Cancelar") { picked = null }
            }
        }
        val ask = asking
        if (ask != null) {
            PasswordRow(ask, accent, onCancel = { asking = null }) { pass -> asking = null; Connectivity.connectWifi(ctx, ask, pass) }
        } else if (w.enabled != false) {
            if (w.networks.isEmpty()) {
                Note(if (w.scanning) "Buscando redes…" else "No se ven redes. Comprueba que la ubicación de la radio está activada (Android la pide para buscar Wi-Fi).")
            }
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), contentPadding = PaddingValues(4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(w.networks, key = { it.ssid }) { n ->
                    FocusTile(
                        onClick = { if (n.connected) picked = n else if (n.secure && !n.ours) asking = n else Connectivity.connectWifi(ctx, n, null) },
                        onLongClick = { picked = n },
                        selected = n.connected,
                        modifier = Modifier.height(84.dp),
                    ) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Bars(n.level, Modifier.size(width = 30.dp, height = 24.dp))
                            Column(Modifier.weight(1f)) {
                                Text(n.ssid, style = MaterialTheme.typography.titleMedium, color = if (n.connected) accent else Cockpit.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(
                                        if (n.connected) "Conectada" else if (n.ours) "Guardada en Athoox" else null,
                                        if (n.secure) "🔒 ${n.security}" else "Abierta",
                                        if (n.freqMhz > 4900) "5 GHz" else "2,4 GHz",
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim, maxLines = 1,
                                )
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Note("Pulsa una red para conectar; mantén pulsado para olvidarla. La radio guarda la red y se conecta sola en unos segundos (la primera vez Android pregunta si permite las redes de Athoox).")
                }
            }
        }
    }
}

@Composable
private fun PasswordRow(net: WifiNet, accent: Color, onCancel: () -> Unit, onConnect: (String) -> Unit) {
    var pass by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Contraseña de «${net.ssid}»", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = pass, onValueChange = { pass = it.take(63) }, singleLine = true,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (pass.length >= 8 || net.security == "WEP") onConnect(pass) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent, unfocusedBorderColor = Cockpit.TextDim,
                    focusedTextColor = Cockpit.Text, unfocusedTextColor = Cockpit.Text, cursorColor = accent,
                ),
                modifier = Modifier.width(520.dp).focusRequester(field),
            )
            Btn(if (show) "Ocultar" else "Mostrar") { show = !show }
            Btn("Conectar", accent) { if (pass.length >= 8 || net.security == "WEP") onConnect(pass) }
            Btn("Cancelar", onClick = onCancel)
        }
        Note(if (pass.isNotEmpty() && pass.length < 8 && net.security != "WEP") "Las contraseñas WPA tienen al menos 8 caracteres." else "${net.security} · se guarda solo en esta radio.")
    }
}

// ── Bluetooth ────────────────────────────────────────────────────────────────

@Composable
private fun BtPanel(b: BtState) {
    val ctx = LocalContext.current
    val accent = ConnTab.BLUETOOTH.accent
    var picked by remember { mutableStateOf<BtDev?>(null) }
    val capi = remember { com.cockpitf30.system.Profiles.current(ctx).capi }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PanelTitle(
            when (b.source) { BtSource.RADIO -> "Bluetooth de la radio"; BtSource.ANDROID -> "Bluetooth"; BtSource.NONE -> "Bluetooth" },
            accent = accent,
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (b.source != BtSource.NONE) {
                        Btn(if (b.powered == false) "Encender" else "Apagar", accent) { Connectivity.setBt(ctx, b.powered == false) }
                        Btn(if (b.searching) "Parar búsqueda" else "Buscar dispositivos") { Connectivity.searchBt(ctx) }
                    }
                    Btn("Ajustes Android") { Connectivity.openAndroidBt(ctx) }
                    Btn("App de la radio") { Connectivity.openBtApp(ctx) }
                }
            },
        )
        if (b.source == BtSource.NONE) {
            Text("No llego al Bluetooth de la radio", style = MaterialTheme.typography.headlineSmall)
            Note("Ni el servicio del coche ni Android responden. «Ajustes Android» abre el Bluetooth de Android; «App de la radio», la de serie.")
            return@Column
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f)) {
                Text(if (b.connectedName != null) "MÓVIL CONECTADO" else "ESTADO", style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim)
                Text(
                    b.connectedName ?: if (b.powered == false) "Bluetooth apagado" else "Ningún móvil conectado",
                    style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (b.connectedName != null) accent else Cockpit.Text,
                )
            }
            b.hfp?.let { Stat("Manos libres", it) }
            b.localName?.takeIf { it.isNotBlank() }?.let { Stat("La radio se llama", it) }
            b.pin?.takeIf { it.isNotBlank() }?.let { Stat("PIN", it) }
        }
        val pick = picked
        if (pick != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(pick.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (pick.connected) Btn("Desconectar", accent) { picked = null; Connectivity.disconnectBt(ctx, pick) }
                else Btn("Conectar", accent) { picked = null; Connectivity.connectBt(ctx, pick) }
                Btn("Olvidar") { picked = null; Connectivity.forgetBt(ctx, pick) }
                Btn("Cancelar") { picked = null }
            }
        }
        LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), contentPadding = PaddingValues(4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) { PanelTitle("Emparejados", accent = accent) }
            if (b.paired.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Note(if (b.source == BtSource.RADIO) "La radio aún no ha dado su lista. Si tienes móviles emparejados, aparecen en unos segundos." else "Ninguno todavía.")
            }
            items(b.paired, key = { "p" + (it.addr ?: it.name) }) { d -> DevTile(d, accent) { picked = d } }
            item(span = { GridItemSpan(maxLineSpan) }) { PanelTitle(if (b.searching) "Cerca · buscando…" else "Cerca", accent = accent) }
            if (b.found.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Note(if (b.searching) "Pon el móvil visible: Ajustes › Bluetooth del móvil abierto." else "Pulsa «Buscar dispositivos» para emparejar uno nuevo.")
            }
            items(b.found, key = { "f" + (it.addr ?: it.name) }) { d -> DevTile(d, accent) { Connectivity.connectBt(ctx, d) } }
            if (capi && b.raw.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = 8.dp)) {
                    PanelTitle("Datos del Bluetooth de la radio (solo Zeus)", accent = Cockpit.TextDim)
                    Text(b.raw, style = MaterialTheme.typography.labelSmall, color = Cockpit.TextDim)
                }
            }
        }
    }
}

@Composable
private fun DevTile(d: BtDev, accent: Color, onClick: () -> Unit) {
    FocusTile(onClick = onClick, selected = d.connected, modifier = Modifier.height(84.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Canvas(Modifier.size(28.dp)) { glyph(ConnTab.BLUETOOTH, if (d.connected) accent else Cockpit.TextDim) }
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleMedium, color = if (d.connected) accent else Cockpit.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(if (d.connected) "Conectado" else null, d.addr).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim, maxLines = 1)
            }
        }
    }
}

// ── Datos móviles ────────────────────────────────────────────────────────────

@Composable
private fun MobilePanel(c: CellState, st: ConnState) {
    val ctx = LocalContext.current
    val accent = ConnTab.MOBILE.accent
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PanelTitle("SIM y cobertura", accent = accent, trailing = { Btn("Ajustes de datos") { Connectivity.openMobileData(ctx) } })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Bars(if (c.simReady) c.level else 0, Modifier.size(width = 120.dp, height = 96.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(c.tech ?: if (c.simReady) "—" else "", style = numeric(72, FontWeight.Medium), color = if (c.simReady) levelColor(c.level) else Cockpit.TextDim)
                    Spacer(Modifier.width(16.dp))
                    Text(c.operator ?: if (c.simReady) "Buscando operador" else "SIM: ${c.sim}", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 14.dp), maxLines = 1)
                }
                Text(
                    when {
                        !c.simReady -> "Sin SIM lista: ${c.sim.lowercase(Locale.ROOT)}"
                        c.rsrp != null -> "Cobertura ${Connectivity.rsrpWord(c.rsrp)}" + if (c.roaming) " · en itinerancia" else ""
                        else -> listOf("Sin señal", "Débil", "Regular", "Buena", "Excelente")[c.level.coerceIn(0, 4)] + if (c.roaming) " · en itinerancia" else ""
                    },
                    style = MaterialTheme.typography.titleLarge, color = Cockpit.TextDim,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            c.rsrp?.let { Stat("RSRP (potencia)", "$it", unit = "dBm", color = levelColor(c.level)) } ?: c.dbm?.let { Stat("Señal", "$it", unit = "dBm", color = levelColor(c.level)) }
            c.rsrq?.let { Stat("RSRQ (calidad)", "$it", unit = "dB") }
            c.snr?.let { Stat("SNR (ruido)", "$it", unit = "dB") }
            Stat("Datos móviles", when (c.dataOn) { true -> "Activados"; false -> "Desactivados"; null -> "—" })
            Stat("Internet ahora", if (st.net.via == "Sin conexión") "Sin conexión" else st.net.via.replaceFirstChar { it.uppercase() })
        }
        if (c.history.size > 2) {
            PanelTitle("Señal · últimos ${(c.history.size * 2 + 59) / 60} min", accent = accent)
            SignalChart(c.history, accent, Modifier.fillMaxWidth().height(130.dp))
        }
        Note("Android solo deja a la radio encender o apagar los datos desde sus ajustes; aquí se ve la cobertura de la SIM y por dónde sale Internet. Útil para saber si en un tramo vas a perder la radio por Internet o el mapa.")
    }
}

@Composable
private fun SignalChart(values: List<Int>, accent: Color, modifier: Modifier) {
    val grid = Cockpit.Track
    val dim = Cockpit.TextDim
    Canvas(modifier) {
        val lo = -125f; val hi = -65f
        fun y(v: Int) = size.height * (1f - ((v - lo) / (hi - lo)).coerceIn(0f, 1f))
        for (g in listOf(-110, -100, -85)) drawLine(grid, Offset(0f, y(g)), Offset(size.width, y(g)), 1.dp.toPx())
        val step = size.width / 59f
        val x0 = size.width - (values.size - 1) * step
        val p = Path()
        values.forEachIndexed { i, v -> val x = x0 + i * step; if (i == 0) p.moveTo(x, y(v)) else p.lineTo(x, y(v)) }
        drawPath(p, accent, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(accent, 5.dp.toPx(), Offset(x0 + (values.size - 1) * step, y(values.last())))
        drawLine(dim.copy(alpha = 0.3f), Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
    }
}

// ── GPS ──────────────────────────────────────────────────────────────────────

@Composable
private fun GpsPanel(g: GpsState) {
    val ctx = LocalContext.current
    val accent = ConnTab.GPS.accent
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PanelTitle("Ubicación GPS", accent = accent, trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("Reiniciar GPS", accent) { Connectivity.restartGps(ctx) }
                Btn("Ajustes de ubicación") { Connectivity.openLocation(ctx) }
            }
        })
        when {
            !g.permission -> { Text("Athoox no tiene permiso de ubicación", style = MaterialTheme.typography.headlineSmall); Note("Concédelo al abrir esta pantalla o en Ajustes › Apps › Athoox."); return@Column }
            !g.enabled -> { Text("La ubicación de la radio está apagada", style = MaterialTheme.typography.headlineSmall); Note("Enciéndela en «Ajustes de ubicación»: la usan el mapa, los radares y el tiempo.") }
        }
        val loc = g.location
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1f)) {
                Text(if (g.fixed) "POSICIÓN FIJA" else if (loc != null) "ÚLTIMA POSICIÓN" else "SIN POSICIÓN", style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim)
                Text(
                    if (g.fixed) "${g.used} satélites en uso de ${g.inView}" else if (g.inView > 0) "Buscando… ${g.inView} satélites a la vista" else "Esperando satélites",
                    style = MaterialTheme.typography.headlineMedium, color = if (g.fixed) levelColor(gpsLevel(g)) else Cockpit.Text, maxLines = 1,
                )
                loc?.let { Text(coords(it.latitude, it.longitude) + (g.ageS?.takeIf { it > 3 }?.let { " · hace ${ago(it)}" } ?: ""), style = numeric(22), color = Cockpit.TextDim) }
            }
            loc?.takeIf { it.hasAccuracy() }?.let { Stat("Precisión", "±${it.accuracy.toInt()}", unit = "m", color = levelColor(gpsLevel(g))) }
            loc?.takeIf { it.hasAltitude() }?.let { Stat("Altitud", "${it.altitude.toInt()}", unit = "m") }
            loc?.takeIf { it.hasSpeed() && g.fixed }?.let { Stat("Velocidad", "${(it.speed * 3.6f).toInt()}", unit = "km/h") }
            g.firstFixMs?.let { Stat("Primer fijo", "${it / 1000}", unit = "s") }
        }
        if (g.sats.isNotEmpty()) {
            val byC = g.sats.groupBy { it.constellation }.entries.sortedByDescending { it.value.size }
            PanelTitle("Satélites · " + byC.joinToString("  ·  ") { (k, v) -> "$k ${v.count { it.used }}/${v.size}" }, accent = accent)
            SatChart(g, accent, Modifier.fillMaxWidth().height(150.dp))
            Note("Cada barra es un satélite y su altura la fuerza con que llega (dB-Hz). En color los que se usan para calcular la posición. Con 30 dB-Hz o más se fija bien.")
        }
    }
}

private fun coords(lat: Double, lon: Double): String {
    fun f(v: Double, pos: String, neg: String) = String.format(Locale("es", "ES"), "%.5f° %s", kotlin.math.abs(v), if (v >= 0) pos else neg)
    return f(lat, "N", "S") + "  " + f(lon, "E", "O")
}

private fun ago(s: Long): String = when { s < 60 -> "$s s"; s < 3600 -> "${s / 60} min"; else -> "${s / 3600} h" }

@Composable
private fun SatChart(g: GpsState, accent: Color, modifier: Modifier) {
    val off = Cockpit.TextDim.copy(alpha = 0.35f)
    val grid = Cockpit.Track
    val sats = g.sats.take(40)
    Canvas(modifier) {
        val max = 50f
        drawLine(grid, Offset(0f, size.height * (1 - 30f / max)), Offset(size.width, size.height * (1 - 30f / max)), 1.dp.toPx())
        if (sats.isEmpty()) return@Canvas
        val gap = 4.dp.toPx()
        val w = ((size.width - gap * (sats.size - 1)) / sats.size).coerceAtMost(28.dp.toPx())
        sats.forEachIndexed { i, s ->
            val h = size.height * (s.cn0 / max).coerceIn(0.02f, 1f)
            drawRoundRect(if (s.used) accent else off, Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(w * 0.2f))
        }
    }
}
