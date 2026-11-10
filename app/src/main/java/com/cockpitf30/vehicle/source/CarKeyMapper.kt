package com.cockpitf30.vehicle.source

import com.cockpitf30.vehicle.VehicleState
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Traduce las claves del almacén de datos de la radio (CarDataManager: "canbus.car_speed",
 * "state.wy.car_outtemp"…) al [VehicleState] de la app.
 * Puertas y presiones no se leen: la caja CAN de esta radio no las manda. Las claves que no se
 * reconocen se ven en Diagnóstico → «Datos del coche».
 */
object CarKeyMapper {
    private val SPEED_KEYS = listOf("can.speed", "canbus.car_speed", "bc.canbus_speed", "state.wy.car_speed", "state.zhtd.car_speed", "canbus.info.speed", "canbus.state.speed")
    private val RPM_KEYS = listOf("can.rpm", "canbus.rotate_speed", "bc.canbus_rpm", "canbus.info.rpm", "canbus.state.rpm")
    private val OUT_TEMP_KEYS = listOf("can.out_temp", "bc.out_temp", "state.wy.car_outtemp", "state.zhtd.car_outtemp")

    fun apply(state: VehicleState, values: Map<String, Any?>): VehicleState {
        var s = state
        number(values, SPEED_KEYS) { it.endsWith("car_speed") || it.endsWith(".speed") && !noSpeed(it) }
            ?.let { s = s.copy(speedKmh = it.roundToInt().coerceIn(0, 400)) }
        number(values, RPM_KEYS) { "rpm" in it || "rotate_speed" in it || "engine_speed" in it || "enginespeed" in it }
            ?.let { s = s.copy(rpm = it.roundToInt().coerceIn(0, 9000)) }
        number(values, OUT_TEMP_KEYS) { "outtemp" in it || "out_temp" in it || "outside_temp" in it }
            ?.let { s = s.copy(outsideTempC = (if (abs(it) > 70) it / 10 else it).toFloat()) }
        number(values, listOf("can.fuel_range")) { ("fuel_range" in it || "fuelrange" in it || it.endsWith(".range") || "endurance" in it) }
            ?.let { s = s.copy(rangeKm = it.roundToInt()) }
        number(values, emptyList()) { "fuel_percent" in it || "fuel_level" in it || "oil_percent" in it }
            ?.let { s = s.copy(fuelLevel = (if (it > 1.0) it / 100 else it).toFloat().coerceIn(0f, 1f)) }
        number(values, emptyList()) { "oil_temp" in it || "oiltemp" in it }
            ?.let { s = s.copy(oilTempC = it.roundToInt()) }
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

    fun toNumber(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }
}
