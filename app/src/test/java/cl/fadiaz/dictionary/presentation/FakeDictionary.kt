package cl.fadiaz.dictionary.presentation

import cl.fadiaz.dictionary.data.PackLoad
import cl.fadiaz.dictionary.core.DictionarySource
import cl.fadiaz.dictionary.core.Entry
import cl.fadiaz.dictionary.core.FuzzyProfile
import cl.fadiaz.dictionary.core.MatchKind
import cl.fadiaz.dictionary.core.PackKind
import cl.fadiaz.dictionary.core.PackMetadata
import cl.fadiaz.dictionary.core.Sense
import cl.fadiaz.dictionary.core.Suggestion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * Un diccionario de mentira que cuenta lo que le preguntaron.
 *
 * No devuelve resultados verosimiles a proposito: lo que estos tests miran es **cuando** se
 * consulta y **con que**, no que la busqueda funcione. Eso ya lo cubren los 25 instrumentados
 * de :dict-data contra un pack de verdad.
 */
class FakeDictionary(
    /** Cuanto tarda cada `suggest`. Sirve para que una consulta siga viva cuando llega otra. */
    private val demora: Long = 0,
) : DictionarySource {

    val consultas = mutableListOf<String>()
    var cerrado = false
        private set

    /** Las consultas que empezaron y **no** terminaron: las que `mapLatest` cancelo. */
    val canceladas = mutableListOf<String>()

    override val metadata: PackMetadata = PackMetadata(
        packId = "fake",
        schemaVersion = 3,
        normVersion = 1,
        kind = PackKind.MONOLINGUAL,
        name = "Diccionario de prueba",
        langSource = "es",
        langTarget = null,
        fuzzyProfile = FuzzyProfile.SPANISH,
        entryCount = 1,
        dataVersion = 1,
        license = "CC0-1.0",
        attribution = "sin atribucion: es un fake",
    )

    override suspend fun suggest(query: String, limit: Int): List<Suggestion> {
        consultas += query
        var termino = false
        try {
            if (demora > 0) delay(demora)
            termino = true
        } finally {
            if (!termino) canceladas += query
        }
        return listOf(
            Suggestion(
                packId = "fake",
                entryId = query.length.toLong(),
                headword = query,
                partOfSpeech = "noun",
                matchKind = MatchKind.PREFIX,
                score = 0,
            ),
        )
    }

    override suspend fun entry(entryId: Long): Entry? = Entry(
        packId = "fake",
        entryId = entryId,
        uid = entryId,
        headword = "entrada$entryId",
        partOfSpeech = "noun",
        senses = listOf(Sense("una glosa")),
    )

    override suspend fun searchDefinitions(query: String, limit: Int): List<Suggestion> = emptyList()

    override fun close() {
        cerrado = true
    }
}

/** Un `abrirPack` que el test decide cuando completar. */
class PackDiferido {
    private val listo = CompletableDeferred<PackLoad>()

    suspend fun abrir(onExtracting: () -> Unit): PackLoad {
        onExtracting()
        return listo.await()
    }

    fun completarCon(resultado: PackLoad) {
        listo.complete(resultado)
    }
}
