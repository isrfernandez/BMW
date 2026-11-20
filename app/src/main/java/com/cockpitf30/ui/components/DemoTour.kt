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
import androidx.compose.runtime.mutableIntStateOf
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
import com.cockpitf30.assistant.VoiceAssistant
import com.cockpitf30.nav.RouteAlerts
import com.cockpitf30.nav.RouteTracker
import com.cockpitf30.service.VehicleService
import com.cockpitf30.system.AppSettings
import com.cockpitf30.system.SourceMode
import com.cockpitf30.ui.screens.Screen
import com.cockpitf30.ui.screens.SettingsSection
import com.cockpitf30.ui.theme.DriveMode
import com.cockpitf30.ui.theme.DriveModes
import com.cockpitf30.ui.theme.numeric
import com.cockpitf30.vehicle.VehicleRepository
import com.cockpitf30.vehicle.source.DemoDrive
import com.cockpitf30.vehicle.telemetry.DragTimes
import com.cockpitf30.vehicle.trip.TripComputer
import com.cockpitf30.weather.Place
import com.cockpitf30.weather.WeatherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Modo demo (botón en Diagnóstico): recorre solo, en unos 3 minutos y medio, todo lo que hace la app
 * y lo pone a prueba, sobre una ruta de ejemplo por Madrid (Atocha–Castellana–Plaza de Castilla, ver
 * DemoRoute) con el tiempo de Madrid: las tres vistas del cuadro, el asistente contestando por voz
 * (sin micrófono) y charlando, la barra lateral reordenándose (sin guardar), las gasolineras y dónde
 * comer por voz, el resumen del viaje, el tiempo con el viento, el viento en ruta en términos
 * náuticos, un aviso turístico en ruta, Turismo con sus
 * pestañas, una salida desde parado cronometrada en Rendimiento, los modos de color y un repaso
 * rápido de cada sección de Ajustes (las filas enseñan el cambio sin guardarlo). El rótulo va
 * arriba (abajo salen los subtítulos del asistente) y dice qué se ve y cuánto falta.
 * Mientras dura, los datos del coche son los de la demo y las marcas del cronómetro no se guardan;
 * al acabar, o al pulsar «Detener» o atrás, todo vuelve a como estaba.
 */
object DemoTour {
    /** Lo que puede hacer un paso mientras se ve: cambiar el rótulo y esperar. */
    class Cue(val app: Context, val scope: CoroutineScope) {
        fun caption(text: String) { DemoTour.caption = text }
    }

    /** Un paso del recorrido: qué pantalla, con qué modo, cuánto tiempo y qué hace mientras. */
    class Step(
        val screen: Screen,
        val title: String,
        val text: String,
        val seconds: Int = 6,
        val section: SettingsSection? = null,
        val mode: DriveMode = DriveMode.NORMAL,
        val daylight: Boolean = false,
        /** Vista del centro del cuadro del Inicio: −1 Athos, 0 coche, 1 mapa. */
        val clusterPage: Int? = null,
        val run: (suspend Cue.() -> Unit)? = null,
    )

    /** Lo que se le «dice» al asistente en la demo (probado en VoiceCommandsTest). */
    const val ASK_RANGE = "¿cuánta autonomía me queda?"
    const val ASK_FUEL = "busca la gasolinera más cercana"
    const val ASK_CHAT = "cuéntame una curiosidad"
    const val ASK_EAT = "¿dónde puedo comer por aquí?"
    const val ASK_WEATHER = "¿qué tiempo hace?"

    /** El tiempo de la demo: el del recorrido de ejemplo, en Madrid. */
    val MADRID = Place("Madrid", "Comunidad de Madrid", "España", 40.4168, -3.7038)

    /** Duración máxima que pidió el usuario para toda la demo. */
    const val MAX_SECONDS = 240

    val steps: List<Step> = listOf(
        Step(Screen.HOME, "Inicio · vista Athoox", "Demo por Madrid: de Atocha por el Prado y la Castellana hasta Plaza de Castilla. El cuadro tiene tres vistas: Athoox, coche y mapa.", 6, clusterPage = -1),
        Step(Screen.HOME, "Asistente Athoox", "«Hola Athoox, ¿cuánta autonomía me queda?»: contesta por voz y el triángulo late con su voz.", 11, clusterPage = -1) {
            delay(400)
            VoiceAssistant.demoAsk(ASK_RANGE)
        },
        Step(Screen.HOME, "Charla con Athoox", "«Cuéntame una curiosidad»: también cuenta chistes, adivinanzas y refranes, y no repite hasta agotarlos.", 10, clusterPage = -1) {
            delay(400)
            VoiceAssistant.demoAsk(ASK_CHAT)
        },
        Step(Screen.HOME, "Inicio · coche", "Barras metálicas de velocidad y vueltas: acero, cobre y rojo según subes. Marcha, consejo de cambio y la ficha de mantenimiento o ITV.", 7, clusterPage = 0),
        Step(Screen.HOME, "Inicio · mapa", "Mapa propio con la ruta por la Castellana, girando con la marcha; se aleja solo al ir más rápido.", 6, clusterPage = 1),
        Step(Screen.HOME, "Inicio · tarjetas", "Tiempo, radio, viaje, gasolineras, rendimiento, turismo… Cada tarjeta abre su pantalla.", 5, clusterPage = 1),
        Step(Screen.HOME, "Barra lateral", "Mantén pulsado un botón y arrástralo (o OK mantenido y ruleta) para cambiar el orden. La demo no lo guarda.", 7, clusterPage = 1) {
            delay(800)
            val id = SidebarMove.order?.invoke()?.lastOrNull() ?: return@Step
            SidebarMove.start?.invoke(id)
            try {
                repeat(2) { delay(900); SidebarMove.step?.invoke(-1) }
                delay(1500)
                repeat(2) { delay(600); SidebarMove.step?.invoke(1) }
                delay(800)
            } finally {
                SidebarMove.cancel?.invoke()
            }
        },
        Step(Screen.HOME, "Gasolineras por voz", "«Hola Athoox, busca la gasolinera más cercana»: abre los precios del Ministerio y te dice la mejor.", 10) {
            delay(300)
            VoiceAssistant.demoAsk(ASK_FUEL)
            // Si el asistente no abre la pantalla (p. ej. sin voz instalada), se abre igual.
            delay(2500)
            navigator?.invoke(Screen.FUEL)
        },
        Step(Screen.HOME, "Comer y dormir", "«¿Dónde puedo comer por aquí?»: busca restaurantes cerca, te dice el mejor y pregunta «¿te llevo?».", 11, clusterPage = 1) {
            delay(400)
            VoiceAssistant.demoAsk(ASK_EAT)
            delay(5000)
            caption("En ruta avisa solo: restaurantes a la hora de comer o cenar, y hoteles o campings de noche.")
        },
        Step(Screen.TRIP, "Viaje", "Ordenador de a bordo: viaje actual y anteriores, consumo, distancia y tiempo.", 8) {
            delay(3000)
            caption("Al parar el motor sale el resumen del trayecto con la nota de eficiencia y la semana.")
            TripComputer.showSummary()
            try { awaitCancellation() } finally { TripComputer.dismissSummary() }
        },
        Step(Screen.WEATHER, "Tiempo y viento", "«¿Qué tiempo hace?»: Madrid ahora, por horas y 7 días, con el viento: de dónde sopla, su fuerza y si va rolando.", 12) {
            delay(400)
            VoiceAssistant.demoAsk(ASK_WEATHER)
            delay(6000)
            caption("El parte al arrancar también dice el viento: «viento de levante, flojo, rolando a poniente por la tarde».")
        },
        Step(Screen.HOME, "Viento en ruta", "Con el rumbo del coche dice cómo entra el viento, como en un barco: de proa, por la amura, de través, por la aleta o de popa.", 10, clusterPage = 1) {
            delay(800)
            RouteAlerts.windTest()
            delay(4500)
            caption("Barlovento es el lado por donde entra el viento; sotavento, el contrario. De través fuerte, avisa de las rachas.")
        },
        Step(Screen.ROUTE, "Ruta", "Lo que hay por delante: radares, peligros, áreas de descanso y turismo a 10, 25 y 50 km.", 8) {
            delay(1500)
            caption("Al pasar cerca de algo que merece la pena (el Prado, Cibeles…), aviso con foto y explicación por voz.")
            RouteAlerts.test(this@DemoTour.scope)
        },
        Step(Screen.TOURISM, "Turismo", "Qué ver en Madrid, ordenado por recomendados, cercanía o popularidad, con foto, horario y web.", 12) {
            val texts = listOf(
                "Para ver: museos, monumentos y parques cerca de la ruta, con su resumen de Wikipedia.",
                "Comer: restaurantes, bares y cafeterías con horario, teléfono y tipo de cocina.",
                "Dormir: hoteles, hostales y campings; con «¿te llevo?» abre la ruta en Google Maps.",
            )
            for (tab in 0..2) {
                tourTab = tab
                caption(texts[tab])
                delay(4000)
            }
        },
        Step(Screen.RADIO, "Radio", "FM e Internet con favoritas. Al arrancar vuelve sola la última emisora; baja cuando hablan Maps o Athoox.", 6),
        Step(Screen.PHONE, "Teléfono", "Agenda y llamadas; también por voz: «Hola Athoox, llama a…».", 5),
        Step(Screen.MAINTENANCE, "Mantenimiento", "Aceite, frenos, ITV y demás avisos con los kilómetros que faltan.", 5),
        Step(Screen.APPS, "Apps", "Todas las apps de la radio, en grupos y en el orden que quieras.", 5),
        Step(Screen.PERFORMANCE, "Rendimiento · modo Sport", "Paleta roja, potencia y par estimados, G-meter, altitud y cronómetro automático.", 27, mode = DriveMode.SPORT) {
            caption("Primero, parado: con 1 s quieto el cronómetro queda armado.")
            DemoDrive.target = 0f
            var waited = 0L
            while ((VehicleRepository.state.value.speedKmh ?: 0) > 0 && waited < 8000) { delay(100); waited += 100 }
            delay(1300)
            caption("Salida a fondo: 0–100 km/h, 60–120 km/h y 0–400 m se cronometran solos.")
            var t = 0f
            while (t <= LAUNCH_SECONDS) {
                DemoDrive.target = launchKmh(t)
                delay(50)
                t += 0.05f
            }
            caption("Frenada fuerte: el G-meter marca la deceleración. Abajo, las marcas recién hechas.")
            DemoDrive.target = 40f
        },
        Step(Screen.HOME, "Modo Sport · Inicio", "El Inicio con la paleta Sport; se elige en Rendimiento o por voz («modo sport»).", 5, mode = DriveMode.SPORT, clusterPage = 0),
        Step(Screen.HOME, "Modo Night", "Ámbar y más apagado: se pone solo al anochecer, en túneles o con los faros.", 5, mode = DriveMode.NIGHT, clusterPage = -1),
        Step(Screen.HOME, "Modo diurno", "Fondos claros y letra oscura para leer a pleno sol.", 5, daylight = true, clusterPage = 1),
        Step(Screen.FUEL, "Modo diurno · Gasolineras", "Todas las pantallas tienen el tema diurno.", 4, daylight = true),
        *SettingsSection.entries.map { s ->
            Step(Screen.SETTINGS, "Ajustes · ${s.title}", s.hint, 5, section = s)
        }.toTypedArray(),
        Step(Screen.DIAGNOSTICS, "Diagnóstico", "Lo técnico: fuente de datos, GPS, voz, avisos y este modo demo. Fin de la demo.", 5),
    )

    /** Duración total en segundos. */
    val totalSeconds: Int get() = steps.sumOf { it.seconds }

    /** Paso actual, o null si no hay demo en marcha. */
    var current by mutableStateOf<Int?>(null)
        private set

    /** Lo que lleva el paso actual (0…1), para la barra del rótulo. */
    var progress by mutableFloatStateOf(0f)
        private set

    /** Segundos que quedan de demo, para el rótulo. */
    var remaining by mutableIntStateOf(0)
        private set

    /** Texto del rótulo cuando el paso lo cambia a medias (null = el del paso). */
    var caption by mutableStateOf<String?>(null)
        private set

    /** Vista del centro del cuadro que pide la demo (null = la que haya). */
    var clusterPage by mutableStateOf<Int?>(null)
        private set

    /** Pestaña de Turismo que pide la demo (0 ver, 1 comer, 2 dormir). */
    var tourTab by mutableStateOf<Int?>(null)
        private set

    /** Fila de Ajustes que está enseñando su cambio (−1 ninguna); la mueve la pantalla de Ajustes. */
    var settingsFlip by mutableIntStateOf(-1)

    val running: Boolean get() = current != null
    val step: Step? get() = current?.let { steps.getOrNull(it) }

    /** Sección de Ajustes que debe enseñarse ahora (null fuera de la demo). */
    val settingsSection: SettingsSection? get() = step?.section

    /** Quién cambia de pantalla (la raíz de la app). */
    var navigator: ((Screen) -> Unit)? = null

    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
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
        DragTimes.demo = true
        RouteTracker.setDemoOverride(true)
        WeatherRepository.demoPlace = MADRID
        scope.launch { runCatching { WeatherRepository.refresh(app) } }
        job = scope.launch {
            try {
                var left = totalSeconds * 1000L
                for ((i, s) in steps.withIndex()) {
                    current = i
                    caption = null
                    DemoDrive.target = null
                    DriveModes.demoMode = s.mode
                    DriveModes.demoDaylight = s.daylight
                    s.clusterPage?.let { clusterPage = it }
                    navigator?.invoke(s.screen)
                    val cue = s.run?.let { r -> launch { Cue(app, this).r() } }
                    val total = s.seconds * 1000L
                    var t = 0L
                    while (t < total) {
                        progress = t / total.toFloat()
                        remaining = ((left - t + 999) / 1000).toInt()
                        delay(TICK_MS)
                        t += TICK_MS
                    }
                    cue?.cancel()
                    left -= total
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
        caption = null
        clusterPage = null
        tourTab = null
        settingsFlip = -1
        DemoDrive.target = null
        DriveModes.demoMode = null
        DriveModes.demoDaylight = null
        TripComputer.dismissSummary()
        RouteAlerts.hide()
        DragTimes.demo = false
        DragTimes.reload()
        RouteTracker.setDemoOverride(false)
        if (WeatherRepository.demoPlace != null) {
            WeatherRepository.demoPlace = null
            scope.launch { runCatching { WeatherRepository.refresh(app) } }
        }
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

    /** Lo que dura la salida a fondo de Rendimiento. */
    const val LAUNCH_SECONDS = 16.6f

    /**
     * Salida a fondo de la demo, como la de un 320d: 0–100 en ~7,2 s y luego más despacio hasta
     * unos 150; así salen las tres marcas (0–100 ~7,2 s, 60–120 ~6,5 s, 0–400 m ~16 s).
     */
    fun launchKmh(t: Float): Float = when {
        t <= 0f -> 0f
        t < 7.2f -> t / 7.2f * 100f
        else -> 100f + (t - 7.2f).coerceAtMost(9.4f) / 9.4f * 50f
    }

    private const val TICK_MS = 100L
}

/** Rótulo de la demo, arriba (abajo van los subtítulos del asistente): qué es, para qué sirve y cuánto queda. */
@Composable
fun DemoTourBanner(modifier: Modifier = Modifier) {
    val index = DemoTour.current
    val step = DemoTour.step
    AnimatedVisibility(
        visible = step != null,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
    ) {
        // Siempre oscuro, también con el tema diurno: así se lee igual encima de cualquier pantalla.
        val accent = Color(0xFF4FA3FF)
        val left = DemoTour.remaining
        Column(
            Modifier
                .width(1080.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xEB101318))
                .padding(start = 24.dp, end = 12.dp, top = 10.dp, bottom = 9.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("DEMO ${(index ?: 0) + 1}/${DemoTour.steps.size}", style = numeric(19, FontWeight.SemiBold), color = accent)
                    Text("%d:%02d".format(left / 60, left % 60), style = numeric(17), color = Color(0xFF8A94A3))
                }
                Column(Modifier.weight(1f)) {
                    Text(step?.title.orEmpty(), color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(DemoTour.caption ?: step?.text.orEmpty(), color = Color(0xFFC3CAD4), fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.14f))
                        .clickable { DemoTour.stop() }.padding(horizontal = 20.dp, vertical = 10.dp),
                ) { Text("Detener", color = Color.White, fontSize = 18.sp) }
            }
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth(DemoTour.progress.coerceIn(0f, 1f)).height(3.dp).background(accent))
            }
        }
    }
}
