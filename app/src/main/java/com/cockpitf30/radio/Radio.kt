package com.cockpitf30.radio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes as SysAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.cockpitf30.system.AppSettings
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

/** Lo que enseña la pantalla de radio y la tarjeta del Inicio. */
data class RadioState(
    /** null = todavía no se ha comprobado si hay sintonizador. */
    val tunerAvailable: Boolean? = null,
    val tunerInfo: String = "Comprobando el sintonizador…",
    val source: RadioSource = RadioSource.INTERNET,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    /** FM: frecuencia en 10 kHz y nombre RDS. */
    val fmFreq: Int = 9800,
    val fmRds: String? = null,
    val fmSeeking: Boolean = false,
    val fmFound: List<FmStation> = emptyList(),
    val fmFavorites: List<FmStation> = emptyList(),
    /** Internet. */
    val station: StreamStation? = null,
    val nowPlaying: String? = null,
    val stations: List<StreamStation> = emptyList(),
    val favorites: List<StreamStation> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** Bajado porque habla el asistente, Maps u otra app. */
    val ducked: Boolean = false,
)

/**
 * Radio: FM por el sintonizador de la propia unidad si lo tiene ([FactoryTuner]) y, si no (o si
 * se elige), emisoras por Internet con ExoPlayer en un servicio multimedia ([RadioService]).
 *
 * Volumen: la radio pide el foco de audio. Cuando Maps da una indicación o el asistente habla
 * (piden foco "transitorio, se puede bajar") se baja al [DUCK_VOLUME] y vuelve al terminar.
 */
@SuppressLint("StaticFieldLeak") // solo guarda el contexto de la aplicación
object Radio {
    private const val TAG = "Radio"
    private const val DUCK_VOLUME = 0.25f

    private val _state = MutableStateFlow(RadioState())
    val state: StateFlow<RadioState> = _state.asStateFlow()

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: ExoPlayer? = null
    private var pollJob: Job? = null
    private var focus: AudioFocusRequest? = null
    private var started = false

    private fun settings() = AppSettings(app)

    /** Solo para capturas de pantalla (Paparazzi). */
    internal fun previewState(s: RadioState) { _state.value = s }

    /** Se llama al arrancar la app: carga favoritos y comprueba el sintonizador en segundo plano. */
    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        val s = settings()
        _state.update {
            it.copy(
                favorites = RadioLogic.favoritesFromJson(s.radioFavorites),
                fmFavorites = RadioLogic.fmFromJson(s.radioFmFavorites),
                fmFreq = s.radioFmFreq,
                station = RadioLogic.favoritesFromJson(s.radioLastStation).firstOrNull(),
            )
        }
        scope.launch {
            val p = withContext(Dispatchers.IO) { runCatching { FactoryTuner.probe(app) }.getOrNull() }
            val available = p?.available == true
            val preferred = runCatching { RadioSource.valueOf(s.radioSource ?: "") }.getOrNull()
            _state.update {
                it.copy(
                    tunerAvailable = available,
                    tunerInfo = p?.reason ?: "No se pudo comprobar el sintonizador",
                    // Sin sintonizador, siempre Internet; con él, lo último que se usó (FM por defecto).
                    source = if (!available) RadioSource.INTERNET else preferred ?: RadioSource.FM,
                )
            }
        }
    }

    fun recheckTuner() {
        scope.launch {
            val p = withContext(Dispatchers.IO) { runCatching { FactoryTuner.probe(app) }.getOrNull() }
            _state.update { it.copy(tunerAvailable = p?.available == true, tunerInfo = p?.reason ?: "No se pudo comprobar") }
        }
    }

    // ------------------------------------------------------------------ fuente

    fun selectSource(source: RadioSource) {
        if (source == RadioSource.FM && _state.value.tunerAvailable != true) {
            _state.update { it.copy(error = "Esta radio no tiene sintonizador FM accesible: se usa Internet") }
            return
        }
        val wasPlaying = _state.value.playing
        stop()
        settings().radioSource = source.name
        _state.update { it.copy(source = source, error = null) }
        if (wasPlaying) play()
    }

    fun togglePlay() = if (_state.value.playing) stop() else play()

    fun play() {
        when (_state.value.source) {
            RadioSource.FM -> playFm(_state.value.fmFreq)
            RadioSource.INTERNET -> _state.value.station?.let(::playStream)
                ?: _state.update { it.copy(error = "Elige una emisora") }
        }
    }

    fun stop() {
        player?.let { it.stop(); it.clearMediaItems() }
        if (_state.value.source == RadioSource.FM && _state.value.playing) {
            scope.launch(Dispatchers.IO) { FactoryTuner.exit(app) }
        }
        pollJob?.cancel()
        abandonFocus()
        _state.update { it.copy(playing = false, buffering = false, ducked = false) }
    }

    // ------------------------------------------------------------------ FM

    fun playFm(freq: Int) {
        if (_state.value.tunerAvailable != true) {
            _state.update { it.copy(error = "Sin sintonizador FM: usa una emisora de Internet") }
            return
        }
        player?.let { it.stop(); it.clearMediaItems() }
        requestFocus()
        _state.update { it.copy(source = RadioSource.FM, playing = true, fmFreq = freq, fmRds = null, error = null) }
        settings().radioFmFreq = freq
        settings().radioSource = RadioSource.FM.name
        scope.launch(Dispatchers.IO) {
            val r = FactoryTuner.enter(app)
            val ok = FactoryTuner.tune(app, freq)
            Log.i(TAG, "FM ${RadioLogic.formatFm(freq)}: $r, tune=$ok")
            if (!ok) _state.update { it.copy(error = "El sintonizador no aceptó la frecuencia") }
        }
        startPolling()
    }

    fun fmStep(dir: Int) {
        val f = RadioLogic.stepFm(_state.value.fmFreq, dir)
        _state.update { it.copy(fmFreq = f, fmRds = null) }
        if (_state.value.playing) scope.launch(Dispatchers.IO) { FactoryTuner.tune(app, f) }
        settings().radioFmFreq = f
    }

    fun fmSeek(dir: Int) {
        if (!_state.value.playing) playFm(_state.value.fmFreq)
        _state.update { it.copy(fmSeeking = true, fmRds = null) }
        scope.launch(Dispatchers.IO) { if (dir > 0) FactoryTuner.seekNext(app) else FactoryTuner.seekPrev(app) }
    }

    /** Barrido completo: la radio guarda lo que encuentra y lo vamos leyendo. */
    fun fmScan() {
        if (!_state.value.playing) playFm(_state.value.fmFreq)
        _state.update { it.copy(fmSeeking = true) }
        scope.launch(Dispatchers.IO) { FactoryTuner.search(app) }
    }

    fun toggleFmFavorite(freq: Int = _state.value.fmFreq) {
        val cur = _state.value.fmFavorites
        val next = if (cur.any { it.freq == freq }) cur.filterNot { it.freq == freq }
        else (cur + FmStation(freq, _state.value.fmRds.orEmpty().takeIf { freq == _state.value.fmFreq }.orEmpty())).sortedBy { it.freq }
        settings().radioFmFavorites = RadioLogic.fmToJson(next)
        _state.update { it.copy(fmFavorites = next) }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && _state.value.playing && _state.value.source == RadioSource.FM) {
                val st = withContext(Dispatchers.IO) { runCatching { FactoryTuner.status(app) }.getOrNull() }
                if (st != null) {
                    _state.update {
                        it.copy(
                            fmFreq = st.freq ?: it.fmFreq,
                            fmRds = st.rds ?: it.fmRds,
                            fmSeeking = st.seeking,
                            fmFound = st.found.ifEmpty { it.fmFound },
                        )
                    }
                    st.freq?.let { f -> if (f != settings().radioFmFreq) settings().radioFmFreq = f }
                }
                delay(1000)
            }
        }
    }

    // ------------------------------------------------------------------ Internet

    fun loadStations(query: String? = null) {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { if (query.isNullOrBlank()) RadioBrowser.popular() else RadioBrowser.search(query) } }
            _state.update {
                it.copy(
                    loading = false,
                    stations = r.getOrNull() ?: it.stations,
                    error = r.exceptionOrNull()?.let { e -> "Sin conexión con el listado de emisoras (${e.message})" }
                        ?: if (r.getOrNull().isNullOrEmpty()) "No hay emisoras con ese nombre" else null,
                )
            }
        }
    }

    fun playStream(station: StreamStation) {
        if (_state.value.source == RadioSource.FM && _state.value.playing) scope.launch(Dispatchers.IO) { FactoryTuner.exit(app) }
        pollJob?.cancel()
        val p = ensurePlayer()
        requestFocus()
        val item = MediaItem.Builder()
            .setUri(station.url)
            .setMediaId(station.id.ifBlank { station.url })
            .setMediaMetadata(MediaMetadata.Builder().setTitle(station.name).setStation(station.name).setArtist("Radio por Internet").build())
            .build()
        p.setMediaItem(item)
        p.prepare()
        p.play()
        settings().radioLastStation = RadioLogic.favoritesToJson(listOf(station))
        settings().radioSource = RadioSource.INTERNET.name
        _state.update { it.copy(source = RadioSource.INTERNET, station = station, playing = true, buffering = true, nowPlaying = null, error = null) }
        scope.launch(Dispatchers.IO) { RadioBrowser.click(station.id) }
        // El servicio mantiene viva la reproducción con la app de fondo (Maps delante).
        runCatching { app.startService(Intent(app, RadioService::class.java)) }.onFailure { Log.w(TAG, "servicio", it) }
    }

    fun toggleFavorite(station: StreamStation) {
        val cur = _state.value.favorites
        val next = if (cur.any { it.id == station.id }) cur.filterNot { it.id == station.id } else cur + station
        settings().radioFavorites = RadioLogic.favoritesToJson(next)
        _state.update { it.copy(favorites = next) }
    }

    /** Siguiente / anterior dentro de favoritos (o del listado si no hay favoritos). */
    fun next(dir: Int) {
        val s = _state.value
        if (s.source == RadioSource.FM) {
            val list = s.fmFavorites.ifEmpty { s.fmFound }
            if (list.isEmpty()) { fmSeek(dir); return }
            val i = list.indexOfFirst { it.freq == s.fmFreq }
            playFm(list[((if (i < 0) -1 else i) + dir).mod(list.size)].freq)
            return
        }
        val list = s.favorites.ifEmpty { s.stations }
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.id == s.station?.id }
        playStream(list[((if (i < 0) -1 else i) + dir).mod(list.size)])
    }

    /** Para el asistente: «pon la radio», «pon los 40». Devuelve lo que contestar. */
    fun playByVoice(query: String?): String {
        val s = _state.value
        if (query.isNullOrBlank()) {
            play()
            return when {
                s.source == RadioSource.FM -> "Pongo la radio, ${RadioLogic.formatFm(s.fmFreq)}"
                s.station != null -> "Pongo ${s.station.name}"
                else -> "Elige una emisora en la pantalla de radio"
            }
        }
        // «pon la 102.3» o «pon el 98»
        Regex("(\\d{2,3})(?:[.,\\s]+(\\d{1,2}))?").find(query)?.let { m ->
            val mhz = m.groupValues[1].toInt()
            val dec = m.groupValues[2].padEnd(2, '0').ifBlank { "00" }.toInt()
            val f = mhz * 100 + dec
            if (f in RadioLogic.FM_MIN..RadioLogic.FM_MAX && s.tunerAvailable == true) {
                playFm(f); return "Sintonizo el ${RadioLogic.formatFm(f)}"
            }
        }
        RadioLogic.matchName(query, s.fmFavorites + s.fmFound) { it.name }?.takeIf { s.tunerAvailable == true && it.name.isNotBlank() }?.let {
            playFm(it.freq); return "Pongo ${it.name}"
        }
        RadioLogic.matchName(query, s.favorites + s.stations) { it.name }?.let {
            playStream(it); return "Pongo ${it.name}"
        }
        // No está en la lista: se busca en Internet y se pone la primera.
        scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { RadioBrowser.search(query) }.getOrNull() }.orEmpty()
            if (found.isNotEmpty()) {
                _state.update { it.copy(stations = found) }
                playStream(found.first())
            } else _state.update { it.copy(error = "No encuentro «$query»") }
        }
        return "Busco $query"
    }

    private fun ensurePlayer(): ExoPlayer = player ?: ExoPlayer.Builder(app)
        // El foco lo gestionamos nosotros (ver requestFocus) para bajar también la FM.
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
        .setHandleAudioBecomingNoisy(false)
        .build().also { p ->
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    _state.update { it.copy(buffering = state == Player.STATE_BUFFERING) }
                }
                override fun onMediaMetadataChanged(m: MediaMetadata) {
                    // Muchas emisoras mandan "artista - canción" en los metadatos ICY.
                    val t = m.title?.toString()?.takeIf { t -> t.isNotBlank() && t != _state.value.station?.name }
                    _state.update { it.copy(nowPlaying = t) }
                }
                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "reproductor", error)
                    _state.update { it.copy(playing = false, buffering = false, error = "No se puede reproducir esta emisora (${error.errorCodeName})") }
                    abandonFocus()
                }
            })
            player = p
        }

    /** El servicio multimedia usa el mismo reproductor. */
    internal fun playerFor(context: Context): ExoPlayer {
        if (!::app.isInitialized) app = context.applicationContext
        return ensurePlayer()
    }

    // ------------------------------------------------------------------ foco de audio

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        scope.launch {
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> duck(true)
                AudioManager.AUDIOFOCUS_GAIN -> duck(false)
                // Otra app de música (Spotify…) se queda el audio: la radio se para.
                AudioManager.AUDIOFOCUS_LOSS -> if (_state.value.playing) stop()
            }
        }
    }

    private fun requestFocus() {
        val am = app.getSystemService(AudioManager::class.java) ?: return
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(SysAttributes.Builder().setUsage(SysAttributes.USAGE_MEDIA).setContentType(SysAttributes.CONTENT_TYPE_MUSIC).build())
            // Bajamos nosotros el volumen (si no, Android lo baja solo y no nos enteramos para la FM).
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusListener)
            .build().also { focus = it }
        runCatching { am.requestAudioFocus(req) }
    }

    private fun abandonFocus() {
        val am = app.getSystemService(AudioManager::class.java) ?: return
        focus?.let { runCatching { am.abandonAudioFocusRequest(it) } }
    }

    /**
     * Baja o sube la radio. Internet: volumen del reproductor. FM: el audio no pasa por Android; lo
     * baja la propia radio con su "mezcla de navegación" (data.set.gps_mix_level) cuando suena la app
     * de navegación elegida en sus ajustes. Desde aquí no hay una orden de volumen conocida.
     */
    fun duck(on: Boolean) {
        if (_state.value.ducked == on) return
        _state.update { it.copy(ducked = on) }
        player?.volume = if (on) DUCK_VOLUME else 1f
    }
}
