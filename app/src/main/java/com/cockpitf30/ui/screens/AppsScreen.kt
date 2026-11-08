package com.cockpitf30.ui.screens

import android.content.ComponentName
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cockpitf30.system.AppLayout
import com.cockpitf30.system.AppSettings
import com.cockpitf30.system.LayoutEntry
import com.cockpitf30.ui.components.FocusTile
import com.cockpitf30.ui.components.TriangleBackground
import com.cockpitf30.ui.components.glassPanel
import com.cockpitf30.ui.components.glassOverlay
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import com.cockpitf30.ui.components.InitialFocus
import com.cockpitf30.ui.components.TopBar
import com.cockpitf30.ui.theme.Cockpit

private typealias AppInfo = com.cockpitf30.system.AppEntry

/** Elemento sobre el que se abrió el menú de pulsación larga; [groupId] si está dentro de un grupo abierto. */
private data class MenuTarget(val id: String, val groupId: String?)

/**
 * Cajón de apps sobre fondo hexagonal. Se ordena A-Z o a mano, y las apps se agrupan en
 * carpetas. Pulsación larga (táctil o OK mantenido en el iDrive) abre el menú: mover, crear
 * grupo, añadir a un grupo, sacar, renombrar o eliminar. En modo mover, la ruleta o las flechas
 * desplazan el elemento; OK lo suelta y Back cancela.
 */
@Composable
fun AppsScreen() {
    val context = LocalContext.current
    val apps by com.cockpitf30.system.AppCatalog.apps.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (apps == null) com.cockpitf30.system.AppCatalog.load(context) }
    val loaded = apps
    if (loaded == null) {
        // Solo pasa si se abre Apps justo al arrancar, antes de que acabe la carga en segundo plano.
        TriangleBackground(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) { TopBar("Apps") {} }
            com.cockpitf30.ui.components.LoadingOverlay(true, "Cargando apps…", graceMs = 0)
        }
    } else {
        AppsContent(loaded)
    }
}

@Composable
private fun AppsContent(apps: Map<String, AppInfo>) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    var layout by remember(apps) { mutableStateOf(AppLayout.merge(settings.appsLayout, apps.keys.toList())) }
    var alphabetical by remember { mutableStateOf(settings.appsAlphabetical) }
    var openGroup by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<MenuTarget?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf<MenuTarget?>(null) }
    val moving by Reorder.moving.collectAsStateWithLifecycle()
    var movingIn by remember { mutableStateOf<String?>(null) }
    var beforeMove by remember { mutableStateOf(layout) }

    fun save(l: List<LayoutEntry>) {
        layout = l
        settings.appsLayout = l
    }
    fun launchApp(a: AppInfo) =
        launch(context, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(a.pkg, a.activity)))
    fun nameOf(e: LayoutEntry) = when (e) {
        is LayoutEntry.App -> apps[e.key]?.label.orEmpty()
        is LayoutEntry.Group -> e.name
    }
    fun startMove(target: MenuTarget) {
        if (alphabetical) {
            alphabetical = false
            settings.appsAlphabetical = false
        }
        beforeMove = layout
        movingIn = target.groupId
        Reorder.moving.value = target.id
    }
    fun step(dir: Int) {
        val id = Reorder.moving.value ?: return
        val gid = movingIn
        layout = if (gid != null) {
            val g = layout.first { it.id == gid } as LayoutEntry.Group
            val i = g.apps.indexOf(id)
            AppLayout.moveInGroup(layout, gid, i, i + dir)
        } else {
            val i = layout.indexOfFirst { it.id == id }
            AppLayout.move(layout, i, i + dir)
        }
    }
    fun finishMove() {
        save(layout)
        Reorder.moving.value = null
    }
    fun cancelMove() {
        layout = beforeMove
        Reorder.moving.value = null
    }
    DisposableEffect(Unit) {
        // Durante la transición entre pantallas conviven dos: cada una solo borra su propio manejador.
        val mine: (Int) -> Unit = ::step
        Reorder.step = mine
        onDispose {
            if (Reorder.step === mine) Reorder.step = null
            if (Reorder.moving.value != null) cancelMove()
        }
    }
    LaunchedEffect(Unit) { Reorder.longPress.collect { if (Reorder.moving.value == null) menu = focused } }
    BackHandler(enabled = moving != null) { cancelMove() }

    val visible = if (alphabetical) layout.sortedBy { nameOf(it).lowercase() } else layout
    val first = remember { FocusRequester() }
    InitialFocus(first)

    TriangleBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Apps") {
                Text("Mantén pulsada una app para ordenar o agrupar", style = MaterialTheme.typography.bodyMedium, color = Cockpit.TextDim)
                Spacer(Modifier.width(16.dp))
                SortChip("A-Z", alphabetical) { alphabetical = true; settings.appsAlphabetical = true }
                SortChip("Personalizado", !alphabetical) { alphabetical = false; settings.appsAlphabetical = false }
                Spacer(Modifier.width(16.dp))
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(180.dp),
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(visible, key = { it.id }) { entry ->
                    val target = MenuTarget(entry.id, null)
                    var mod = Modifier.fillMaxWidth().height(160.dp).onFocusChanged { if (it.isFocused) focused = target }
                    if (entry === visible.first()) mod = mod.focusRequester(first)
                    EntryTile(
                        entry, apps, isMoving = moving == entry.id && movingIn == null, modifier = mod,
                        onStep = ::step,
                        onClick = {
                            when {
                                moving == entry.id -> finishMove()
                                moving != null -> Unit
                                entry is LayoutEntry.App -> apps[entry.key]?.let(::launchApp)
                                entry is LayoutEntry.Group -> openGroup = entry.id
                            }
                        },
                        onLongClick = { if (moving == null) menu = target },
                    )
                }
            }
        }
    }

    // Panel del grupo abierto.
    val group = openGroup?.let { id -> layout.firstOrNull { it.id == id } as? LayoutEntry.Group }
    if (openGroup != null && group == null) openGroup = null
    if (group != null) {
        Dialog(onDismissRequest = { if (moving == null) openGroup = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            TriangleBackground(Modifier.width(1500.dp).heightIn(max = 620.dp).clip(RoundedCornerShape(20.dp)).glassPanel(20.dp).padding(28.dp), dim = 0.5f) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(group.name, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 34.sp), modifier = Modifier.weight(1f))
                        SortChip("Renombrar", false) { renaming = group.id; openGroup = null }
                        Spacer(Modifier.width(12.dp))
                        SortChip("Cerrar", false) { openGroup = null }
                    }
                    Spacer(Modifier.height(16.dp))
                    val inner = remember(group.id) { FocusRequester() }
                    InitialFocus(inner)
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(180.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(group.apps, key = { it }) { key ->
                            val target = MenuTarget(key, group.id)
                            var mod = Modifier.fillMaxWidth().height(160.dp).onFocusChanged { if (it.isFocused) focused = target }
                            if (key == group.apps.first()) mod = mod.focusRequester(inner)
                            EntryTile(
                                LayoutEntry.App(key), apps, isMoving = moving == key && movingIn == group.id, modifier = mod,
                                onStep = ::step,
                                onClick = {
                                    when {
                                        moving == key -> finishMove()
                                        moving != null -> Unit
                                        else -> apps[key]?.let(::launchApp)
                                    }
                                },
                                onLongClick = { if (moving == null) menu = target },
                            )
                        }
                    }
                }
            }
        }
    }

    menu?.let { target ->
        val entry = if (target.groupId != null) LayoutEntry.App(target.id) else layout.firstOrNull { it.id == target.id }
        if (entry == null) {
            menu = null
            return@let
        }
        val groups = layout.filterIsInstance<LayoutEntry.Group>()
        val options = buildList<Pair<String, () -> Unit>> {
            add((if (target.groupId != null) "Mover dentro del grupo" else "Mover") to { startMove(target) })
            when {
                target.groupId != null -> add("Sacar del grupo" to { save(AppLayout.removeFromGroup(layout, target.id)) })
                entry is LayoutEntry.Group -> {
                    add("Renombrar grupo" to { renaming = entry.id })
                    add("Eliminar grupo (las apps quedan sueltas)" to { save(AppLayout.ungroup(layout, entry.id)) })
                }
                else -> {
                    add("Crear grupo" to {
                        val id = "g${System.currentTimeMillis()}"
                        save(AppLayout.newGroup(layout, target.id, id, "Grupo ${groups.size + 1}"))
                        renaming = id
                    })
                    groups.forEach { g -> add("Añadir a \"${g.name}\"" to { save(AppLayout.addToGroup(layout, target.id, g.id)) }) }
                }
            }
            add("Cancelar" to {})
        }
        ActionSheet(nameOf(entry), options) { menu = null }
    }

    renaming?.let { id ->
        val g = layout.firstOrNull { it.id == id } as? LayoutEntry.Group
        if (g == null) {
            renaming = null
            return@let
        }
        RenameOverlay(g.name, onDone = { save(AppLayout.rename(layout, id, it.ifBlank { g.name })); renaming = null }, onCancel = { renaming = null })
    }
}

@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FocusTile(onClick = onClick, selected = selected, modifier = Modifier.height(56.dp)) {
        Text(label, Modifier.align(Alignment.Center).padding(horizontal = 20.dp), style = MaterialTheme.typography.titleMedium)
    }
}

/** Tile de app o de grupo (cuadrícula 2x2 de iconos). En modo mover tiembla y muestra flechas. */
@Composable
private fun EntryTile(
    entry: LayoutEntry, apps: Map<String, AppInfo>, isMoving: Boolean, modifier: Modifier,
    onStep: (Int) -> Unit, onClick: () -> Unit, onLongClick: () -> Unit,
) {
    val wobble = if (isMoving) {
        rememberInfiniteTransition(label = "wobble").animateFloat(-1f, 1f, infiniteRepeatable(tween(140), RepeatMode.Reverse), label = "rot").value
    } else 0f
    FocusTile(
        onClick = onClick, onLongClick = onLongClick, selected = isMoving,
        modifier = modifier.graphicsLayer { rotationZ = wobble; scaleX = if (isMoving) 1.06f else 1f; scaleY = scaleX },
    ) {
        Column(
            Modifier.fillMaxSize()
                // Halo azul detrás del icono y placa de cristal por encima, como las tarjetas del Inicio.
                .drawBehind {
                    drawCircle(
                        Brush.radialGradient(listOf(Cockpit.BlueLight.copy(alpha = 0.22f), Color.Transparent), Offset(size.width / 2, size.height * 0.40f), size.width * 0.42f),
                        size.width * 0.42f, Offset(size.width / 2, size.height * 0.40f),
                    )
                }
                .drawWithContent { drawContent(); glassOverlay(Cockpit.BlueLight, shade = 0.25f) }
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            when (entry) {
                is LayoutEntry.App -> apps[entry.key]?.let { Image(it.icon, null, Modifier.size(72.dp)) }
                is LayoutEntry.Group -> Column(
                    Modifier.background(Cockpit.Glass, RoundedCornerShape(14.dp)).padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    entry.apps.take(4).chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { k -> apps[k]?.let { Image(it.icon, null, Modifier.size(32.dp)) } }
                        }
                    }
                }
            }
            val label = when (entry) {
                is LayoutEntry.App -> apps[entry.key]?.label.orEmpty()
                is LayoutEntry.Group -> "${entry.name} (${entry.apps.size})"
            }
            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (isMoving) {
            Row(Modifier.align(Alignment.Center).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ArrowButton("◀") { onStep(-1) }
                ArrowButton("▶") { onStep(1) }
            }
        }
    }
}

@Composable
private fun ArrowButton(symbol: String, onClick: () -> Unit) {
    FocusTile(onClick = onClick, modifier = Modifier.size(56.dp)) {
        Text(symbol, Modifier.align(Alignment.Center), style = MaterialTheme.typography.headlineSmall)
    }
}

/** Menú de opciones grande, manejable con la ruleta. */
@Composable
private fun ActionSheet(title: String, options: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(720.dp).background(Cockpit.SurfaceHigh, RoundedCornerShape(20.dp)).padding(28.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 32.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val firstOption = remember { FocusRequester() }
            InitialFocus(firstOption)
            options.forEachIndexed { i, (label, action) ->
                val mod = Modifier.fillMaxWidth().height(80.dp)
                FocusTile(onClick = { onDismiss(); action() }, modifier = if (i == 0) mod.focusRequester(firstOption) else mod) {
                    Text(label, Modifier.align(Alignment.CenterStart).padding(horizontal = 24.dp), style = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp))
                }
            }
        }
    }
}

/**
 * Cambio de nombre dibujado dentro de la pantalla, no en un Dialog: un campo de texto dentro de
 * un Dialog hace fallar a Compose al cerrarse con el teclado conectado.
 */
@Composable
private fun RenameOverlay(current: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun close(action: () -> Unit) {
        keyboard?.hide()
        focusManager.clearFocus(force = true)
        action()
    }
    BackHandler { close(onCancel) }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(720.dp).background(Cockpit.SurfaceHigh, RoundedCornerShape(20.dp)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Nombre del grupo", style = MaterialTheme.typography.headlineSmall.copy(fontSize = 30.sp))
            val field = remember { FocusRequester() }
            InitialFocus(field)
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontSize = 30.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { close { onDone(text) } }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Cockpit.BlueLight, unfocusedBorderColor = Cockpit.GlassBorder,
                    focusedTextColor = Cockpit.Text, unfocusedTextColor = Cockpit.Text, cursorColor = Cockpit.BlueLight,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(field),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FocusTile(onClick = { close { onDone(text) } }, selected = true, modifier = Modifier.weight(1f).height(80.dp)) {
                    Text("Guardar", Modifier.align(Alignment.Center), style = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp))
                }
                FocusTile(onClick = { close(onCancel) }, modifier = Modifier.weight(1f).height(80.dp)) {
                    Text("Cancelar", Modifier.align(Alignment.Center), style = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp))
                }
            }
        }
    }
}
