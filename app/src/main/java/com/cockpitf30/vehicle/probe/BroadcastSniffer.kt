package com.cockpitf30.vehicle.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Escucha las acciones de broadcast que declaran las apps del CAN/MCU y apunta cada una
 * que llegue con sus extras. Si la radio reparte puertas, velocidad, etc. por broadcast,
 * aparecerán aquí al abrir una puerta o arrancar.
 */
object BroadcastSniffer {
    private const val LOG_SIZE = 80

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _listening = MutableStateFlow(0)
    /** Número de acciones escuchadas; 0 = parado. */
    val listening: StateFlow<Int> = _listening.asStateFlow()

    private var receiver: BroadcastReceiver? = null

    fun start(context: Context, actions: Collection<String>) {
        stop(context)
        // Las del sistema (arranque, pantalla, red…) solo meten ruido.
        val wanted = actions.filterNot { it.startsWith("android.") || it.startsWith("androidx.") || it.startsWith("com.android.vending") }.distinct().take(500)
        if (wanted.isEmpty()) return
        val filter = IntentFilter().apply { wanted.forEach(::addAction) }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val line = "${intent.action}${intent.`package`?.let { " pkg=$it" }.orEmpty()} ${describe(intent.extras)}"
                _log.update { (listOf(line) + it).take(LOG_SIZE) }
            }
        }
        ContextCompat.registerReceiver(context.applicationContext, r, filter, ContextCompat.RECEIVER_EXPORTED)
        receiver = r
        _listening.value = wanted.size
    }

    fun stop(context: Context) {
        receiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        receiver = null
        _listening.value = 0
    }

    @Suppress("DEPRECATION")
    fun describe(extras: Bundle?): String {
        extras ?: return "{}"
        return extras.keySet().joinToString(", ", "{", "}") { key ->
            val v = runCatching { extras.get(key) }.getOrNull()
            "$key=" + when (v) {
                is ByteArray -> PortProbe.hex(v, minOf(v.size, 64))
                is IntArray -> v.joinToString(",", "[", "]")
                is FloatArray -> v.joinToString(",", "[", "]")
                is Array<*> -> v.joinToString(",", "[", "]")
                else -> v.toString()
            }
        }
    }
}
