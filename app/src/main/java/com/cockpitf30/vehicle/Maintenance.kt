package com.cockpitf30.vehicle

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** Tareas de mantenimiento que se vigilan. */
enum class ServiceItem(
    val label: String,
    val spoken: String,
    /** Cada cuántos km (null = solo por fecha). */
    val everyKm: Int?,
    /** Cada cuántos meses (null = solo por km). */
    val everyMonths: Int?,
    val hint: String,
) {
    OIL("Aceite y filtro", "el cambio de aceite", 25_000, 24, "BMW Longlife recomienda 25.000 km o 2 años"),
    TYRES("Neumáticos", "la revisión de neumáticos", 10_000, 6, "Presión, desgaste y rotación; también cada 6 meses"),
    ITV("ITV", "la ITV", null, null, "Fecha de la próxima inspección"),
}

enum class ServiceLevel { OK, SOON, DUE, UNKNOWN }

/** Cómo está una tarea: lo que queda y qué parte del intervalo se ha consumido. */
data class ServiceStatus(
    val item: ServiceItem,
    val level: ServiceLevel,
    /** 0..1 (puede pasar de 1 si está vencida). */
    val used: Float,
    val kmLeft: Int?,
    val daysLeft: Int?,
    /** Texto corto: «Quedan 12.300 km o 8 meses», «Vence en 12 días», «Vencida hace 300 km». */
    val text: String,
)

/** Registro de la última vez que se hizo cada tarea. */
data class ServiceRecord(val doneAtKm: Long?, val doneAtMillis: Long?)

/** Cálculo puro del estado del mantenimiento. */
object Maintenance {
    /** «Pronto» cuando queda menos de esto. */
    const val SOON_KM = 1_500
    const val SOON_DAYS = 30

    fun status(
        item: ServiceItem, record: ServiceRecord?, odometerKm: Long?, itvDate: LocalDate?, now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        everyKm: Int? = item.everyKm,
    ): ServiceStatus {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        if (item == ServiceItem.ITV) {
            if (itvDate == null) return ServiceStatus(item, ServiceLevel.UNKNOWN, 0f, null, null, "Pon la fecha de la próxima ITV")
            val days = ChronoUnit.DAYS.between(today, itvDate).toInt()
            val level = when { days < 0 -> ServiceLevel.DUE; days <= SOON_DAYS -> ServiceLevel.SOON; else -> ServiceLevel.OK }
            // Barra: el último año antes de la fecha.
            val used = (1f - days / 365f).coerceIn(0f, 1.2f)
            val text = when {
                days < 0 -> "Caducada hace ${days(-days)}"
                days == 0 -> "Vence hoy"
                else -> "Vence en ${days(days)}"
            }
            return ServiceStatus(item, level, used, null, days, text)
        }
        if (record == null || (record.doneAtKm == null && record.doneAtMillis == null)) {
            return ServiceStatus(item, ServiceLevel.UNKNOWN, 0f, null, null, "Marca «Hecho» la próxima vez que lo hagas")
        }
        val kmLeft = if (everyKm != null && record.doneAtKm != null && odometerKm != null) (record.doneAtKm + everyKm - odometerKm).toInt() else null
        val daysLeft = if (item.everyMonths != null && record.doneAtMillis != null) {
            val done = Instant.ofEpochMilli(record.doneAtMillis).atZone(zone).toLocalDate()
            ChronoUnit.DAYS.between(today, done.plusMonths(item.everyMonths.toLong())).toInt()
        } else null
        val usedKm = if (kmLeft != null) 1f - kmLeft.toFloat() / everyKm!! else 0f
        val usedTime = if (daysLeft != null) 1f - daysLeft / (item.everyMonths!! * 30.44f) else 0f
        val used = maxOf(usedKm, usedTime).coerceIn(0f, 1.2f)
        val due = (kmLeft != null && kmLeft <= 0) || (daysLeft != null && daysLeft <= 0)
        val soon = (kmLeft != null && kmLeft <= SOON_KM) || (daysLeft != null && daysLeft <= SOON_DAYS)
        val level = when { due -> ServiceLevel.DUE; soon -> ServiceLevel.SOON; else -> ServiceLevel.OK }
        val text = when {
            due && kmLeft != null && kmLeft <= 0 -> "Pasado hace ${km(-kmLeft)}"
            due -> "Vencido hace ${days(-(daysLeft ?: 0))}"
            kmLeft != null && daysLeft != null -> "Quedan ${km(kmLeft)} o ${period(daysLeft)}"
            kmLeft != null -> "Quedan ${km(kmLeft)}"
            daysLeft != null -> "Quedan ${period(daysLeft)}"
            else -> "Pon los km del coche"
        }
        return ServiceStatus(item, level, used, kmLeft, daysLeft, text)
    }

    /** Frase para el asistente con lo vencido o próximo, o null si todo va bien. */
    fun spoken(list: List<ServiceStatus>): String? {
        val due = list.filter { it.level == ServiceLevel.DUE }
        val soon = list.filter { it.level == ServiceLevel.SOON }
        val parts = due.map { "${it.item.spoken} está ${if (it.item == ServiceItem.ITV) "caducada" else "pendiente"}" } +
            soon.map { s ->
                when {
                    s.kmLeft != null && s.kmLeft <= SOON_KM -> "${s.item.spoken} toca en ${s.kmLeft} kilómetros"
                    s.daysLeft != null -> "${s.item.spoken} toca en ${days(s.daysLeft)}"
                    else -> "${s.item.spoken} toca pronto"
                }
            }
        if (parts.isEmpty()) return null
        val joined = if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " y " + parts.last()
        return "Recuerda que " + joined + "."
    }

    /** Lo más urgente primero. */
    fun mostUrgent(list: List<ServiceStatus>): ServiceStatus? =
        list.filter { it.level != ServiceLevel.UNKNOWN }.maxByOrNull { it.used } ?: list.firstOrNull()

    private fun km(v: Int) = "%,d km".format(java.util.Locale("es", "ES"), v).replace(',', '.')
    private fun days(d: Int) = if (d == 1) "1 día" else "$d días"
    private fun period(d: Int): String = when {
        d < 45 -> days(d)
        d < 365 -> "${(d / 30.44).roundToInt()} meses"
        else -> "%.1f años".format(java.util.Locale("es", "ES"), d / 365.0).replace(",0 ", " ")
    }
}
