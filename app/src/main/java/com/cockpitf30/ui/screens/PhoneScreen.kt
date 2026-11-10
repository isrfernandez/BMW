package com.cockpitf30.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.cockpitf30.system.Contact
import com.cockpitf30.system.Contacts
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cockpitf30.system.VoiceMatch
import kotlinx.coroutines.delay
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.cockpitf30.ui.components.FocusTile
import com.cockpitf30.ui.components.InitialFocus
import com.cockpitf30.ui.components.TopBar
import com.cockpitf30.ui.components.TriangleBackground
import com.cockpitf30.ui.theme.Cockpit
import com.cockpitf30.ui.theme.numeric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val KEYS = listOf(
    "1" to "", "2" to "ABC", "3" to "DEF",
    "4" to "GHI", "5" to "JKL", "6" to "MNO",
    "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
    "*" to "", "0" to "+", "#" to "",
)
private val CallGreen = Color(0xFF1DB954)


/**
 * Marcador propio con letra grande para el coche: número en Saira, teclado de teclas grandes,
 * botón de llamar y contactos del teléfono. Todo manejable con la ruleta.
 */
@Composable
fun PhoneScreen() {
    val context = LocalContext.current
    var number by remember { mutableStateOf("") }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var hasContacts by remember { mutableStateOf(granted(context, Manifest.permission.READ_CONTACTS)) }

    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasContacts = it }
    val callPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { call(context, number) }
    LaunchedEffect(hasContacts) { contacts = loadContacts(context) }

    fun dial() {
        if (number.isBlank()) return
        if (granted(context, Manifest.permission.CALL_PHONE)) call(context, number) else callPermission.launch(Manifest.permission.CALL_PHONE)
    }

    // Llamada por voz propia: lo dicho se busca en los contactos.
    var candidates by remember { mutableStateOf<List<Contact>?>(null) }
    var pending by remember { mutableStateOf<Contact?>(null) }
    var voiceMessage by remember { mutableStateOf<String?>(null) }
    fun handleSpoken(alternatives: List<String>) {
        alternatives.firstNotNullOfOrNull(VoiceMatch::spokenNumber)?.let {
            pending = Contact(it, it)
            return
        }
        val found = alternatives.asSequence().map { VoiceMatch.match(it, contacts) { c -> c.name } }.firstOrNull { it.isNotEmpty() }
        when {
            found == null -> voiceMessage = "No encuentro \"${alternatives.firstOrNull().orEmpty()}\" en tus contactos"
            found.size == 1 -> pending = found.first()
            else -> candidates = found
        }
    }
    val recognizer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
        if (spoken.isNotEmpty()) handleSpoken(spoken)
    }
    fun listen() {
        voiceMessage = null
        if (!hasContacts) {
            contactsPermission.launch(Manifest.permission.READ_CONTACTS)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "¿A quién llamo?")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        runCatching { recognizer.launch(intent) }.onFailure { voiceMessage = "Este dispositivo no tiene reconocimiento de voz" }
    }
    // "Llamando a X" con 3 segundos para cancelar.
    var countdown by remember { mutableIntStateOf(0) }
    LaunchedEffect(pending) {
        val target = pending ?: return@LaunchedEffect
        candidates = null
        for (i in 3 downTo 1) {
            countdown = i
            delay(1_000)
        }
        number = target.number
        pending = null
        dial()
    }
    BackHandler(enabled = pending != null || candidates != null) {
        pending = null
        candidates = null
    }

    val first = remember { FocusRequester() }
    InitialFocus(first)

    pending?.let { CallingOverlay(it, countdown) { pending = null } }
    TriangleBackground(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Teléfono")
        Row(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            // Número y teclado
            Column(Modifier.width(560.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth().height(84.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        number.ifEmpty { "Introduce un número" },
                        style = if (number.isEmpty()) MaterialTheme.typography.headlineSmall.copy(fontSize = 30.sp) else numeric(52, FontWeight.Medium),
                        color = if (number.isEmpty()) Cockpit.TextDim else Cockpit.Text,
                        maxLines = 1, overflow = TextOverflow.Clip,
                        modifier = Modifier.weight(1f),
                    )
                    FocusTile(onClick = { number = number.dropLast(1) }, modifier = Modifier.size(width = 104.dp, height = 80.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Borrar", Modifier.align(Alignment.Center).size(40.dp), tint = Cockpit.Text)
                    }
                }
                KEYS.chunked(3).forEachIndexed { r, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEachIndexed { c, (digit, letters) ->
                            val mod = Modifier.weight(1f).height(100.dp)
                            FocusTile(onClick = { number += digit }, modifier = if (r == 0 && c == 0) mod.focusRequester(first) else mod) {
                                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(digit, style = numeric(50, FontWeight.Medium))
                                    // Siempre se reserva la línea de letras para que todos los dígitos queden alineados.
                                    Text(letters.ifEmpty { " " }, style = MaterialTheme.typography.labelLarge, color = Cockpit.TextDim)
                                }
                            }
                        }
                    }
                }
            }
            // Llamar y contactos
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FocusTile(onClick = ::dial, modifier = Modifier.fillMaxWidth().height(100.dp)) {
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(64.dp)) {
                            Canvas(Modifier.fillMaxSize()) { drawCircle(CallGreen) }
                            Icon(Icons.Filled.Call, null, Modifier.align(Alignment.Center).size(36.dp), tint = Color.White)
                        }
                        Spacer(Modifier.width(20.dp))
                        Text("Llamar", style = MaterialTheme.typography.headlineSmall.copy(fontSize = 36.sp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    VoiceButton("Asistente de Google", Modifier.weight(1f)) {
                        if (!openAssistant(context)) voiceMessage = "No se encontró el Asistente de Google"
                    }
                    VoiceButton("Llamar por voz", Modifier.weight(1f), ::listen)
                }
                voiceMessage?.let { Text(it, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp), color = Cockpit.Alert) }
                Text(
                    if (candidates != null) "¿A QUIÉN LLAMO?" else "CONTACTOS",
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 18.sp), color = Cockpit.BlueLight,
                )
                if (!hasContacts) {
                    FocusTile(onClick = { contactsPermission.launch(Manifest.permission.READ_CONTACTS) }, modifier = Modifier.fillMaxWidth().height(96.dp)) {
                        Text("Permitir acceso a los contactos", Modifier.align(Alignment.Center), style = MaterialTheme.typography.headlineSmall.copy(fontSize = 30.sp))
                    }
                } else if (contacts.isEmpty()) {
                    Text("No hay contactos en este dispositivo", style = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp), color = Cockpit.TextDim)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(candidates ?: contacts, key = { it.name + it.number }) { contact ->
                        FocusTile(
                            onClick = { if (candidates != null) pending = contact else number = contact.number },
                            modifier = Modifier.fillMaxWidth().height(96.dp),
                        ) {
                            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center) {
                                Text(contact.name, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 34.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(contact.number, style = numeric(24), color = Cockpit.TextDim, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** Llama directamente si hay permiso; si no, abre el marcador del sistema con el número puesto. */
private fun call(context: Context, number: String) {
    if (number.isBlank()) return
    val uri = Uri.parse("tel:" + Uri.encode(number))
    val action = if (granted(context, Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
    launch(context, Intent(action, uri))
}

/** Contactos de Android o, si no hay, la agenda Bluetooth de la radio. */
private suspend fun loadContacts(context: Context): List<Contact> = withContext(Dispatchers.IO) { Contacts.load(context) }

/** Botón con micrófono para la voz. */
@Composable
private fun VoiceButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusTile(onClick = onClick, modifier = modifier.height(88.dp)) {
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(width = 26.dp, height = 40.dp)) { mic(Cockpit.BlueLight) }
            Spacer(Modifier.width(16.dp))
            Text(label, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp))
        }
    }
}

/** Micrófono dibujado: cápsula, arco y pie. */
private fun DrawScope.mic(color: Color) {
    val w = size.width
    val h = size.height
    drawRoundRect(color, Offset(w * 0.25f, 0f), Size(w * 0.5f, h * 0.62f), CornerRadius(w * 0.25f))
    drawArc(color, 0f, 180f, false, Offset(w * 0.05f, h * 0.22f), Size(w * 0.9f, h * 0.55f), style = Stroke(w * 0.1f))
    drawLine(color, Offset(w / 2, h * 0.77f), Offset(w / 2, h * 0.95f), w * 0.1f)
    drawLine(color, Offset(w * 0.25f, h * 0.96f), Offset(w * 0.75f, h * 0.96f), w * 0.1f)
}

/** Aviso a pantalla completa "Llamando a X" con cuenta atrás y botón de cancelar. */
@Composable
private fun CallingOverlay(contact: Contact, seconds: Int, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(820.dp).background(Cockpit.SurfaceHigh, RoundedCornerShape(20.dp)).padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Llamando a", style = MaterialTheme.typography.headlineSmall.copy(fontSize = 30.sp), color = Cockpit.TextDim)
            Text(contact.name, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 48.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (contact.name != contact.number) Text(contact.number, style = numeric(30), color = Cockpit.TextDim)
            Spacer(Modifier.height(16.dp))
            Text("$seconds", style = numeric(72, FontWeight.Medium), color = CallGreen)
            Spacer(Modifier.height(16.dp))
            val focus = remember { FocusRequester() }
            InitialFocus(focus)
            FocusTile(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(96.dp).focusRequester(focus)) {
                Text("Cancelar", Modifier.align(Alignment.Center), style = MaterialTheme.typography.headlineSmall.copy(fontSize = 34.sp))
            }
        }
    }
}

/**
 * Abre el Asistente de Google para que el usuario diga "llama a…". Prueba la orden de voz
 * del sistema, luego el asistente predeterminado y por último la app de Google.
 */
private fun openAssistant(context: Context): Boolean {
    val pm = context.packageManager
    val options = listOfNotNull(
        Intent(Intent.ACTION_VOICE_COMMAND),
        Intent(Intent.ACTION_ASSIST),
        pm.getLaunchIntentForPackage("com.google.android.googlequicksearchbox"),
    )
    val intent = options.firstOrNull { it.resolveActivity(pm) != null } ?: return false
    launch(context, intent)
    return true
}
