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
 * Botón de voz del volante = «Hola Athos». No sabemos qué manda la radio al pulsarlo, así que se
 * atienden las tres vías posibles y cada una queda apuntada en Diagnóstico › Entradas:
 * - el aviso del servicio del coche «android.car.action.VOICE_BUTTON» (está en el lanzador de serie);
 * - una tecla de voz (VOICE_ASSIST, SEARCH, ASSIST) con la app delante;
 * - que la radio abra «la app de voz» elegida: [VoiceButtonActivity] responde a VOICE_COMMAND/ASSIST.
 */
object VoiceButton {
    private const val TAG = "VoiceButton"
    /** Nuestro aviso: el servicio del coche lo manda al pulsar el botón si [claim] ha funcionado. */
    const val OWN_ACTION = "com.cockpitf30.action.VOICE_BUTTON"
    val ACTIONS = listOf(
        OWN_ACTION,
        "android.car.action.VOICE_BUTTON",
        "android.car.action.VOICE_SHOW",
        "com.android.car.action.VOICE_BUTTON",
    )
    /** Teclas del coche en general: se apuntan todas (la de voz es la 26 del servicio del coche). */
    val KEY_ACTIONS = listOf("android.car.action.CAR_KEY", "android.car.action.MCU_KEY")
    /** Tecla de voz en CarInputManager.handleOtherKey del servicio del coche. */
    const val CAR_KEY_VOICE = 26
    /**
     * Ajuste del sistema que decide qué hace la tecla de voz: «tipo/paquete/acción», tipo 0 =
     * mandar el aviso «acción» al paquete, 1 = abrir el paquete (CarInputManager del servicio).
     */
    const val VOICE_APP_KEY = "data.set.voice_app"
    val VOICE_APP_VALUE = "0/com.cockpitf30/$OWN_ACTION"

    /** Último resultado de [claim] (para Diagnóstico). */
    @Volatile var claimNote: String = "sin comprobar"
        private set
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
        // Todas las teclas que reparte el servicio del coche, para ver qué manda el botón.
        val keys = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = onCarKey(app, intent)
        }
        runCatching { ContextCompat.registerReceiver(app, keys, IntentFilter().apply { KEY_ACTIONS.forEach { addAction(it) } }, ContextCompat.RECEIVER_EXPORTED) }
        Thread({ claim(app) }, "boton-voz").start()
    }

    /**
     * Pide al servicio del coche que la tecla de voz nos mande [OWN_ACTION] (ajuste
     * [VOICE_APP_KEY]). Sin esto el servicio no hace nada con ella (o abre otra app de voz).
     */
    fun claim(context: Context) {
        claimNote = runCatching {
            val car = com.cockpitf30.vehicle.source.VendorCar.connect(context).getOrThrow()
            val before = car.getString(VOICE_APP_KEY)
            if (before == VOICE_APP_VALUE) return@runCatching "tecla de voz ya asignada a la app ($VOICE_APP_KEY)"
            val ok = car.setString(VOICE_APP_KEY, VOICE_APP_VALUE)
            val after = car.getString(VOICE_APP_KEY)
            "tecla de voz: $VOICE_APP_KEY «${before ?: "∅"}» → «${after ?: "∅"}» (${if (ok) "aceptado" else "rechazado"})"
        }.getOrElse { "no se pudo asignar la tecla de voz: ${it.message ?: it.javaClass.simpleName}" }
        VehicleRepository.logInput("Botón de voz: $claimNote")
        Log.i(TAG, claimNote)
    }

    private fun describe(intent: Intent): String {
        val b = intent.extras ?: return intent.action.orEmpty()
        return intent.action + " (" + b.keySet().joinToString { k -> "$k=${b.get(k)}" } + ")"
    }

    /** Tecla del coche (CAR_KEY/MCU_KEY): se apunta siempre; si es la de voz, cuenta como pulsación. */
    fun onCarKey(context: Context, intent: Intent) {
        val text = describe(intent)
        VehicleRepository.logInput("Tecla del coche: $text")
        val b = intent.extras ?: return
        val isVoice = intent.action == "android.car.action.CAR_KEY" &&
            b.keySet().any { k -> (b.get(k) as? Int) == CAR_KEY_VOICE && !k.contains("action", true) && !k.contains("state", true) }
        if (isVoice) press(context, "tecla del coche $text")
    }

    /** Tecla con la app delante: true si era la de voz (y se ha atendido). */
    fun onKey(context: Context, e: KeyEvent): Boolean {
        if (e.keyCode !in KEYS) return false
        if (e.action == KeyEvent.ACTION_UP && e.repeatCount == 0) press(context, "tecla ${KeyEvent.keyCodeToString(e.keyCode)}")
        return true
    }

    /** Como decir «Hola Athos» (o tocar el asistente). Dos avisos seguidos del mismo toque cuentan una vez. */
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

/** El aviso del botón de voz llega aunque la app no esté abierta (el servicio lo manda a nuestro paquete). */
class VoiceButtonReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in VoiceButton.KEY_ACTIONS) VoiceButton.onCarKey(context, intent)
        else VoiceButton.press(context, "aviso ${intent.action} (receptor)")
    }
}
