package com.cockpitf30.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.cockpitf30.CockpitApp
import com.cockpitf30.R
import com.cockpitf30.system.AppSettings
import com.cockpitf30.system.SourceMode
import com.cockpitf30.ui.MainActivity
import com.cockpitf30.vehicle.VehicleRepository
import com.cockpitf30.vehicle.trip.TripComputer
import com.cockpitf30.vehicle.source.CarPropertySource
import com.cockpitf30.vehicle.source.DemoSource
import com.cockpitf30.vehicle.source.RadioCarSource
import com.cockpitf30.vehicle.source.SerialSource
import com.cockpitf30.vehicle.source.VehicleDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Servicio en primer plano que mantiene viva la lectura del coche aunque se abra
 * Maps, Spotify, etc. encima.
 */
class VehicleService : LifecycleService() {
    private var source: VehicleDataSource? = null
    private var sourceScope: CoroutineScope? = null
    private val tripScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        goForeground()
        TripComputer.start(this, tripScope)
        com.cockpitf30.vehicle.telemetry.Telemetry.start(this, tripScope)
        com.cockpitf30.nav.SpeedCameras.start(this, tripScope)
        com.cockpitf30.nav.RoutePois.start(this, tripScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Arrancado desde la app en pantalla: se vuelve a declarar el servicio, ahora también con
        // micrófono, para que «Hola BMW» siga funcionando con Maps delante.
        if (intent?.getBooleanExtra(EXTRA_FROM_UI, false) == true) goForeground()
        if (source == null || intent?.action == ACTION_RESTART) restartSource()
        return START_STICKY
    }

    override fun onDestroy() {
        stopSource()
        tripScope.cancel()
        TripComputer.stop()
        com.cockpitf30.vehicle.telemetry.Telemetry.stop()
        com.cockpitf30.nav.SpeedCameras.stop()
        com.cockpitf30.nav.RoutePois.stop()
        super.onDestroy()
    }

    /**
     * Servicio en primer plano. Con el permiso de micrófono se declara también de tipo micrófono:
     * desde Android 11 es lo que deja escuchar con otra app delante. Android 14 solo lo acepta si
     * la app está en pantalla (desde el arranque del coche fallaría), así que si falla se queda sin él.
     */
    private fun goForeground() {
        val notification = buildNotification()
        val special = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        val mic = Build.VERSION.SDK_INT >= 30 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            AppSettings(this).listenOutsideApp
        if (mic) {
            val ok = runCatching {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, special or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            }.isSuccess
            if (ok) return
        }
        // Antes de Android 14 el tipo specialUse no existe: basta con el del manifiesto (0 = todos los declarados,
        // y en Android 10-13 el micrófono solo se concede si se arrancó con la app en pantalla).
        val type = if (Build.VERSION.SDK_INT >= 34) special else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type) }
    }

    private fun restartSource() {
        stopSource()
        val next = createSource(AppSettings(this))
        VehicleRepository.reset(next.name)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        next.start(scope)
        source = next
        sourceScope = scope
    }

    private fun stopSource() {
        source?.stop()
        sourceScope?.cancel()
        source = null
        sourceScope = null
    }

    private fun createSource(settings: AppSettings): VehicleDataSource = when (settings.sourceMode) {
        SourceMode.DEMO -> DemoSource()
        SourceMode.CAR_PROPERTY -> CarPropertySource(this)
        SourceMode.RADIO -> RadioCarSource(this)
        SourceMode.SERIAL -> SerialSource(settings.serialPath, settings.baudRate)
        SourceMode.AUTO -> when {
            CarPropertySource.isAvailable(this) -> CarPropertySource(this)
            RadioCarSource.isAvailable(this) -> RadioCarSource(this)
            else -> SerialSource(settings.serialPath, settings.baudRate)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CockpitApp.CHANNEL_VEHICLE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_RESTART = "com.cockpitf30.RESTART_SOURCE"
        private const val EXTRA_FROM_UI = "from_ui"

        /** Desde la app en pantalla (también cuando vuelve delante): así puede usar el micrófono después. */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, VehicleService::class.java).putExtra(EXTRA_FROM_UI, true))
            }
        }

        /** Desde el arranque del coche (la app aún no está en pantalla). */
        fun startAtBoot(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, VehicleService::class.java))
        }

        /** Vuelve a crear la fuente tras cambiar los ajustes. */
        fun restart(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, VehicleService::class.java).setAction(ACTION_RESTART),
            )
        }
    }
}
