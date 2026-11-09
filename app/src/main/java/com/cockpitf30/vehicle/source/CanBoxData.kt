package com.cockpitf30.vehicle.source

import android.os.Bundle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Lo que llega de la caja CAN por el aviso "callback.pt.canbus_cmd" del servicio del coche:
 * se decodifica con [CanBoxFrames] y se guarda como claves "can.*" que [RadioCarSource] mezcla
 * con el resto de datos de la radio.
 */
object CanBoxData {
    const val CANBUS_CMD = "callback.pt.canbus_cmd"

    private class Stat(var count: Long, var last: String)

    private val values = ConcurrentHashMap<String, Any>()
    private val stats = ConcurrentHashMap<Int, Stat>()
    private val bad = AtomicLong()
    private val time = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val lastPayload = ConcurrentHashMap<Int, String>()
    private val changeLog = java.util.concurrent.ConcurrentLinkedDeque<String>()

    /**
     * Cada vez que cambia el contenido de una trama (menos 0x16, que cambia sin parar), con la hora:
     * abriendo las puertas de una en una se ve qué bit es cada una.
     */
    fun changes(): List<String> = changeLog.toList()

    /** true en cuanto llega una trama 0x12 válida: la caja habla este protocolo y se le puede preguntar. */
    @Volatile var baseSeen = false
        private set

    @Volatile var onFrame: (() -> Unit)? = null

    fun isCanbusCmd(raw: List<Any?>) = raw.firstOrNull() == CANBUS_CMD

    /** Argumentos de un aviso del servicio. Devuelve true si era una trama de la caja (ya procesada). */
    fun handle(raw: List<Any?>): Boolean {
        if (!isCanbusCmd(raw)) return false
        val bytes = raw.filterIsInstance<Bundle>().firstNotNullOfOrNull { it.getByteArray("arg_byte_array") } ?: return true
        feed(bytes)
        return true
    }

    fun feed(bytes: ByteArray) {
        val frames = CanBoxFrames.split(bytes) { bad.incrementAndGet() }
        for (f in frames) {
            val hex = f.hex()
            stats.getOrPut(f.type) { Stat(0, "") }.apply { count++; last = hex }
            if (f.type != CanBoxFrames.TYPE_DYNAMIC && lastPayload.put(f.type, hex) != hex) {
                changeLog.addFirst("${java.time.LocalTime.now().format(time)}  $hex")
                while (changeLog.size > 300) changeLog.pollLast()
            }
            CanBoxFrames.decode(f).forEach { (k, v) -> values[k] = v }
            if (f.type == CanBoxFrames.TYPE_BASE) baseSeen = true
        }
        if (frames.isNotEmpty()) onFrame?.invoke()
    }

    fun values(): Map<String, Any?> = HashMap(values)

    /** Para Diagnóstico y el informe: cuántas tramas de cada tipo y la última. */
    fun summary(): List<String> = buildList {
        stats.toSortedMap().forEach { (t, s) -> add("0x%02X ×%d  %s".format(t, s.count, s.last)) }
        if (bad.get() > 0) add("suma incorrecta ×${bad.get()}")
    }

    fun framesTotal(): Long = stats.values.sumOf { it.count }
}
