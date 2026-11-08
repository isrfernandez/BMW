package com.cockpitf30.vehicle.source

/**
 * Tramas de la caja CAN (Raise/WY) tal como las reenvía el servicio del coche en el aviso
 * "callback.pt.canbus_cmd" {arg_byte_array}. Es lo mismo que lee la app CarCanbus de la radio
 * (android.car.app.canbus, clase CmdHandler de la instancia "osuwy"), sacado de su código.
 *
 * Formato: `5A A5 | len | tipo | datos[len] | suma`, con suma = (len + tipo + datos − 1) & 0xFF.
 * Ejemplo real del coche: `5A A5 09 12 01 00 00 01 00 12 00 28 00 56`.
 *
 * Tipos que nos interesan:
 *  - 0x12 info básica: d0 ACC, d2 cierre centralizado, d3 freno de mano, d4 puertas (bit7 conductor,
 *    bit6 acompañante, bit5 trasera izq., bit4 trasera dcha., bit3 maletero, bit2 capó), d5 combustible,
 *    d6 cinturones, d7 temperatura exterior (bits 0-6, bit7 = signo positivo).
 *  - 0x16 info dinámica: velocidad = d0·256 + d1 (km/h), rpm = d2·256 + d3. La app de la radio la pide
 *    cada 500 ms con la consulta `90 16` mientras enseña su cuentarrevoluciones.
 *  - 0x27 viaje: kilometraje = d0·65536 + d1·256 + d2, autonomía = d3·256 + d4 (km).
 */
object CanBoxFrames {
    const val TYPE_BASE = 0x12
    const val TYPE_DYNAMIC = 0x16
    const val TYPE_TRAVEL = 0x27
    const val TYPE_VERSION = 0x30
    private const val CMD_QUERY = 0x90

    data class Frame(val type: Int, val data: ByteArray) {
        fun u(i: Int): Int = data[i].toInt() and 0xFF
        fun hex(): String = "%02X ".format(type) + data.joinToString(" ") { "%02X".format(it) }
        override fun equals(other: Any?) = other is Frame && other.type == type && other.data.contentEquals(data)
        override fun hashCode() = type * 31 + data.contentHashCode()
    }

    /** Suma de comprobación de la caja: (len + tipo + datos − 1) & 0xFF. */
    fun checksum(len: Int, type: Int, data: ByteArray): Int =
        (len + type + data.sumOf { it.toInt() and 0xFF } - 1) and 0xFF

    /** Todas las tramas válidas que haya en [bytes] (normalmente una). Las de suma incorrecta se cuentan en [bad]. */
    fun split(bytes: ByteArray, bad: (ByteArray) -> Unit = {}): List<Frame> {
        val out = mutableListOf<Frame>()
        var i = 0
        while (i + 4 < bytes.size) {
            if ((bytes[i].toInt() and 0xFF) != 0x5A || (bytes[i + 1].toInt() and 0xFF) != 0xA5) { i++; continue }
            val len = bytes[i + 2].toInt() and 0xFF
            val end = i + 4 + len // posición de la suma
            if (end >= bytes.size) break
            val type = bytes[i + 3].toInt() and 0xFF
            val data = bytes.copyOfRange(i + 4, end)
            if (checksum(len, type, data) == (bytes[end].toInt() and 0xFF)) {
                out += Frame(type, data)
                i = end + 1
            } else {
                bad(bytes.copyOfRange(i, end + 1))
                i += 2
            }
        }
        return out
    }

    /**
     * Valores de la trama con nombres "can.*", que [CarKeyMapper] ya sabe traducir
     * (can.speed, can.rpm, can.door_fl…, can.fuel_range). Vacío si no es un tipo conocido o es corta.
     */
    fun decode(f: Frame): Map<String, Any> = when (f.type) {
        TYPE_BASE -> if (f.data.size < 8) emptyMap() else buildMap {
            put("can.acc", f.u(0) == 1)
            put("can.central_lock", f.u(2) == 1)
            put("can.handbrake", f.u(3) == 1)
            val d = f.u(4)
            put("can.door_fl", d and 0x80 != 0)
            put("can.door_fr", d and 0x40 != 0)
            put("can.door_rl", d and 0x20 != 0)
            put("can.door_rr", d and 0x10 != 0)
            put("can.door_back", d and 0x08 != 0)
            put("can.door_front", d and 0x04 != 0)
            put("can.fuel_liters", f.u(5))
            put("can.belt_driver", (f.u(6) shr 6) and 0x03 == 1)
            put("can.belt_passenger", (f.u(6) shr 4) and 0x03 == 1)
            val t = f.u(7) and 0x7F
            val temp = if (f.u(7) and 0x80 != 0) t else -t
            put("can.exterior_temp_raw", temp)
            // −40 (0x28) es lo que manda la caja cuando no tiene el dato.
            if (temp != -40) put("can.out_temp", temp)
        }
        TYPE_DYNAMIC -> if (f.data.size < 4) emptyMap() else mapOf(
            "can.speed" to (f.u(0) shl 8 or f.u(1)),
            "can.rpm" to (f.u(2) shl 8 or f.u(3)),
        )
        TYPE_TRAVEL -> if (f.data.size < 5) emptyMap() else mapOf(
            "can.mileage" to (f.u(0) shl 16 or (f.u(1) shl 8) or f.u(2)),
            "can.fuel_range" to (f.u(3) shl 8 or f.u(4)),
        )
        TYPE_VERSION -> mapOf("can.version" to String(f.data, Charsets.US_ASCII).trim { it < ' ' })
        else -> emptyMap()
    }

    /** Trama completa para mandar a la caja (igual que ShangSheCmdHelper.makeCmd5AA5 de la radio). */
    fun make(type: Int, data: ByteArray): ByteArray {
        val len = data.size
        return byteArrayOf(0x5A, 0xA5.toByte(), len.toByte(), type.toByte()) + data + checksum(len, type, data).toByte()
    }

    /** Consulta "mándame la trama [type]": `5A A5 01 90 type suma`. Es la que usa la app de la radio. */
    fun query(type: Int): ByteArray = make(CMD_QUERY, byteArrayOf(type.toByte()))
}
