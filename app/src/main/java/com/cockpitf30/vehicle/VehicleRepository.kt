package com.cockpitf30.vehicle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Punto único de verdad compartido entre el servicio (que escribe) y la interfaz (que lee).
 * Vive en el mismo proceso, así que basta con StateFlows.
 */
object VehicleRepository {
    private const val LOG_SIZE = 60

    private val _state = MutableStateFlow(VehicleState())
    val state: StateFlow<VehicleState> = _state.asStateFlow()

    private val _status = MutableStateFlow(SourceStatus())
    val status: StateFlow<SourceStatus> = _status.asStateFlow()

    /** Últimas tramas recibidas en hexadecimal, la más reciente primero. */
    private val _rawLog = MutableStateFlow<List<String>>(emptyList())
    val rawLog: StateFlow<List<String>> = _rawLog.asStateFlow()

    /** Últimos eventos de entrada (teclas, ruleta) para mapear el iDrive. */
    private val _inputLog = MutableStateFlow<List<String>>(emptyList())
    val inputLog: StateFlow<List<String>> = _inputLog.asStateFlow()

    /** Bytes en crudo tal como llegan del puerto (aunque no sean tramas Raise válidas). */
    private val _byteLog = MutableStateFlow<List<String>>(emptyList())
    val byteLog: StateFlow<List<String>> = _byteLog.asStateFlow()

    fun updateState(transform: (VehicleState) -> VehicleState) = _state.update(transform)

    fun updateStatus(transform: (SourceStatus) -> SourceStatus) = _status.update(transform)

    fun reset(sourceName: String) {
        _state.value = VehicleState()
        _status.value = SourceStatus(sourceName = sourceName)
        _rawLog.value = emptyList()
        _byteLog.value = emptyList()
    }

    fun logRaw(line: String) = _rawLog.update { (listOf(line) + it).take(LOG_SIZE) }

    fun logBytes(line: String) = _byteLog.update { (listOf(line) + it).take(LOG_SIZE) }

    fun logInput(line: String) = _inputLog.update { (listOf(line) + it).take(LOG_SIZE) }
}
