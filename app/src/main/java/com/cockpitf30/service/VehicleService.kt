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
        // El tipo specialUse solo existe desde Android 14; antes basta con el del manifiesto.
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        TripComputer.start(this, tripScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (source == null || intent?.action == ACTION_RESTART) restartSource()
        return START_STICKY
    }

    override fun onDestroy() {
        stopSource()
        tripScope.cancel()
        TripComputer.stop()
        super.onDestroy()
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
        SourceMode.SERIAL -> SerialSource(settings.serialPath, settings.baudRate)
        SourceMode.AUTO ->
            if (CarPropertySource.isAvailable(this)) CarPropertySource(this)
            else SerialSource(settings.serialPath, settings.baudRate)
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

        fun start(context: Context) {
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
