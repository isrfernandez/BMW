package com.cockpitf30.radio

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.cockpitf30.ui.MainActivity

/**
 * Servicio multimedia de la radio por Internet. Con él Android sabe que hay audio sonando: no
 * cierra la app cuando Maps está delante, enseña la emisora en la notificación y los mandos
 * multimedia (volante, Bluetooth) llegan a la radio.
 */
class RadioService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, Radio.playerFor(this)).setSessionActivity(open).build().also { addSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        // El reproductor es de [Radio]: solo se suelta la sesión.
        session?.release()
        session = null
        super.onDestroy()
    }
}
