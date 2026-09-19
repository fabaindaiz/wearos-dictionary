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

/**
 * Que el historial sobreviva a reconstruir un pack.
 *
 * `entry.id` es el rowid y se corre cuando la fuente agrega una palabra en el medio (D-055). El
 * modo de falla NO es un crash: es abrir *otra palabra* con el lema correcto escrito en la lista,
 * que es la clase de bug que este repo no puede ver desde el codigo.
 *
 * Por que no se respalda `entry.uid`, que si es estable: no tiene indice (D-056), asi que
 * resolverlo cuesta un scan de 114.619 filas en español y 794.355 en ingles **por toque**, y la
 * app tiene prohibido calcularlo (D-057). El lema, en cambio, entra por `idx_entry_norm`.
 */
class DestinoDeVisitaTest {

    private val visita = Visita(packId = "es-def-wikc", entryId = 42, headword = "perro", partOfSpeech = "noun")

    @Test
    fun siElIdSigueSiendoEseLemaSeAbreDirecto() {
        // El caso normal, y el que tiene que costar CERO consultas extra.
        assertEquals(
            DestinoDeVisita.Directo(42),
            destinoDeVisita(visita, headwordEnElId = "perro", reresuelto = 999),
        )
    }

    @Test
    fun siElIdQuedoApuntandoAOtraPalabraSeCorrigePorElLema() {
        // Exactamente lo que hace un rebuild: el 42 ahora es otra entrada.
        assertEquals(
            DestinoDeVisita.Reresuelto(777),
            destinoDeVisita(visita, headwordEnElId = "perpetuo", reresuelto = 777),
        )
    }

    @Test
    fun siElIdYaNoExisteSeCorrigePorElLema() {
        // El pack encogio --D-116 saco 31.575 entradas del español-- y el id quedo fuera de rango.
        assertEquals(
            DestinoDeVisita.Reresuelto(777),
            destinoDeVisita(visita, headwordEnElId = null, reresuelto = 777),
        )
    }

    @Test
    fun siLaPalabraYaNoEstaEnElPackSeDaPorPerdida() {
        // Tambien es D-116: la palabra podada existe en el historial y ya no en el pack. Se
        // pierde la fila, no se abre cualquier otra.
        assertEquals(
            DestinoDeVisita.Perdido,
            destinoDeVisita(visita, headwordEnElId = null, reresuelto = null),
        )
    }

    @Test
    fun sinLemaConQueCorregirSeConfiaEnElIdSiExiste() {
        // El deep link de un tile puede llegar sin lema --la `Visita` se arma desde los extras
        // del intent, que es entrada no confiable--. Sin lema no hay con que re-resolver, asi
        // que la unica pregunta que queda es si ese id existe.
        val sinLema = visita.copy(headword = "")
        assertEquals(
            DestinoDeVisita.Directo(42),
            destinoDeVisita(sinLema, headwordEnElId = "cualquiera", reresuelto = null),
        )
    }

    @Test
    fun sinLemaYConUnIdQueNoExisteNoSeAbreNada() {
        val sinLema = visita.copy(headword = "")
        assertEquals(
            DestinoDeVisita.Perdido,
            destinoDeVisita(sinLema, headwordEnElId = null, reresuelto = null),
        )
    }

    @Test
    fun unLemaQueEstaPeroConOtroIdNoSeConfundeConElDirecto() {
        // Si re-resolver devuelve el MISMO id, sigue siendo directo: no hay nada que corregir.
        assertEquals(
            DestinoDeVisita.Directo(42),
            destinoDeVisita(visita, headwordEnElId = "perro", reresuelto = 42),
        )
    }
}
