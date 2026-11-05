package com.cockpitf30.fuel

import com.cockpitf30.nav.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuelStationsTest {
    // Formato de EstacionesTerrestres del Ministerio: coordenadas y precios con coma decimal.
    private val json = """
        {"Fecha":"05/11/2026 21:15:30","ListaEESSPrecio":[
          {"C.P.":"28001","Dirección":"CALLE SERRANO, 1","Horario":"L-D: 24H","Latitud":"40,423000","Localidad":"MADRID",
           "Longitud (WGS84)":"-3,688000","Municipio":"Madrid","Precio Gasoleo A":"1,459","Precio Gasolina 95 E5":"1,599",
           "Provincia":"MADRID","Rótulo":"REPSOL","IDEESS":"1"},
          {"Dirección":"AVENIDA DE AMÉRICA, 50","Horario":"L-V: 07:00-22:00","Latitud":"40,440000","Localidad":"MADRID",
           "Longitud (WGS84)":"-3,670000","Municipio":"Madrid","Precio Gasoleo A":"","Precio Gasolina 95 E5":"1,539",
           "Rótulo":"CEPSA","IDEESS":"2"},
          {"Dirección":"SIN COORDENADAS","Latitud":"","Longitud (WGS84)":"","Rótulo":"X","IDEESS":"3"}
        ],"ResultadoConsulta":"OK"}
    """.trimIndent()

    @Test fun parsesMinistryFormat() {
        val list = FuelStations.parse(json)
        assertEquals(2, list.size)
        val r = list[0]
        assertEquals("REPSOL", r.brand)
        assertEquals("CALLE SERRANO, 1", r.address)
        assertEquals("Madrid", r.town)
        assertEquals(40.423, r.lat, 1e-9)
        assertEquals(-3.688, r.lon, 1e-9)
        assertEquals(1.459, r.diesel!!, 1e-9)
        assertNull(list[1].diesel)
        assertEquals("05/11/2026 21:15:30", FuelStations.date(json))
    }

    @Test fun nearestIsSortedByDistance() {
        val list = FuelStations.parse(json)
        val near = FuelStations.nearest(list, GeoPoint(40.441, -3.671))
        assertEquals(listOf("CEPSA", "REPSOL"), near.map { it.station.brand })
        assertEquals(true, near[0].meters < 200)
    }

    @Test fun cacheRoundTrip() {
        val list = FuelStations.parse(json)
        assertEquals(list, FuelStations.decode(FuelStations.encode(list)))
    }

    @Test fun formatsDistance() {
        assertEquals("850 m", FuelStations.formatDistance(853.0))
        assertEquals("1,2 km", FuelStations.formatDistance(1234.0))
        assertEquals("14 km", FuelStations.formatDistance(14_400.0))
    }
}
