package com.cockpitf30.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Acción de navegación independiente de cómo la mande la placa. */
enum class NavAction { NEXT, PREVIOUS, UP, DOWN, LEFT, RIGHT, LONG_PRESS }

/**
 * Lo que se puede asignar a un botón del iDrive desde Diagnóstico. Cada uno se traduce a la
 * tecla estándar de Android que el resto de la app ya entiende.
 */
enum class KnobFunction(val label: String, val keyCode: Int) {
    DOWN("Abajo", KeyEvent.KEYCODE_DPAD_DOWN),
    UP("Arriba", KeyEvent.KEYCODE_DPAD_UP),
    LEFT("Izquierda", KeyEvent.KEYCODE_DPAD_LEFT),
    RIGHT("Derecha", KeyEvent.KEYCODE_DPAD_RIGHT),
    OK("OK", KeyEvent.KEYCODE_DPAD_CENTER),
    BACK("Atrás", KeyEvent.KEYCODE_BACK),
    NEXT("Girar →", KeyEvent.KEYCODE_NAVIGATE_NEXT),
    PREVIOUS("Girar ←", KeyEvent.KEYCODE_NAVIGATE_PREVIOUS),
    ;

    companion object {
        fun ofKeyCode(code: Int) = entries.firstOrNull { it.keyCode == code }
    }
}

/**
 * Traduce lo que llegue del iDrive a acciones de foco.
 *
 * No sabemos aún cómo entrega la ZQ9109 la ruleta. Cubrimos los casos típicos:
 * - Encoder rotatorio de Android: SOURCE_ROTARY_ENCODER con AXIS_SCROLL (no AXIS_VSCROLL).
 * - Rueda de ratón emulada: ACTION_SCROLL con AXIS_VSCROLL.
 * - Teclas: DPAD, TAB, NAVIGATE_NEXT/PREVIOUS, PAGE_UP/DOWN. ENTER y DPAD_CENTER los gestiona Compose.
 * - Cualquier otra tecla que mande la radio se puede "enseñar" desde Diagnóstico ([learned]):
 *   se traduce a la tecla estándar antes de procesarla.
 */
object IDriveInput {

    /** Teclas aprendidas: código que manda la radio → código estándar de [KnobFunction]. */
    @Volatile var learned: Map<Int, Int> = emptyMap()

    private val _learning = MutableStateFlow<KnobFunction?>(null)
    /** Función que se está aprendiendo: la próxima tecla que llegue se le asigna. */
    val learning: StateFlow<KnobFunction?> = _learning.asStateFlow()

    fun startLearning(function: KnobFunction?) {
        _learning.value = function
    }

    /**
     * Si estamos aprendiendo y llega una tecla, la asigna y devuelve el nuevo mapa
     * (para guardarlo). Devuelve null si no había nada que aprender.
     */
    fun learn(event: KeyEvent): Map<Int, Int>? {
        val function = _learning.value ?: return null
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return null
        _learning.value = null
        // Si la tecla ya es la estándar, no hace falta traducción; quitamos una posible anterior.
        learned = if (event.keyCode == function.keyCode) learned - event.keyCode
        else learned + (event.keyCode to function.keyCode)
        return learned
    }

    /** Copia del evento con el código traducido, o el mismo evento si no hay traducción. */
    fun translate(event: KeyEvent): KeyEvent {
        val target = learned[event.keyCode] ?: return event
        return KeyEvent(
            event.downTime, event.eventTime, event.action, target, event.repeatCount,
            event.metaState, event.deviceId, event.scanCode, event.flags, event.source,
        )
    }

    fun fromMotion(event: MotionEvent): NavAction? {
        val value = when {
            event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER) -> event.getAxisValue(MotionEvent.AXIS_SCROLL)
            event.action == MotionEvent.ACTION_SCROLL -> event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            else -> return null
        }
        return when {
            // En rueda de ratón, positivo = "hacia arriba"; lo tratamos como giro a la izquierda.
            value > 0 -> if (event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) NavAction.NEXT else NavAction.PREVIOUS
            value < 0 -> if (event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) NavAction.PREVIOUS else NavAction.NEXT
            else -> null
        }
    }

    /** OK/centro del iDrive mantenido: la primera repetición de la tecla marca la pulsación larga. */
    fun isCenter(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A ||
            keyCode == KeyEvent.KEYCODE_BUTTON_SELECT

    /** Teclas de navegación que gestionamos nosotros (en DOWN y en UP), no Compose. */
    fun navActionFor(keyCode: Int, shift: Boolean = false): NavAction? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN -> NavAction.DOWN
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP -> NavAction.UP
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT -> NavAction.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT -> NavAction.RIGHT
        KeyEvent.KEYCODE_TAB -> if (shift) NavAction.PREVIOUS else NavAction.NEXT
        KeyEvent.KEYCODE_NAVIGATE_NEXT, KeyEvent.KEYCODE_PAGE_DOWN -> NavAction.NEXT
        KeyEvent.KEYCODE_NAVIGATE_PREVIOUS, KeyEvent.KEYCODE_PAGE_UP -> NavAction.PREVIOUS
        else -> null
    }

    fun fromKey(event: KeyEvent): NavAction? {
        if (event.action != KeyEvent.ACTION_DOWN) return null
        if (isCenter(event.keyCode)) {
            return if (event.repeatCount == 1 || (event.repeatCount > 0 && event.isLongPress)) NavAction.LONG_PRESS else null
        }
        return navActionFor(event.keyCode, event.isShiftPressed)
    }

    fun describe(event: MotionEvent): String =
        "Motion src=0x%08X act=%d scroll=%.2f vscroll=%.2f dev=%s".format(
            event.source, event.actionMasked,
            event.getAxisValue(MotionEvent.AXIS_SCROLL), event.getAxisValue(MotionEvent.AXIS_VSCROLL),
            event.device?.name ?: "?",
        )

    fun describe(event: KeyEvent): String {
        val mapped = learned[event.keyCode]?.let { " → ${KnobFunction.ofKeyCode(it)?.label ?: KeyEvent.keyCodeToString(it)}" }.orEmpty()
        return "Key ${KeyEvent.keyCodeToString(event.keyCode)} (${event.keyCode}) " +
            (if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP") +
            (if (event.repeatCount > 0) " rep=${event.repeatCount}" else "") +
            " scan=${event.scanCode} src=0x%08X dev=${event.device?.name ?: "?"}".format(event.source) + mapped
    }

    /** "k1:t1,k2:t2" ↔ mapa, para guardarlo en los ajustes. */
    fun encode(map: Map<Int, Int>): String = map.entries.joinToString(",") { "${it.key}:${it.value}" }

    fun decode(text: String?): Map<Int, Int> =
        text.orEmpty().split(',').mapNotNull { pair ->
            val (k, v) = pair.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val key = k.trim().toIntOrNull() ?: return@mapNotNull null
            val value = v.trim().toIntOrNull() ?: return@mapNotNull null
            key to value
        }.toMap()
}
