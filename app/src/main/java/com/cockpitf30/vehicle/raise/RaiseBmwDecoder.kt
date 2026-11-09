package com.cockpitf30.vehicle.raise

import com.cockpitf30.vehicle.VehicleState

/**
 * Traduce tramas Raise a [VehicleState].
 *
 * ⚠️ El mapeo de tipos y posiciones viene de una pista sin confirmar
 * (0x01 = velocidad/RPM, 0x02 = puertas). Antes de fiarse de él hay que capturar tramas
 * reales con la pantalla de Diagnóstico (acelerar, abrir puertas…) y corregir aquí.
 * Los índices son sobre `data`, es decir, sin cabecera, tipo ni longitud.
 */
object RaiseBmwDecoder {
    const val TYPE_SPEED_RPM = 0x01

    fun apply(frame: RaiseFrame, state: VehicleState): VehicleState = when (frame.type) {
        TYPE_SPEED_RPM -> {
            val rpmHigh = frame.u8(1)
            val rpmLow = frame.u8(2)
            val speed = frame.u8(3)
            state.copy(
                rpm = if (rpmHigh != null && rpmLow != null) (rpmHigh shl 8) or rpmLow else state.rpm,
                speedKmh = speed ?: state.speedKmh,
            )
        }
        else -> state
    }
}
