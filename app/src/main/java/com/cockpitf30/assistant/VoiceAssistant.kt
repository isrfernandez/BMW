package com.cockpitf30.assistant

import com.cockpitf30.vehicle.Maintenance

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
    /** Escuchando sin «Hola BMW» justo después de responder (conversación seguida). */
    val followUp: Boolean = false,
    /** En pausa («pausa»): «sigue» retoma lo que estaba diciendo. */
    val paused: Boolean = false,
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
    /** Tras responder, tiempo para seguir la conversación sin «Hola BMW». */
    private const val FOLLOW_MS = 12_000L
    /** Silencio tras la última palabra para dar la frase por terminada (Vosk corta en cada pausa). */
    private const val END_SILENCE_MS = 1_200L
    /** Tras «pausa», tiempo para decir «sigue» sin «Hola BMW». */
    private const val PAUSE_MS = 30_000L
    /** Lo mismo cuando lo dicho hasta ahora todavía no se entiende (frase a medias). */
    private const val END_SILENCE_UNSURE_MS = 2_500L
    /** Al empezar a hablar no se atiende a interrupciones (aún se oye el final de lo que dijo el conductor). */
    private const val BARGE_GRACE_MS = 1_000L
    /** Tiempo que debe mantenerse en el parcial una orden de interrupción para hacerle caso. */
    private const val BARGE_STABLE_MS = 300L
    /** Tras una pregunta del copiloto, algo más. */
    private const val COPILOT_FOLLOW_MS = 9_000L

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

    /** Memoria de la conversación en curso. */
    private val conversation = Conversation()
    /** Escuchando sin «Hola BMW» tras una respuesta. */
    private var followUp = false
    /** Abrir la escucha sin «Hola BMW» al terminar de hablar (y durante cuánto). */
    private var listenAfterReply = 0L
    /** Por dónde va hablando (carácter de la respuesta, lo dice el motor de voz). */
    @Volatile private var spokenPos = 0
    /** Lo que quedó por decir al interrumpirla con «pausa» o «espera» (para «sigue»). */
    private var pausedText: String? = null
    private var pausedAt = 0L
    /** Gasolineras de la última respuesta, de la más cercana a la más lejana. */
    private var nearStations: List<com.cockpitf30.fuel.NearStation> = emptyList()

    // Copiloto
    private var copilotJob: Job? = null
    private val copilotMemory = Copilot.Memory()
    private var lastActivityAt = 0L
    private var lastCopilotAt = 0L
    private var copilotSnoozeUntil = 0L

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var google: SpeechRecognizer? = null
    private var unmuteJob: Job? = null

    private fun settings() = AppSettings(app)
    private fun hasMic() = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Solo para capturas de pantalla en tests. */
    internal fun preview(s: AssistantState) { _state.value = s }

    /**
     * La app está en pantalla. Si no lo está pero el asistente sigue escuchando (encima de Maps),
     * las respuestas solo se dicen: no se cambia de pantalla salvo que se pida abrir una.
     */
    @Volatile var appVisible = false
        private set

    /** La app vuelve a estar en pantalla (o ha cambiado un ajuste / el permiso). */
    fun resume(context: Context) {
        appVisible = true
        contactsCache = null
        app = context.applicationContext
        scope.launch(Dispatchers.IO) { contactsCache = com.cockpitf30.system.Contacts.load(app).takeIf { it.isNotEmpty() } }
        resumed = true
        _humor.value = settings().assistantHumor
        if (Personality.userName == null) Personality.userName = settings().assistantUserName
        if (!recentLoaded) { recentLoaded = true; Personality.recent.load(settings().assistantRecentLines) }
        lastActivityAt = System.currentTimeMillis()
        initTts()
        refresh()
        startCopilot()
    }

    private var recentLoaded = false

    /**
     * La app sale de pantalla (Maps, Spotify…) pero el conductor quiere seguir usando «Hola BMW»:
     * el micrófono sigue abierto (lo permite el servicio en primer plano con tipo micrófono) y el
     * mini cockpit enseña lo que oye y responde.
     */
    fun background(context: Context) {
        if (!resumed) resume(context)
        appVisible = false
    }

    /** La app sale de pantalla: suelta el micrófono para no molestar a Maps, llamadas, etc. */
    fun pause() {
        appVisible = false
        resumed = false
        muted = false
        copilotJob?.cancel()
        copilotJob = null
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
                // Si el micrófono deja de dar audio (pasa en el emulador al hablar la voz), se vuelve a abrir.
                var failures = 0
                while (coroutineScopeActive()) {
                    try {
                        listen(m) { failures = 0 }
                        break
                    } catch (e: MicLost) {
                        failures++
                        Log.w(TAG, "Micrófono perdido (${e.message}); se reabre ($failures)")
                        _state.update { it.copy(error = "Micrófono reabierto $failures ${if (failures == 1) "vez" else "veces"}: ${e.message}") }
                        if (failures > 20) throw e
                        delay(500L * failures.coerceAtMost(6))
                    }
                }
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

    /** El micrófono ha dejado de dar audio: hay que cerrarlo y abrirlo otra vez. */
    private class MicLost(msg: String) : Exception(msg)

    @SuppressLint("MissingPermission") // Se comprueba en refresh().
    private suspend fun listen(m: Model, onAudio: () -> Unit) {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        fun open(source: Int) = AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE))
            .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        // VOICE_RECOGNITION quita el control automático de ganancia; si la radio no lo tiene, el micro normal.
        val audio = open(MediaRecorder.AudioSource.VOICE_RECOGNITION) ?: open(MediaRecorder.AudioSource.MIC)
            ?: error("no se puede abrir el micrófono")
        // Cancelador de eco del sistema, si lo hay: resta la voz del asistente para oír al conductor encima.
        val echo = runCatching {
            if (android.media.audiofx.AcousticEchoCanceler.isAvailable())
                android.media.audiofx.AcousticEchoCanceler.create(audio.audioSessionId)?.apply { enabled = true }
            else null
        }.getOrNull()
        // Dos reconocedores: uno restringido a la frase de activación (mucho más fiable que buscarla en
        // texto libre, porque el modelo no conoce «BMW») y otro libre para la orden.
        val wakeRec = Recognizer(m, RATE.toFloat(), VoiceCommands.WAKE_GRAMMAR).apply { setWords(true) }
        val cmdRec = Recognizer(m, RATE.toFloat())
        // Mientras habla la radio solo se escucha «Hola BMW» o «basta», para poder interrumpirla
        // (con gramática cerrada: su propia voz no se confunde con eso).
        val bargeRec = Recognizer(m, RATE.toFloat(), VoiceCommands.BARGE_GRAMMAR)
        var usingCmd = false
        var bargeHit: String? = null
        var bargeSince = 0L
        // Lo dicho hasta ahora en la orden: Vosk corta en cada pausa corta, y se espera a que el
        // conductor termine de verdad (END_SILENCE_MS sin hablar) antes de entenderla.
        val utterance = StringBuilder()
        var utteranceAt = 0L
        var speaking = false
        try {
            audio.startRecording()
            if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) error("el micrófono está ocupado")
            withContext(Dispatchers.Main) { if (_state.value.phase == AssistantPhase.LOADING) set(AssistantPhase.WAITING) }
            val buf = ShortArray(RATE / 10) // 100 ms
            var lastPartial = ""
            var lastLevel = 0L
            var badReads = 0
            var silentSince = 0L
            var heardSomething = false
            var mutedSince = 0L
            while (coroutineScopeActive()) {
                val n = audio.read(buf, 0, buf.size)
                if (n <= 0) {
                    // Un error de lectura seguido (p. ej. ERROR_DEAD_OBJECT) no se arregla solo.
                    if (++badReads > 20) throw MicLost("error de lectura $n")
                    delay(50); continue
                }
                badReads = 0
                val now = System.currentTimeMillis()
                // Ceros exactos durante mucho rato (sin estar callado) = el micrófono se ha quedado mudo.
                var allZero = true
                for (i in 0 until n) if (buf[i].toInt() != 0) { allZero = false; break }
                if (!allZero) { silentSince = 0L; if (!heardSomething) { heardSomething = true; onAudio() } }
                else if (!muted) {
                    // Solo esperando «Hola BMW»: en plena conversación no se reabre (cortaba la frase).
                    // Windows/el emulador dan ceros exactos en silencio, así que se espera bastante.
                    if (silentSince == 0L) silentSince = now
                    else if (heardSomething && now - silentSince > 15_000 && _state.value.phase == AssistantPhase.WAITING) throw MicLost("sin audio")
                }
                if (now - lastLevel > 80) {
                    lastLevel = now
                    val lvl = if (muted) 0f else rms(buf, n)
                    // Solo se publica si cambia de forma apreciable: cada publicación recompone la
                    // tarjeta y la burbuja del asistente, y con ruido de fondo eran 12 por segundo.
                    val shown = _state.value.level
                    if (kotlin.math.abs(lvl - shown) >= 0.04f || (lvl == 0f && shown != 0f)) _state.update { it.copy(level = lvl) }
                }
                if (resetRecognizer) {
                    resetRecognizer = false; wakeRec.reset(); cmdRec.reset(); bargeRec.reset(); lastPartial = ""
                    utterance.clear(); speaking = false
                }
                // Por si no llega el aviso de fin de la voz: nunca más de 30 s sordo.
                if (muted) { if (mutedSince == 0L) mutedSince = now else if (now - mutedSince > 30_000) { muted = false; resetRecognizer = true } }
                else mutedSince = 0L
                if (muted) {
                    // Hablando: solo se atiende a la interrupción, y solo con frases completas (los
                    // resultados parciales de la gramática se confundían con su propia voz y cortaban).
                    if (mutedSince != 0L && now - mutedSince < BARGE_GRACE_MS) { bargeRec.reset(); continue }
                    // Con la voz sonando casi nunca hay silencio, así que Vosk no cierra la frase: se mira
                    // también el parcial, pero solo si termina en una orden de interrupción y se mantiene
                    // BARGE_STABLE_MS (un parcial suelto de su propia voz no basta).
                    val final = bargeRec.acceptWaveForm(buf, n)
                    val raw = if (final) JSONObject(bargeRec.result).optString("text") else JSONObject(bargeRec.partialResult).optString("partial")
                    val hit = VoiceCommands.bargePhraseAtEnd(raw)
                    if (hit != bargeHit) { bargeHit = hit; bargeSince = now }
                    if (hit != null && (final || now - bargeSince >= BARGE_STABLE_MS)) {
                        bargeHit = null
                        bargeRec.reset()
                        _state.update { it.copy(lastText = "(hablando) $hit") }
                        withContext(Dispatchers.Main) { safely("interrupción") { onBargeIn(hit) } }
                    } else if (final) bargeHit = null
                    continue
                }
                val wantCmd = _state.value.phase == AssistantPhase.LISTENING
                if (wantCmd != usingCmd) {
                    usingCmd = wantCmd
                    (if (wantCmd) cmdRec else wakeRec).reset()
                    lastPartial = ""
                    utterance.clear(); speaking = false
                }
                val rec = if (usingCmd) cmdRec else wakeRec
                if (rec.acceptWaveForm(buf, n)) {
                    val json = JSONObject(rec.result)
                    val text = json.optString("text")
                    lastPartial = ""
                    speaking = false
                    val clean = text.replace("[unk]", "").trim()
                    if (text.isNotBlank()) _state.update { it.copy(lastText = text) }
                    if (clean.isNotBlank()) {
                        if (usingCmd) {
                            if (utterance.isNotEmpty()) utterance.append(' ')
                            utterance.append(clean)
                            utteranceAt = now
                            if (listenUntil > 0) listenUntil = maxOf(listenUntil, now + END_SILENCE_UNSURE_MS + 1_000)
                        } else if (wakeAccepted(json, clean, settings().wakeSensitivity)) {
                            withContext(Dispatchers.Main) { safely("onFinal") { onFinal(clean) } }
                        }
                    }
                } else {
                    val partial = JSONObject(rec.partialResult).optString("partial")
                    if (partial != lastPartial) {
                        lastPartial = partial
                        if (listenUntil > 0) listenUntil = maxOf(listenUntil, now + 2_500)
                        val clean = partial.replace("[unk]", "").trim()
                        speaking = clean.isNotBlank()
                        if (speaking) utteranceAt = now
                        val shown = listOf(utterance.toString(), clean).filter { it.isNotBlank() }.joinToString(" ")
                        if (shown.isNotBlank()) withContext(Dispatchers.Main) { safely("onPartial") { onPartial(shown) } }
                    }
                }
                // Terminó de hablar: se entiende la frase entera.
                // Si lo dicho aún no tiene sentido («busca la…»), se espera más: estará pensando.
                val endSilence = if (utterance.isNotEmpty() && VoiceCommands.parse(VoiceCommands.stripWakeWords(utterance.toString())) is Command.Unknown) END_SILENCE_UNSURE_MS else END_SILENCE_MS
                if (utterance.isNotEmpty() && !speaking && (now - utteranceAt >= endSilence || listenUntil in 1 until now)) {
                    val whole = utterance.toString()
                    utterance.clear()
                    listenUntil = 0
                    withContext(Dispatchers.Main) { safely("onFinal") { onFinal(whole) } }
                }
                if (listenUntil in 1 until now && !speaking) {
                    listenUntil = 0
                    withContext(Dispatchers.Main) { safely("timeout") { onListenTimeout() } }
                }
            }
        } finally {
            runCatching { audio.stop() }
            runCatching { echo?.release() }
            audio.release()
            wakeRec.close()
            cmdRec.close()
            bargeRec.close()
        }
    }

    /**
     * Un fallo al ejecutar una orden no debe matar la escucha (antes, tras un error, «Hola BMW» dejaba
     * de funcionar hasta reiniciar la app): se avisa y se vuelve a esperar.
     */
    private inline fun safely(what: String, block: () -> Unit) {
        try { block() } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Fallo en $what", e)
            listenUntil = 0
            followUp = false
            muted = false
            resetRecognizer = true
            _state.update { it.copy(phase = AssistantPhase.WAITING, heard = null, followUp = false, paused = false, reply = "Algo ha fallado. Vuelve a intentarlo", replyAt = System.currentTimeMillis(), error = "$what: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    /**
     * ¿Vale esta frase de activación? Mira la confianza de cada palabra que da Vosk y, en sensibilidad
     * baja, exige la sigla entera («be eme uve doble»). 0 = baja, 1 = normal, 2 = alta.
     */
    private fun wakeAccepted(json: JSONObject, text: String, sensitivity: Int): Boolean {
        if (VoiceCommands.afterWakeWord(text) == null) return false
        val words = json.optJSONArray("result")
        val minConf = if (words == null || words.length() == 0) 1.0
        else (0 until words.length()).minOf { words.getJSONObject(it).optDouble("conf", 1.0) }
        val needed = when (sensitivity) { 0 -> 0.85; 1 -> 0.6; else -> 0.0 }
        val full = VoiceCommands.normalize(text).let { it.contains("eme uve doble") || it.contains("eme doble uve") }
        val ok = minConf >= needed && (sensitivity > 0 || full)
        _state.update { it.copy(lastText = "$text (confianza ${"%.2f".format(minConf)}${if (ok) "" else ", descartada"})") }
        return ok
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
                // Con resultados parciales se activaba sin decir «Hola BMW»: solo en sensibilidad alta.
                if (settings().wakeSensitivity < 2) return
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
                if (rest.isNotBlank()) execute(rest, followUp)
            }
            else -> Unit
        }
    }

    private fun onListenTimeout() {
        if (_state.value.phase != AssistantPhase.LISTENING) return
        if (followUp) {
            // Fin de la conversación seguida: sin «No te he oído», que no se ha preguntado nada.
            followUp = false
            _state.update { it.copy(phase = AssistantPhase.WAITING, heard = null, engine = null, followUp = false, paused = false) }
            return
        }
        _state.update { it.copy(phase = AssistantPhase.WAITING, heard = null, reply = "No te he oído", replyAt = System.currentTimeMillis(), engine = null) }
    }

    /** «Hola BMW»: pitido y a escuchar la orden. */
    private fun wake() {
        followUp = false
        listenUntil = System.currentTimeMillis() + LISTEN_MS
        _state.update { it.copy(phase = AssistantPhase.LISTENING, heard = null, reply = null, engine = "Vosk", followUp = false, paused = false) }
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
                followUp = false
                stopGoogle()
                _state.update { it.copy(phase = AssistantPhase.WAITING, heard = null, reply = null, followUp = false, paused = false) }
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

    private fun execute(text: String, inFollowUp: Boolean = false) {
        listenUntil = 0
        followUp = false
        val now = System.currentTimeMillis()
        val waitingAnswer = conversation.pending != null
        val command = conversation.resolve(text, now)
        Log.i(TAG, "«$text» -> $command")
        val words = VoiceCommands.normalize(text).split(' ').count { it.isNotBlank() }
        // En la escucha seguida se descarta lo suelto (ruido, la radio); una frase de verdad se contesta.
        if (inFollowUp && !waitingAnswer && ((command is Command.Unknown && words < 3) || command == Command.Yes || command == Command.Another)) {
            // Sin «Hola BMW» y sin sentido: será la radio o alguien hablando. No se contesta.
            _state.update { it.copy(phase = AssistantPhase.WAITING, heard = null, followUp = false, paused = false) }
            return
        }
        _state.update { it.copy(heard = text, followUp = false, paused = false) }
        lastActivityAt = now
        var reply = perform(command, now)
        val repeatable = command is Command.Info || command is Command.WeatherDay
        if (repeatable && conversation.askedRecently(command, now) && kotlin.random.Random.nextDouble() < Personality.jokeChance(humor())) {
            reply = Personality.line("again") + " " + reply
        }
        conversation.record(text, command, reply, now)
        saveRecent()
        val ends = command == Command.Cancel || command == Command.Thanks || command == Command.No || command == Command.Quiet ||
            command is Command.Navigate || command is Command.Call || command == Command.Maps || command == Command.Music ||
            command is Command.AskGoogle || (command is Command.Station && command.action == StationAction.GO) ||
            command is Command.RadioPlay || command == Command.RadioStop || command is Command.RadioNext || command is Command.RadioVolume
        say(reply, listenAfter = if (ends || !settings().assistantFollowUp) 0L else FOLLOW_MS)
    }

    private fun saveRecent() { runCatching { settings().assistantRecentLines = Personality.recent.save() } }

    /**
     * Abre una pantalla de la app. Fuera de la app (encima de Maps) solo si se ha pedido
     * expresamente ([force]); las consultas («¿cuánta gasolina?») se contestan sin quitar el mapa.
     */
    private fun open(screen: Screen, force: Boolean = false) {
        if (appVisible) { navigator?.invoke(screen); return }
        if (!force) return
        if (screen == Screen.VEHICLE) { navigator?.invoke(screen); return }
        afterSpeaking { com.cockpitf30.ui.MainActivity.show(app, screen) }
    }

    /**
     * Busca en la agenda y llama. Con un único contacto que encaja llama al terminar de decirlo;
     * con varios pide nombre y apellido; un número dictado se marca tal cual.
     */
    private fun callContact(who: String): String {
        val number = com.cockpitf30.system.VoiceMatch.spokenNumber(who)
        if (number != null) {
            afterSpeaking { com.cockpitf30.system.Contacts.call(app, number) }
            return "Llamando al ${number.chunked(3).joinToString(" ")}"
        }
        val book = contactsCache ?: com.cockpitf30.system.Contacts.load(app).also { if (it.isNotEmpty()) contactsCache = it }
        if (book.isEmpty()) {
            open(Screen.PHONE)
            return if (!com.cockpitf30.system.Contacts.canRead(app)) "No tengo permiso para ver tus contactos. Te abro el teléfono"
            else "No veo la agenda. ¿Está el móvil conectado por Bluetooth?"
        }
        val found = com.cockpitf30.system.VoiceMatch.match(who, book) { it.name }
        val people = found.distinctBy { com.cockpitf30.system.VoiceMatch.normalize(it.name) }
        return when {
            found.isEmpty() -> joke("No encuentro a $who en tus contactos", "call.notfound")
            people.size == 1 -> {
                val c = found.first()
                afterSpeaking { com.cockpitf30.system.Contacts.call(app, c.number) }
                joke("Llamando a ${c.name}", "call.found")
            }
            else -> {
                val names = people.take(3).map { it.name }
                conversation.ask(Pending.ChooseContact(names), System.currentTimeMillis())
                "Tengo ${people.size}: " + names.joinToString(", ") + ". ¿Cuál? Dime nombre y apellido, o el primero, el segundo…"
            }
        }
    }

    /**
     * Aviso hablado que no viene de una orden (p. ej. un radar): solo con la app en pantalla y si
     * no se está escuchando al conductor en ese momento.
     */
    fun announce(text: String) {
        scope.launch {
            val phase = _state.value.phase
            if (!resumed || phase == AssistantPhase.LISTENING) return@launch
            conversation.record(null, null, text, System.currentTimeMillis())
            say(text)
        }
    }

    /**
     * Aviso en ruta: como [announce], pero si el asistente está escuchando o hablando devuelve false
     * para que se intente un poco después (y no corte lo que se está diciendo).
     */
    fun announceIfFree(text: String): Boolean {
        val phase = _state.value.phase
        if (phase == AssistantPhase.LISTENING || phase == AssistantPhase.ANSWERING) return false
        announce(text)
        return true
    }

    /** La agenda leída (se relee al reanudar la app, por si se conectó otro móvil). */
    @Volatile private var contactsCache: List<com.cockpitf30.system.Contact>? = null

    private fun humor() = settings().assistantHumor

    /** Respuesta con o sin broma según el nivel de humor; [key] es el tipo de broma de [Lines.QUIPS]. */
    private fun joke(plain: String, key: String?) = Personality.spice(plain, humor(), key)

    private fun perform(c: Command, now: Long = System.currentTimeMillis()): String = when (c) {
        is Command.Open -> {
            open(c.screen, force = true)
            when (c.screen) {
                Screen.FUEL -> joke("Te enseño las gasolineras", "open.fuel")
                Screen.VEHICLE -> joke("Te paso al iDrive original del coche", "open.vehicle")
                Screen.TRIP -> joke("Datos de viaje", "open.trip")
                Screen.WEATHER -> joke("Este es el tiempo", "open.weather")
                Screen.PHONE -> joke("Abro el teléfono", "open.phone")
                Screen.APPS -> joke("Tus aplicaciones", "open.apps")
                Screen.DIAGNOSTICS -> joke("Abro el diagnóstico", null)
                Screen.SETTINGS -> joke("Abro los ajustes", "open.settings")
                Screen.HOME -> joke("Vuelvo al inicio", "open.home")
                Screen.ROUTE -> joke("Radares y tiempo en ruta", "open.route")
                Screen.PERFORMANCE -> joke("Rendimiento", "open.performance")
                Screen.MAINTENANCE -> Maintenance.spoken(com.cockpitf30.ui.screens.serviceStatuses(settings())) ?: "Mantenimiento: todo al día"
                Screen.RADIO -> "Abro la radio"
            }
        }
        is Command.Info -> info(c.topic, now)
        is Command.Call -> callContact(c.who)
        is Command.Mode -> {
            com.cockpitf30.ui.theme.DriveModes.select(app, c.mode)
            when (c.mode) {
                com.cockpitf30.ui.theme.DriveMode.SPORT -> joke("Modo Sport", "mode.sport")
                com.cockpitf30.ui.theme.DriveMode.NIGHT -> joke("Modo noche", "mode.night")
                com.cockpitf30.ui.theme.DriveMode.NORMAL -> joke("Modo normal", "mode.normal")
            }
        }
        is Command.Navigate -> {
            afterSpeaking { navigateTo(c.destination) }
            joke("Vamos a ${c.destination}", "nav")
        }
        Command.Music -> { afterSpeaking { openMusic(app) }; joke("Abro la música", "music") }
        Command.Maps -> { afterSpeaking { openMaps(app) }; joke("Abro el mapa", "maps") }
        is Command.AskGoogle -> if (canOpenGoogleAssistant(c.query)) {
            afterSpeaking { openGoogleAssistant(c.query) }
            joke("Se lo pregunto a Google", "google")
        } else "La radio no tiene la app de Google"
        is Command.RadioPlay -> {
            com.cockpitf30.radio.Radio.init(app)
            open(Screen.RADIO)
            com.cockpitf30.radio.Radio.playByVoice(c.query)
        }
        Command.RadioStop -> { com.cockpitf30.radio.Radio.stop(); "Radio apagada" }
        is Command.RadioVolume -> {
            val r = com.cockpitf30.radio.Radio
            if (c.set != null) r.setVolume(c.set) else r.changeVolume(c.delta)
            "Radio al ${r.state.value.volume} por ciento"
        }
        is Command.RadioNext -> { com.cockpitf30.radio.Radio.next(c.dir); if (c.dir > 0) "Siguiente emisora" else "Emisora anterior" }
        Command.Help -> "Puedo abrir gasolineras, viaje, tiempo, teléfono o apps; decirte la autonomía, el tiempo de hoy o de mañana " +
            "y la gasolinera más cercana; llevarte a un sitio, llamar a alguien, contarte un chiste o preguntar a Google. " +
            "Después de contestarte sigo escuchando unos segundos, así que puedes seguir: otra, y mañana, llévame. " +
            "Y puedes ajustar mi humor, decir activa el copiloto, qué hay por aquí, o desactiva los avisos turísticos."
        Command.Cancel -> joke(Personality.line("cancel"), "cancel")
        Command.Thanks -> joke(Personality.line("thanks"), "thanks")
        Command.No -> Personality.line("no")
        Command.Yes -> Personality.line("yes.nocontext")
        Command.Another -> Personality.line("another.nocontext")
        Command.GoThere -> Personality.line("nothere")
        Command.Price -> info(InfoTopic.FUEL_NEAR, now)
        Command.Repeat -> conversation.lastReply ?: Personality.line("repeat.none")
        Command.Recap -> conversation.recap(now) ?: Personality.chat("recap.none", humor())
        Command.Joke -> if (humor() <= 0) Personality.line("joke.nohumor") else Personality.joke()
        is Command.Chat -> {
            when (c.key) {
                // Lo que proponen estas respuestas se puede aceptar con un «sí».
                "tired" -> conversation.ask(Pending.Confirm(Command.Info(InfoTopic.FUEL_NEAR)), now)
                "bored" -> conversation.ask(Pending.Confirm(Command.Joke), now)
            }
            Personality.chat(c.key, humor())
        }
        is Command.SetName -> {
            Personality.userName = c.name
            settings().assistantUserName = c.name
            Personality.chat("name.set", humor())
        }
        Command.AskName -> Personality.chat(if (Personality.userName.isNullOrBlank()) "name.unknown" else "name.ask", humor())
        is Command.Copilot -> {
            val on = c.on ?: true
            settings().copilotEnabled = on
            copilotSnoozeUntil = 0
            if (on) {
                startCopilot()
                "Modo copiloto activado. Te daré conversación cada ${settings().copilotMinutes} minutos más o menos. Si molesto, di cállate un rato."
            } else joke("Modo copiloto desactivado", "cancel")
        }
        Command.Pause -> if (pausedText != null) "Sigo en pausa. Di sigue cuando quieras" else Personality.line("ack")
        Command.Resume -> {
            val rest = pausedText?.takeIf { now - pausedAt < 10 * 60_000L }
            pausedText = null
            rest ?: "No me había quedado a medias. Pregúntame lo que quieras"
        }
        is Command.Alerts -> alerts(c)
        Command.Nearby -> com.cockpitf30.nav.RouteAlerts.nearby(scope)
        Command.AboutThat -> com.cockpitf30.nav.RouteAlerts.more() ?: "Todavía no te he hablado de ningún sitio. Pregúntame qué hay por aquí."
        Command.Quiet -> {
            copilotSnoozeUntil = now + 30 * 60_000L
            joke("Vale, me callo media hora", "cancel")
        }
        is Command.Station -> station(c, now)
        is Command.WeatherDay -> weatherDay(c.offset)
        is Command.Humor -> {
            val level = humor()
            if (c.set == null && c.delta == 0) {
                "Mi nivel de humor está al $level por ciento"
            } else {
                val l = (c.set ?: (level + c.delta)).coerceIn(0, 100)
                setHumor(app, l)
                Personality.humorReply(l)
            }
        }
        Command.Honesty -> "Sinceridad al noventa por ciento. El diez restante es por tu bien."
        is Command.Unknown -> Personality.line(if (kotlin.random.Random.nextDouble() < Personality.jokeChance(humor())) "unknown.funny" else "unknown")
    }

    private fun navigateTo(q: String) {
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(q))).setPackage(GOOGLE_MAPS)
        if (nav.resolveActivity(app.packageManager) != null) launch(app, nav)
        else launch(app, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))))
    }

    /** Una de las gasolineras de las que se acaba de hablar: describirla, su precio o ir. */
    private fun station(c: Command.Station, now: Long): String {
        val n = nearStations.getOrNull(c.index) ?: return Personality.line("nostations")
        conversation.setStations(nearStations.size, c.index)
        val s = n.station
        return when (c.action) {
            StationAction.DESCRIBE -> {
                open(Screen.FUEL)
                conversation.ask(Pending.Confirm(Command.Station(c.index, StationAction.GO)), now)
                (if (c.index == 0) "La más cercana es " else "La siguiente es ") +
                    "${s.brand}, a ${FuelStations.formatDistance(n.meters)}, en ${place(s)}" +
                    (s.diesel?.let { ". El diésel, a ${FuelStations.formatPrice(it)} euros" } ?: "") +
                    ". " + Personality.line("ask.go")
            }
            StationAction.PRICE -> {
                conversation.ask(Pending.Confirm(Command.Station(c.index, StationAction.GO)), now)
                when {
                    s.diesel != null -> "En ${s.brand} el diésel está a ${FuelStations.formatPrice(s.diesel)} euros" +
                        (s.gasoline95?.let { " y la gasolina 95 a ${FuelStations.formatPrice(it)}" } ?: "") + ". " + Personality.line("ask.go")
                    s.gasoline95 != null -> "En ${s.brand} no hay diésel; la gasolina 95 está a ${FuelStations.formatPrice(s.gasoline95)} euros"
                    else -> "No tengo los precios de ${s.brand}"
                }
            }
            StationAction.GO -> {
                afterSpeaking { navigateTo("${s.lat},${s.lon}") }
                joke("Vamos a ${s.brand}, en ${place(s)}", "station.go")
            }
        }
    }

    private fun weatherDay(offset: Int): String {
        open(Screen.WEATHER)
        val f = WeatherRepository.state.value.forecast ?: return "Todavía no tengo el tiempo"
        val d = f.days.getOrNull(offset) ?: return "No tengo previsión para ese día"
        val es = Locale("es", "ES")
        val label = when (offset) {
            0 -> "Hoy"
            1 -> "Mañana"
            2 -> "Pasado mañana"
            else -> "El " + d.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, es)
        }
        val rain = d.precipProbMax?.takeIf { it >= 30 }?.let { ". Probabilidad de lluvia del $it por ciento" } ?: ""
        return joke("$label, ${wmoCondition(d.code).text.lowercase(es)}, entre ${d.minC} y ${d.maxC} grados$rain", "weather")
    }

    private fun info(topic: InfoTopic, now: Long = System.currentTimeMillis()): String {
        val car = VehicleRepository.state.value
        val es = Locale("es", "ES")
        return when (topic) {
            InfoTopic.RANGE -> {
                val range = car.rangeKm?.let { "Te quedan unos $it kilómetros de autonomía" }
                val level = car.fuelLevel?.let { "el depósito está al ${(it * 100).toInt()} por ciento" }
                val key = if ((car.rangeKm ?: 999) < 80) "range.low" else "range"
                if ((car.rangeKm ?: 999) < 80) conversation.ask(Pending.Confirm(Command.Info(InfoTopic.FUEL_NEAR)), now)
                when {
                    range != null && level != null -> joke("$range y $level", key)
                    range != null -> joke(range, key)
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
                    nearStations = FuelStations.nearest(list, pos, limit = 5)
                    if (nearStations.isEmpty()) "No tengo gasolineras cerca"
                    else station(Command.Station(0, StationAction.DESCRIBE), now)
                }
            }
            InfoTopic.TYRES -> {
                open(Screen.VEHICLE)
                "La caja no me da la presión. Te abro el iDrive original: entra en Estado del vehículo"
            }
            InfoTopic.DOORS -> {
                open(Screen.VEHICLE)
                joke("No puedo ver las puertas. Te abro el iDrive original", "doors")
            }
            InfoTopic.TRIP -> {
                open(Screen.TRIP)
                val trips = TripComputer.state.value
                val trip = trips.current ?: trips.last
                if (trip == null) "Aún no hay ningún viaje"
                else joke("${if (trips.current != null) "Llevas" else "En el último viaje hiciste"} ${km(trip.distanceKm)} kilómetros en ${Copilot.duration(trip.durationMillis)}", "trip")
            }
            InfoTopic.WEATHER -> {
                open(Screen.WEATHER)
                val w = WeatherRepository.state.value
                val f = w.forecast
                if (f == null) "Todavía no tengo el tiempo"
                else {
                    val today = f.days.firstOrNull()
                    val rain = today?.precipProbMax?.takeIf { it >= 30 }?.let { ". Probabilidad de lluvia del $it por ciento" } ?: ""
                    joke((w.placeName?.let { "En $it, " } ?: "") + "${f.tempC} grados y ${wmoCondition(f.code).text.lowercase(es)}" +
                        (today?.let { ". Hoy entre ${it.minC} y ${it.maxC}" } ?: "") + rain, "weather")
                }
            }
            InfoTopic.TIME -> LocalTime.now().let { joke("Son las ${it.hour}:${"%02d".format(it.minute)}", "time") }
            InfoTopic.SPEED -> car.speedKmh?.let { joke("Vas a $it kilómetros por hora", "speed") } ?: "No me llega la velocidad"
            InfoTopic.GUIDANCE -> com.cockpitf30.nav.MapsGuidanceState.current.value?.let { com.cockpitf30.nav.MapsGuidanceState.spoken(it) }
                ?: if (!com.cockpitf30.nav.MapsGuidanceState.hasAccess(app)) "Para leer las indicaciones de Maps dame acceso a las notificaciones en Ajustes, Junto a Google Maps"
                else "Maps no está navegando ahora mismo"
        }
    }

    /** «Desactiva los avisos», «activa los avisos turísticos»… */
    private fun alerts(c: Command.Alerts): String {
        val st = settings()
        val cat = c.category
        if (cat == null) {
            st.routeAlerts = c.on
            if (!c.on) com.cockpitf30.nav.RouteAlerts.hide()
            return if (c.on) "Avisos en ruta activados: radares, seguridad, combustible, descanso y sitios turísticos. Puedes quitar uno, por ejemplo: desactiva los avisos turísticos."
            else joke("Vale, sin avisos en ruta. Si los quieres otra vez, di activa los avisos", "cancel")
        }
        st.setAlertCategory(cat, c.on)
        if (c.on && !st.routeAlerts) st.routeAlerts = true
        return if (c.on) "Avisos ${cat.spoken} activados" else joke("Avisos ${cat.spoken} desactivados", "cancel")
    }

    // ---------------------------------------------------------------- copiloto

    private fun startCopilot() {
        if (copilotJob?.isActive == true || !resumed) return
        copilotJob = scope.launch {
            while (isActive) {
                delay(15_000)
                runCatching { copilotTick(force = false) }.onFailure { Log.w(TAG, "Copiloto", it) }
            }
        }
    }

    /** Diagnóstico: olvidar el nombre del conductor, la conversación y las frases ya dichas. */
    fun forgetMemory(context: Context) {
        app = context.applicationContext
        Personality.userName = null
        Personality.recent.clear()
        conversation.forget()
        settings().assistantUserName = null
        settings().assistantRecentLines = null
    }

    /** Diagnóstico: que el copiloto diga algo ya. */
    fun copilotNow(context: Context) {
        app = context.applicationContext
        copilotTick(force = true)
    }

    private fun isDemo() = VehicleRepository.status.value.sourceName == "Demo"

    private fun copilotTick(force: Boolean) {
        val st = settings()
        val phase = _state.value.phase
        val now = System.currentTimeMillis()
        if (!force) {
            if (!resumed || !st.copilotEnabled || !st.assistantEnabled) return
            if (phase != AssistantPhase.WAITING && phase != AssistantPhase.NO_MODEL) return
            if (now < copilotSnoozeUntil) return
            val demo = isDemo()
            // Solo con el coche en marcha (en demo, siempre).
            if (!demo && TripComputer.state.value.current == null) return
            if (now - maxOf(lastActivityAt, lastCopilotAt) < Copilot.intervalMs(st.copilotMinutes, demo)) return
        } else if (phase == AssistantPhase.LISTENING || phase == AssistantPhase.ANSWERING) return
        val line = Copilot.next(copilotSnapshot(now), copilotMemory, knowsName = !Personality.userName.isNullOrBlank()) ?: return
        lastCopilotAt = now
        val question = line.text.contains('?')
        val text = if (question) line.text else Personality.spice(line.text, humor(), "copilot")
        conversation.ask(line.yes?.let { Pending.Confirm(it) }, now)
        conversation.record(null, null, text, now)
        saveRecent()
        say(text, listenAfter = if (question && st.assistantFollowUp) COPILOT_FOLLOW_MS else 0L)
    }

    private fun copilotSnapshot(now: Long): CopilotSnapshot {
        val car = VehicleRepository.state.value
        val trips = TripComputer.state.value
        val trip = trips.current
        val route = RouteTracker.state.value
        val rw = com.cockpitf30.weather.RouteWeather.state.value
        val dest = rw.destination
        val pos = route.position
        val destKm = if (dest != null && pos != null) RouteTracker.distance(pos, com.cockpitf30.nav.GeoPoint(dest.lat, dest.lon)) / 1000.0 else null
        val f = WeatherRepository.state.value.forecast
        val cond = f?.code?.let(::wmoCondition)
        val rainAhead = rw.points.firstOrNull { (it.precipProb ?: 0) >= 50 || (it.precipMm ?: 0f) >= 0.5f }?.km
        val t = LocalTime.now()
        return CopilotSnapshot(
            now = now, hour = t.hour, minute = t.minute, demo = isDemo(),
            speedKmh = car.speedKmh,
            movingMs = trip?.movingMillis,
            tripKm = trip?.distanceKm,
            tripAvgKmh = trip?.avgSpeedKmh,
            rangeKm = car.rangeKm,
            avgConsumption = car.avgConsumption ?: trip?.avgConsumption(trips.tankLiters),
            destName = dest?.name,
            destKm = destKm,
            weatherKind = cond?.kind,
            weatherText = cond?.text,
            tempC = f?.tempC ?: car.outsideTempC?.let { kotlin.math.round(it).toInt() },
            rainAheadKm = rainAhead,
            camerasNear = com.cockpitf30.nav.SpeedCameras.state.value.near.size,
            sightsNear = if (settings().alertOn(com.cockpitf30.nav.AlertCategory.TOURISM)) com.cockpitf30.nav.RoutePois.state.value.near
                .filter { it.poi.category == com.cockpitf30.nav.AlertCategory.TOURISM && it.poi.kind != "place" }.mapNotNull { it.poi.name }.take(2) else emptyList(),
        )
    }

    private fun canOpenGoogleAssistant(query: String?): Boolean = googleIntent(query) != null

    /** Abre la app de Google con la pregunta, o el asistente de Google si no hay pregunta. */
    private fun openGoogleAssistant(query: String?): Boolean {
        val intent = googleIntent(query) ?: return false
        launch(app, intent)
        return true
    }

    private fun googleIntent(query: String?): Intent? {
        val pm = app.packageManager
        val candidates = if (query != null) {
            listOf(
                Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query).setPackage("com.google.android.googlequicksearchbox"),
                Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query),
            )
        } else {
            listOf(Intent(Intent.ACTION_VOICE_COMMAND), Intent("android.intent.action.ASSIST"))
        }
        return candidates.firstOrNull { it.resolveActivity(pm) != null }
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
                // Palabra que está diciendo: para saber dónde seguir tras «pausa» y no confundir su voz con una orden.
                override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) { spokenPos = start }
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
                "Hola. Soy el asistente del coche. Humor al ${humor()} por ciento.", humor(), null,
                "Todo listo para el despegue.", "Prometo no abrir ninguna escotilla.",
            ),
        )
    }

    /**
     * Lo que hay que hacer cuando termine de hablar: abrir Maps, la música, llamar… Si se abriera
     * antes, Maps se pone delante y corta la frase (la app pasa a segundo plano).
     */
    private var afterSpeech: (() -> Unit)? = null

    private fun afterSpeaking(action: () -> Unit) { afterSpeech = action }

    private fun runAfterSpeech() {
        val a = afterSpeech ?: return
        afterSpeech = null
        runCatching { a() }.onFailure { Log.w(TAG, "Tras hablar", it) }
    }

    private fun say(text: String, listenAfter: Long = 0L) {
        listenAfterReply = listenAfter
        spokenPos = 0
        _state.update { it.copy(phase = AssistantPhase.ANSWERING, reply = text, replyAt = System.currentTimeMillis(), engine = null, paused = false) }
        unmuteJob?.cancel()
        stopPlayer()
        val t = tts
        if (t != null && ttsReady && settings().assistantSpeaks) {
            // No escuchar mientras habla la radio: se oiría a sí misma.
            muted = true
            if (settings().assistantRobotVoice && settings().assistantRobotStrength.filter && !robotBroken && synthesizeRobot(t, text)) {
                // La síntesis a archivo tarda un poco más; el tiempo real se ajusta al reproducir.
                unmuteJob = scope.launch {
                    delay(6_000L + text.length * 120L)
                    // Una tablet lenta puede tardar más en preparar la voz: mientras siga pendiente, se espera.
                    var waited = 0L
                    while (waited < 30_000L && robotText.isNotEmpty()) { delay(300); waited += 300 }
                    doneSpeaking()
                }
            } else {
                t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
                // El aviso real es onDone; esto es solo por si el motor no lo manda.
                unmuteJob = finishWhenSilent(4_000L + text.length * 140L)
            }
        } else {
            if (settings().assistantSpeaks) {
                ttsLastError = "no habla: motor de voz ${_tts.value.text}"
                initTts() // por si ya se ha instalado
            }
            unmuteJob = scope.launch { delay(1_200); doneSpeaking() }
        }
    }

    /**
     * Plan B por si el motor de voz no avisa al terminar (onDone): pasado el tiempo estimado, solo da
     * por terminada la frase cuando el motor ya no está hablando. Antes se cortaba a la hora
     * estimada aunque siguiera hablando (en una tablet, con avisos largos o voz más lenta): se
     * quitaba el texto, subía la radio y se pisaban los audios.
     */
    private fun finishWhenSilent(estimateMs: Long) = scope.launch {
        delay(estimateMs)
        var waited = 0L
        while (waited < 120_000L && (runCatching { tts?.isSpeaking == true }.getOrDefault(false) ||
                player?.playState == AudioTrack.PLAYSTATE_PLAYING)) {
            delay(300)
            waited += 300
        }
        doneSpeaking()
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
        unmuteJob = finishWhenSilent(2_000L + text.length * 90L)
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
            // Por si la duración calculada se queda corta: se espera a que el audio llegue al final.
            var waited = 0L
            while (waited < 60_000L && player === track &&
                runCatching { track.playState == AudioTrack.PLAYSTATE_PLAYING && track.playbackHeadPosition < pcm.samples.size }.getOrDefault(false)) {
                delay(200)
                waited += 200
            }
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

    /**
     * Mientras habla se le puede interrumpir:
     * - «Hola BMW»: corta y escucha una orden nueva.
     * - «para», «cancela», «silencio», «basta»…: corta y se calla.
     * - «pausa»: corta y espera a «sigue» / «continúa» para retomar donde iba.
     * - «espera», «un momento», «a ver»…: corta y escucha sin perder el hilo («sigue» también retoma).
     */
    private fun onBargeIn(text: String) {
        if (_state.value.phase != AssistantPhase.ANSWERING || !muted) return
        val wake = VoiceCommands.afterWakeWord(text) != null
        val action = if (wake) null else VoiceCommands.bargeAction(text)
        if (!wake && (action == null || action == VoiceCommands.Barge.RESUME)) return
        // Si esa palabra la está diciendo ella misma ahora («para llegar…»), es su eco: no se corta.
        if (!wake && saysItNow(text)) return
        Log.i(TAG, "Interrumpida ($action): «$text»")
        // «Hola BMW» = orden nueva: lo pendiente se descarta. «Para», «espera»…: se hace ya.
        if (wake) afterSpeech = null else runAfterSpeech()
        val reply = _state.value.reply.orEmpty()
        val rest = remainingText(reply, spokenPos)
        unmuteJob?.cancel()
        listenAfterReply = 0
        stopSpeaking()
        muted = false
        resetRecognizer = true
        val now = System.currentTimeMillis()
        if (action != VoiceCommands.Barge.STOP && rest != null) { pausedText = rest; pausedAt = now } else pausedText = null
        when (action) {
            null -> wake()
            VoiceCommands.Barge.STOP -> _state.update { it.copy(phase = AssistantPhase.WAITING, heard = null, followUp = false, paused = false) }
            VoiceCommands.Barge.PAUSE -> listenFollowUp(PAUSE_MS, paused = rest != null)
            else -> listenFollowUp(FOLLOW_MS, paused = false)
        }
    }

    private fun listenFollowUp(ms: Long, paused: Boolean) {
        followUp = true
        listenUntil = System.currentTimeMillis() + ms
        _state.update { it.copy(phase = AssistantPhase.LISTENING, heard = null, engine = "Vosk", followUp = true, paused = paused, level = 0f) }
    }

    /** ¿Está diciendo [word] justo ahora? Mira el trozo de la respuesta alrededor de la palabra en curso. */
    private fun saysItNow(word: String): Boolean {
        val reply = _state.value.reply.orEmpty()
        val w = VoiceCommands.normalize(word)
        val pos = spokenPos
        // Sin posición (voz con filtro, o el motor no la da) se mira la frase entera.
        val window = if (pos <= 0) reply else reply.substring((pos - 30).coerceAtLeast(0), (pos + 40).coerceAtMost(reply.length))
        return " ${VoiceCommands.normalize(window)} ".contains(" $w ")
    }

    /** Lo que falta por decir desde el principio de la frase (o trozo entre comas) en curso; null si nada. */
    private fun remainingText(reply: String, pos: Int): String? {
        if (reply.isBlank()) return null
        if (pos <= 0) return reply
        val cut = reply.lastIndexOfAny(charArrayOf('.', ',', ';', ':', '?', '!'), (pos - 1).coerceAtLeast(0))
        return reply.substring(cut + 1).trim().takeIf { it.length > 2 }
    }

    private fun doneSpeaking() {
        unmuteJob?.cancel()
        muted = false
        runAfterSpeech()
        // Si ya se interrumpió y está escuchando, no se borra lo que se está oyendo.
        if (_state.value.phase != AssistantPhase.ANSWERING) return
        resetRecognizer = true
        val follow = listenAfterReply
        listenAfterReply = 0
        when {
            audioJob?.isActive == true && resumed && follow > 0 -> {
                // Conversación seguida: se puede contestar sin volver a decir «Hola BMW».
                followUp = true
                listenUntil = System.currentTimeMillis() + follow
                _state.update { it.copy(phase = AssistantPhase.LISTENING, heard = null, engine = "Vosk", followUp = true, level = 0f) }
            }
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
