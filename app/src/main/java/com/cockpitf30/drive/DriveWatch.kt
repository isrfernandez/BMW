package com.cockpitf30.drive

/** Qué toca decir de la carretera. */
enum class RoadKind { SUBIDA, BAJADA, CARA, COLA, ARRASTRE }

/**
 * Decide cuándo comentar la carretera: solo si la situación se mantiene un rato ([steadyMs], una
 * cuesta de verdad y no un repecho), con un mínimo entre comentarios y sin repetir el mismo tipo
 * enseguida.
 */
class RoadWatch(
    private val steadyMs: Long = 25_000L,
    private val gapMs: Long = 6 * 60_000L,
    private val sameGapMs: Long = 20 * 60_000L,
) {
    private var candidate: RoadKind? = null
    private var since = 0L
    private var lastAt = 0L
    private val lastBy = HashMap<RoadKind, Long>()
    private var dragSaid = false

    fun reset() { candidate = null; since = 0L; lastAt = 0L; lastBy.clear(); dragSaid = false }

    /** Lo que se nota ahora, o null. */
    fun kind(l: RoadLoad, dragExcessPct: Int?): RoadKind? = when {
        l.speedKmh < 50f -> null
        l.slopePct >= 4f && l.extraPct >= 30 -> RoadKind.SUBIDA
        l.slopePct <= -4f -> RoadKind.BAJADA
        l.headwindKmh >= 20f && l.extraPct >= 25 && kotlin.math.abs(l.slopePct) < 3f -> RoadKind.CARA
        l.headwindKmh <= -20f && kotlin.math.abs(l.slopePct) < 3f -> RoadKind.COLA
        !dragSaid && (dragExcessPct ?: 0) >= 25 -> RoadKind.ARRASTRE
        else -> null
    }

    /** Cada segundo; devuelve el tipo del que hay que hablar ya, si toca. */
    fun tick(l: RoadLoad, dragExcessPct: Int?, now: Long): RoadKind? {
        val k = kind(l, dragExcessPct)
        if (k == null) { candidate = null; return null }
        if (k != candidate) { candidate = k; since = now; return null }
        if (now - since < steadyMs) return null
        if (lastAt != 0L && now - lastAt < gapMs) return null
        if ((lastBy[k]?.let { now - it < sameGapMs }) == true) return null
        lastAt = now; lastBy[k] = now
        if (k == RoadKind.ARRASTRE) dragSaid = true
        candidate = null
        return k
    }
}

/** Qué pasa con la cobertura ahora. */
enum class CoverageEvent { BLIND_IN, BLIND_OUT, NO_DATA, DATA_BACK }

/**
 * Túnel o zona sin cobertura: el GPS deja de mandar posiciones con el coche en marcha (a ciegas) o
 * se va internet. Mientras se está a ciegas, la posición se estima con la velocidad del CAN y el
 * último rumbo (eso lo hace [com.cockpitf30.nav.RouteTracker.deadReckon]).
 *
 * Los túneles cortos (puentes, pasos inferiores) no se anuncian: solo a partir de [announceMs].
 */
class CoverageWatch(
    /** Sin posición del GPS desde hace esto, con el coche en marcha: a ciegas. */
    private val lostMs: Long = 3_000L,
    private val announceMs: Long = 10_000L,
    private val noDataMs: Long = 60_000L,
    private val noDataGapMs: Long = 30 * 60_000L,
) {
    var blind = false
        private set
    /** Se dijo que íbamos a ciegas (para hacer luego el guiño al salir). */
    var blindAnnounced = false
        private set
    var blindSince = 0L
        private set
    private var netLostSince = 0L
    private var noDataAnnounced = false
    private var lastNoDataAt = 0L
    /** Hubo que esperar por la red (para refrescar lo pendiente al volver). */
    var dataPending = false
        private set

    fun reset() { blind = false; blindAnnounced = false; blindSince = 0L; netLostSince = 0L; noDataAnnounced = false; dataPending = false }

    /**
     * Cada segundo. [gpsAgeMs]: desde la última posición real del GPS (null si nunca ha llegado una,
     * p. ej. en un garaje al arrancar: así no se anuncia nada). [netOk]: internet funciona.
     */
    fun tick(now: Long, gpsAgeMs: Long?, speedKmh: Float?, netOk: Boolean): CoverageEvent? {
        val moving = (speedKmh ?: 0f) >= 15f
        if (!netOk) { if (netLostSince == 0L) netLostSince = now; dataPending = true } else netLostSince = 0L

        if (!blind) {
            if (gpsAgeMs != null && gpsAgeMs >= lostMs && moving) { blind = true; blindSince = now - gpsAgeMs }
        } else if (gpsAgeMs != null && gpsAgeMs < 2_000L) {
            blind = false
            val said = blindAnnounced
            blindAnnounced = false
            if (said) { if (netOk) { dataPending = false; noDataAnnounced = false }; return CoverageEvent.BLIND_OUT }
        }
        if (blind && !blindAnnounced && now - blindSince >= announceMs && moving) {
            blindAnnounced = true
            return CoverageEvent.BLIND_IN
        }
        if (!blind && netOk && noDataAnnounced) {
            noDataAnnounced = false
            dataPending = false
            return CoverageEvent.DATA_BACK
        }
        if (!blind && !netOk && !noDataAnnounced && moving && now - netLostSince >= noDataMs &&
            (lastNoDataAt == 0L || now - lastNoDataAt >= noDataGapMs)
        ) {
            noDataAnnounced = true
            lastNoDataAt = now
            return CoverageEvent.NO_DATA
        }
        return null
    }

    /** Lo pendiente ya se actualizó (red de vuelta sin haberlo anunciado). */
    fun refreshed() { dataPending = false }
}
