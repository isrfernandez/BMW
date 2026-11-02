package com.cockpitf30.vehicle

/** Estado del coche. `null` significa "todavía no recibido". */
data class VehicleState(
    val speedKmh: Int? = null,
    val rpm: Int? = null,
    val doors: Doors = Doors(),
    val outsideTempC: Float? = null,
    /** Marcha: "N", "R", "1".."6" (manual de 6). Si el CAN no la da, ver [GearEstimator]. */
    val gear: String? = null,
    /** Nivel de combustible, 0.0 (vacío) a 1.0 (lleno). */
    val fuelLevel: Float? = null,
    val oilTempC: Int? = null,
    val rangeKm: Int? = null,
    val tyres: Tyres = Tyres(),
    /** Consumo medio del ordenador de a bordo en l/100 km, si la caja CAN lo manda. */
    val avgConsumption: Float? = null,
)

/** Presión de cada neumático en bar. */
data class Tyres(
    val frontLeft: Float? = null,
    val frontRight: Float? = null,
    val rearLeft: Float? = null,
    val rearRight: Float? = null,
)

data class Doors(
    val driver: Boolean? = null,
    val passenger: Boolean? = null,
    val rearLeft: Boolean? = null,
    val rearRight: Boolean? = null,
    val trunk: Boolean? = null,
    val hood: Boolean? = null,
) {
    val anyOpen: Boolean
        get() = listOf(driver, passenger, rearLeft, rearRight, trunk, hood).any { it == true }
}

/** Información de diagnóstico sobre la fuente de datos activa. */
data class SourceStatus(
    val sourceName: String = "—",
    val connected: Boolean = false,
    val framesReceived: Long = 0,
    val badChecksums: Long = 0,
    /** Bytes leídos del puerto, formen trama o no. */
    val bytesReceived: Long = 0,
    val lastError: String? = null,
)
