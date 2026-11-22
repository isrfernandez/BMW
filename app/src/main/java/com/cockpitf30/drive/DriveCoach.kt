package com.cockpitf30.drive

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.cockpitf30.nav.AlertCategory
import com.cockpitf30.nav.RouteAlert
import com.cockpitf30.nav.RouteAlerts
import com.cockpitf30.nav.RouteTracker
import com.cockpitf30.system.AppSettings
import com.cockpitf30.vehicle.GearEstimator
import com.cockpitf30.vehicle.VehicleRepository
import com.cockpitf30.vehicle.telemetry.Telemetry
import com.cockpitf30.vehicle.trip.TripComputer
import com.cockpitf30.weather.Wind
import com.cockpitf30.weather.WeatherRepository
import com.cockpitf30.weather.wmoCondition
import com.cockpitf30.weather.WeatherKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlin.math.roundToInt

/** Lo que sabe Athoox de cómo va el coche por la carretera (para el cuadro y Diagnóstico). */
data class DriveState(
    /** Lectura de carretera; [RoadReading.label] es la línea del cuadro (null = no se enseña). */
    val road: RoadReading? = null,
    val rhythm: Rhythm? = null,
    val stats: RhythmStats? = null,
    /** Sin GPS con el coche en marcha: la posición es estimada. */
    val blind: Boolean = false,
    val netOk: Boolean = true,
    /** Pruebas de rodadura en punto muerto hechas en este viaje. */
    val coastSegments: Int = 0,
)

/**
 * Junta las tres cosas que «lee» Athoox mientras se conduce: la carretera (cuesta, viento y
 * resistencia), el ritmo del viaje (paseo, autopista, ciudad) y la cobertura (túnel, sin datos).
 * Corre cada segundo en el servicio del coche y dice lo que toque con el asistente, como un
 * aviso en ruta más.
 */
object DriveCoach {
    private val _state = MutableStateFlow(DriveState())
    val state: StateFlow<DriveState> = _state.asStateFlow()

    private var job: Job? = null
    private val meter = RhythmMeter()
    private val coast = CoastMeter()
    private val road = RoadWatch()
    private val coverage = CoverageWatch()
    private val rhythmSaid = HashSet<Rhythm>()
    private val ahead = AheadWatch()
    private var pending: RouteAlert? = null
    private var tripActive = false
    private var lastGpsAt: Long? = null
    private var lastTick = 0L
    private var previewOn = false
    private var lastLabel: String? = null
    private var labelUntil = 0L
    private const val LABEL_HOLD_MS = 8_000L

    /** Humor del asistente con el ritmo de ahora. */
    fun humor(base: Int): Int = _state.value.rhythm?.humor(base) ?: base

    fun start(context: Context, scope: CoroutineScope) {
        if (job != null) return
        val ctx = context.applicationContext
        job = scope.launch {
            while (isActive) {
                runCatching { tick(ctx, scope) }
                delay(1000)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    /** Para las capturas y la Demo: fija lo que se enseña. */
    fun preview(s: DriveState?) {
        previewOn = s != null
        if (s != null) _state.value = s
    }

    private fun tick(ctx: Context, scope: CoroutineScope) {
        if (previewOn) return
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val dt = if (lastTick == 0L) 1.0 else ((elapsed - lastTick) / 1000.0).coerceIn(0.0, 5.0)
        lastTick = elapsed
        val st = AppSettings(ctx)
        val car = VehicleRepository.state.value
        val tel = Telemetry.state.value
        val route = RouteTracker.state.value
        val demo = VehicleRepository.status.value.sourceName == "Demo"
        val speed = car.speedKmh?.toFloat()

        // Viaje nuevo: se empieza de cero.
        val active = TripComputer.state.value.current != null
        if (active != tripActive) {
            tripActive = active
            if (!active) { meter.reset(); coast.reset(); road.reset(); rhythmSaid.clear(); ahead.reset() }
        }

        // ── Ritmo ──
        val t = LocalDateTime.now()
        val weekend = t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY
        val gear = car.gear ?: GearEstimator.estimate(car.speedKmh, car.rpm)
        val changed = meter.feed(now, speed, tel.longG, gear, weekend, t.hour)
        if (changed != null && changed !in rhythmSaid && (active || demo) && st.assistantEnabled && st.copilotEnabled) {
            rhythmSaid += changed
            queue(RouteAlert(
                id = "rhythm.${changed.name}.$now", category = AlertCategory.ROAD,
                title = "Ritmo: ${changed.label}", subtitle = rhythmHint(changed),
                speech = DriveLines.rhythm(changed, humor(st.assistantHumor)),
            ))
        }

        // ── Carretera ──
        val wind = if (demo || st.weatherPlace == null) WeatherRepository.state.value.forecast?.let { Wind.now(it) } else null
        val load = speed?.let { RoadFeel.load(it, tel.slopePct, wind?.fromDeg, wind?.kmh, route.bearing) }
        coast.feed(elapsed / 1000f, speed, car.rpm, tel.slopePct, load?.headwindKmh ?: 0f)
        val excess = coast.excessPct
        // La línea del cuadro se queda unos segundos aunque deje de notarse, para que no parpadee.
        val fresh = load?.let { RoadFeel.label(it, excess) }
        if (fresh != null) { lastLabel = fresh; labelUntil = now + LABEL_HOLD_MS }
        val label = fresh ?: lastLabel.takeIf { now < labelUntil && (speed ?: 0f) >= RoadFeel.MIN_SPEED_KMH }
        val reading = load?.let { RoadReading(it, label, excess) }
        val roadOn = st.alertOn(AlertCategory.ROAD) && _state.value.rhythm != Rhythm.CIUDAD
        if (load != null && roadOn) road.tick(load, excess, now)?.let { k ->
            val h = humor(st.assistantHumor)
            val speech = when (k) {
                RoadKind.SUBIDA -> DriveLines.climb(load, h)
                RoadKind.BAJADA -> DriveLines.descent(load, h)
                RoadKind.CARA -> DriveLines.headwind(load, h)
                RoadKind.COLA -> DriveLines.tailwind(load, h)
                RoadKind.ARRASTRE -> DriveLines.drag(excess ?: 0, h)
            }
            queue(RouteAlert(
                id = "road.${k.name}.$now", category = AlertCategory.ROAD,
                title = roadTitle(k, load, excess), subtitle = if (k == RoadKind.ARRASTRE) "Prueba en punto muerto" else DriveLines.effort(load),
                speech = speech,
            ))
        }

        // ── Cobertura ──
        route.fix?.takeIf { it.fromGps }?.let { lastGpsAt = it.elapsedMs }
        val gpsAge = lastGpsAt?.let { elapsed - it }
        val netOk = netOk(ctx)
        val estimatedBefore = route.position.takeIf { route.estimated }
        val ev = if (demo) null else coverage.tick(elapsed, gpsAge, speed, netOk)
        if (coverage.blind && speed != null) {
            // Sin ninguna posición reciente (ni de la red): se estima con el CAN y el rumbo.
            val anyAge = route.fix?.let { elapsed - it.elapsedMs } ?: Long.MAX_VALUE
            if (anyAge > 3_000L) RouteTracker.deadReckon(speed, dt)
        }
        val coverOn = st.alertOn(AlertCategory.ROAD)
        val h = humor(st.assistantHumor)
        when (ev) {
            CoverageEvent.BLIND_IN -> if (coverOn) queue(RouteAlert(
                id = "cover.in.$now", category = AlertCategory.ROAD, title = "Navegación a ciegas",
                subtitle = "Velocidad del coche + último rumbo", speech = DriveLines.blindIn(!netOk, h),
            ))
            CoverageEvent.BLIND_OUT -> {
                val drift = estimatedBefore?.let { e -> RouteTracker.state.value.position?.let { RouteTracker.distance(e, it).roundToInt() } }
                if (netOk) refresh(ctx, scope)
                if (coverOn) queue(RouteAlert(
                    id = "cover.out.$now", category = AlertCategory.ROAD, title = "Señal recuperada",
                    subtitle = drift?.let { "Desvío a ciegas: $it m" } ?: "Posición real del GPS",
                    speech = DriveLines.blindOut(drift, sunny(), netOk, h),
                ))
            }
            CoverageEvent.NO_DATA -> if (coverOn) queue(RouteAlert(
                id = "cover.nodata.$now", category = AlertCategory.ROAD, title = "Sin datos",
                subtitle = "Sigo con lo guardado", speech = DriveLines.noData(h),
            ))
            CoverageEvent.DATA_BACK -> {
                refresh(ctx, scope)
                if (coverOn) queue(RouteAlert(
                    id = "cover.back.$now", category = AlertCategory.ROAD, title = "Cobertura de vuelta",
                    subtitle = "Actualizando", speech = DriveLines.dataBack(h),
                ))
            }
            null -> if (netOk && coverage.dataPending && !coverage.blind) refresh(ctx, scope)
        }

        // ── Carretera por delante: curvas cerradas y pendientes fuertes ──
        val pos = route.position
        if (pos != null && speed != null && speed >= 30f && !coverage.blind && st.alertOn(AlertCategory.SAFETY)) {
            val (path, curves) = com.cockpitf30.nav.RoadAhead.update(scope, pos, route.bearing, netOk)
            ahead.tick(now, speed, path, curves, com.cockpitf30.nav.RoadAhead.gradesNow(pos))?.let { e ->
                queue(when (e) {
                    is AheadEvent.Curve -> RouteAlert(
                        id = "ahead.curve.$now", category = AlertCategory.SAFETY,
                        title = DriveLines.curveTitle(e.c), subtitle = "En ${e.inM} m · mejor a ${e.c.adviceKmh} km/h",
                        speech = DriveLines.curve(e.c, e.inM, h),
                    )
                    is AheadEvent.Grade -> RouteAlert(
                        id = "ahead.grade.$now", category = AlertCategory.SAFETY,
                        title = DriveLines.gradeTitle(e.g), subtitle = "En ${e.inM} m · unos ${e.g.lengthM.roundToInt()} m",
                        speech = DriveLines.grade(e.g, e.inM, h),
                    )
                })
            }
        }

        _state.value = DriveState(
            road = reading, rhythm = meter.rhythm, stats = meter.stats,
            blind = coverage.blind, netOk = netOk, coastSegments = coast.segments,
        )

        // Lo que está esperando a que el asistente quede libre (como mucho 30 s).
        pending?.let { p ->
            // Lo de la carretera por delante solo vale unos segundos: luego ya no está delante.
            if (now - p.at > (if (p.id.startsWith("ahead.")) 8_000L else 30_000L)) pending = null
            else if (now >= RouteAlerts.holdUntil && RouteAlerts.deliverNow(p)) pending = null
        }
    }

    private fun queue(a: RouteAlert) {
        // Lo de la cobertura pasa por delante de lo demás.
        if (pending == null || a.id.startsWith("cover.") || a.id.startsWith("ahead.")) pending = a
    }

    private fun refresh(ctx: Context, scope: CoroutineScope) {
        coverage.refreshed()
        scope.launch {
            runCatching { WeatherRepository.refresh(ctx) }
            runCatching { com.cockpitf30.weather.RouteWeather.refresh(ctx) }
            com.cockpitf30.nav.RoutePois.requestRefresh()
        }
    }

    private fun netOk(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(true)

    /** De día y con el cielo despejado o casi (para el «y sol» al salir del túnel). */
    private fun sunny(): Boolean {
        val f = WeatherRepository.state.value.forecast ?: return false
        return f.isDay && wmoCondition(f.code).kind in setOf(WeatherKind.CLEAR, WeatherKind.PARTLY_CLOUDY)
    }

    private fun roadTitle(k: RoadKind, l: RoadLoad, excess: Int?): String = when (k) {
        RoadKind.SUBIDA -> "Subida ${l.slopePct.roundToInt()} %"
        RoadKind.BAJADA -> "Bajada ${(-l.slopePct).roundToInt()} %"
        RoadKind.CARA -> "Viento de cara ${l.headwindKmh.roundToInt()} km/h"
        RoadKind.COLA -> "Viento de cola ${(-l.headwindKmh).roundToInt()} km/h"
        RoadKind.ARRASTRE -> "Resistencia extra +${excess ?: 0} %"
    }

    private fun rhythmHint(r: Rhythm) = when (r) {
        Rhythm.PASEO -> "Más charla y humor"
        Rhythm.AUTOPISTA -> "Frases breves y al grano"
        Rhythm.CIUDAD -> "Solo avisos prácticos"
    }
}
