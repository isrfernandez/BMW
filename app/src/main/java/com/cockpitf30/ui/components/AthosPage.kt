package com.cockpitf30.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.cockpitf30.nav.RouteTracker
import com.cockpitf30.nav.SpeedCameras
import com.cockpitf30.ui.theme.numeric
import com.cockpitf30.vehicle.VehicleRepository
import com.cockpitf30.vehicle.telemetry.Telemetry
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Vista «Athos» del centro del cuadro: la imagen de inicio con las lecturas de la app en marcha
 * (velocidad, rpm, fuerzas G, altitud, GPS, caja CAN, radares) y un anillo que gira alrededor del
 * triángulo, como si el sistema lo estuviera vigilando todo. Todo son datos reales o derivados de
 * ellos (la radio no tiene acelerómetro: las G salen de la velocidad y el rumbo).
 *
 * Se pinta en su propia capa y solo mientras se ve ([AthosFeed.active]): el reloj [AthosFeed.clock]
 * es lo único que la hace repintar (unas 30 veces por segundo).
 */
internal class AthosFeed {
    /** Reloj de la animación en ms; leerlo al pintar hace que la capa se repinte con él. */
    val clock = mutableLongStateOf(0L)
    val speed = Ring(120)
    val rpm = Ring(120)
    val g = Ring(120)
    val alt = Ring(120)
    val log = ArrayDeque<String>()
    var canRate = 0f
    private var lastFrames = -1L
    private var lastFramesAt = 0L
    private var logStep = 0

    class Ring(val n: Int) {
        val v = FloatArray(n)
        var count = 0
        fun add(x: Float) { System.arraycopy(v, 1, v, 0, n - 1); v[n - 1] = x; if (count < n) count++ }
        fun max(): Float = v.maxOrNull()?.takeIf { it > 0f } ?: 1f
    }

    fun sample(now: Long) {
        val vs = VehicleRepository.state.value
        val r = RouteTracker.state.value
        val t = Telemetry.state.value
        speed.add((vs.speedKmh?.toFloat() ?: r.fix?.speedKmh ?: 0f))
        rpm.add((vs.rpm ?: 0).toFloat())
        g.add(sqrt(t.longG * t.longG + t.latG * t.latG))
        alt.add(t.altitudeM ?: 0f)
        val frames = VehicleRepository.status.value.framesReceived
        if (lastFrames >= 0 && now > lastFramesAt) canRate = (frames - lastFrames) * 1000f / (now - lastFramesAt)
        lastFrames = frames; lastFramesAt = now
    }

    /** Una línea nueva del registro, rotando entre fuentes, con valores reales. */
    fun addLog() {
        val time = java.time.LocalTime.now().withNano(0).toString()
        val r = RouteTracker.state.value
        val t = Telemetry.state.value
        val st = VehicleRepository.status.value
        val cams = SpeedCameras.state.value
        val line = when (logStep++ % 6) {
            0 -> r.position?.let { p -> "GPS   ±${r.fix?.accuracyM?.roundToInt() ?: "?"} m  ${coord(p.lat, "N", "S")}  ${coord(p.lon, "E", "O")}" } ?: "GPS   buscando satélites…"
            1 -> "IMU   long ${gTxt(t.longG)}  lat ${gTxt(t.latG)}  (${t.gSource.name.lowercase()})"
            2 -> "CAN   ${st.framesReceived} tramas · ${canRate.roundToInt()}/s · ${st.sourceName}"
            3 -> cams.ahead?.let { "RADAR delante a ${radarDistance(it.meters)}" } ?: "RADAR ${cams.near.size} en zona · ${cams.cameras.size} cargados"
            4 -> "ALT   ${t.altitudeM?.roundToInt() ?: "?"} m · pendiente ${t.slopePct?.let { "%+.0f %%".format(Locale.US, it) } ?: "?"} · ${t.altSource}"
            else -> "RUMBO ${r.bearing.roundToInt()}° ${compass(r.bearing)} · luz ${t.lux?.roundToInt()?.let { "$it lx" } ?: "—"}"
        }
        log.addFirst("$time  $line")
        while (log.size > 4) log.removeLast()
    }
}

private fun coord(v: Double, pos: String, neg: String) = "%.5f %s".format(Locale.US, abs(v), if (v >= 0) pos else neg)
private fun gTxt(v: Float) = "%+.2f g".format(Locale.US, v).replace('.', ',')
private fun compass(b: Float) = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")[(((b % 360) + 360) % 360 / 45f).roundToInt() % 8]

/** Reloj y muestreo de la vista Athos: solo corren mientras [visible] devuelve true. */
@Composable
internal fun rememberAthosFeed(visible: () -> Boolean): AthosFeed {
    val feed = remember { AthosFeed() }
    LaunchedEffect(feed) {
        var lastSample = 0L
        var lastLog = 0L
        while (true) {
            if (!visible()) { delay(250); continue }
            val now = System.currentTimeMillis()
            if (now - lastSample >= 250) { feed.sample(now); lastSample = now }
            if (now - lastLog >= 1_700) { feed.addLog(); lastLog = now }
            feed.clock.longValue = now
            delay(33)
        }
    }
    return feed
}

/** Estilos de la vista (se crean una vez). */
internal class AthosText(
    val label: TextStyle = numeric(13, FontWeight.Medium).copy(letterSpacing = 2.5.sp),
    val value: TextStyle = numeric(30, FontWeight.SemiBold),
    val unit: TextStyle = numeric(14, FontWeight.Medium),
    val mono: TextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
    val hud: TextStyle = numeric(15, FontWeight.SemiBold).copy(letterSpacing = 3.sp),
)

/** Altura del centro del anillo (y del triángulo) en el panel, como fracción del alto. */
private const val RING_Y = 0.52f

private val AthosBlue = Color(0xFF59B8FF)
private val AthosCopper = Color(0xFFFFA25C)
private val AthosOk = Color(0xFF3DDC97)
private val Dim = Color.White.copy(alpha = 0.55f)

/** Pinta la vista Athos en [b] (ya recortada a la forma del panel). */
internal fun DrawScope.drawAthos(b: Rect, image: ImageBitmap, feed: AthosFeed, tm: TextMeasurer, st: AthosText) {
    val t = feed.clock.longValue
    val w = b.width; val h = b.height

    // 1) Fondo: la imagen a la escala justa para que el triángulo entero ocupe el 80 % del alto,
    //    centrado en el panel y a la altura del anillo, con un vaivén muy lento.
    val k = h * 0.80f / (TRI_Y1 - TRI_Y0) * (1.0f + 0.02f * sin(t / 7000f))
    val dw = image.width * k; val dh = image.height * k
    val left = b.center.x - (TRI_X0 + TRI_X1) / 2 * k + sin(t / 11000f) * w * 0.006f
    val topY = b.top + h * RING_Y - (TRI_Y0 + TRI_Y1) / 2 * k
    drawRect(Color.Black, b.topLeft, b.size)
    drawImage(image, IntOffset.Zero, IntSize(image.width, image.height), IntOffset(left.toInt(), topY.toInt()), IntSize(dw.toInt(), dh.toInt()))
    // Bordes de la imagen fundidos a negro si no llega a los lados del panel.
    val fade = w * 0.05f
    drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = left, endX = left + fade), Offset(left, b.top), Size(fade, h))
    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = left + dw - fade, endX = left + dw), Offset(left + dw - fade, b.top), Size(fade, h))
    // Equivalente como recorte de la imagen (para situar el triángulo al hablar).
    val sw = w / k; val sh = h / k
    val sx = (b.left - left) / k; val sy = (b.top - topY) / k
    // 1b) Cuando habla el asistente, el triángulo «habla»: una onda lo difumina desde su centro,
    //     brilla y lanza ondas, todo al ritmo de la voz (sin desplazarlo).
    val speaking = com.cockpitf30.assistant.VoiceAssistant.state.value.phase == com.cockpitf30.assistant.AssistantPhase.ANSWERING
    val lvl = if (speaking) com.cockpitf30.assistant.SpeechPulse.level(t) else 0f
    if (lvl > 0.02f) drawTriangleVoice(b, image, sx, sy, sw, sh, lvl, t)
    // Laterales oscurecidos para leer las cifras; el centro (el triángulo) queda limpio.
    drawRect(
        Brush.horizontalGradient(
            0f to Color.Black.copy(alpha = 0.78f), 0.30f to Color.Black.copy(alpha = 0.25f),
            0.70f to Color.Black.copy(alpha = 0.25f), 1f to Color.Black.copy(alpha = 0.78f),
            startX = b.left, endX = b.right,
        ),
        b.topLeft, b.size,
    )

    // 2) Barrido de escaneo que baja por la imagen.
    val scanY = b.top + (t % 4200) / 4200f * h
    drawRect(
        Brush.verticalGradient(listOf(Color.Transparent, AthosBlue.copy(alpha = 0.10f), Color.Transparent), startY = scanY - h * 0.06f, endY = scanY + h * 0.01f),
        Offset(b.left, scanY - h * 0.06f), Size(w, h * 0.07f), blendMode = BlendMode.Plus,
    )
    drawLine(AthosBlue.copy(alpha = 0.35f), Offset(b.left, scanY), Offset(b.right, scanY), 1.2f, blendMode = BlendMode.Plus)

    // 3) Anillo alrededor del triángulo: escala que gira, arcos de cobre en sentido contrario,
    //    barrido de radar con los radares cercanos y el rumbo arriba.
    val c = Offset(b.center.x, b.top + h * RING_Y)
    val r = h * 0.36f
    val route = RouteTracker.state.value
    val spin = (t / 60f) % 360f
    rotate(spin, c) {
        drawCircle(AthosBlue.copy(alpha = 0.55f), r, c, style = Stroke(1.6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 9f))))
    }
    rotate(-spin * 0.6f, c) {
        for (i in 0 until 3) {
            drawArc(
                AthosCopper.copy(alpha = 0.7f), i * 120f, 38f, false,
                Offset(c.x - r * 0.9f, c.y - r * 0.9f), Size(r * 1.8f, r * 1.8f), style = Stroke(2.4f, cap = StrokeCap.Round),
            )
        }
    }
    // Escala de rumbo (marcas cada 5°) fija al norte; el rumbo actual se marca arriba.
    rotate(-route.bearing, c) {
        for (i in 0 until 72) {
            val a = Math.toRadians(i * 5.0 - 90).toFloat()
            val long = i % 6 == 0
            val r0 = r * 1.07f; val r1 = r * (if (long) 1.14f else 1.10f)
            drawLine(Color.White.copy(alpha = if (long) 0.6f else 0.25f), Offset(c.x + cos(a) * r0, c.y + sin(a) * r0), Offset(c.x + cos(a) * r1, c.y + sin(a) * r1), if (long) 2f else 1f)
        }
    }
    val sweep = (t / 12f) % 360f
    drawArc(
        Brush.sweepGradient(listOf(Color.Transparent, AthosBlue.copy(alpha = 0.0f), AthosBlue.copy(alpha = 0.22f)), c),
        sweep - 50f, 50f, true, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), blendMode = BlendMode.Plus,
    )
    // Los radares ya no se pintan sobre el triángulo (con la imagen apenas se veían): van en la
    // cabecera, en una placa oscura (ver más abajo).
    // Indicador de rumbo arriba del anillo.
    val tip = Offset(c.x, c.y - r * 1.17f)
    drawPath(Path().apply { moveTo(tip.x, tip.y + 10f); lineTo(tip.x - 8f, tip.y - 4f); lineTo(tip.x + 8f, tip.y - 4f); close() }, AthosCopper)
    centered(tm, "${route.bearing.roundToInt()}° ${compass(route.bearing)}", st.hud, AthosCopper, Offset(tip.x, tip.y - 26f))

    // 4) Cabecera (los laterales del panel ya llevan las cifras del cuadro): estado de los
    //    sistemas a la izquierda; reloj y la última lectura del registro a la derecha.
    val pad = w * 0.075f
    val top = b.top + h * 0.075f
    val tel = Telemetry.state.value
    val cams = SpeedCameras.state.value
    val blink = if ((t / 600) % 2 == 0L) 1f else 0.35f
    drawCircle(AthosOk.copy(alpha = blink), 5f, Offset(b.left + pad, top + 9f))
    text(tm, "ATHOS · VIGILANCIA ACTIVA", st.label, AthosOk, Offset(b.left + pad + 14f, top))
    var sx2 = b.left + pad + 14f
    listOf(
        "GPS" to (route.live && route.fix != null),
        "CAN" to VehicleRepository.status.value.connected,
        "IMU" to (tel.gSource.name != "NONE"),
        "MAPA" to cams.cameras.isNotEmpty(),
    ).forEach { (name, ok) ->
        drawCircle(if (ok) AthosOk else AthosCopper.copy(alpha = 0.7f), 3.5f, Offset(sx2 - 8f, top + 28f))
        val lay = tm.measure(name, st.mono.copy(color = if (ok) Color.White.copy(alpha = 0.85f) else Dim))
        drawText(lay, topLeft = Offset(sx2, top + 20f))
        sx2 += lay.size.width + 22f
    }
    // Radares: bajo los sistemas, en una placa oscura que se lee sobre la imagen.
    val radarText = cams.ahead?.let { "RADAR DELANTE · ${radarDistance(it.meters)}" + (it.camera.maxspeed?.let { m -> " · $m km/h" } ?: "") }
        ?: cams.near.size.takeIf { it > 0 }?.let { "$it RADAR${if (it == 1) "" else "ES"} EN ZONA" }
    radarText?.let { txt ->
        val lay = tm.measure(txt, st.mono.copy(color = if (cams.ahead != null) Color(0xFFFF8A7A) else Color.White.copy(alpha = 0.85f)))
        val at = Offset(b.left + pad, top + 48f)
        drawRoundRect(Color.Black.copy(alpha = 0.6f), Offset(at.x - 8f, at.y - 4f), Size(lay.size.width + 30f, lay.size.height + 8f), androidx.compose.ui.geometry.CornerRadius(6f))
        drawCircle(Color(0xFFFF4A3A).copy(alpha = if (cams.ahead != null) blink else 0.8f), 4f, Offset(at.x + 2f, at.y + lay.size.height / 2f))
        drawText(lay, topLeft = Offset(at.x + 14f, at.y))
    }
    textRight(tm, java.time.LocalTime.now().withNano(0).toString(), st.label, Dim, Offset(b.right - pad, top))
    feed.log.firstOrNull()?.let { textRight(tm, it, st.mono, AthosBlue, Offset(b.right - pad, top + 20f)) }

    // 5) Etiquetas alrededor del anillo, unidas a él con una línea: cada una con su gráfica.
    val vs = VehicleRepository.state.value
    fun tag(angleDeg: Float, dx: Float, dy: Float, label: String, value: String, ring: AthosFeed.Ring?, color: Color) {
        val a = Math.toRadians(angleDeg.toDouble()).toFloat()
        val anchor = Offset(c.x + cos(a) * r, c.y + sin(a) * r)
        val right = dx > 0
        val at = Offset(c.x + dx, c.y + dy)
        val elbow = Offset(at.x - (if (right) 14f else -14f), at.y + 10f)
        drawLine(color.copy(alpha = 0.6f), anchor, elbow, 1.2f)
        drawCircle(color, 3f, anchor)
        val lLay = tm.measure(label, st.label.copy(color = Dim))
        val vLay = tm.measure(value, st.hud.copy(color = Color.White, fontSize = 19.sp))
        val tw = maxOf(lLay.size.width, vLay.size.width).toFloat()
        val x0 = if (right) at.x else at.x - tw
        // Fondo oscuro detrás de la etiqueta para leerla sobre la imagen.
        drawRoundRect(
            Color.Black.copy(alpha = 0.45f), Offset(x0 - 8f, at.y - lLay.size.height - 4f),
            Size(tw + 16f, lLay.size.height + vLay.size.height + 26f), androidx.compose.ui.geometry.CornerRadius(6f),
        )
        drawText(lLay, topLeft = Offset(x0, at.y - lLay.size.height))
        drawText(vLay, topLeft = Offset(x0, at.y))
        if (ring != null && ring.count > 1) {
            val sw2 = w * 0.075f
            val sx0 = if (right) x0 else at.x - sw2
            spark(ring, Rect(sx0, at.y + vLay.size.height + 3f, sx0 + sw2, at.y + vLay.size.height + 20f), color)
        }
    }
    val spd = vs.speedKmh ?: route.fix?.speedKmh?.roundToInt()
    val gNow = sqrt(tel.longG * tel.longG + tel.latG * tel.latG)
    tag(-150f, -r * 1.55f, -r * 0.55f, "GPS ±${route.fix?.accuracyM?.roundToInt() ?: "—"} m",
        route.position?.let { "%.4f  %.4f".format(Locale.US, it.lat, it.lon) } ?: "SIN POSICIÓN", feed.speed, AthosBlue)
    tag(-30f, r * 1.55f, -r * 0.55f, "FUERZA G", "%.2f g".format(Locale.US, gNow).replace('.', ','), feed.g, AthosBlue)
    tag(165f, -r * 1.5f, r * 0.25f, "ALTITUD", tel.altitudeM?.roundToInt()?.let { "$it m" } ?: "— m", feed.alt, AthosCopper)
    tag(15f, r * 1.5f, r * 0.25f, "CAN · ${spd ?: "—"} km/h", "${feed.canRate.roundToInt()} tramas/s", feed.rpm, AthosCopper)
}

/** Caja del triángulo en athos_view.webp (px de la imagen). */
private const val TRI_X0 = 555f
private const val TRI_X1 = 1040f
private const val TRI_Y0 = 135f
private const val TRI_Y1 = 580f

private fun DrawScope.drawTriangleVoice(b: Rect, image: ImageBitmap, sx: Float, sy: Float, sw: Float, sh: Float, lvl: Float, t: Long) {
    val kx = b.width / sw; val ky = b.height / sh
    fun dx(ix: Float) = b.left + (ix - sx) * kx
    fun dy(iy: Float) = b.top + (iy - sy) * ky
    val center = Offset(dx((TRI_X0 + TRI_X1) / 2), dy(TRI_Y0 + (TRI_Y1 - TRI_Y0) * 0.5f))
    val size = (TRI_Y1 - TRI_Y0) * ky

    // Halo que respira con la voz.
    drawCircle(
        Brush.radialGradient(listOf(AthosBlue.copy(alpha = 0.45f * lvl), AthosBlue.copy(alpha = 0.12f * lvl), Color.Transparent), center, size * (0.55f + 0.15f * lvl)),
        size * (0.55f + 0.15f * lvl), center, blendMode = BlendMode.Plus,
    )
    // Onda y difuminado (sin mover el triángulo): copias cada vez más grandes y tenues desde su
    // centro, como una onda que se abre y lo emborrona, y un leve desenfoque alrededor. La radio
    // tiene Android 10 (sin RenderEffect), así que el desenfoque se hace con copias superpuestas.
    val boxSrc = IntOffset(TRI_X0.toInt(), TRI_Y0.toInt())
    val boxSize = IntSize((TRI_X1 - TRI_X0).toInt(), (TRI_Y1 - TRI_Y0).toInt())
    val bw = (TRI_X1 - TRI_X0) * kx; val bh = (TRI_Y1 - TRI_Y0) * ky
    val breathe = 1f + 0.5f * sin(t / 70f)
    fun copy(scale: Float, alpha: Float, ox: Float = 0f, oy: Float = 0f) {
        val w2 = bw * scale; val h2 = bh * scale
        drawImage(
            image, boxSrc, boxSize,
            IntOffset((center.x - w2 / 2 + ox).toInt(), (center.y - h2 / 2 + oy).toInt()), IntSize(w2.toInt(), h2.toInt()),
            alpha = alpha.coerceIn(0f, 1f), blendMode = BlendMode.Plus,
        )
    }
    // Onda: seis copias que crecen hasta un 7 % con la voz.
    val steps = 6
    for (i in 1..steps) {
        val f = i / steps.toFloat()
        copy(1f + lvl * 0.07f * f * breathe, 0.16f * lvl * (1f - f * 0.8f))
    }
    // Difuminado: copias desplazadas 2–5 px en ocho direcciones.
    val blur = 2f + 3f * lvl
    for (k in 0 until 8) {
        val ang = k * Math.PI / 4
        copy(1f, 0.07f * lvl, (cos(ang) * blur).toFloat(), (sin(ang) * blur).toFloat())
    }
    // Ondas que salen del triángulo mientras habla.
    for (i in 0 until 3) {
        val f = ((t / 1100f) + i / 3f) % 1f
        val rr = size * (0.35f + 0.75f * f)
        drawCircle(AthosBlue.copy(alpha = (1f - f) * 0.55f * lvl), rr, center, style = Stroke(1.5f + 2f * lvl), blendMode = BlendMode.Plus)
    }
    // Línea de «forma de onda» bajo el triángulo, como un ecualizador fino.
    val ww = size * 0.9f
    val wy = center.y + size * 0.56f
    val p = Path()
    val n = 48
    for (i in 0..n) {
        val x = center.x - ww / 2 + ww * i / n
        val env = sin(Math.PI * i / n).toFloat()
        val y = wy - env * lvl * size * 0.08f * sin(i * 0.9f + t / 40f) * (0.6f + 0.4f * sin(i * 0.37f - t / 90f))
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    drawPath(p, AthosBlue.copy(alpha = 0.35f), style = Stroke(5f), blendMode = BlendMode.Plus)
    drawPath(p, Color.White.copy(alpha = 0.8f * lvl + 0.2f), style = Stroke(1.4f))
}

private fun DrawScope.spark(ring: AthosFeed.Ring, r: Rect, color: Color) {
    val n = ring.count
    val max = ring.max()
    val min = ring.v.takeLast(n).minOrNull() ?: 0f
    val span = (max - min).takeIf { it > 0.0001f } ?: 1f
    val p = Path()
    for (i in 0 until n) {
        val v = ring.v[ring.n - n + i]
        val x = r.left + r.width * i / (ring.n - 1).toFloat() + r.width * (ring.n - n) / (ring.n - 1).toFloat()
        val y = r.bottom - (v - min) / span * r.height
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    drawPath(p, color.copy(alpha = 0.35f), style = Stroke(5f), blendMode = BlendMode.Plus)
    drawPath(p, color, style = Stroke(1.6f))
    drawLine(Color.White.copy(alpha = 0.12f), Offset(r.left, r.bottom), Offset(r.right, r.bottom), 1f)
}

private fun DrawScope.text(tm: TextMeasurer, s: String, style: TextStyle, color: Color, at: Offset) =
    drawText(tm.measure(s, style.copy(color = color)), topLeft = at)

private fun DrawScope.textRight(tm: TextMeasurer, s: String, style: TextStyle, color: Color, at: Offset) {
    val l = tm.measure(s, style.copy(color = color))
    drawText(l, topLeft = Offset(at.x - l.size.width, at.y))
}

private fun DrawScope.centered(tm: TextMeasurer, s: String, style: TextStyle, color: Color, at: Offset) {
    val l = tm.measure(s, style.copy(color = color))
    drawText(l, topLeft = Offset(at.x - l.size.width / 2f, at.y - l.size.height / 2f))
}
