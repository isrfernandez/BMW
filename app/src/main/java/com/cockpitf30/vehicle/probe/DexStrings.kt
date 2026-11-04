package com.cockpitf30.vehicle.probe

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Saca la tabla de cadenas de un classes.dex. Ahí están los nombres de clases, métodos,
 * URIs de content providers y claves de extras que usa una app: así vemos cómo leen
 * las apps del fabricante las puertas o la velocidad sin tener su código.
 */
object DexStrings {
    fun parse(dex: ByteArray, limit: Int = 200_000): List<String> {
        if (dex.size < 0x70 || !String(dex, 0, 3, Charsets.ISO_8859_1).startsWith("dex")) return emptyList()
        val buf = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.getInt(0x38).coerceIn(0, limit)
        val idsOff = buf.getInt(0x3C)
        val out = ArrayList<String>(count)
        for (i in 0 until count) {
            val idPos = idsOff + i * 4
            if (idPos + 4 > dex.size) break
            var p = buf.getInt(idPos)
            if (p !in dex.indices) continue
            // Longitud en ULEB128 (en caracteres UTF-16) y luego MUTF-8 terminado en 0.
            var shift = 0
            while (p < dex.size) {
                val b = dex[p++].toInt()
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 28) break
            }
            val start = p
            while (p < dex.size && dex[p].toInt() != 0) p++
            out += String(dex, start, p - start, Charsets.UTF_8)
        }
        return out
    }
}
