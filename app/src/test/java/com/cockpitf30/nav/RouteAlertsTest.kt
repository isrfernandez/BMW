package com.cockpitf30.nav

import com.cockpitf30.assistant.Command
import com.cockpitf30.assistant.VoiceCommands
import com.cockpitf30.fuel.FuelStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteAlertsTest {
    private val overpass = """
        {"elements":[
          {"type":"node","id":1,"lat":40.40,"lon":-3.70,"tags":{"railway":"level_crossing"}},
          {"type":"node","id":2,"lat":40.41,"lon":-3.71,"tags":{"hazard":"animal_crossing"}},
          {"type":"way","id":3,"center":{"lat":40.42,"lon":-3.72},"tags":{"highway":"rest_area","name":"Área de Villacastín"}},
          {"type":"way","id":4,"center":{"lat":40.43,"lon":-3.73},"tags":{"historic":"castle","name":"Castillo de Coca","wikidata":"Q1","wikipedia":"es:Castillo de Coca"}},
          {"type":"node","id":5,"lat":40.44,"lon":-3.74,"tags":{"tourism":"viewpoint"}},
          {"type":"node","id":6,"lat":40.45,"lon":-3.75,"tags":{"place":"town","name":"Segovia","wikidata":"Q2"}}
        ]}
    """.trimIndent()

    private val all = AlertCategory.entries.toSet()
    private fun poi(id: String, cat: AlertCategory, kind: String, name: String? = null) = Poi(id, 0.0, 0.0, cat, kind, name)

    @Test fun parsesPois() {
        val p = RoutePois.parse(overpass).associateBy { it.id }
        assertEquals(5, p.size) // el mirador sin nombre no cuenta
        assertEquals(AlertCategory.SAFETY, p["n1"]!!.category)
        assertEquals("Paso a nivel", p["n1"]!!.what)
        assertEquals("Paso de animales", p["n2"]!!.what)
        assertEquals(AlertCategory.REST, p["w3"]!!.category)
        assertEquals(40.43, p["w4"]!!.lat, 1e-9)
        assertEquals("Castillo", p["w4"]!!.what)
        assertEquals("place", p["n6"]!!.kind)
    }

    @Test fun overpassTimeoutIsAnError() {
        val r = runCatching { RoutePois.parse("""{"elements":[],"remark":"runtime error: Query timed out in \"query\" at line 3 after 31 seconds."}""") }
        assertTrue(r.isFailure)
    }

    @Test fun safetyFirstAndOnlyAhead() {
        val mem = RouteAlerts.Memory()
        val near = listOf(
            PoiView(poi("t", AlertCategory.TOURISM, "castle", "Castillo"), 900.0, 0f),
            PoiView(poi("s", AlertCategory.SAFETY, "level_crossing"), 350.0, 5f),
        ).sortedBy { it.meters }
        assertEquals("s", RouteAlerts.choose(near, all, 0, 0, false, mem)?.poi?.id)
        // Detrás no se avisa.
        val behind = listOf(PoiView(poi("s", AlertCategory.SAFETY, "level_crossing"), 350.0, 170f))
        assertNull(RouteAlerts.choose(behind, all, 0, 0, false, mem))
    }

    @Test fun categoryOffAndNoRepeat() {
        val mem = RouteAlerts.Memory()
        val v = PoiView(poi("t", AlertCategory.TOURISM, "castle", "Castillo de Coca"), 1500.0, 40f)
        assertNull(RouteAlerts.choose(listOf(v), all - AlertCategory.TOURISM, 0, 0, false, mem))
        val a = RouteAlerts.build(v, null, WikiSummary("Castillo de Coca", "El castillo de Coca es una fortaleza. Está en Segovia."))
        assertTrue(a.speech, a.speech.startsWith("A la derecha, a 1,5 kilómetros: Castillo de Coca."))
        mem.mark(a, 1000)
        assertNull(RouteAlerts.choose(listOf(v), all, 0, 2000, false, mem))
    }

    @Test fun restOnlyWhenTired() {
        val mem = RouteAlerts.Memory()
        val v = listOf(PoiView(poi("r", AlertCategory.REST, "rest_area"), 2000.0, 0f))
        assertNull(RouteAlerts.choose(v, all, 30 * 60_000L, 0, false, mem))
        assertEquals("r", RouteAlerts.choose(v, all, 2 * 3_600_000L, 0, false, mem)?.poi?.id)
    }

    @Test fun lowFuel() {
        val mem = RouteAlerts.Memory()
        assertTrue(!RouteAlerts.fuelDue(120, mem, 0))
        assertTrue(RouteAlerts.fuelDue(50, mem, 0))
        val st = FuelStation("1", "REPSOL", "Calle Mayor 1", "Coca", 0.0, 0.0, "24H", 1.459, 1.6)
        val a = RouteAlerts.fuelAlert(50, listOf(StationView(st, 3000.0, -40f)))
        assertTrue(a.speech, a.speech.contains("gasolinera Repsol a 3 kilómetros, a la izquierda"))
        mem.fuelRange = 50; mem.mark(a, 0)
        assertTrue(!RouteAlerts.fuelDue(45, mem, 60_000))
        assertTrue(RouteAlerts.fuelDue(25, mem, 60_000)) // baja de 30
    }

    @Test fun wikiText() {
        assertEquals("es" to "Alcázar de Segovia", Wiki.parseTag("es:Alcázar de Segovia"))
        assertEquals("El acueducto es romano.", Wiki.firstSentences("El acueducto (en latín, aqueductus) es romano. " + "Otra frase muy larga ".repeat(20), 60))
        // Una primera frase larguísima: se corta en una coma y acaba en punto, nunca a media palabra con «…».
        val long = "La catedral de Santa María, de estilo gótico, " + "con capillas, retablos y vidrieras de varios siglos, ".repeat(12) + "es la mayor de la ciudad."
        val said = Wiki.firstSentences(long)
        assertTrue(said, said.endsWith(".") && !said.contains("…") && said.length <= 360)
        assertTrue(said, !said.endsWith(",.") && said.split(" ").last().length > 2)
        assertEquals("Coca es un municipio.", Wiki.firstSentences("Coca es un municipio"))
        val s = Wiki.parseSummary("""{"type":"standard","title":"Coca","extract":"Coca es un municipio.","thumbnail":{"source":"https://x/y.jpg"}}""")
        assertEquals("https://x/y.jpg", s?.third)
    }

    @Test fun voiceCommands() {
        assertEquals(Command.Alerts(null, false), VoiceCommands.parse("desactiva los avisos"))
        assertEquals(Command.Alerts(AlertCategory.TOURISM, false), VoiceCommands.parse("quita los avisos turísticos"))
        assertEquals(Command.Alerts(AlertCategory.RADAR, true), VoiceCommands.parse("activa los avisos de radares"))
        assertEquals(Command.Alerts(AlertCategory.TOURISM, false), VoiceCommands.parse("desactiva la información turística"))
        assertEquals(Command.Alerts(AlertCategory.FUEL, false), VoiceCommands.parse("no quiero avisos de gasolineras"))
        assertEquals(Command.Nearby, VoiceCommands.parse("¿qué hay por aquí?"))
        assertEquals(Command.AboutThat, VoiceCommands.parse("háblame de eso"))
    }
}
