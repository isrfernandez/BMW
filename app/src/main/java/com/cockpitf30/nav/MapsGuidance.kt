package com.cockpitf30.nav

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Siguiente indicación de Google Maps mientras navega. */
data class MapsGuidance(
    /** Lo grande de la notificación: normalmente la distancia («300 m») o la maniobra. */
    val title: String,
    /** La calle o la maniobra («Gira a la derecha hacia Calle Mayor»). */
    val text: String?,
    /** Tiempo, distancia y hora de llegada («12 min · 8,4 km · llegada 14:32»). */
    val eta: String?,
    /** Flecha de la maniobra que dibuja Maps. */
    val arrow: Bitmap?,
    val at: Long,
)

/**
 * Indicaciones de Google Maps dentro de la app: Maps no se puede incrustar (es otra app y Android
 * no deja meter su ventana en la nuestra), pero mientras navega publica una notificación con el
 * siguiente giro, la flecha y la llegada. Con el acceso a notificaciones concedido (Ajustes ›
 * Junto a Google Maps) se leen aquí y se enseñan en el cockpit y en el asistente.
 */
object MapsGuidanceState {
    private val _current = MutableStateFlow<MapsGuidance?>(null)
    val current: StateFlow<MapsGuidance?> = _current.asStateFlow()

    internal fun set(g: MapsGuidance?) { _current.value = g }

    /** ¿Tiene la app acceso a las notificaciones? */
    fun hasAccess(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(context, MapsGuidanceListener::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** Abre la pantalla del sistema donde se da el acceso (algunas radios la esconden). */
    fun requestAccess(context: Context): Boolean = runCatching {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    /** Frase para el asistente: «A 300 metros, gira a la derecha…; llegada a las 14:32». */
    fun spoken(g: MapsGuidance): String {
        val all = listOfNotNull(g.title, g.text).joinToString(", ") + (g.eta?.let { ". $it" } ?: "")
        return all
            .replace(Regex("""(\d+(?:[.,]\d+)?)\s?km\b""")) { "${it.groupValues[1]} kilómetros" }
            .replace(Regex("""(\d+)\s?m\b""")) { "${it.groupValues[1]} metros" }
            .replace(Regex("""(\d+)\s?min\b""")) { "${it.groupValues[1]} minutos" }
            .replace(" · ", ", ")
    }
}

class MapsGuidanceListener : NotificationListenerService() {
    override fun onListenerConnected() {
        runCatching { activeNotifications?.forEach { onNotificationPosted(it) } }
    }

    override fun onListenerDisconnected() = MapsGuidanceState.set(null)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn?.takeIf { it.packageName == GOOGLE_MAPS }?.notification ?: return
        if (!isNavigation(n)) return
        val e = n.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim()
        val eta = e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
        if (title.isEmpty() && text.isNullOrEmpty()) return
        val arrow = runCatching { iconBitmap(n.getLargeIcon()) }.getOrNull()
        MapsGuidanceState.set(MapsGuidance(title.ifEmpty { text.orEmpty() }, text.takeIf { title.isNotEmpty() }, eta?.takeIf { it.isNotEmpty() }, arrow, System.currentTimeMillis()))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val n = sbn?.takeIf { it.packageName == GOOGLE_MAPS }?.notification ?: return
        if (isNavigation(n)) MapsGuidanceState.set(null)
    }

    /** La de navegación es la fija («ongoing») o la de categoría navegación. */
    private fun isNavigation(n: Notification) =
        n.category == Notification.CATEGORY_NAVIGATION || (n.flags and Notification.FLAG_ONGOING_EVENT) != 0

    private fun iconBitmap(icon: Icon?): Bitmap? {
        val d = icon?.loadDrawable(this) ?: return null
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: 96
        val h = d.intrinsicHeight.takeIf { it > 0 } ?: 96
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        d.setBounds(0, 0, w, h)
        d.draw(c)
        return b
    }

    private companion object {
        const val GOOGLE_MAPS = "com.google.android.apps.maps"
    }
}
