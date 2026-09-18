package cl.fadiaz.dictionary.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El codec del historial, que es donde esto puede corromperse en silencio.
 *
 * Se guarda como texto en SharedPreferences, asi que el formato es un contrato con el disco: una
 * version vieja tras una actualizacion, o un lema con un caracter inesperado, no pueden tumbar
 * la app ni mostrar una palabra equivocada. Por eso `parsearVisitas` **descarta** lo que no
 * entiende en vez de tirar.
 */
class VisitaTest {

    private fun visita(lema: String, pack: String = "es-def", id: Long = 1, pos: String? = "noun") =
        Visita(packId = pack, entryId = id, headword = lema, partOfSpeech = pos)

    @Test
    fun loQueSeGuardaSeRecupera() {
        val original = listOf(visita("perro"), visita("house", "en-def", 7, "verb"))
        assertEquals(original, parsearVisitas(serializarVisitas(original)))
    }

    @Test
    fun unLemaConAcentosComillasYTabsSobrevive() {
        // Los lemas salen del Wikcionario: hay refranes con comillas, y un tab perdido en una
        // glosa ya paso una vez en este proyecto.
        val raro = visita("mas corre el galgo\tque el \"mastin\"")
        assertEquals(listOf(raro), parsearVisitas(serializarVisitas(listOf(raro))))
    }

    @Test
    fun unPosNuloSobrevive() {
        // El pack de juguete tiene una entrada sin pos, y el Wikcionario tambien.
        val sinPos = visita("arbol", pos = null)
        assertEquals(listOf(sinPos), parsearVisitas(serializarVisitas(listOf(sinPos))))
    }

    @Test
    fun unaLineaQueNoSeEntiendeSeDescartaYNoTumbaElResto() {
        // Es el caso de un formato viejo tras actualizar la app. Perder el historial es
        // aceptable; que la app no arranque, no.
        val bueno = serializarVisitas(listOf(visita("perro")))
        assertEquals(listOf(visita("perro")), parsearVisitas("basura sin separadores\n" + bueno))
    }

    @Test
    fun unTextoVacioDaUnHistorialVacio() {
        assertTrue(parsearVisitas("").isEmpty())
    }

    @Test
    fun unEntryIdQueNoEsNumeroSeDescarta() {
        val roto = listOf("es-def", "no-es-un-numero", "perro", "noun").joinToString(SEPARADOR)
        assertTrue(parsearVisitas(roto).isEmpty())
    }
}
