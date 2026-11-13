package com.cockpitf30.assistant

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.cockpitf30.vehicle.VehicleRepository

/**
 * Botón de voz del volante = «Hola BMW». No sabemos qué manda la radio al pulsarlo, así que se
 * atienden las tres vías posibles y cada una queda apuntada en Diagnóstico › Entradas:
 * - el aviso del servicio del coche «android.car.action.VOICE_BUTTON» (está en el lanzador de serie);
 * - una tecla de voz (VOICE_ASSIST, SEARCH, ASSIST) con la app delante;
 * - que la radio abra «la app de voz» elegida: [VoiceButtonActivity] responde a VOICE_COMMAND/ASSIST.
 */
object VoiceButton {
    private const val TAG = "VoiceButton"
    val ACTIONS = listOf(
        "android.car.action.VOICE_BUTTON",
        "android.car.action.VOICE_SHOW",
        "com.android.car.action.VOICE_BUTTON",
    )
    val KEYS = setOf(KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_ASSIST)

    private var lastAt = 0L

    fun register(context: Context) {
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val extras = intent.extras?.keySet()?.joinToString { k -> "$k=${intent.extras?.get(k)}" }.orEmpty()
                press(app, "aviso ${intent.action}" + if (extras.isNotEmpty()) " ($extras)" else "")
            }
        }
        val filter = IntentFilter().apply { ACTIONS.forEach { addAction(it) } }
        runCatching { ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED) }
            .onFailure { Log.w(TAG, "No se puede escuchar el botón de voz", it) }
    }

    /** Tecla con la app delante: true si era la de voz (y se ha atendido). */
    fun onKey(context: Context, e: KeyEvent): Boolean {
        if (e.keyCode !in KEYS) return false
        if (e.action == KeyEvent.ACTION_UP && e.repeatCount == 0) press(context, "tecla ${KeyEvent.keyCodeToString(e.keyCode)}")
        return true
    }

    /** Como decir «Hola BMW» (o tocar el asistente). Dos avisos seguidos del mismo toque cuentan una vez. */
    fun press(context: Context, source: String) {
        VehicleRepository.logInput("Botón de voz: $source")
        val now = System.currentTimeMillis()
        if (now - lastAt < 800) return
        lastAt = now
        Log.i(TAG, "Botón de voz: $source")
        VoiceAssistant.tap(context)
    }
}

/** La radio puede abrir «la app de voz» con el botón del volante: se atiende y se cierra sin verse. */
class VoiceButtonActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VoiceButton.press(this, "app de voz (${intent?.action})")
        finish()
        overridePendingTransition(0, 0)
    }
}
