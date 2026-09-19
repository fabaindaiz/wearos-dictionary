package cl.fadiaz.dictionary.presentation

import cl.fadiaz.dictionary.core.DictionarySource
import cl.fadiaz.dictionary.core.Entry
import cl.fadiaz.dictionary.core.EntrySummary
import cl.fadiaz.dictionary.core.FuzzyProfile
import cl.fadiaz.dictionary.core.PackKind
import cl.fadiaz.dictionary.core.PackMetadata
import cl.fadiaz.dictionary.core.Suggestion
import cl.fadiaz.dictionary.data.PackHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Que ofrece el menu de una palabra.
 *
 * Lo que defiende es que **no se ofrezca lo que no funciona**: "ver traduccion" buscaba el mismo
 * lema en el otro diccionario, y con dos packs monolingues eso casi nunca encuentra nada. Un
 * boton que no hace nada ensena a desconfiar de los que si.
 */
class AccionesDeLaPalabraTest {

    private fun pack(id: String, kind: PackKind) = PackHandle.Abierto(
        source = FuenteVacia(
            PackMetadata(
                packId = id,
                schemaVersion = 3,
                normVersion = 2,
                kind = kind,
                name = "Diccionario $id",
                langSource = "es",
                langTarget = if (kind == PackKind.BILINGUAL) "en" else null,
                fuzzyProfile = FuzzyProfile.SPANISH,
                entryCount = 1,
                dataVersion = 1,
                license = "CC0-1.0",
                attribution = "fake",
            ),
        ),
    )

    private fun acciones(traduccion: PackHandle.Abierto?) = accionesDeLaPalabra(
        esFavorita = false,
        onAlternarFavorita = {},
        packDeTraduccion = traduccion,
        onVerTraduccion = {},
        onCopiar = {},
    ).map { it.etiqueta }

    @Test
    fun sinPackDeTraduccionNoSeOfreceTraducir() {
        assertEquals(listOf("Guardar", "Copiar"), acciones(null))
    }

    @Test
    fun conUnPackBilingueSiSeOfrece() {
        assertEquals(
            listOf("Guardar", "Ver traducción", "Copiar"),
            acciones(pack("es-en", PackKind.BILINGUAL)),
        )
    }

    @Test
    fun otroDiccionarioMONOLINGUENoEsUnPackDeTraduccion() {
        // Es el caso real de hoy: espanol e ingles, los dos monolingues. Buscar "house" en el
        // diccionario espanol no devuelve una traduccion, devuelve nada.
        val abiertos = listOf(
            pack("es-def", PackKind.MONOLINGUAL),
            pack("en-def", PackKind.MONOLINGUAL),
        )
        assertNull(packDeTraduccion(abiertos, packDeLaEntrada = "es-def"))
    }

    @Test
    fun elPackDeTraduccionEsBilingueYNoElQueEstamosMirando() {
        val abiertos = listOf(
            pack("es-def", PackKind.MONOLINGUAL),
            pack("es-en", PackKind.BILINGUAL),
        )
        assertEquals("es-en", packDeTraduccion(abiertos, "es-def")?.packId)
        // Mirando el bilingue, no se ofrece traducirse a si mismo.
        assertNull(packDeTraduccion(abiertos, "es-en"))
    }

    @Test
    fun guardarCambiaDeEtiquetaSegunSiYaEstaGuardada() {
        val guardada = accionesDeLaPalabra(
            esFavorita = true,
            onAlternarFavorita = {},
            packDeTraduccion = null,
            onVerTraduccion = {},
            onCopiar = {},
        )
        assertTrue(guardada.first().etiqueta == "Quitar de guardadas")
    }
}

/** Lo minimo para envolver una metadata. Ninguna accion consulta el pack. */
private class FuenteVacia(override val metadata: PackMetadata) : DictionarySource {
    override suspend fun suggest(query: String, limit: Int) = emptyList<Suggestion>()
    override suspend fun entry(entryId: Long): Entry? = null
    override suspend fun searchDefinitions(query: String, limit: Int) = emptyList<Suggestion>()
    override suspend fun resolveHeadwords(norms: Set<String>) = emptyMap<String, Long>()
    override suspend fun summary(entryId: Long): EntrySummary? = null
    override fun close() = Unit
}
