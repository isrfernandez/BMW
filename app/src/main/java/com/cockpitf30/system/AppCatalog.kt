package com.cockpitf30.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AppEntry(val key: String, val label: String, val pkg: String, val activity: String, val icon: ImageBitmap)

/**
 * Lista de apps instaladas con sus iconos, cargada en segundo plano al arrancar y guardada en
 * memoria. Antes se leía (y se pintaba cada icono) al abrir la pantalla Apps, en el hilo de la
 * interfaz: de ahí el tirón. Se vuelve a leer sola cuando se instala o desinstala algo.
 */
object AppCatalog {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val _apps = MutableStateFlow<Map<String, AppEntry>?>(null)
    val apps: StateFlow<Map<String, AppEntry>?> = _apps.asStateFlow()
    private var watching = false

    fun load(context: Context) {
        val app = context.applicationContext
        scope.launch { lock.withLock { _apps.value = read(app) } }
        if (!watching) {
            watching = true
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            }
            ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = load(app)
            }, filter, ContextCompat.RECEIVER_EXPORTED)
        }
    }

    private fun read(context: Context): Map<String, AppEntry> {
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .mapNotNull {
                runCatching {
                    val key = "${it.activityInfo.packageName}/${it.activityInfo.name}"
                    AppEntry(key, it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name, it.loadIcon(pm).toBitmap(128, 128).asImageBitmap())
                }.getOrNull()
            }
            .sortedBy { it.label.lowercase() }
            .associateBy { it.key }
    }
}
