package com.cockpitf30.system

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * El permiso REQUEST_IGNORE_BATTERY_OPTIMIZATIONS solo deja mostrar el diálogo;
 * la exclusión la concede el usuario en él.
 */
object BatteryOptimization {
    // Algunas ROMs (y el renderizador de capturas) no tienen el servicio: se toma como no excluida.
    fun isIgnoring(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    @SuppressLint("BatteryLife")
    fun request(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        try {
            context.startActivity(direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // Algunas ROMs chinas quitan este diálogo; abrimos la lista general.
            runCatching {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
