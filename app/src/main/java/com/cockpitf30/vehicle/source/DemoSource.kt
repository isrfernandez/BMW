package com.cockpitf30.vehicle.source

import com.cockpitf30.nav.RouteTracker
import com.cockpitf30.ui.theme.DriveMode
import com.cockpitf30.ui.theme.DriveModes
import com.cockpitf30.vehicle.GearEstimator
import com.cockpitf30.vehicle.Shift
import com.cockpitf30.vehicle.ShiftAdvisor
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
            // Conductor simulado con caja manual de 6: no cambia en el punto ideal sino un rato
            // después de que el cuadro le aconseje hacerlo, así en la demo se ve la flecha de cambio.
            val driver = DemoDriver()
            while (isActive) {
                // Ciclo lento (~1 min de 10 a 130 km/h y vuelta): aceleraciones realistas.
                val speed = (70 + 60 * sin(t / 18)).roundToInt()
                // Cambia donde lo aconseja el cuadro en el modo de conducción elegido.
                driver.limits = if (DriveModes.current == DriveMode.SPORT) ShiftAdvisor.SPORT else ShiftAdvisor.ECO
                val gear = driver.update(System.currentTimeMillis(), speed)
                val rpm = DemoDriver.rpmFor(speed, gear)
                VehicleRepository.updateState {
                    it.copy(
                        speedKmh = speed,
                        rpm = rpm,
                        outsideTempC = 18.5f,
                        gear = if (gear == 0) "N" else "$gear",
                        fuelLevel = 0.62f,
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

/**
 * Conductor de la demo: lleva una marcha metida y la cambia cuando el consejo de cambio
 * ([ShiftAdvisor], umbrales Eco) lleva [REACTION_MS] a la vista, o antes si el motor se pasa de
 * vueltas o se ahoga. Puro (sin reloj propio) para poder probarlo.
 */
class DemoDriver(var limits: ShiftAdvisor.Limits = ShiftAdvisor.ECO) {
    companion object {
        const val REACTION_MS = 2_000L
        private const val MAX_RPM = 4_300
        private const val MIN_RPM = 850
        private val RATIOS = GearEstimator.KMH_PER_1000_RPM

        /** rpm del motor a esa velocidad en esa marcha (ralentí parado o en punto muerto). */
        fun rpmFor(speedKmh: Int, gear: Int): Int =
            if (speedKmh < 3 || gear == 0) 820
            else (speedKmh * 1000f / RATIOS[gear - 1]).roundToInt().coerceAtLeast(MIN_RPM)
    }

    private val advisor = ShiftAdvisor()
    private var shownSince = 0L
    /** 0 = punto muerto. */
    var gear = 0
        private set

    fun update(now: Long, speedKmh: Int): Int {
        if (speedKmh < 3) { gear = 0; advisor.update(now, null, null, null, limits); return gear }
        if (gear == 0) gear = 1
        val rpm = (speedKmh * 1000f / RATIOS[gear - 1]).roundToInt()
        val advice = advisor.update(now, gear, rpm, speedKmh, limits)
        if (advice == null) shownSince = 0L else if (shownSince == 0L) shownSince = now
        val react = advice != null && now - shownSince >= REACTION_MS
        when {
            gear < RATIOS.size && (rpm > MAX_RPM || (react && advice == Shift.UP)) -> gear++
            gear > 1 && (rpm < MIN_RPM || (react && advice == Shift.DOWN)) -> gear--
            else -> return gear
        }
        shownSince = 0L
        return gear
    }
}
