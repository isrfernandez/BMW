package com.cockpitf30.radio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.cockpitf30.assistant.AssistantPhase
import com.cockpitf30.assistant.VoiceAssistant
import com.cockpitf30.system.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Mientras el asistente escucha una orden o contesta, pide el foco de audio "transitorio, se puede
 * bajar", igual que Google Maps con sus indicaciones: la radio de la app (y Spotify u otras apps
 * de música) baja el volumen y vuelve a subirlo al terminar.
 */
object AssistantDuck {
    private var started = false
    private var request: AudioFocusRequest? = null

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val am = app.getSystemService(AudioManager::class.java) ?: return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { }
            .build()
        request = req
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            VoiceAssistant.state.map { it.phase == AssistantPhase.LISTENING || it.phase == AssistantPhase.ANSWERING }
                .distinctUntilChanged()
                .collect { active ->
                    if (active && AppSettings(app).radioDuckAssistant) runCatching { am.requestAudioFocus(req) }
                    else runCatching { am.abandonAudioFocusRequest(req) }
                }
        }
    }
}
