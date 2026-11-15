package com.cockpitf30.nav

import com.cockpitf30.assistant.Command
import com.cockpitf30.assistant.VoiceCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodLodgingTest {
    private val json = """
        {"elements":[
          {"type":"node","id":1,"lat":40.40,"lon":-3.70,"tags":{"amenity":"restaurant","name":"Casa Pepe","cuisine":"regional","website":"x","opening_hours":"Mo-Fr 13:00-16:00; Su off","outdoor_seating":"yes","addr:street":"Calle Mayor","addr:housenumber":"3","wikimedia_commons":"File:Casa Pepe.jpg"}},
          {"type":"node","id":2,"lat":40.41,"lon":-3.71,"tags":{"amenity":"bar","name":"Bar Manolo"}},
          {"type":"way","id":3,"center":{"lat":40.42,"lon":-3.72},"tags":{"tourism":"hotel","name":"Hotel Sol","stars":"3"}},
          {"type":"node","id":4,"lat":40.43,"lon":-3.73,"tags":{"tourism":"camp_site","name":"Camping El Pinar"}},
          {"type":"node","id":5,"lat":40.43,"lon":-3.73,"tags":{"amenity":"restaurant"}}
        ]}
    """.trimIndent()

    @Test fun parsesKindsDetailAndScore() {
        val list = FoodLodging.parse(json)
        assertEquals(4, list.size)
        val pepe = list.first { it.name == "Casa Pepe" }
        assertEquals(StayKind.EAT, pepe.kind)
        assertEquals("cocina regional", pepe.detail)
        assertEquals(3, pepe.score)
        val hotel = list.first { it.kind == StayKind.SLEEP }
        assertEquals("3 estrellas", hotel.detail)
        assertEquals("w3", hotel.id)
        assertEquals(StayKind.CAMP, list.first { it.name.startsWith("Camping") }.kind)
        assertEquals(StayKind.DRINK, list.first { it.name == "Bar Manolo" }.kind)
    }

    @Test fun parsesDetails() {
        val pepe = FoodLodging.parse(json).first { it.name == "Casa Pepe" }
        assertEquals("Calle Mayor, 3", pepe.address)
        assertEquals(listOf("Terraza"), pepe.features)
        assertEquals("File:Casa Pepe.jpg", pepe.commons)
        assertEquals("L-V 13:00-16:00 · D cerrado", FoodLodging.hoursText(pepe.hours!!))
        assertEquals("https://commons.wikimedia.org/wiki/Special:FilePath/Casa_Pepe.jpg?width=640", Wiki.commonsThumb("Casa Pepe.jpg"))
    }

    @Test fun alertsOnlyAtMealTimeOrNight() {
        val list = FoodLodging.parse(json)
        val eat = StayView(list.first { it.kind == StayKind.EAT }, 900.0, 10f)
        val hotel = StayView(list.first { it.kind == StayKind.SLEEP }, 2000.0, 10f)
        val far = Long.MAX_VALUE
        assertNull(FoodLodging.choose(listOf(eat, hotel), meal = false, night = false, movingMs = 0, now = 0, sinceFood = far, sinceStay = far, recent = { false }))
        assertEquals(eat, FoodLodging.choose(listOf(eat, hotel), meal = true, night = false, movingMs = 0, now = 0, sinceFood = far, sinceStay = far, recent = { false }))
        assertEquals(hotel, FoodLodging.choose(listOf(eat, hotel), meal = false, night = true, movingMs = 0, now = 0, sinceFood = far, sinceStay = far, recent = { false }))
        // Recién avisado: no se repite.
        assertNull(FoodLodging.choose(listOf(eat), meal = true, night = false, movingMs = 0, now = 0, sinceFood = 60_000, sinceStay = far, recent = { false }))
        val a = FoodLodging.alert(eat)
        assertEquals(AlertCategory.FOOD, a.category)
        assertTrue(a.speech, a.speech.startsWith("Para comer, delante a 900 metros: Restaurante Casa Pepe, cocina regional"))
    }

    @Test fun answersNearest() {
        val list = FoodLodging.parse(json)
        val views = list.mapIndexed { i, s -> StayView(s, 500.0 + i * 300, 0f) }
        val st = StayState(list, updatedAt = 1)
        val (text, first) = FoodLodging.answer(setOf(StayKind.CAMP), views, st, true)
        assertNotNull(first)
        assertTrue(text, text.contains("Camping El Pinar"))
        assertTrue(FoodLodging.answer(setOf(StayKind.EAT), emptyList(), StayState(), true).first.startsWith("Estoy buscando"))
    }

    @Test fun voiceCommands() {
        assertEquals(Command.Stays(setOf(StayKind.EAT)), VoiceCommands.parse("¿dónde comer?"))
        assertEquals(Command.Stays(setOf(StayKind.EAT)), VoiceCommands.parse("busca restaurantes"))
        assertEquals(Command.Stays(setOf(StayKind.DRINK)), VoiceCommands.parse("hay bares cerca"))
        assertEquals(Command.Stays(setOf(StayKind.SLEEP)), VoiceCommands.parse("alojamiento"))
        assertEquals(Command.Stays(setOf(StayKind.SLEEP)), VoiceCommands.parse("busca un hotel para dormir"))
        assertEquals(Command.Stays(setOf(StayKind.CAMP)), VoiceCommands.parse("campings"))
        assertEquals(Command.Navigate("hotel sol"), VoiceCommands.parse("llévame al hotel Sol"))
        assertEquals(Command.Alerts(AlertCategory.FOOD, false), VoiceCommands.parse("desactiva los avisos de restaurantes"))
        assertEquals(Command.Open(com.cockpitf30.ui.screens.Screen.TOURISM), VoiceCommands.parse("abre turismo"))
    }
}
