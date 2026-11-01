package com.cockpitf30.vehicle.probe

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lee la tabla de cadenas de un AndroidManifest.xml compilado (XML binario de Android).
 * No interpreta el árbol: solo saca las cadenas, que es donde aparecen los nombres de las
 * acciones de broadcast, servicios y authorities que declara una app del sistema.
 */
object AxmlStrings {
    private const val CHUNK_XML = 0x0003
    private const val CHUNK_STRING_POOL = 0x0001
    private const val FLAG_UTF8 = 0x100

    fun parse(bytes: ByteArray): List<String> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 8 || (buf.getShort(0).toInt() and 0xFFFF) != CHUNK_XML) return emptyList()
        val pool = buf.getShort(2).toInt() and 0xFFFF // tras la cabecera del XML va la tabla
        if (pool + 28 > bytes.size || (buf.getShort(pool).toInt() and 0xFFFF) != CHUNK_STRING_POOL) return emptyList()
        val headerSize = buf.getShort(pool + 2).toInt() and 0xFFFF
        val count = buf.getInt(pool + 8)
        val flags = buf.getInt(pool + 16)
        val stringsStart = pool + buf.getInt(pool + 20)
        val utf8 = flags and FLAG_UTF8 != 0
        val out = ArrayList<String>(count.coerceIn(0, 10_000))
        for (i in 0 until count.coerceAtMost(10_000)) {
            val offsetPos = pool + headerSize + i * 4
            if (offsetPos + 4 > bytes.size) break
            val at = stringsStart + buf.getInt(offsetPos)
            runCatching { if (utf8) readUtf8(bytes, at) else readUtf16(buf, at) }.getOrNull()?.let(out::add)
        }
        return out
    }

    private fun readUtf8(bytes: ByteArray, start: Int): String {
        var p = start
        // Primero la longitud en UTF-16 (que no usamos) y luego la longitud en bytes; 1 o 2 bytes cada una.
        p += if (bytes[p].toInt() and 0x80 != 0) 2 else 1
        var len = bytes[p].toInt() and 0xFF
        if (len and 0x80 != 0) {
            len = ((len and 0x7F) shl 8) or (bytes[p + 1].toInt() and 0xFF)
            p += 2
        } else {
            p += 1
        }
        return String(bytes, p, len, Charsets.UTF_8)
    }

    private fun readUtf16(buf: ByteBuffer, start: Int): String {
        var p = start
        var len = buf.getShort(p).toInt() and 0xFFFF
        p += 2
        if (len and 0x8000 != 0) {
            len = ((len and 0x7FFF) shl 16) or (buf.getShort(p).toInt() and 0xFFFF)
            p += 2
        }
        val chars = CharArray(len) { buf.getChar(p + it * 2) }
        return String(chars)
    }

    /** Cadenas que parecen nombres de acción de un Intent (con puntos, sin espacios). */
    fun looksLikeAction(s: String): Boolean =
        s.length in 8..120 && !s.contains(".permission.") && !s.contains(".category.") && ' ' !in s && '/' !in s && s.count { it == '.' } >= 2 &&
            (s.contains(".action", ignoreCase = true) || s.contains("ACTION") || s.contains(".intent.") ||
                s.substringAfterLast('.').let { last -> last.length > 2 && last == last.uppercase() && last.any(Char::isLetter) })
}
