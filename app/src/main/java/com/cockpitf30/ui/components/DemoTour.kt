package com.cockpitf30.ui.components

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cockpitf30.service.VehicleService
import com.cockpitf30.system.AppSettings
import com.cockpitf30.system.SourceMode
import com.cockpitf30.ui.screens.Screen
import com.cockpitf30.ui.screens.SettingsSection
import com.cockpitf30.ui.theme.DriveMode
import com.cockpitf30.ui.theme.DriveModes
import com.cockpitf30.ui.theme.numeric
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Modo demo (botón en Diagnóstico): recorre solo todas las pantallas, las secciones de Ajustes y
 * los modos de color, con un rótulo abajo que dice qué se está viendo y cuánto falta. Mientras
 * dura, los datos del coche son los de la demo (la ruta de ejemplo con velocidad, rpm y puertas)
 * y al acabar, o al pulsar «Detener» o atrás, todo vuelve a como estaba (el modo y el tema
 * diurno de la demo no se guardan).
 */
object DemoTour {
    /** Un paso del recorrido: qué pantalla, con qué modo y cuánto tiempo. */
    data class Step(
        val screen: Screen,
        val title: String,
        val text: String,
        val seconds: Int = 10,
        val section: SettingsSection? = null,
        val mode: DriveMode = DriveMode.NORMAL,
        val daylight: Boolean = false,
    )

    val steps: List<Step> = listOf(
        Step(Screen.HOME, "Inicio", "Cuadro de mandos con velocímetro, cuentavueltas y el coche en el centro; después, el mapa con la ruta.", 16),
        Step(Screen.HOME, "Inicio · tarjetas", "Debajo: tiempo, radio, viaje, gasolineras y rendimiento. Cada tarjeta abre su pantalla.", 10),
        Step(Screen.TRIP, "Viaje", "Ordenador de a bordo: viaje actual y anteriores, consumo, distancia y tiempo."),
        Step(Screen.FUEL, "Gasolineras", "Precios del Ministerio de las gasolineras cercanas y en la ruta, ordenadas por precio y distancia."),
        Step(Screen.WEATHER, "Tiempo", "Tiempo de aquí y las próximas horas, y el de la ruta."),
        Step(Screen.ROUTE, "Ruta", "Lo que hay por delante: radares, peligros, áreas de descanso y turismo a 10, 25 y 50 km."),
        Step(Screen.RADIO, "Radio", "FM de la radio y emisoras por Internet, con favoritas."),
        Step(Screen.PHONE, "Teléfono", "Agenda y llamadas; también con la voz: «Hola BMW, llama a…»."),
        Step(Screen.MAINTENANCE, "Mantenimiento", "Aceite, frenos, ITV y demás avisos con los kilómetros que faltan."),
        Step(Screen.APPS, "Apps", "Todas las apps de la radio, en grupos y en el orden que quieras."),
        Step(Screen.PERFORMANCE, "Modo Sport · Rendimiento", "Paleta roja, relojes de potencia y par, G-meter y cronómetro.", 14, mode = DriveMode.SPORT),
        Step(Screen.HOME, "Modo Sport · Inicio", "El Inicio con la paleta Sport.", 8, mode = DriveMode.SPORT),
        Step(Screen.HOME, "Modo Night", "Ámbar y más apagado: se pone solo al anochecer, en túneles o con los faros.", 10, mode = DriveMode.NIGHT),
        Step(Screen.PERFORMANCE, "Modo Night · Rendimiento", "Rendimiento con la paleta Night.", 8, mode = DriveMode.NIGHT),
        Step(Screen.HOME, "Modo diurno", "Fondos claros y letra oscura para leer a pleno sol.", 10, daylight = true),
        Step(Screen.TRIP, "Modo diurno · Viaje", "Las pantallas con el tema diurno.", 8, daylight = true),
        *SettingsSection.entries.map { s ->
            Step(Screen.SETTINGS, "Ajustes · ${s.title}", s.hint, 8, section = s)
        }.toTypedArray(),
        Step(Screen.DIAGNOSTICS, "Diagnóstico", "Lo técnico: fuente de datos, tramas de la caja CAN, informe y este modo demo. Fin de la demo.", 8),
    )

    /** Paso actual, o null si no hay demo en marcha. */
    var current by mutableStateOf<Int?>(null)
        private set

    /** Lo que lleva el paso actual (0…1), para la barra del rótulo. */
    var progress by mutableFloatStateOf(0f)
        private set

    val running: Boolean get() = current != null
    val step: Step? get() = current?.let { steps.getOrNull(it) }

    /** Sección de Ajustes que debe enseñarse ahora (null fuera de la demo). */
    val settingsSection: SettingsSection? get() = step?.section

    /** Quién cambia de pantalla (la raíz de la app). */
    var navigator: ((Screen) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var saved: Saved? = null

    private data class Saved(val source: SourceMode)

    fun start(context: Context) {
        if (running) return
        val app = context.applicationContext
        val settings = AppSettings(app)
        saved = Saved(settings.sourceMode)
        // Si la app se cierra a medias, al volver a abrirla se recupera la fuente de datos.
        settings.demoRestoreSource = settings.sourceMode.name
        if (settings.sourceMode != SourceMode.DEMO) {
            settings.sourceMode = SourceMode.DEMO
            VehicleService.restart(app)
        }
        job = scope.launch {
            try {
                for ((i, s) in steps.withIndex()) {
                    current = i
                    DriveModes.demoMode = s.mode
                    DriveModes.demoDaylight = s.daylight
                    navigator?.invoke(s.screen)
                    val total = s.seconds * 1000L
                    var t = 0L
                    while (t < total) {
                        progress = t / total.toFloat()
                        delay(TICK_MS)
                        t += TICK_MS
                    }
                }
            } finally {
                finish(app)
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private fun finish(app: Context) {
        val s = saved
        saved = null
        job = null
        current = null
        progress = 0f
        DriveModes.demoMode = null
        DriveModes.demoDaylight = null
        if (s != null) restoreSource(app, s.source)
        navigator?.invoke(Screen.HOME)
    }

    private fun restoreSource(app: Context, source: SourceMode) {
        val settings = AppSettings(app)
        settings.demoRestoreSource = null
        if (settings.sourceMode != source) {
            settings.sourceMode = source
            VehicleService.restart(app)
        }
    }

    /** Al abrir la app: si una demo quedó a medias (app cerrada), vuelve la fuente de datos de antes. */
    fun recover(context: Context) {
        val settings = AppSettings(context)
        val name = settings.demoRestoreSource ?: return
        settings.demoRestoreSource = null
        val source = runCatching { SourceMode.valueOf(name) }.getOrNull() ?: return
        if (settings.sourceMode != source) settings.sourceMode = source
    }

    private const val TICK_MS = 100L
}

/** Rótulo de la demo, abajo en el centro: qué pantalla es, para qué sirve y cuánto queda. */
@Composable
fun DemoTourBanner(modifier: Modifier = Modifier) {
    val index = DemoTour.current
    val step = DemoTour.step
    AnimatedVisibility(
        visible = step != null,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
    ) {
        // Siempre oscuro, también con el tema diurno: así se lee igual encima de cualquier pantalla.
        val accent = Color(0xFF4FA3FF)
        Column(
            Modifier
                .width(1100.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xE6101318))
                .padding(start = 28.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(
                    "DEMO ${(index ?: 0) + 1}/${DemoTour.steps.size}",
                    style = numeric(22, FontWeight.SemiBold), color = accent,
                )
                Column(Modifier.weight(1f)) {
                    Text(step?.title.orEmpty(), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(step?.text.orEmpty(), color = Color(0xFFC3CAD4), fontSize = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.14f))
                        .clickable { DemoTour.stop() }.padding(horizontal = 22.dp, vertical = 12.dp),
                ) { Text("Detener", color = Color.White, fontSize = 20.sp) }
            }
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth(DemoTour.progress.coerceIn(0f, 1f)).height(4.dp).background(accent))
            }
        }
    }
}
