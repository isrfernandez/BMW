package com.cockpitf30.vehicle.source

import com.cockpitf30.nav.RouteTracker
import com.cockpitf30.vehicle.GearEstimator
import com.cockpitf30.vehicle.VehicleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sin

/** Datos simulados para probar la interfaz fuera del coche. */
class DemoSource : VehicleDataSource {
    override val name = "Demo"

    override fun start(scope: CoroutineScope) {
        scope.launch {
            VehicleRepository.updateStatus { it.copy(connected = true) }
            var t = 0.0
            var tick = 0
            while (isActive) {
                val speed = (70 + 60 * sin(t / 9)).roundToInt()
                // Manual de 6: rpm según la marcha que tocaría a esa velocidad.
                val gear = GearEstimator.gearFor(speed)
                val rpm = if (speed < 3) 820 else (speed * 1000f / GearEstimator.KMH_PER_1000_RPM[gear - 1]).roundToInt().coerceAtLeast(850)
                VehicleRepository.updateState {
                    it.copy(
                        speedKmh = speed,
                        rpm = rpm,
                        outsideTempC = 18.5f,
                        gear = if (speed < 3) "N" else "$gear",
                        fuelLevel = 0.62f,
                        oilTempC = (60 + t * 2).coerceAtMost(105.0).roundToInt(),
                        rangeKm = 451,
                        avgConsumption = (6.8 + 0.3 * sin(t / 40)).toFloat(),
                    )
                }
                // El contador se publica una vez por segundo: cada cambio de estado recompone el Inicio.
                if (tick % 20 == 0) VehicleRepository.updateStatus { it.copy(framesReceived = it.framesReceived + 20) }
                // Sin GPS real, el mapa del Inicio recorre una ruta de ejemplo a esta velocidad.
                RouteTracker.simulate(speed, 0.05)
                t += 0.1
                tick++
                delay(50)
            }
        }
    }
}
