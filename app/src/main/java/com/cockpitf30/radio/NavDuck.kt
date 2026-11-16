package com.cockpitf30.radio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.cockpitf30.system.AppSettings
import com.cockpitf30.vehicle.source.VendorCar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Bajar la radio cuando habla Google Maps.
 *
 * Lo que se vio en el servicio del coche (android.car.server, descompilado):
 * - La FM no pasa por Android: la baja la MCU con su «mezcla del navegador», y solo cuando suena
 *   la app registrada como navegador en «data.set.gps_app» (AppManager.handleGpsAppResumed apunta
 *   su pid/uid y el audio de ese proceso cuenta como «sonido GPS»). Si ese ajuste está vacío o es
 *   otra app, las indicaciones de Maps suenan por encima de la FM sin bajarla. El propio servicio
 *   lo borra (restoreGpsApp) cuando falla al abrir el navegador, así que puede perderse solo.
 * - La otra vía que baja la FM es el foco de audio: con «transitorio, se puede bajar» el servicio
 *   pasa a canal temporal con la FM bajada (requestTempToArmSoundChannel). Es lo que hace el
 *   asistente ([AssistantDuck]) y por eso con él sí baja.
 *
 * Así que: (1) se deja Google Maps como navegador de la radio ([claim]) y (2) por si aun así no
 * baja, se escucha qué suena en Android: cuando hay una indicación de navegación sonando
 * (USAGE_ASSISTANCE_NAVIGATION_GUIDANCE) con la FM puesta, se pide ese mismo foco transitorio
 * mientras dure. La radio por Internet se baja directamente desde [Radio] con [guidance].
 */
object NavDuck {
    private const val TAG = "NavDuck"
    const val GPS_APP_KEY = "data.set.gps_app"
    const val MAPS = "com.google.android.apps.maps"

    /** Suena ahora una indicación de navegación (de Maps u otro navegador). */
    private val _guidance = MutableStateFlow(false)
    val guidance: StateFlow<Boolean> = _guidance.asStateFlow()

    /** Resultado de la última comprobación de «data.set.gps_app» (para Diagnóstico). */
    @Volatile var claimNote: String = "sin comprobar"
        private set

    /** Cómo se sabe si un reproductor está sonando (para Diagnóstico). */
    @Volatile var detectNote: String = "sin datos"
        private set

    private val _log = MutableStateFlow<List<String>>(emptyList())
    /** Últimas indicaciones vistas y qué se hizo con la radio. */
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var holding = false
    private var releaseJob: Job? = null
    private var lastClaimCheck = 0L
    private var request: AudioFocusRequest? = null
    private lateinit var app: Context

    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        val am = app.getSystemService(AudioManager::class.java) ?: return
        request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()
        runCatching {
            am.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                    onPlayers(configs.orEmpty())
                }
            }, Handler(Looper.getMainLooper()))
            onPlayers(am.activePlaybackConfigurations)
        }.onFailure { detectNote = "no se puede escuchar qué suena: ${it.message}" }
        if (AppSettings(app).radioDuckNav) Thread({ claim(app) }, "navegador-radio").start()
    }

    /**
     * Deja Google Maps como navegador de la radio (data.set.gps_app = su actividad principal), para
     * que la propia radio baje la FM con sus indicaciones. Si ya hay otro navegador elegido a mano
     * solo se cambia con [force] (botón de Diagnóstico).
     */
    fun claim(context: Context, force: Boolean = false): String {
        val note = runCatching {
            val comp = context.packageManager.getLaunchIntentForPackage(MAPS)?.component
                ?: return@runCatching "Google Maps no está instalado"
            val car = VendorCar.connect(context).getOrThrow()
            val before = car.getString(GPS_APP_KEY)
            if (before?.startsWith("$MAPS/") == true) return@runCatching "navegador de la radio: Google Maps ($before)"
            val chosenByHand = !before.isNullOrBlank() && !before.startsWith("android.car.app.gps")
            if (chosenByHand && !force) return@runCatching "navegador de la radio: «$before» (no es Google Maps; se puede cambiar en Diagnóstico)"
            val value = comp.flattenToString()
            val ok = car.setString(GPS_APP_KEY, value)
            val after = car.getString(GPS_APP_KEY)
            "navegador de la radio: «${before ?: "∅"}» → «${after ?: "∅"}» (${if (ok) "aceptado" else "rechazado"}). Abre Maps una vez para que la radio lo reconozca."
        }.getOrElse { "no se pudo leer el navegador de la radio: ${it.message ?: it.javaClass.simpleName}" }
        claimNote = note
        Log.i(TAG, note)
        note(note)
        return note
    }

    private fun onPlayers(configs: List<AudioPlaybackConfiguration>) {
        val nav = configs.filter { it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE }
        val playing = nav.any { isPlaying(it) }
        if (playing == _guidance.value) return
        _guidance.value = playing
        val s = AppSettings(app)
        if (playing) {
            releaseJob?.cancel()
            val st = Radio.state.value
            if (s.radioDuckNav && st.playing && st.source == RadioSource.FM && !holding) {
                holding = runCatching {
                    app.getSystemService(AudioManager::class.java)?.requestAudioFocus(request!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                }.getOrDefault(false)
                note("indicación del navegador · FM ${if (holding) "bajada (foco transitorio)" else "foco denegado"}")
            } else {
                note("indicación del navegador" + when {
                    !s.radioDuckNav -> " · bajar radio desactivado en Ajustes"
                    !st.playing -> " · radio parada"
                    st.source == RadioSource.INTERNET -> " · radio por Internet bajada"
                    else -> ""
                })
            }
            // Si la radio ha olvidado el navegador (lo borra al fallar), se vuelve a poner.
            if (s.radioDuckNav && System.currentTimeMillis() - lastClaimCheck > 60_000) {
                lastClaimCheck = System.currentTimeMillis()
                Thread({ claim(app) }, "navegador-radio").start()
            }
        } else if (holding) {
            // Maps a veces deja un instante entre frases: se espera un poco antes de subir la FM.
            releaseJob?.cancel()
            releaseJob = scope.launch {
                delay(900)
                release()
            }
        }
    }

    private fun release() {
        if (!holding) return
        holding = false
        request?.let { r -> runCatching { app.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(r) } }
        note("fin de la indicación · FM restaurada")
    }

    /**
     * ¿Está sonando? Android 10 no da el estado del reproductor a las apps normales de forma pública:
     * se prueba isActive()/getPlayerState() y, si no se dejan, se toma como sonando todo lo que
     * aparece en la lista (Maps la quita al terminar cada indicación).
     */
    private fun isPlaying(c: AudioPlaybackConfiguration): Boolean {
        runCatching { (c.javaClass.getMethod("isActive").invoke(c) as Boolean) }.getOrNull()?.let {
            detectNote = "estado del reproductor: isActive()"
            return it
        }
        runCatching { (c.javaClass.getMethod("getPlayerState").invoke(c) as Int) }.getOrNull()?.let {
            detectNote = "estado del reproductor: getPlayerState()"
            return it == 2 // PLAYER_STATE_STARTED
        }
        detectNote = "estado del reproductor no disponible: cuenta cualquier reproductor de navegación"
        return true
    }

    private val time = DateTimeFormatter.ofPattern("HH:mm:ss")
    private fun note(line: String) = _log.update { (listOf("${LocalTime.now().format(time)} $line") + it).take(12) }
}
