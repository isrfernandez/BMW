package com.cockpitf30.vehicle.source

import com.cockpitf30.nav.RouteTracker
import com.cockpitf30.vehicle.Doors
import com.cockpitf30.vehicle.Tyres
import com.cockpitf30.vehicle.VehicleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Datos simulados para probar la interfaz fuera del coche. */
class DemoSource : VehicleDataSource {
    override val name = "Demo"

    override fun start(scope: CoroutineScope) {
        scope.launch {
            VehicleRepository.updateStatus { it.copy(connected = true) }
            var t = 0.0
            var doors = CLOSED
            var tick = 0
            while (isActive) {
                val speed = (70 + 60 * sin(t / 9)).roundToInt()
                val rpm = (900 + speed * 28 + 600 * sin(t / 2)).roundToInt()
                VehicleRepository.updateState {
                    it.copy(
                        speedKmh = speed,
                        rpm = rpm,
                        outsideTempC = 18.5f,
                        gear = if (speed < 3) "P" else "D",
                        fuelLevel = 0.62f,
                        oilTempC = (60 + t * 2).coerceAtMost(105.0).roundToInt(),
                        rangeKm = 451,
                        tyres = Tyres(2.4f, 2.4f, 2.3f, 2.4f),
                        doors = doors,
                        avgConsumption = (6.8 + 0.3 * sin(t / 40)).toFloat(),
                    )
                }
                // El contador se publica una vez por segundo: cada cambio de estado recompone el Inicio.
                if (tick % 20 == 0) VehicleRepository.updateStatus { it.copy(framesReceived = it.framesReceived + 20) }
                // Sin GPS real, el mapa del Inicio recorre una ruta de ejemplo a esta velocidad.
                RouteTracker.simulate(speed, 0.05)
                t += 0.1
                // Ciclo de 30 s: 24 s todo cerrado (se ve el mapa del Inicio) y 6 s con
                // una combinación aleatoria de puertas, capó y maletero abiertos.
                when (++tick % 600) {
                    480 -> doors = randomDoors()
                    0 -> doors = CLOSED
                }
                delay(50)
            }
        }
    }

    /** Cada pieza abierta con un 35 % (al menos una). */
    private fun randomDoors(): Doors {
        while (true) {
            val o = List(6) { Random.nextFloat() < 0.35f }
            if (o.any { it }) return Doors(o[0], o[1], o[2], o[3], trunk = o[4], hood = o[5])
        }
    }

    private companion object {
        val CLOSED = Doors(false, false, false, false, trunk = false, hood = false)
    }
}
