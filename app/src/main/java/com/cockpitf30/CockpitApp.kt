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
    }

    companion object {
        const val CHANNEL_VEHICLE = "vehicle"
    }
}
