package com.cockpitf30.vehicle.telemetry

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import com.cockpitf30.vehicle.VehicleRepository
import com.cockpitf30.vehicle.telemetry.TelemetryMath.V3
import com.cockpitf30.weather.WeatherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Punto del perfil de altitud: kilómetro recorrido y cota en metros. */
data class AltPoint(val km: Float, val altM: Float)

/** De dónde salen las fuerzas G. */
enum class GSource(val label: String) { SENSOR("Acelerómetro"), GPS("Velocidad y GPS"), NONE("Sin datos") }

data class TelemetryState(
    /** Fuerza G longitudinal: + acelerando, − frenando. */
    val longG: Float = 0f,
    /** Fuerza G lateral: + girando a la derecha. */
    val latG: Float = 0f,
    val gSource: GSource = GSource.NONE,
    /** Últimos segundos de fuerzas G (long, lat) para la estela del G-meter. */
    val trail: List<Pair<Float, Float>> = emptyList(),
    val peakAccelG: Float = 0f,
    val peakBrakeG: Float = 0f,
    val peakLatG: Float = 0f,
    /** Potencia y par estimados del motor (0 si no empuja, null sin datos de velocidad). */
    val powerCv: Float? = null,
    val torqueNm: Float? = null,
    val peakCv: Float = 0f,
    val peakNm: Float = 0f,
    val altitudeM: Float? = null,
    /** Pendiente actual en %, + subida. */
    val slopePct: Float? = null,
    val ascentM: Float = 0f,
    val descentM: Float = 0f,
    val profile: List<AltPoint> = emptyList(),
    val altSource: String = "Sin datos",
    /** Luz ambiente en lux si la radio tiene sensor de luz. */
    val lux: Float? = null,
    /** El acelerómetro tiene ya la gravedad del coche parado. */
    val calibrated: Boolean = false,
)

/**
 * Telemetría calculada: fuerzas G (acelerómetro de la radio si tiene, si no velocidad y rumbo del
 * GPS), potencia y par estimados con la física del coche, y altitud con su pendiente (barómetro
 * si lo hay, si no GPS, corregido con la cota del terreno de Open-Meteo para quitar el error del
 * geoide). Corre en el servicio del coche, así que el perfil sigue aunque la app no esté delante.
 */
object Telemetry {
    private const val TICK_MS = 100L
    private const val TRAIL = 40
    private const val PROFILE_STEP_M = 25f
    private const val PROFILE_MAX = 1200
    private const val SLOPE_WINDOW_M = 200f

    private val _state = MutableStateFlow(TelemetryState())
    val state: StateFlow<TelemetryState> = _state.asStateFlow()

    private var job: Job? = null
    private var sensors: SensorManager? = null
    private var locations: LocationManager? = null
    private var appContext: Context? = null

    // Acelerómetro: lectura filtrada, gravedad en reposo y media lenta (por si aún no se calibró).
    @Volatile private var acc: V3? = null
    @Volatile private var gravity: V3? = null
    @Volatile private var slowAcc: V3? = null
    private var stillSince = 0L

    // Barómetro y luz.
    @Volatile private var baroAlt: Float? = null
    @Volatile private var lux: Float? = null

    // GPS.
    @Volatile private var gpsAlt: Float? = null
    @Volatile private var gpsSpeed: Float? = null
    @Volatile private var lastFix: Location? = null
    private var yawRate = 0f
    private var lastBearing: Float? = null
    private var lastBearingAt = 0L

    // Corrección de la altitud: cota del terreno menos lo que da el GPS (o el barómetro).
    @Volatile private var demOffset: Float? = null
    private var baroOffset: Float? = null
    private var lastDemAt = 0L

    private val speedT = FloatArray(10)
    private val speedV = FloatArray(10)
    private var speedN = 0
    private var startAt = 0L

    private var meters = 0f
    private var lastProfileM = -1f
    private var altSmooth: Float? = null
    private var climbRef: Float? = null

    private var longF = 0f
    private var latF = 0f
    private var powerF = 0f

    @SuppressLint("MissingPermission")
    fun start(context: Context, scope: CoroutineScope) {
        if (job != null) return
        val ctx = context.applicationContext
        appContext = ctx
        gravity = loadGravity(ctx)
        val sm = ctx.getSystemService(SensorManager::class.java)
        sensors = sm
        sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME) }
        sm?.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sm?.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        startGps(ctx)
        startAt = SystemClock.elapsedRealtime()
        job = scope.launch {
            while (isActive) {
                tick()
                maybeFetchElevation()
                delay(TICK_MS)
            }
        }
    }

    /** Se puede llamar otra vez al conceder el permiso de ubicación. */
    @SuppressLint("MissingPermission")
    fun startGps(context: Context) {
        if (locations != null || !WeatherRepository.hasLocationPermission(context)) return
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        runCatching {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, Looper.getMainLooper())
                locations = lm
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        sensors?.unregisterListener(sensorListener); sensors = null
        locations?.removeUpdates(gpsListener); locations = null
    }

    /** Guarda como «parado y recto» la lectura actual del acelerómetro. */
    fun calibrate() {
        val a = acc ?: return
        gravity = a
        appContext?.let { saveGravity(it, a) }
        _state.value = _state.value.copy(calibrated = true)
    }

    fun resetPeaks() {
        _state.value = _state.value.copy(peakAccelG = 0f, peakBrakeG = 0f, peakLatG = 0f, peakCv = 0f, peakNm = 0f)
    }

    fun resetProfile() {
        meters = 0f; lastProfileM = -1f; climbRef = null
        _state.value = _state.value.copy(profile = emptyList(), ascentM = 0f, descentM = 0f, slopePct = null)
    }

    /** Sensores de la radio, para Diagnóstico. */
    fun sensorList(context: Context): List<String> {
        val sm = context.getSystemService(SensorManager::class.java) ?: return listOf("(sin SensorManager)")
        val all = sm.getSensorList(Sensor.TYPE_ALL)
        if (all.isEmpty()) return listOf("La radio no tiene sensores (ni acelerómetro ni luz): G por GPS, altitud por GPS")
        return all.map { "${it.stringType.removePrefix("android.sensor.")} · ${it.name} (${it.vendor})" }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    val r = V3(e.values[0], e.values[1], e.values[2])
                    acc = acc?.let { lerp(it, r, 0.12f) } ?: r
                    slowAcc = slowAcc?.let { lerp(it, r, 0.002f) } ?: r
                }
                Sensor.TYPE_PRESSURE -> {
                    val alt = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, e.values[0])
                    baroAlt = baroAlt?.let { it + (alt - it) * 0.2f } ?: alt
                }
                Sensor.TYPE_LIGHT -> lux = e.values[0]
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val gpsListener = LocationListener { loc ->
        lastFix = loc
        if (loc.hasAltitude()) gpsAlt = loc.altitude.toFloat()
        gpsSpeed = if (loc.hasSpeed()) loc.speed else null
        val now = SystemClock.elapsedRealtime()
        if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 3f) {
            val prev = lastBearing
            if (prev != null && now - lastBearingAt in 1..3000) {
                var d = loc.bearing - prev
                if (d > 180) d -= 360
                if (d < -180) d += 360
                val rate = Math.toRadians(d.toDouble()).toFloat() / ((now - lastBearingAt) / 1000f)
                yawRate += (rate - yawRate) * 0.6f
            }
            lastBearing = loc.bearing
            lastBearingAt = now
        } else {
            yawRate *= 0.5f
            lastBearing = null
        }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val car = VehicleRepository.state.value
        val vKmh = car.speedKmh?.toFloat() ?: gpsSpeed?.let { it * 3.6f }
        val v = vKmh?.div(3.6f)
        val t = (now - startAt) / 1000f

        // Aceleración por la velocidad (recta de los últimos 10 datos, ~1 s).
        var aSpeed: Float? = null
        if (v != null) {
            if (speedN == speedT.size) {
                System.arraycopy(speedT, 1, speedT, 0, speedN - 1)
                System.arraycopy(speedV, 1, speedV, 0, speedN - 1)
                speedN--
            }
            speedT[speedN] = t; speedV[speedN] = v; speedN++
            aSpeed = TelemetryMath.slope(speedT, speedV, speedN)
            meters += v * TICK_MS / 1000f
        } else speedN = 0

        // Gravedad: si el coche lleva 3 s parado, la lectura del acelerómetro es la referencia.
        val a = acc
        if (a != null && (vKmh ?: 0f) < 0.5f) {
            if (stillSince == 0L) stillSince = now
            if (now - stillSince > 3000) {
                val g = gravity
                gravity = if (g == null) a else lerp(g, a, 0.02f)
                if (g == null || now % 30_000 < TICK_MS) appContext?.let { saveGravity(it, gravity!!) }
            }
        } else stillSince = 0L

        val ref = gravity ?: slowAcc
        val (rawLong, rawLat, source) = when {
            a != null && ref != null -> TelemetryMath.gForces(a, ref).let { Triple(it.first, it.second, GSource.SENSOR) }
            v != null -> Triple((aSpeed ?: 0f) / TelemetryMath.G, v * yawRate / TelemetryMath.G, GSource.GPS)
            else -> Triple(0f, 0f, GSource.NONE)
        }
        longF += (rawLong.coerceIn(-1.5f, 1.5f) - longF) * 0.35f
        latF += (rawLat.coerceIn(-1.5f, 1.5f) - latF) * 0.35f

        // Altitud.
        val alt = altitude()
        val s = _state.value
        var profile = s.profile
        var ascent = s.ascentM
        var descent = s.descentM
        var slopePct = s.slopePct
        if (alt != null) {
            altSmooth = altSmooth?.let { it + (alt - it) * (if (baroAlt != null) 0.3f else 0.08f) } ?: alt
            val sm = altSmooth!!
            val hyst = if (baroAlt != null) 1.5f else 4f
            val c = climbRef
            if (c == null) climbRef = sm
            else if (sm - c > hyst) { ascent += sm - c; climbRef = sm }
            else if (c - sm > hyst) { descent += c - sm; climbRef = sm }
            if (lastProfileM < 0 || meters - lastProfileM >= PROFILE_STEP_M) {
                lastProfileM = meters
                profile = (profile + AltPoint(meters / 1000f, sm)).let { if (it.size > PROFILE_MAX) it.drop(it.size - PROFILE_MAX) else it }
                val back = profile.lastOrNull { meters / 1000f - it.km >= SLOPE_WINDOW_M / 1000f }
                    ?: profile.firstOrNull()?.takeIf { meters / 1000f - it.km >= 0.08f }
                slopePct = back?.let { ((sm - it.altM) / ((meters / 1000f - it.km) * 1000f) * 100f).coerceIn(-30f, 30f) }
            }
        }

        // Potencia y par.
        var cv: Float? = null
        var nm: Float? = null
        if (v != null && aSpeed != null) {
            val p = if (v < 1.5f) 0f else TelemetryMath.enginePowerW(v, aSpeed, (slopePct ?: 0f) / 100f).coerceAtLeast(0f)
            powerF += (p - powerF) * 0.3f
            cv = (powerF / TelemetryMath.W_PER_CV).coerceIn(0f, 300f)
            nm = (TelemetryMath.torqueNm(powerF, car.rpm) ?: 0f).coerceIn(0f, 600f)
        }

        _state.value = s.copy(
            longG = longF, latG = latF, gSource = source,
            trail = (s.trail + (longF to latF)).takeLast(TRAIL),
            peakAccelG = max(s.peakAccelG, longF), peakBrakeG = max(s.peakBrakeG, -longF), peakLatG = max(s.peakLatG, abs(latF)),
            powerCv = cv, torqueNm = nm,
            peakCv = max(s.peakCv, cv ?: 0f), peakNm = max(s.peakNm, nm ?: 0f),
            altitudeM = altSmooth, slopePct = slopePct, ascentM = ascent, descentM = descent, profile = profile,
            altSource = when {
                baroAlt != null -> "Barómetro"
                gpsAlt != null && demOffset != null -> "GPS + mapa del terreno"
                gpsAlt != null -> "GPS"
                else -> "Sin datos"
            },
            lux = lux,
            calibrated = gravity != null,
        )
    }

    /** Altitud sobre el nivel del mar con lo que haya. */
    private fun altitude(): Float? {
        val g = gpsAlt?.let { it + (demOffset ?: 0f) }
        val b = baroAlt ?: return g
        // El barómetro da muy bien los cambios; el nivel se ajusta despacio al GPS corregido.
        if (g != null) baroOffset = baroOffset?.let { it + ((g - b) - it) * 0.002f } ?: (g - b)
        return b + (baroOffset ?: 0f)
    }

    /** Cada 2 minutos, la cota del terreno donde estamos (Open-Meteo) para corregir el GPS. */
    private suspend fun maybeFetchElevation() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDemAt < 120_000) return
        val fix = lastFix ?: return
        val gps = gpsAlt ?: return
        lastDemAt = now
        val dem = withContext(Dispatchers.IO) { runCatching { fetchElevation(fix.latitude, fix.longitude) }.getOrNull() } ?: return
        val off = dem - gps
        // Con el coche en marcha el GPS baila: media suave salvo la primera vez.
        demOffset = demOffset?.let { it + (off - it) * 0.3f } ?: off
    }

    private fun fetchElevation(lat: Double, lon: Double): Float? {
        val url = URL("https://api.open-meteo.com/v1/elevation?latitude=%.5f&longitude=%.5f".format(Locale.US, lat, lon))
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        return try {
            if (c.responseCode != 200) null
            else JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("elevation")?.optDouble(0)?.toFloat()?.takeIf { !it.isNaN() }
        } finally {
            c.disconnect()
        }
    }

    private fun lerp(a: V3, b: V3, k: Float) = V3(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k, a.z + (b.z - a.z) * k)

    private fun loadGravity(ctx: Context): V3? {
        val p = ctx.getSharedPreferences("cockpit", Context.MODE_PRIVATE)
        if (!p.contains("grav_x")) return null
        return V3(p.getFloat("grav_x", 0f), p.getFloat("grav_y", 0f), p.getFloat("grav_z", 0f))
    }

    private fun saveGravity(ctx: Context, g: V3) {
        ctx.getSharedPreferences("cockpit", Context.MODE_PRIVATE).edit()
            .putFloat("grav_x", g.x).putFloat("grav_y", g.y).putFloat("grav_z", g.z).apply()
    }

    /** Para las capturas y la Demo. */
    fun preview(state: TelemetryState) { _state.value = state }

}
