package com.cockpitf30.vehicle.probe

import java.io.File
import java.io.FileInputStream

/**
 * Prueba cada puerto serie: si se puede abrir y si llega algún byte en un par de segundos.
 * Sirve para saber si el decodificador habla por un UART accesible o si la radio no nos deja.
 */
object PortProbe {
    private val PREFIXES = listOf("ttyMT", "ttyS", "ttyGS", "ttyUSB", "ttyACM", "ttyHS", "ttyAMA", "ttyV")

    /** Puertos de /dev con nombres típicos; si /dev no se puede listar, los habituales de MediaTek. */
    fun candidates(): List<File> {
        val listed = File("/dev").listFiles { f -> PREFIXES.any { f.name.startsWith(it) } }
        if (listed != null && listed.isNotEmpty()) return listed.sortedBy { it.name }
        return ((0..3).map { File("/dev/ttyMT$it") } + (0..7).map { File("/dev/ttyS$it") }).filter { it.exists() }
    }

    fun devListable(): Boolean = File("/dev").listFiles() != null

    fun describe(f: File): String = "${f.name} ${if (f.canRead()) "r" else "-"}${if (f.canWrite()) "w" else "-"}"

    /** Abre [file] y escucha [millis] ms. Devuelve una línea legible con el resultado. */
    fun probe(file: File, millis: Long = 2_000): String {
        val input = try {
            FileInputStream(file)
        } catch (e: Exception) {
            return "${file.path}: no se puede abrir · ${e.message ?: e.javaClass.simpleName}"
        }
        val got = java.io.ByteArrayOutputStream()
        var error: String? = null
        // read() bloquea; lo hacemos en otro hilo y cerramos el fichero al acabar el tiempo.
        val reader = Thread {
            val buf = ByteArray(256)
            try {
                while (got.size() < 4096) {
                    val n = input.read(buf)
                    if (n < 0) break
                    synchronized(got) { got.write(buf, 0, n) }
                }
            } catch (e: Exception) {
                if (!Thread.currentThread().isInterrupted) error = e.message ?: e.javaClass.simpleName
            }
        }.apply { isDaemon = true; start() }
        reader.join(millis)
        reader.interrupt()
        runCatching { input.close() }
        val bytes = synchronized(got) { got.toByteArray() }
        return buildString {
            append("${file.path}: abierto · ${bytes.size} bytes en ${millis / 1000} s")
            if (bytes.isNotEmpty()) append(" · ").append(hex(bytes.copyOf(minOf(bytes.size, 48))))
            error?.let { append(" · error de lectura: ").append(it) }
        }
    }

    fun hex(bytes: ByteArray, count: Int = bytes.size): String =
        (0 until count).joinToString(" ") { "%02X".format(bytes[it].toInt() and 0xFF) }
}
