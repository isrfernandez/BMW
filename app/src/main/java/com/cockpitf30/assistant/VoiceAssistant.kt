package com.cockpitf30.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import com.cockpitf30.fuel.FuelRepository
import com.cockpitf30.fuel.FuelStations
import com.cockpitf30.nav.RouteTracker
import com.cockpitf30.system.AppSettings
import com.cockpitf30.ui.screens.DEMO_CENTER
import com.cockpitf30.ui.screens.GOOGLE_MAPS
import com.cockpitf30.ui.screens.Screen
import com.cockpitf30.ui.screens.hm
import com.cockpitf30.ui.screens.km
import com.cockpitf30.ui.screens.launch
import com.cockpitf30.ui.screens.openMaps
import com.cockpitf30.ui.screens.openMusic
import com.cockpitf30.ui.screens.place
import com.cockpitf30.vehicle.VehicleRepository
import com.cockpitf30.vehicle.trip.TripComputer
import com.cockpitf30.weather.WeatherRepository
import com.cockpitf30.weather.wmoCondition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.time.LocalTime
import java.util.Locale
import kotlin.math.sqrt

/** En qué está el asistente. */
enum class AssistantPhase {
    /** Desactivado en Ajustes. */
    OFF,
    NO_PERMISSION,
    /** Falta el modelo de voz y no se está descargando (p. ej. falló la descarga). */
    NO_MODEL,
    DOWNLOADING,
    LOADING,
    /** Escuchando en segundo plano a la espera de «Hola BMW». */
    WAITING,
    /** Tras «Hola BMW»: escuchando la orden. */
    LISTENING,
    /** Respondiendo (en voz o en pantalla). */
    ANSWERING,
    /** La app no está en pantalla: micrófono liberado. */
    PAUSED,
}

data class AssistantState(
    val phase: AssistantPhase = AssistantPhase.PAUSED,
    /** Lo último que ha entendido. */
    val heard: String? = null,
    /** Lo último que ha respondido. */
    val reply: String? = null,
    val replyAt: Long = 0,
    /** Volumen del micrófono, 0..1, para la animación. */
    val level: Float = 0f,
    /** Descarga del modelo, 0..1. */
    val progress: Float? = null,
    val error: String? = null,
    /** Quién reconoce la orden en curso: "Vosk" (sin conexión) o "Google". */
    val engine: String? = null,
    /** Último texto reconocido, también mientras espera «Hola BMW» (para Diagnóstico). */
    val lastText: String? = null,
)

/**
 * Asistente de voz del cockpit. Escucha sin conexión con Vosk (modelo pequeño en español) mientras
 * la app está en pantalla; al oír «Hola BMW» atiende la orden (en la misma frase o en la siguiente),
 * la interpreta con [VoiceCommands] y responde en voz alta con el TextToSpeech de la radio.
 *
 * Si no hay modelo (aún se descarga o no hay Internet), al tocar la tarjeta se usa el reconocedor de
 * Google si la radio lo tiene. «Pregunta a Google …» lleva la pregunta a la app de Google.
 */
@SuppressLint("StaticFieldLeak") // Solo guarda el applicationContext.
object VoiceAssistant {
    private const val TAG = "VoiceAssistant"
    private const val RATE = 16_000
    /** Tiempo para decir la orden después de «Hola BMW». */
    private const val LISTEN_MS = 7_000L

    private val _humor = MutableStateFlow(Personality.DEFAULT_HUMOR)
    /** Nivel de humor, 0..100 % (se guarda en Ajustes). */
    val humor: StateFlow<Int> = _humor.asStateFlow()

    fun loadHumor(context: Context) {
        app = context.applicationContext
        _humor.value = settings().assistantHumor
    }

    fun setHumor(context: Context, level: Int) {
        app = context.applicationContext
        val l = level.coerceIn(0, 100)
        settings().assistantHumor = l
        _humor.value = l
    }

    private val _state = MutableStateFlow(AssistantState())
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    /** Lo pone la pantalla principal: abre una pantalla de la app. */
    var navigator: ((Screen) -> Unit)? = null
    /** Lo pone la actividad: pide el permiso de micrófono. */
    var permissionRequester: (() -> Unit)? = null

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var audioJob: Job? = null
    private var installJob: Job? = null
    private var model: Model? = null
    private var resumed = false

    @Volatile private var muted = false
    @Volatile private var resetRecognizer = false
    @Volatile private var listenUntil = 0L

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var google: SpeechRecognizer? = null
    private var unmuteJob: Job? = null

    private fun settings() = AppSettings(app)
    private fun hasMic() = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Solo para capturas de pantalla en tests. */
    internal fun preview(s: AssistantState) { _state.value = s }

    /** La app vuelve a estar en pantalla (o ha cambiado un ajuste / el permiso). */
    fun resume(context: Context) {
        app = context.applicationContext
        resumed = true
        _humor.value = settings().assistantHumor
        initTts()
        refresh()
    }

    /** La app sale de pantalla: suelta el micrófono para no molestar a Maps, llamadas, etc. */
    fun pause() {
        resumed = false
        stopAudio()
        stopGoogle()
        stopSpeaking()
        _state.update { it.copy(phase = AssistantPhase.PAUSED, level = 0f) }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        app = context.applicationContext
        settings().assistantEnabled = enabled
        if (!enabled) {
            stopAudio()
            stopGoogle()
        }
        refresh()
    }

    /** Pone el asistente en el estado que toca según ajustes, permiso y modelo. */
    private fun refresh() {
        if (!::app.isInitialized) return
        val phase = _state.value.phase
        when {
            !settings().assistantEnabled -> set(AssistantPhase.OFF)
            !resumed -> set(AssistantPhase.PAUSED)
            !hasMic() -> set(AssistantPhase.NO_PERMISSION)
            !VoiceModel.isReady(app) -> install()
            audioJob?.isActive == true || google != null -> Unit
            phase == AssistantPhase.LOADING -> Unit
            else -> startAudio()
        }
    }

    private fun set(phase: AssistantPhase) = _state.update { it.copy(phase = phase, level = if (phase == AssistantPhase.WAITING || phase == AssistantPhase.LISTENING) it.level else 0f) }

    private fun install() {
        if (installJob?.isActive == true) return
        _state.update { it.copy(phase = AssistantPhase.DOWNLOADING, progress = 0f, error = null) }
        installJob = scope.launch {
            val r = runCatching { VoiceModel.install(app) { p -> _state.update { it.copy(progress = p) } } }
            r.exceptionOrNull()?.let { e ->
                Log.w(TAG, "Descarga del modelo", e)
                _state.update { it.copy(phase = AssistantPhase.NO_MODEL, progress = null, error = e.message ?: e.javaClass.simpleName) }
                return@launch
            }
            _state.update { it.copy(progress = null) }
            refresh()
        }
    }

    // ---------------------------------------------------------------- Vosk (escucha continua)

    private fun startAudio() {
        audioJob?.cancel()
        set(AssistantPhase.LOADING)
        audioJob = scope.launch(Dispatchers.IO) {
            try {
                val m = model ?: run {
                    LibVosk.setLogLevel(LogLevel.WARNINGS)
                    Model(VoiceModel.dir(app).absolutePath).also { model = it }
                }
                listen(m)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Vosk", e)
                _state.update { it.copy(phase = AssistantPhase.NO_MODEL, error = "Micrófono o modelo: ${e.message ?: e.javaClass.simpleName}", level = 0f) }
            }
        }
    }

    private fun stopAudio() {
        audioJob?.cancel()
        audioJob = null
    }

    @SuppressLint("MissingPermission") // Se comprueba en refresh().
    private suspend fun listen(m: Model) {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        fun open(source: Int) = AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE))
            .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        // VOICE_RECOGNITION quita el control automático de ganancia; si la radio no lo tiene, el micro normal.
        val audio = open(MediaRecorder.AudioSource.VOICE_RECOGNITION) ?: open(MediaRecorder.AudioSource.MIC)
            ?: error("no se puede abrir el micrófono")
        // Dos reconocedores: uno restringido a la frase de activación (mucho más fiable que buscarla en
        // texto libre, porque el modelo no conoce «BMW») y otro libre para la orden.
        val wakeRec = Recognizer(m, RATE.toFloat(), VoiceCommands.WAKE_GRAMMAR)
        val cmdRec = Recognizer(m, RATE.toFloat())
        var usingCmd = false
        try {
            audio.startRecording()
            if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) error("el micrófono está ocupado")
            withContext(Dispatchers.Main) { if (_state.value.phase == AssistantPhase.LOADING) set(AssistantPhase.WAITING) }
            val buf = ShortArray(RATE / 10) // 100 ms
            var lastPartial = ""
            var lastLevel = 0L
            while (coroutineScopeActive()) {
                val n = audio.read(buf, 0, buf.size)
                if (n <= 0) { delay(50); continue }
                val now = System.currentTimeMillis()
                if (now - lastLevel > 80) {
                    lastLevel = now
                    val lvl = if (muted) 0f else rms(buf, n)
                    _state.update { it.copy(level = lvl) }
                }
                if (resetRecognizer) { resetRecognizer = false; wakeRec.reset(); cmdRec.reset(); lastPartial = "" }
                if (muted) continue
                val wantCmd = _state.value.phase == AssistantPhase.LISTENING
                if (wantCmd != usingCmd) {
                    usingCmd = wantCmd
                    (if (wantCmd) cmdRec else wakeRec).reset()
                    lastPartial = ""
                }
                val rec = if (usingCmd) cmdRec else wakeRec
                if (rec.acceptWaveForm(buf, n)) {
                    val text = JSONObject(rec.result).optString("text")
                    lastPartial = ""
                    val clean = text.replace("[unk]", "").trim()
                    if (text.isNotBlank()) _state.update { it.copy(lastText = text) }
                    if (clean.isNotBlank()) withContext(Dispatchers.Main) { onFinal(clean) }
                } else {
                    val partial = JSONObject(rec.partialResult).optString("partial")
                    if (partial != lastPartial) {
                        lastPartial = partial
                        if (listenUntil > 0) listenUntil = maxOf(listenUntil, now + 2_500)
                        val clean = partial.replace("[unk]", "").trim()
                        if (clean.isNotBlank()) withContext(Dispatchers.Main) { onPartial(clean) }
                    }
                }
                if (listenUntil in 1 until now) {
                    listenUntil = 0
                    withContext(Dispatchers.Main) { onListenTimeout() }
                }
            }
        } finally {
            runCatching { audio.stop() }
            audio.release()
            wakeRec.close()
            cmdRec.close()
        }
    }

    private suspend fun coroutineScopeActive() = kotlin.coroutines.coroutineContext.isActive

    private fun rms(buf: ShortArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
        // ~3000 de RMS es voz alta cerca del micro.
        return (sqrt(sum / n) / 3000.0).toFloat().coerceIn(0f, 1f)
    }

    private fun onPartial(text: String) {
        when (_state.value.phase) {
            AssistantPhase.WAITING -> {
                val rest = VoiceCommands.afterWakeWord(text) ?: return
                wake()
                if (rest.isNotBlank()) _state.update { it.copy(heard = rest) }
            }
            AssistantPhase.LISTENING -> _state.update { it.copy(heard = VoiceCommands.stripWakeWords(VoiceCommands.afterWakeWord(text) ?: text).ifBlank { null }) }
            else -> Unit
        }
    }

    private fun onFinal(text: String) {
        when (_state.value.phase) {
            AssistantPhase.WAITING -> {
                val rest = VoiceCommands.afterWakeWord(text) ?: return
                if (rest.isBlank()) wake() else execute(rest)
            }
            AssistantPhase.LISTENING -> {
                val rest = VoiceCommands.stripWakeWords(VoiceCommands.afterWakeWord(text) ?: text)
                if (rest.isNotBlank()) execute(rest)
            }
            else -> Unit
        }
    }

    private fun onListenTimeout() {
        if (_state.value.phase != AssistantPhase.LISTENING) return
        _state.update { it.copy(phase = AssistantPhase.WAITING, heard = null, reply = "No te he oído", replyAt = System.currentTimeMillis(), engine = null) }
    }

    /** «Hola BMW»: pitido y a escuchar la orden. */
    private fun wake() {
        listenUntil = System.currentTimeMillis() + LISTEN_MS
        _state.update { it.copy(phase = AssistantPhase.LISTENING, heard = null, reply = null, engine = "Vosk") }
        beep()
    }

    private fun beep() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 150)
            scope.launch { delay(400); tone.release() }
        }
    }

    // ---------------------------------------------------------------- toque en la tarjeta

    /** Tocar la tarjeta: atender una orden ya, sin «Hola BMW». */
    fun tap(context: Context) {
        app = context.applicationContext
        when (_state.value.phase) {
            AssistantPhase.WAITING, AssistantPhase.ANSWERING -> {
                stopSpeaking()
                muted = false
                resetRecognizer = true
                wake()
            }
            AssistantPhase.LISTENING -> {
                // Segundo toque: cancelar.
                listenUntil = 0
                stopGoogle()
                _state.update { it.copy(phase = AssistantPhase.WAITING, heard = null, reply = null) }
                refresh()
            }
            AssistantPhase.NO_PERMISSION -> permissionRequester?.invoke()
            AssistantPhase.OFF -> setEnabled(context, true)
            else -> {
                // Sin modelo todavía: Google si lo hay; y reintentar la descarga si falló.
                if (_state.value.phase == AssistantPhase.NO_MODEL) install()
                if (!hasMic()) permissionRequester?.invoke() else listenWithGoogle()
            }
        }
    }

    private fun listenWithGoogle() {
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            if (!openGoogleAssistant(null)) say("Aún no tengo el modelo de voz y la radio no tiene el reconocedor de Google")
            return
        }
        stopAudio()
        stopGoogle()
        val r = SpeechRecognizer.createSpeechRecognizer(app)
        google = r
        _state.update { it.copy(phase = AssistantPhase.LISTENING, heard = null, reply = null, engine = "Google") }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = beep()
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = _state.update { it.copy(level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                stopGoogle()
                say(if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "No te he oído" else "El reconocedor de Google ha fallado (error $error)")
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                stopGoogle()
                if (text.isBlank()) say("No te he oído") else execute(VoiceCommands.afterWakeWord(text)?.ifBlank { null } ?: text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { t -> _state.update { it.copy(heard = t) } }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName),
        )
    }

    private fun stopGoogle() {
        google?.let { runCatching { it.destroy() } }
        google = null
    }

    // ---------------------------------------------------------------- órdenes y respuestas

    private fun execute(text: String) {
        listenUntil = 0
        val command = VoiceCommands.parse(text)
        Log.i(TAG, "«$text» -> $command")
        _state.update { it.copy(heard = text) }
        say(perform(command))
    }

    private fun open(screen: Screen) = navigator?.invoke(screen)

    private fun humor() = settings().assistantHumor

    /** Respuesta con o sin broma según el nivel de humor. */
    private fun joke(plain: String, vararg quips: String) = Personality.spice(plain, humor(), *quips)

    private fun perform(c: Command): String = when (c) {
        is Command.Open -> {
            open(c.screen)
            when (c.screen) {
                Screen.FUEL -> joke("Te enseño las gasolineras", "Prometo no comentar tu consumo.", "Yo funciono con electricidad, por cierto.")
                Screen.VEHICLE -> joke("Aquí tienes el vehículo", "Sigue teniendo cuatro ruedas. Lo he comprobado.")
                Screen.TRIP -> joke("Datos de viaje", "Sin juzgar.", "Los números no mienten. Yo tampoco, casi nunca.")
                Screen.WEATHER -> joke("Este es el tiempo", "Yo no me mojo. Literalmente.")
                Screen.PHONE -> joke("Abro el teléfono", "No escucharé. Mucho.")
                Screen.APPS -> joke("Tus aplicaciones", "Ninguna tan útil como yo.")
                Screen.DIAGNOSTICS -> joke("Abro los ajustes", "Procura no tocar mi nivel de humor.")
                Screen.HOME -> joke("Vuelvo al inicio", "Hogar, dulce hogar.")
            }
        }
        is Command.Info -> info(c.topic)
        is Command.Navigate -> {
            val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(c.destination))).setPackage(GOOGLE_MAPS)
            if (nav.resolveActivity(app.packageManager) != null) launch(app, nav)
            else launch(app, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(c.destination))))
            joke("Vamos a ${c.destination}", "Ningún agujero negro en la ruta.", "Rumbo fijado. Sin maniobras de acoplamiento, espero.")
        }
        Command.Music -> { openMusic(app); joke("Abro la música", "Tus gustos no son asunto mío.") }
        Command.Maps -> { openMaps(app); joke("Abro el mapa", "Sin agujeros de gusano a la vista.") }
        is Command.AskGoogle -> if (openGoogleAssistant(c.query)) joke("Se lo pregunto a Google", "Me duele, pero lo hago.") else "La radio no tiene la app de Google"
        Command.Help -> "Puedo abrir gasolineras, vehículo, viaje, tiempo, teléfono o apps; decirte la autonomía, " +
            "la presión de las ruedas o el tiempo; llevarte a un sitio, o preguntar a Google. " +
            "Y puedes ajustar mi humor: di humor al cincuenta por ciento."
        Command.Cancel -> joke("Vale", "Vuelvo a mi rincón.", "Me quedo aquí, vigilando.")
        is Command.Humor -> {
            val now = humor()
            if (c.set == null && c.delta == 0) {
                "Mi nivel de humor está al $now por ciento"
            } else {
                val level = (c.set ?: (now + c.delta)).coerceIn(0, 100)
                setHumor(app, level)
                Personality.humorReply(level)
            }
        }
        Command.Honesty -> "Sinceridad al noventa por ciento. El diez restante es por tu bien."
        is Command.Unknown -> joke(
            "No te he entendido. Prueba con: qué tiempo hace, o pregunta a Google y tu pregunta.",
            "=No te he entendido. Y mira que lo he intentado. Prueba con: qué tiempo hace.",
            "=Eso no está en mi programación. Prueba con: qué tiempo hace, o pregunta a Google.",
        )
    }

    private fun info(topic: InfoTopic): String {
        val car = VehicleRepository.state.value
        val es = Locale("es", "ES")
        return when (topic) {
            InfoTopic.RANGE -> {
                val range = car.rangeKm?.let { "Te quedan unos $it kilómetros de autonomía" }
                val level = car.fuelLevel?.let { "el depósito está al ${(it * 100).toInt()} por ciento" }
                when {
                    range != null && level != null -> joke("$range y $level", "Suficiente para llegar. No para salir del sistema solar.")
                    range != null -> joke(range, "Suficiente para llegar. No para salir del sistema solar.")
                    level != null -> level.replaceFirstChar { it.uppercase() }
                    else -> "Todavía no me llega la autonomía del coche"
                }
            }
            InfoTopic.FUEL_NEAR -> {
                open(Screen.FUEL)
                val route = RouteTracker.state.value
                val pos = route.position
                if (pos == null) {
                    "No sé dónde estamos todavía; te abro las gasolineras"
                } else {
                    val list = if (!route.live) FuelRepository.demoStations(DEMO_CENTER) else FuelRepository.state.value.stations
                    val n = FuelStations.nearest(list, pos, limit = 1).firstOrNull()
                    if (n == null) "No tengo gasolineras cerca"
                    else "La más cercana es ${n.station.brand}, a ${FuelStations.formatDistance(n.meters)}, en ${place(n.station)}" +
                        (n.station.diesel?.let { ". El diésel está a ${FuelStations.formatPrice(it)} euros" } ?: "")
                }
            }
            InfoTopic.TYRES -> {
                open(Screen.VEHICLE)
                val t = car.tyres
                val all = listOf(t.frontLeft, t.frontRight, t.rearLeft, t.rearRight)
                if (all.all { it == null }) {
                    "La radio aún no me da la presión de los neumáticos"
                } else {
                    fun b(v: Float?) = v?.let { String.format(es, "%.1f", it) } ?: "sin dato"
                    val ok = all.filterNotNull().all { it in 1.9f..3.2f }
                    "Delanteras ${b(t.frontLeft)} y ${b(t.frontRight)}; traseras ${b(t.rearLeft)} y ${b(t.rearRight)} bares. " +
                        if (ok) joke("Todo correcto", "Ni un pinchazo a la vista.") else "Revisa la presión"
                }
            }
            InfoTopic.DOORS -> {
                val d = car.doors
                val open = listOfNotNull(
                    "la del conductor".takeIf { d.driver == true },
                    "la del acompañante".takeIf { d.passenger == true },
                    "la trasera izquierda".takeIf { d.rearLeft == true },
                    "la trasera derecha".takeIf { d.rearRight == true },
                    "el maletero".takeIf { d.trunk == true },
                    "el capó".takeIf { d.hood == true },
                )
                if (open.isEmpty()) joke("Todo cerrado", "Nadie saldrá despedido por una escotilla.") else { open(Screen.VEHICLE); "Abierto: " + open.joinToString(", ") }
            }
            InfoTopic.TRIP -> {
                open(Screen.TRIP)
                val trips = TripComputer.state.value
                val trip = trips.current ?: trips.last
                if (trip == null) "Aún no hay ningún viaje"
                else "${if (trips.current != null) "Llevas" else "En el último viaje hiciste"} ${km(trip.distanceKm)} kilómetros en ${hm(trip.durationMillis)}"
            }
            InfoTopic.WEATHER -> {
                open(Screen.WEATHER)
                val w = WeatherRepository.state.value
                val f = w.forecast
                if (f == null) "Todavía no tengo el tiempo"
                else {
                    val today = f.days.firstOrNull()
                    val rain = today?.precipProbMax?.takeIf { it >= 30 }?.let { ". Probabilidad de lluvia del $it por ciento" } ?: ""
                    (w.placeName?.let { "En $it, " } ?: "") + "${f.tempC} grados y ${wmoCondition(f.code).text.lowercase(es)}" +
                        (today?.let { ". Hoy entre ${it.minC} y ${it.maxC}" } ?: "") + rain
                }
            }
            InfoTopic.TIME -> LocalTime.now().let {
                joke("Son las ${it.hour}:${"%02d".format(it.minute)}", "Aquí. Cerca de un agujero negro serían otras.", "Hora de la Tierra, claro.")
            }
            InfoTopic.SPEED -> car.speedKmh?.let { joke("Vas a $it kilómetros por hora", "Algo por debajo de la velocidad de la luz.") } ?: "No me llega la velocidad"
        }
    }

    /** Abre la app de Google con la pregunta, o el asistente de Google si no hay pregunta. */
    private fun openGoogleAssistant(query: String?): Boolean {
        val pm = app.packageManager
        val candidates = if (query != null) {
            listOf(
                Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query).setPackage("com.google.android.googlequicksearchbox"),
                Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query),
            )
        } else {
            listOf(Intent(Intent.ACTION_VOICE_COMMAND), Intent("android.intent.action.ASSIST"))
        }
        val intent = candidates.firstOrNull { it.resolveActivity(pm) != null } ?: return false
        launch(app, intent)
        return true
    }

    // ---------------------------------------------------------------- voz

    /** Estado del motor de voz de la radio, para Diagnóstico. */
    enum class TtsStatus(val text: String) {
        STARTING("arrancando"),
        NO_ENGINE("la radio no tiene motor de voz: instala «Servicios de voz de Google» desde Play Store"),
        NO_SPANISH("el motor de voz no tiene el español: descarga la voz española"),
        READY("listo"),
    }

    private val _tts = MutableStateFlow(TtsStatus.STARTING)
    val ttsStatus: StateFlow<TtsStatus> = _tts.asStateFlow()
    /** Motor de voz en uso (paquete) y último error al hablar, para Diagnóstico. */
    @Volatile var ttsEngine: String? = null; private set
    @Volatile var ttsLastError: String? = null; private set

    /** Paquete de «Servicios de voz de Google» (voces naturales sin conexión, también de hombre). */
    const val GOOGLE_TTS = "com.google.android.tts"

    private fun initTts() {
        // Si no estaba listo (p. ej. se acaba de instalar el motor o la voz española), se vuelve a probar.
        if (tts != null && (ttsReady || _tts.value == TtsStatus.STARTING)) return
        tts?.let { runCatching { it.shutdown() } }
        tts = null
        ttsReady = false
        val engines = runCatching { TextToSpeech(app, null).let { t -> t.engines.also { t.shutdown() } } }.getOrNull().orEmpty()
        // Sin ningún motor, TextToSpeech no llama nunca al init o falla: se avisa en Diagnóstico.
        _tts.value = if (engines.isEmpty()) TtsStatus.NO_ENGINE else TtsStatus.STARTING
        if (engines.isEmpty()) Log.w(TAG, "No hay motor de voz")
        // Si está el de Google, ese (voces naturales); si no, el que tenga la radio por defecto.
        val engine = engines.firstOrNull { it.name == GOOGLE_TTS }?.name
        val listener = TextToSpeech.OnInitListener { status ->
            val t = tts ?: return@OnInitListener
            ttsEngine = runCatching { t.defaultEngine }.getOrNull()
            if (status != TextToSpeech.SUCCESS) {
                _tts.value = TtsStatus.NO_ENGINE
                return@OnInitListener
            }
            if (engine != null) ttsEngine = engine
            val r = t.setLanguage(Locale("es", "ES"))
            ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            _tts.value = if (ttsReady) TtsStatus.READY else TtsStatus.NO_SPANISH
            applyVoice()
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { ttsLastError = null }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    ttsLastError = "error $errorCode al hablar"
                    @Suppress("DEPRECATION") onError(utteranceId)
                }
                override fun onDone(utteranceId: String?) {
                    if (utteranceId?.startsWith(ROBOT_ID) == true) scope.launch { playRobot(utteranceId) }
                    else scope.launch { doneSpeaking() }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { scope.launch { if (utteranceId?.startsWith(ROBOT_ID) == true) robotFailed(utteranceId) else doneSpeaking() } }
            })
        }
        tts = if (engine != null) TextToSpeech(app, listener, engine) else TextToSpeech(app, listener)
    }

    private fun isInstalled(pkg: String) = runCatching { app.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    /** Diagnóstico: abre Play Store en «Servicios de voz de Google». */
    fun installVoiceEngine(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$GOOGLE_TTS"))
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$GOOGLE_TTS"))
        launch(context, if (market.resolveActivity(context.packageManager) != null) market else web)
    }

    /** Diagnóstico: pide al motor que descargue la voz española. */
    fun installSpanishVoice(context: Context) {
        val i = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
        ttsEngine?.let { i.setPackage(it) }
        launch(context, if (i.resolveActivity(context.packageManager) != null) i else Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
    }

    /** Diagnóstico: volver a arrancar el motor de voz (tras instalar algo). */
    fun restartTts(context: Context) {
        app = context.applicationContext
        ttsReady = false
        _tts.value = TtsStatus.NO_ENGINE // para que initTts no espere al arranque anterior
        initTts()
    }

    private const val ROBOT_ID = "robot-"
    private var robotSeq = 0
    /** Texto pendiente de cada síntesis a archivo, por si falla y hay que decirlo con la voz normal. */
    private val robotText = HashMap<String, String>()
    private var player: AudioTrack? = null
    @Volatile private var robotBroken = false

    /** Voces en español del motor de la radio (para elegir en Diagnóstico). */
    fun spanishVoices(): List<String> = runCatching {
        tts?.voices.orEmpty()
            .filter { it.locale.language == "es" && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.locale.country != "ES" }, { it.name }))
            .map { it.name }
    }.getOrDefault(emptyList())

    /** Voz actual del motor. */
    fun currentVoice(): String? = runCatching { tts?.voice?.name }.getOrNull()

    /** Elegir voz (null = automática) y aplicar tono y velocidad según el estilo. */
    fun applyVoice(name: String? = settings().assistantVoiceName) {
        val t = tts ?: return
        runCatching {
            val robot = settings().assistantRobotVoice
            val voices = t.voices.orEmpty().filter { it.locale.language == "es" && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            // Sin elegir: una voz masculina local si el motor la marca así (TARS es grave), si no la de siempre.
            val pick = voices.firstOrNull { it.name == name } ?: if (robot) maleVoice(voices) else null
            if (pick != null) t.voice = pick else t.setLanguage(Locale("es", "ES"))
            val st = settings().assistantRobotStrength
            t.setPitch(if (robot) st.pitch else 1f)
            t.setSpeechRate(if (robot) st.rate else 1f)
        }.onFailure { Log.w(TAG, "Voz", it) }
    }

    /**
     * Una voz de hombre en español si el motor la marca así («male» en el nombre o las
     * características) o, en el motor de Google, las que suelen ser masculinas (es-ES «eed»/«eef»,
     * es-US «esd»). Si no, null (la voz por defecto). Se prefieren las que no necesitan Internet.
     */
    private fun maleVoice(voices: List<android.speech.tts.Voice>): android.speech.tts.Voice? {
        val male = Regex("(^|[^e])male")
        val local = voices.sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.locale.country != "ES" }))
        return local.firstOrNull { v -> male.containsMatchIn(v.name.lowercase()) || v.features.orEmpty().any { male.containsMatchIn(it.lowercase()) } }
            ?: listOf("es-es-x-eed", "es-es-x-eef", "es-us-x-esd").firstNotNullOfOrNull { id -> local.firstOrNull { it.name.lowercase().startsWith(id) } }
    }

    /** Diagnóstico: pasar a la siguiente voz en español y probarla. Devuelve su nombre. */
    fun nextVoice(context: Context): String? {
        app = context.applicationContext
        val list = spanishVoices()
        if (list.isEmpty()) return null
        val i = list.indexOf(currentVoice())
        val next = list[(i + 1) % list.size]
        settings().assistantVoiceName = next
        applyVoice(next)
        testVoice(context)
        return next
    }

    /** Diagnóstico: probar la voz con una frase. */
    fun testVoice(context: Context) {
        app = context.applicationContext
        initTts()
        applyVoice()
        say(
            Personality.spice(
                "Hola. Soy el asistente del coche. Humor al ${humor()} por ciento.", humor(),
                "Todo listo para el despegue.", "Prometo no abrir ninguna escotilla.",
            ),
        )
    }

    private fun say(text: String) {
        _state.update { it.copy(phase = AssistantPhase.ANSWERING, reply = text, replyAt = System.currentTimeMillis(), engine = null) }
        unmuteJob?.cancel()
        stopPlayer()
        val t = tts
        if (t != null && ttsReady && settings().assistantSpeaks) {
            // No escuchar mientras habla la radio: se oiría a sí misma.
            muted = true
            if (settings().assistantRobotVoice && settings().assistantRobotStrength.filter && !robotBroken && synthesizeRobot(t, text)) {
                // La síntesis a archivo tarda un poco más; el tiempo real se ajusta al reproducir.
                unmuteJob = scope.launch { delay(6_000L + text.length * 120L); doneSpeaking() }
            } else {
                t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
                unmuteJob = scope.launch { delay(2_000L + text.length * 90L); doneSpeaking() }
            }
        } else {
            if (settings().assistantSpeaks) {
                ttsLastError = "no habla: motor de voz ${_tts.value.text}"
                initTts() // por si ya se ha instalado
            }
            unmuteJob = scope.launch { delay(1_200); doneSpeaking() }
        }
    }

    private fun robotFile(id: String) = java.io.File(app.cacheDir, "$id.wav")

    /** Pide al motor la frase en un WAV, que luego se pasa por [RobotVoice] y se reproduce. */
    private fun synthesizeRobot(t: TextToSpeech, text: String): Boolean {
        robotText.keys.toList().forEach { robotFile(it).delete() }
        robotText.clear()
        val id = ROBOT_ID + (++robotSeq)
        robotText[id] = text
        val r = runCatching { t.synthesizeToFile(text, Bundle(), robotFile(id), id) }.getOrDefault(TextToSpeech.ERROR)
        if (r != TextToSpeech.SUCCESS) { robotText.remove(id); return false }
        return true
    }

    /** No se pudo hacer el WAV: se dice con la voz normal (y no se reintenta hasta reiniciar la app). */
    private fun robotFailed(id: String) {
        val text = robotText.remove(id) ?: return
        robotFile(id).delete()
        Log.w(TAG, "Voz robot no disponible; voz normal")
        robotBroken = true
        if (_state.value.phase != AssistantPhase.ANSWERING) return
        unmuteJob?.cancel()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
        unmuteJob = scope.launch { delay(2_000L + text.length * 90L); doneSpeaking() }
    }

    private suspend fun playRobot(id: String) {
        if (!robotText.containsKey(id)) return // ya se canceló o llegó otra frase
        val file = robotFile(id)
        val pcm = withContext(Dispatchers.Default) { val st = settings().assistantRobotStrength
            RobotVoice.readWav(file)?.let { RobotVoice.process(it, st) } }
        file.delete()
        if (pcm == null || pcm.samples.isEmpty()) { robotFailed(id); return }
        if (robotText.remove(id) == null || _state.value.phase != AssistantPhase.ANSWERING) return
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(pcm.rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build().also { it.write(pcm.samples, 0, pcm.samples.size); it.play() }
        }.onFailure { Log.w(TAG, "AudioTrack", it) }.getOrNull()
        if (track == null) { robotBroken = true; doneSpeaking(); return }
        player = track
        unmuteJob?.cancel()
        unmuteJob = scope.launch {
            delay(pcm.durationMs + 300)
            if (player === track) stopPlayer()
            doneSpeaking()
        }
    }

    private fun stopPlayer() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    private fun stopSpeaking() {
        tts?.stop()
        robotText.clear()
        stopPlayer()
    }

    private fun doneSpeaking() {
        unmuteJob?.cancel()
        muted = false
        resetRecognizer = true
        if (_state.value.phase != AssistantPhase.ANSWERING) return
        when {
            audioJob?.isActive == true -> set(AssistantPhase.WAITING)
            installJob?.isActive == true -> set(AssistantPhase.DOWNLOADING)
            else -> {
                // La orden fue con Google (sin Vosk): volvemos a lo que toque (escuchar «Hola BMW» si ya hay modelo).
                set(AssistantPhase.NO_MODEL)
                refresh()
            }
        }
    }
}
