package com.cockpitf30.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Arranca el servicio al encender. Ojo: muchas unidades TopWay no reinician al quitar el
 * contacto (entran en suspensión), así que el servicio sigue vivo gracias a START_STICKY.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return
        VehicleService.startAtBoot(context)
    }

    private companion object {
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
