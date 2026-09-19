package cl.fadiaz.dictionary.tile

import cl.fadiaz.dictionary.data.Visita
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Que dibuja cada tile, decidido sin Android y por lo tanto dentro del gate.
 *
 * Un tile falla distinto que una pantalla: nadie lo abre a proposito, asi que **un tile que
 * muestra algo viejo no se reporta**. De ahi que la mitad de estos tests sean sobre la cache
 * vencida, el reloj corrido y el texto corrupto --los tres casos en los que la respuesta correcta
 * es no mostrar nada-- y no sobre el camino feliz.
 */
class TileContenidoTest {

    private fun visita(lema: String, id: Long = 1, pack: String = "es-def-wikc") =
        Visita(packId = pack, entryId = id, headword = lema, partOfSpeech = "noun")

    // ----------------------------------------------------------------- historial

    @Test
    fun sinHistorialNoHayNadaQueMostrar() {
        assertEquals(TileContenido.Vacio, ContenidoDeTiles.historial(emptyList()))
    }

    @Test
    fun conUnaSolaVisitaSeMuestraEsa() {
        val una = listOf(visita("perro"))
        assertEquals(TileContenido.Filas(una), ContenidoDeTiles.historial(una))
    }

    @Test
    fun noSeMuestranMasFilasDeLasQueEntran() {
        val muchas = (1L..6L).map { visita("lema$it", it) }
        val contenido = ContenidoDeTiles.historial(muchas, max = 3)
        assertEquals(TileContenido.Filas(muchas.take(3)), contenido)
    }

    @Test
    fun elOrdenDelHistorialSeRespeta() {
        // El move-to-front ya lo aplico el ViewModel al guardar: el tile no reordena nada, y si
        // lo hiciera la fila de arriba dejaria de ser la ultima palabra abierta.
        val orden = listOf(visita("tres", 3), visita("dos", 2), visita("uno", 1))
        assertEquals(TileContenido.Filas(orden), ContenidoDeTiles.historial(orden))
    }

    // ----------------------------------------------------------- palabra del dia

    private val semana = listOf(
        visita("lunes", 1), visita("martes", 2), visita("miercoles", 3),
        visita("jueves", 4), visita("viernes", 5), visita("sabado", 6), visita("domingo", 7),
    )

    @Test
    fun elPrimerDiaDeLaCacheEsLaPrimeraPalabra() {
        val contenido = ContenidoDeTiles.palabraDelDia("2026-09-19", semana, "2026-09-19")
        assertEquals(TileContenido.Palabra(semana[0]), contenido)
    }

    @Test
    fun cadaDiaCorreUnaPosicion() {
        val contenido = ContenidoDeTiles.palabraDelDia("2026-09-19", semana, "2026-09-21")
        assertEquals(TileContenido.Palabra(semana[2]), contenido)
    }

    @Test
    fun cruzarUnFinDeMesNoDesalinea() {
        // El indice es una diferencia de fechas, no una resta de dias del mes.
        val contenido = ContenidoDeTiles.palabraDelDia("2026-09-29", semana, "2026-10-02")
        assertEquals(TileContenido.Palabra(semana[3]), contenido)
    }

    @Test
    fun unaCacheVencidaNoMuestraUnaPalabraVieja() {
        // ESTE ES EL TEST QUE PAGA EL ARCHIVO. Si la app no se abrio en mas de una semana, la
        // cache se queda corta; mostrar la ultima palabra que tenia seria una "palabra del dia"
        // equivocada, todos los dias, sin que nada avise.
        val contenido = ContenidoDeTiles.palabraDelDia("2026-09-19", semana, "2026-09-30")
        assertEquals(TileContenido.Vacio, contenido)
    }

    @Test
    fun siElRelojVaHaciaAtrasNoHayPalabra() {
        val contenido = ContenidoDeTiles.palabraDelDia("2026-09-19", semana, "2026-09-18")
        assertEquals(TileContenido.Vacio, contenido)
    }

    @Test
    fun sinCacheNoHayPalabra() {
        assertEquals(TileContenido.Vacio, ContenidoDeTiles.palabraDelDia(null, emptyList(), "2026-09-19"))
        assertEquals(TileContenido.Vacio, ContenidoDeTiles.palabraDelDia("2026-09-19", emptyList(), "2026-09-19"))
    }

    @Test
    fun sinFechaDeHoyNoHayPalabra() {
        // Mismo criterio que el ViewModel: sin fecha cableada no hay palabra del dia, y la
        // ausencia se ve en vez de congelar una.
        assertEquals(TileContenido.Vacio, ContenidoDeTiles.palabraDelDia("2026-09-19", semana, null))
    }

    @Test
    fun loQueLaAppAdelantaEsExactamenteLoQueElTileLee() {
        // La propiedad que importa, y la razon de que las dos mitades vivan en el mismo archivo:
        // la app llena la cache sumando dias y el tile la lee restandolos. Si las dos aritmeticas
        // se separaran, el tile mostraria la palabra del dia equivocado --corrida un dia-- que es
        // justo el error que nadie nota.
        val hoy = "2026-09-19"
        for (dia in semana.indices) {
            val eseDia = ContenidoDeTiles.sumarDias(hoy, dia)
            assertEquals(
                TileContenido.Palabra(semana[dia]),
                ContenidoDeTiles.palabraDelDia(hoy, semana, eseDia),
                "el dia $dia no coincide",
            )
        }
    }

    @Test
    fun sumarDiasCruzaMesesYAnios() {
        assertEquals("2026-10-01", ContenidoDeTiles.sumarDias("2026-09-29", 2))
        assertEquals("2027-01-01", ContenidoDeTiles.sumarDias("2026-12-31", 1))
        assertEquals("2026-03-01", ContenidoDeTiles.sumarDias("2026-02-28", 1))
    }

    @Test
    fun sumarDiasSobreBasuraDaNull() {
        assertEquals(null, ContenidoDeTiles.sumarDias(null, 1))
        assertEquals(null, ContenidoDeTiles.sumarDias("ayer", 1))
    }

    @Test
    fun unaFechaCorruptaNoTumbaElTile() {
        // Se lee de SharedPreferences, que es un contrato con el disco: una version vieja o un
        // byte cambiado no pueden hacer que el tile tire una excepcion en el hilo principal.
        for (basura in listOf("", "   ", "ayer", "2026-13-45", "2026-09")) {
            assertEquals(
                TileContenido.Vacio,
                ContenidoDeTiles.palabraDelDia(basura, semana, "2026-09-19"),
                "la fecha $basura deberia descartarse, no explotar",
            )
            assertEquals(
                TileContenido.Vacio,
                ContenidoDeTiles.palabraDelDia("2026-09-19", semana, basura),
                "el hoy $basura deberia descartarse, no explotar",
            )
        }
    }
}
