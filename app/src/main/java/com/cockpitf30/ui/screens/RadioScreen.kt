package com.cockpitf30.ui.screens

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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cockpitf30.radio.FmStation
import com.cockpitf30.radio.Radio
import com.cockpitf30.radio.RadioLogic
import com.cockpitf30.radio.RadioSource
import com.cockpitf30.radio.RadioState
import com.cockpitf30.radio.StreamStation
import com.cockpitf30.ui.components.FocusTile
import com.cockpitf30.ui.components.InitialFocus
import com.cockpitf30.ui.components.PanelTitle
import com.cockpitf30.ui.components.TopBar
import com.cockpitf30.ui.components.TriangleBackground
import com.cockpitf30.ui.components.glassPanel
import com.cockpitf30.ui.theme.Cockpit
import com.cockpitf30.ui.theme.numeric

/** Verde para la antena (0 MB), azul para Internet (gasta datos). */
fun sourceColor(s: RadioSource): Color = if (s == RadioSource.FM) Color(0xFF3DDC97) else Color(0xFF4FA3FF)

/**
 * Radio: a la izquierda lo que suena (con la fuente bien visible), a la derecha las emisoras de la
 * fuente elegida. FM solo si la unidad tiene sintonizador accesible; si no, Internet.
 */
@Composable
fun RadioScreen() {
    val st by Radio.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(st.source) }
    LaunchedEffect(st.source) { tab = st.source }
    LaunchedEffect(tab) { if (tab == RadioSource.INTERNET && st.stations.isEmpty()) Radio.loadStations() }
    val first = remember { FocusRequester() }
    InitialFocus(first)

    TriangleBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Radio") { SourceBadge(st) }
            Row(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                NowPlaying(st, Modifier.width(600.dp).fillMaxHeight(), first)
                Column(Modifier.weight(1f).fillMaxHeight().glassPanel(16.dp).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        SourceTab("FM · antena", RadioSource.FM, tab == RadioSource.FM, enabled = st.tunerAvailable == true) { tab = RadioSource.FM }
                        SourceTab("Internet", RadioSource.INTERNET, tab == RadioSource.INTERNET, enabled = true) { tab = RadioSource.INTERNET }
                        Spacer(Modifier.weight(1f))
                        st.error?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Color(0xFFFFC94D), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(520.dp)) }
                    }
                    val test by Radio.fmTest.collectAsStateWithLifecycle()
                    when {
                        test != null -> FmTestPanel(test!!)
                        tab == RadioSource.FM -> FmList(st)
                        else -> InternetList(st)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceBadge(st: RadioState) {
    val c = sourceColor(st.source)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        st.duckReason?.let { Text("$it · radio bajada", style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim) }
        Box(Modifier.size(10.dp).background(if (st.playing) c else Cockpit.TextDim, RoundedCornerShape(5.dp)))
        Text(
            "Fuente: " + st.source.label + if (st.source == RadioSource.FM) " · 0 MB" else (st.station?.mbPerHour?.let { " · ~$it MB/h" } ?: ""),
            style = MaterialTheme.typography.titleMedium, color = c,
        )
    }
}

@Composable
private fun SourceTab(label: String, source: RadioSource, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FocusTile(onClick = { if (enabled) onClick() else Radio.selectSource(source) }, selected = selected) {
        Text(
            label, Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium,
            color = when { !enabled -> Cockpit.TextDim; selected -> sourceColor(source); else -> Cockpit.Text },
        )
    }
}

@Composable
private fun NowPlaying(st: RadioState, modifier: Modifier, first: FocusRequester) {
    val c = sourceColor(st.source)
    Column(modifier.glassPanel(16.dp, c).padding(horizontal = 28.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PanelTitle(if (st.source == RadioSource.FM) "FM POR ANTENA · SIN DATOS" else "RADIO POR INTERNET", accent = c, trailing = {
            Text(
                when {
                    st.buffering -> "  · conectando…"
                    st.fmSeeking && st.source == RadioSource.FM -> "  · buscando…"
                    st.playing -> "  · sonando"
                    else -> "  · parada"
                },
                style = MaterialTheme.typography.labelLarge, color = Cockpit.TextDim,
            )
        })
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            Column {
                if (st.source == RadioSource.FM) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(RadioLogic.formatFm(st.fmFreq), style = numeric(110, FontWeight.Medium), maxLines = 1)
                        Text(" MHz", style = numeric(30), color = Cockpit.TextDim, modifier = Modifier.padding(bottom = 22.dp))
                    }
                    Text(st.fmRds ?: st.fmFavorites.firstOrNull { it.freq == st.fmFreq }?.name?.ifBlank { null } ?: "FM", style = MaterialTheme.typography.headlineSmall, color = Cockpit.TextDim, maxLines = 1)
                    FmDial(st.fmFreq, c, Modifier.fillMaxWidth().height(44.dp).padding(top = 10.dp))
                } else {
                    Text(st.station?.name ?: "Elige una emisora", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 52.sp, lineHeight = 58.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(st.nowPlaying ?: st.station?.let { quality(it) } ?: "Emisoras de Radio-Browser", style = MaterialTheme.typography.titleMedium, color = Cockpit.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ prevIcon(it) }) { Radio.next(-1) }
            IconButton({ if (st.playing) pauseIcon(it) else playIcon(it) }, big = true, modifier = Modifier.focusRequester(first)) { Radio.togglePlay() }
            IconButton({ nextIcon(it) }) { Radio.next(1) }
            val fav = if (st.source == RadioSource.FM) st.fmFavorites.any { it.freq == st.fmFreq } else st.favorites.any { it.id == st.station?.id }
            FocusTile(onClick = { if (st.source == RadioSource.FM) Radio.toggleFmFavorite() else st.station?.let(Radio::toggleFavorite) }, selected = fav) {
                Text(if (fav) "★ Favorita" else "☆ Guardar", Modifier.padding(horizontal = 18.dp, vertical = 20.dp), style = MaterialTheme.typography.titleMedium, color = if (fav) Color(0xFFFFC94D) else Cockpit.Text)
            }
        }
        VolumeRow(st)
        if (st.source == RadioSource.FM) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallButton("−0.1") { Radio.fmStep(-1) }
                SmallButton("+0.1") { Radio.fmStep(1) }
                SmallButton("◂ Buscar") { Radio.fmSeek(-1) }
                SmallButton("Buscar ▸") { Radio.fmSeek(1) }
                SmallButton("Explorar") { Radio.fmScan() }
                SmallButton("Prueba FM") { Radio.runFmTest() }
            }
        }
    }
}

/** Volumen propio de la radio: aparte del volumen general y del asistente. */
@Composable
private fun VolumeRow(st: RadioState) {
    val c = sourceColor(st.source)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SmallButton("−") { Radio.changeVolume(-5) }
        Column(Modifier.weight(1f)) {
            Row {
                Text("VOLUMEN DE LA RADIO", style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim, modifier = Modifier.weight(1f))
                Text("${st.volume} %", style = numeric(18, FontWeight.Medium))
            }
            Canvas(Modifier.fillMaxWidth().height(10.dp).padding(top = 4.dp)) {
                val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                drawRoundRect(Cockpit.Track, cornerRadius = r)
                drawRoundRect(c, size = androidx.compose.ui.geometry.Size(size.width * st.volume / 100f, size.height), cornerRadius = r)
            }
            if (st.source == RadioSource.FM) {
                Text("En FM el volumen lo lleva la radio del coche", style = MaterialTheme.typography.labelSmall, color = Cockpit.TextDim, maxLines = 1)
            }
        }
        SmallButton("+") { Radio.changeVolume(5) }
    }
}

private fun quality(s: StreamStation): String = listOfNotNull(
    s.codec.takeIf { it.isNotBlank() }, s.bitrate.takeIf { it > 0 }?.let { "$it kbps" }, s.mbPerHour?.let { "~$it MB/h" },
).joinToString(" · ")

/** Resultado de «Prueba FM»: qué se pidió al sintonizador y qué contestó, para compartirlo. */
@Composable
private fun FmTestPanel(test: Radio.FmTest) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (test.running) "Probando el sintonizador…" else "Prueba FM", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (!test.running) {
                SmallButton("Compartir") { shareReport(context, test.lines.joinToString("\n"), "Cockpit F30 prueba FM") }
                SmallButton("Guardar en Descargas") { saveReport(context, test.lines.joinToString("\n"), "cockpit-prueba-fm") }
                SmallButton("Cerrar") { Radio.closeFmTest() }
            }
        }
        test.lines.firstOrNull { it.startsWith("Resultado:") }?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = sourceColor(RadioSource.FM))
        }
        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(test.lines.size) { i ->
                Text(test.lines[i], style = MaterialTheme.typography.labelMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), color = Cockpit.TextDim)
            }
        }
    }
}

@Composable
private fun FmList(st: RadioState) {
    if (st.tunerAvailable != true) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (st.tunerAvailable == null) "Comprobando el sintonizador…" else "Sin sintonizador FM accesible", style = MaterialTheme.typography.headlineSmall)
            Text(st.tunerInfo, style = MaterialTheme.typography.bodyLarge, color = Cockpit.TextDim)
            Text(
                "Esta pantalla usa entonces la radio por Internet. La FM sigue en el iDrive original del coche (botón Vehículo).",
                style = MaterialTheme.typography.bodyLarge, color = Cockpit.TextDim,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallButton("Volver a comprobar") { Radio.recheckTuner() }
                SmallButton("Prueba FM") { Radio.runFmTest() }
                SmallButton("Usar Internet") { Radio.selectSource(RadioSource.INTERNET) }
            }
        }
        return
    }
    val list = (st.fmFavorites + st.fmFound.filter { f -> st.fmFavorites.none { it.freq == f.freq } })
    if (list.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Todavía no hay emisoras", style = MaterialTheme.typography.headlineSmall)
            Text("Pulsa «Explorar» para que la radio recorra la banda, o sintoniza con «Buscar» y guarda las que te gusten.", style = MaterialTheme.typography.bodyLarge, color = Cockpit.TextDim)
            Text(st.tunerInfo, style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim)
        }
        return
    }
    LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxSize(), contentPadding = PaddingValues(4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(list, key = { it.freq }) { f -> FmTile(f, st) }
    }
}

@Composable
private fun FmTile(f: FmStation, st: RadioState) {
    val on = st.source == RadioSource.FM && st.playing && st.fmFreq == f.freq
    val fav = st.fmFavorites.any { it.freq == f.freq }
    FocusTile(onClick = { Radio.playFm(f.freq) }, onLongClick = { Radio.toggleFmFavorite(f.freq) }, selected = on, modifier = Modifier.height(96.dp)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(RadioLogic.formatFm(f.freq), style = numeric(32, FontWeight.Medium), color = if (on) sourceColor(RadioSource.FM) else Cockpit.Text)
                if (fav) Text("  ★", style = MaterialTheme.typography.titleMedium, color = Color(0xFFFFC94D))
            }
            Text(f.name.ifBlank { "FM" }, style = MaterialTheme.typography.labelLarge, color = Cockpit.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun InternetList(st: RadioState) {
    var query by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query, onValueChange = { query = it.take(40) }, singleLine = true,
            placeholder = { Text("Buscar emisora (p. ej. Cadena SER)") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { Radio.loadStations(query) }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Cockpit.BlueLight, unfocusedBorderColor = Cockpit.TextDim,
                focusedTextColor = Cockpit.Text, unfocusedTextColor = Cockpit.Text, cursorColor = Cockpit.BlueLight,
            ),
            modifier = Modifier.width(460.dp),
        )
        SmallButton("Buscar") { Radio.loadStations(query) }
        SmallButton("Populares") { query = ""; Radio.loadStations() }
        if (st.loading) Text("Cargando…", style = MaterialTheme.typography.labelLarge, color = Cockpit.TextDim)
    }
    val list = st.favorites + st.stations.filter { s -> st.favorites.none { it.id == s.id } }
    if (list.isEmpty() && !st.loading) {
        Text("Sin emisoras todavía. Hace falta conexión a Internet para el listado.", style = MaterialTheme.typography.bodyLarge, color = Cockpit.TextDim)
        return
    }
    LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), contentPadding = PaddingValues(4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(list, key = { it.id.ifBlank { it.url } }) { s -> StreamTile(s, st) }
    }
}

@Composable
private fun StreamTile(s: StreamStation, st: RadioState) {
    val on = st.source == RadioSource.INTERNET && st.playing && st.station?.id == s.id
    val fav = st.favorites.any { it.id == s.id }
    FocusTile(onClick = { Radio.playStream(s) }, onLongClick = { Radio.toggleFavorite(s) }, selected = on, modifier = Modifier.height(96.dp)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, style = MaterialTheme.typography.titleMedium, color = if (on) sourceColor(RadioSource.INTERNET) else Cockpit.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (fav) Text("  ★", style = MaterialTheme.typography.titleMedium, color = Color(0xFFFFC94D))
            }
            Text(quality(s).ifBlank { s.tags.take(40) }, style = MaterialTheme.typography.labelMedium, color = Cockpit.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    FocusTile(onClick = onClick) {
        Text(label, Modifier.padding(horizontal = 18.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun IconButton(icon: DrawScope.(Color) -> Unit, big: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusTile(onClick = onClick, modifier = modifier.size(if (big) 96.dp else 76.dp)) { focused ->
        val c = if (focused) Color.White else Cockpit.Text
        Canvas(Modifier.size(if (big) 40.dp else 30.dp).align(Alignment.Center)) { icon(c) }
    }
}

private fun DrawScope.playIcon(c: Color) {
    drawPath(Path().apply { moveTo(size.width * 0.18f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * 0.18f, size.height); close() }, c)
}

private fun DrawScope.pauseIcon(c: Color) {
    val w = size.width * 0.3f
    drawRect(c, Offset(size.width * 0.1f, 0f), androidx.compose.ui.geometry.Size(w, size.height))
    drawRect(c, Offset(size.width * 0.6f, 0f), androidx.compose.ui.geometry.Size(w, size.height))
}

private fun DrawScope.nextIcon(c: Color) {
    val h = size.height
    val w = size.width
    drawPath(Path().apply { moveTo(0f, 0f); lineTo(w * 0.5f, h / 2); lineTo(0f, h); close() }, c)
    drawPath(Path().apply { moveTo(w * 0.42f, 0f); lineTo(w * 0.92f, h / 2); lineTo(w * 0.42f, h); close() }, c)
    drawRect(c, Offset(w * 0.9f, 0f), androidx.compose.ui.geometry.Size(w * 0.1f, h))
}

private fun DrawScope.prevIcon(c: Color) {
    val h = size.height
    val w = size.width
    drawPath(Path().apply { moveTo(w, 0f); lineTo(w * 0.5f, h / 2); lineTo(w, h); close() }, c)
    drawPath(Path().apply { moveTo(w * 0.58f, 0f); lineTo(w * 0.08f, h / 2); lineTo(w * 0.58f, h); close() }, c)
    drawRect(c, Offset(0f, 0f), androidx.compose.ui.geometry.Size(w * 0.1f, h))
}

/** Escala del dial FM (87,5–108) con la aguja en la frecuencia actual. */
@Composable
private fun FmDial(freq: Int, accent: Color, modifier: Modifier) {
    Canvas(modifier) {
        val span = (RadioLogic.FM_MAX - RadioLogic.FM_MIN).toFloat()
        var f = 8800
        while (f <= RadioLogic.FM_MAX) {
            val x = (f - RadioLogic.FM_MIN) / span * size.width
            val major = f % 200 == 0
            drawLine(Cockpit.TextDim.copy(alpha = if (major) 0.8f else 0.4f), Offset(x, if (major) size.height * 0.2f else size.height * 0.5f), Offset(x, size.height), strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx())
            f += 50
        }
        val x = (freq - RadioLogic.FM_MIN) / span * size.width
        drawLine(accent, Offset(x, 0f), Offset(x, size.height), strokeWidth = 4.dp.toPx())
    }
}
