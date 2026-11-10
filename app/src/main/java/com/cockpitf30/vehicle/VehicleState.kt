package com.cockpitf30.vehicle

/** Estado del coche. `null` significa "todavía no recibido". */
data class VehicleState(
    val speedKmh: Int? = null,
    val rpm: Int? = null,
    val outsideTempC: Float? = null,
    /** Marcha: "N", "R", "1".."6" (manual de 6). Si el CAN no la da, ver [GearEstimator]. */
    val gear: String? = null,
    /** Nivel de combustible, 0.0 (vacío) a 1.0 (lleno). */
    val fuelLevel: Float? = null,
    val oilTempC: Int? = null,
    val rangeKm: Int? = null,
    /** Consumo medio del ordenador de a bordo en l/100 km, si la caja CAN lo manda. */
    val avgConsumption: Float? = null,
)

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
