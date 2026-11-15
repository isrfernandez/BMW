package com.cockpitf30.system

/**
 * Si alguien está mirando Diagnóstico. Mientras no, los registros de depuración (eventos del
 * iDrive, avisos del servicio del coche descritos en texto, historial de cambios de claves) no
 * se preparan: así Diagnóstico no cuesta nada en el uso normal. Los datos que usa la app
 * (velocidad, faros, puertas…) se siguen leyendo igual.
 */
object DiagnosticsGate {
    /** Pantallas de Diagnóstico abiertas ahora mismo. */
    @Volatile private var open = 0

    val active: Boolean get() = open > 0

    @Synchronized fun enter() { open++ }

    @Synchronized fun leave() { if (open > 0) open-- }
}
