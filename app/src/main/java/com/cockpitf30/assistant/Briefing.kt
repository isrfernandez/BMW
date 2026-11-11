package com.cockpitf30.assistant

/** Lo que sabe la app al arrancar, para el saludo del asistente. */
data class BriefingInput(
    val hour: Int,
    val name: String? = null,
    val tempC: Int? = null,
    /** «despejado», «lluvia moderada»… */
    val weather: String? = null,
    val rainLater: Boolean = false,
    val rangeKm: Int? = null,
    /** Radares en el camino al destino habitual (null = no hay destino habitual conocido). */
    val camerasOnRoute: Int? = null,
    /** Km en línea recta al destino habitual. */
    val routeKm: Double? = null,
    /** Radares a menos de 5 km. */
    val camerasNear: Int = 0,
    /** Frase de mantenimiento (ver Maintenance.spoken). */
    val service: String? = null,
)

/** Saludo al arrancar: hora del día, tiempo, autonomía, radares y recordatorios. */
object Briefing {
    /** No se repite si la app vuelve a arrancar antes de esto (paradas cortas). */
    const val GAP_MS = 45 * 60_000L

    fun text(b: BriefingInput): String {
        val greet = when (b.hour) { in 6..12 -> "Buenos días"; in 13..20 -> "Buenas tardes"; else -> "Buenas noches" }
        val parts = mutableListOf(greet + (b.name?.let { ", $it" } ?: "") + ".")
        val w = listOfNotNull(b.tempC?.let { "$it grados" }, b.weather?.lowercase()).joinToString(" y ")
        if (w.isNotEmpty()) parts += "Fuera hay $w" + if (b.rainLater) ", y puede llover más tarde." else "."
        b.rangeKm?.let { r ->
            parts += when {
                r < 60 -> "Ojo: solo quedan $r kilómetros de autonomía; conviene repostar pronto."
                else -> "Tienes $r kilómetros de autonomía."
            }
        }
        val cams = b.camerasOnRoute
        if (cams != null) {
            val where = b.routeKm?.let { "en tu ruta habitual, unos ${it.toInt()} kilómetros," } ?: "en tu ruta habitual"
            parts += when (cams) {
                0 -> "No hay radares fijos $where."
                1 -> "Hay un radar fijo $where."
                else -> "Hay $cams radares fijos $where."
            }
        } else if (b.camerasNear > 0) {
            parts += if (b.camerasNear == 1) "Hay un radar fijo cerca." else "Hay ${b.camerasNear} radares fijos cerca."
        }
        b.service?.let { parts += it }
        parts += "Buen viaje."
        return parts.joinToString(" ")
    }
}
