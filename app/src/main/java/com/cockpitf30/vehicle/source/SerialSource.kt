package com.cockpitf30.vehicle.source

import com.cockpitf30.vehicle.VehicleRepository
import com.cockpitf30.vehicle.probe.PortProbe
import com.cockpitf30.vehicle.raise.RaiseBmwDecoder
import com.cockpitf30.vehicle.raise.RaiseFramer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Lee el decodificador Raise directamente del UART (/dev/ttyMT*).
 *
 * Limitaciones conocidas:
 * - Una app normal casi nunca tiene permiso para abrir /dev/ttyMT* (permisos de Unix y SELinux).
 *   Normalmente hace falta root o firmar la app como app de sistema.
 * - El McuService de TopWay probablemente ya tiene el puerto abierto; si leemos a la vez,
 *   nos repartimos los bytes y ambos ven tramas rotas. Úsalo solo para investigar.
 * - La velocidad del puerto (baudios) se intenta fijar con `stty`, que también necesita root.
 */
class SerialSource(
    private val path: String,
    private val baudRate: Int,
) : VehicleDataSource {
    override val name = "Serie $path @ $baudRate"

    @Volatile
    private var stream: FileInputStream? = null

    override fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val framer = RaiseFramer(
                onFrame = { frame ->
                    VehicleRepository.updateState { RaiseBmwDecoder.apply(frame, it) }
                    VehicleRepository.updateStatus { it.copy(framesReceived = it.framesReceived + 1) }
                    VehicleRepository.logRaw(frame.toHex())
                },
                onBadChecksum = { bytes ->
                    VehicleRepository.updateStatus { it.copy(badChecksums = it.badChecksums + 1) }
                    VehicleRepository.logRaw("✗ " + bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) })
                },
            )
            val buffer = ByteArray(256)
            while (isActive) {
                try {
                    configurePort()
                    FileInputStream(File(path)).use { input ->
                        stream = input
                        VehicleRepository.updateStatus { it.copy(connected = true, lastError = null) }
                        while (isActive) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (n > 0) {
                                VehicleRepository.updateStatus { it.copy(bytesReceived = it.bytesReceived + n) }
                                // Todo lo que llega, por si el formato no es el Raise que suponemos.
                                VehicleRepository.logBytes(PortProbe.hex(buffer, minOf(n, 64)) + if (n > 64) " …(+${n - 64})" else "")
                                framer.feed(buffer, n)
                            }
                        }
                    }
                } catch (e: IOException) {
                    VehicleRepository.updateStatus { it.copy(connected = false, lastError = "$path: ${e.message}") }
                } catch (e: SecurityException) {
                    VehicleRepository.updateStatus { it.copy(connected = false, lastError = "Sin permiso: ${e.message}") }
                }
                stream = null
                delay(RETRY_MS)
            }
        }
    }

    override fun stop() {
        // Cerrar el stream desbloquea el read() del hilo de lectura.
        runCatching { stream?.close() }
    }

    private fun configurePort() {
        val cmd = "stty -F $path $baudRate raw -echo"
        val withRoot = runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor() == 0 }
        if (withRoot.getOrDefault(false)) return
        runCatching { Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor() }
    }

    companion object {
        private const val RETRY_MS = 3_000L

        /** Puertos candidatos en placas MediaTek. */
        fun candidatePorts(): List<File> = PortProbe.candidates()
    }
}
