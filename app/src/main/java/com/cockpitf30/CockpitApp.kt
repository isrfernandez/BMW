package com.cockpitf30

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class CockpitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // IMPORTANCE_MIN oculta el icono de la barra de estado en la mayoría de ROMs.
        val channel = NotificationChannel(CHANNEL_VEHICLE, getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN)
            .apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        com.cockpitf30.nav.MapTiles.init(this)
        com.cockpitf30.radio.Radio.init(this)
        com.cockpitf30.radio.AssistantDuck.start(this)
        com.cockpitf30.assistant.VoiceButton.register(this)
        // Radares: aviso hablado al acercarse a uno que queda delante. Va aquí (y no en la pantalla)
        // para que avise también con Maps delante o con la actividad recreada.
        com.cockpitf30.assistant.VoiceAssistant.attach(this)
        com.cockpitf30.nav.SpeedCameras.onAlert = { v ->
            if (com.cockpitf30.system.AppSettings(this).alertOn(com.cockpitf30.nav.AlertCategory.RADAR)) {
                val limit = v.camera.maxspeed?.let { ", límite $it" } ?: ""
                val what = if (v.camera.kind == "Tramo") "Tramo de radar" else "Radar"
                val dist = com.cockpitf30.ui.components.radarDistance(v.meters).replace(" m", " metros").replace(" km", " kilómetros")
                com.cockpitf30.assistant.VoiceAssistant.announce(urgent = true, text = "$what a $dist$limit")
            } else com.cockpitf30.assistant.VoiceAssistant.note("radar sin aviso: desactivado en Ajustes")
        }
    }

    companion object {
        const val CHANNEL_VEHICLE = "vehicle"
    }
}
