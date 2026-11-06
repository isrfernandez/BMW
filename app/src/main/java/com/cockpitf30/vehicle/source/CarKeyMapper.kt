package com.cockpitf30.vehicle.source

import com.cockpitf30.vehicle.Doors
import com.cockpitf30.vehicle.Tyres
import com.cockpitf30.vehicle.VehicleState
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Traduce las claves del almacén de datos de la radio (CarDataManager: "canbus.car_speed",
 * "state.wy.car_outtemp"…) al [VehicleState] de la app.
 *
 * Solo algunas claves están confirmadas por su nombre en el código del fabricante; para puertas
 * y presiones aún no sabemos el nombre exacto, así que se reconocen por palabras ("door" + posición,
 * "tpms"/"tire" + rueda). Las claves que no se reconocen se ven en Diagnóstico → «Datos del coche».
 */
object CarKeyMapper {
    private val SPEED_KEYS = listOf("canbus.car_speed", "bc.canbus_speed", "state.wy.car_speed", "state.zhtd.car_speed", "canbus.info.speed", "canbus.state.speed")
    private val RPM_KEYS = listOf("canbus.rotate_speed", "bc.canbus_rpm", "canbus.info.rpm", "canbus.state.rpm")
    private val OUT_TEMP_KEYS = listOf("bc.out_temp", "state.wy.car_outtemp", "state.zhtd.car_outtemp")

    fun apply(state: VehicleState, values: Map<String, Any?>): VehicleState {
        var s = state
        number(values, SPEED_KEYS) { it.endsWith("car_speed") || it.endsWith(".speed") && !noSpeed(it) }
            ?.let { s = s.copy(speedKmh = it.roundToInt().coerceIn(0, 400)) }
        number(values, RPM_KEYS) { "rpm" in it || "rotate_speed" in it || "engine_speed" in it || "enginespeed" in it }
            ?.let { s = s.copy(rpm = it.roundToInt().coerceIn(0, 9000)) }
        number(values, OUT_TEMP_KEYS) { "outtemp" in it || "out_temp" in it || "outside_temp" in it }
            ?.let { s = s.copy(outsideTempC = (if (abs(it) > 70) it / 10 else it).toFloat()) }
        number(values, emptyList()) { ("fuel_range" in it || "fuelrange" in it || it.endsWith(".range") || "endurance" in it) }
            ?.let { s = s.copy(rangeKm = it.roundToInt()) }
        number(values, emptyList()) { "fuel_percent" in it || "fuel_level" in it || "oil_percent" in it }
            ?.let { s = s.copy(fuelLevel = (if (it > 1.0) it / 100 else it).toFloat().coerceIn(0f, 1f)) }
        number(values, emptyList()) { "oil_temp" in it || "oiltemp" in it }
            ?.let { s = s.copy(oilTempC = it.roundToInt()) }
        s = s.copy(doors = doors(s.doors, values), tyres = tyres(s.tyres, values))
        return s
    }

    private fun noSpeed(k: String) = listOf("rotate", "wind", "encoder", "unit", "pointer", "gps", "fan", "lower", "max", "cruis").any { it in k }

    /** Primero las claves conocidas, en orden; si no, la primera que case con [match]. */
    private inline fun number(values: Map<String, Any?>, known: List<String>, match: (String) -> Boolean): Double? {
        known.firstNotNullOfOrNull { k -> values[k]?.let(::toNumber) }?.let { return it }
        return values.entries.firstOrNull { (k, v) -> !isSetting(k) && match(k.lowercase()) && toNumber(v) != null }?.let { toNumber(it.value) }
    }

    /** Claves de ajustes de fábrica o de configuración: no son datos del coche. */
    private fun isSetting(k: String) = k.startsWith("data.factory.") || k.startsWith("data.set.") ||
        listOf("setting", "alarm", "disable", "enable", "auto_hide", "unit", "max_").any { it in k.lowercase() }

    enum class Pos { FL, FR, RL, RR, TRUNK, HOOD }

    /** Rueda o puerta a la que se refiere la clave por su sufijo ("door_fl", "doorLB", "tpms.rear_right"…). */
    fun position(key: String, word: Regex): Pos? {
        val tail = key.lowercase().split(word).drop(1).joinToString("").replace(Regex("[^a-z]"), "")
        if (tail.isEmpty()) return null
        return when {
            tail in setOf("fl", "lf", "frontleft", "leftfront", "driver", "left") -> Pos.FL
            tail in setOf("fr", "rf", "frontright", "rightfront", "passenger", "right") -> Pos.FR
            tail in setOf("rl", "lr", "lb", "bl", "rearleft", "leftrear", "backleft", "leftback") -> Pos.RL
            tail in setOf("rr", "rb", "br", "rearright", "rightrear", "backright", "rightback") -> Pos.RR
            tail in setOf("back", "trunk", "tail", "rear", "boot") -> Pos.TRUNK
            tail in setOf("front", "hood", "engine", "bonnet") -> Pos.HOOD
            else -> null
        }
    }

    private val DOOR = Regex("door")
    private val TYRE = Regex("tpms|tire|tyre|pressure")

    private fun doors(current: Doors, values: Map<String, Any?>): Doors {
        var d = current
        for ((k, v) in values) {
            if (isSetting(k) || "radar" in k.lowercase() || !DOOR.containsMatchIn(k.lowercase())) continue
            val open = toNumber(v)?.let { it != 0.0 } ?: continue
            d = when (position(k, DOOR)) {
                Pos.FL -> d.copy(driver = open)
                Pos.FR -> d.copy(passenger = open)
                Pos.RL -> d.copy(rearLeft = open)
                Pos.RR -> d.copy(rearRight = open)
                Pos.TRUNK -> d.copy(trunk = open)
                Pos.HOOD -> d.copy(hood = open)
                null -> d
            }
        }
        return d
    }

    private fun tyres(current: Tyres, values: Map<String, Any?>): Tyres {
        var t = current
        for ((k, v) in values) {
            val low = k.lowercase()
            if (isSetting(k) || "temp" in low || "axis" in low || !TYRE.containsMatchIn(low)) continue
            val bar = toNumber(v)?.let(::toBar) ?: continue
            t = when (position(k, TYRE)) {
                Pos.FL -> t.copy(frontLeft = bar)
                Pos.FR -> t.copy(frontRight = bar)
                Pos.RL -> t.copy(rearLeft = bar)
                Pos.RR -> t.copy(rearRight = bar)
                else -> t
            }
        }
        return t
    }

    /** kPa (240), décimas de bar (24) o bar (2.4) → bar. */
    fun toBar(v: Double): Float? = when {
        v <= 0 -> null
        v >= 50 -> (v / 100).toFloat()
        v > 6 -> (v / 10).toFloat()
        else -> v.toFloat()
    }

    fun toNumber(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }
}
