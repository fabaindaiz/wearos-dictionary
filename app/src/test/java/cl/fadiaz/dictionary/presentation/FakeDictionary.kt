package cl.fadiaz.dictionary.presentation

import cl.fadiaz.dictionary.data.PackHandle
import cl.fadiaz.dictionary.data.PackSet
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
    /** Distingue dos packs en los tests de selector. */
    private val packId: String = "fake",
    private val lang: String = "es",
    /** Cuanto tarda cada `suggest`. Sirve para que una consulta siga viva cuando llega otra. */
    private val demora: Long = 0,
) : DictionarySource {

    val consultas = mutableListOf<String>()
    var cerrado = false
        private set

    /** Las consultas que empezaron y **no** terminaron: las que `mapLatest` cancelo. */
    val canceladas = mutableListOf<String>()

    override val metadata: PackMetadata = PackMetadata(
        packId = packId,
        schemaVersion = 3,
        normVersion = 1,
        kind = PackKind.MONOLINGUAL,
        name = "Diccionario $packId",
        langSource = lang,
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
                packId = packId,
                entryId = query.length.toLong(),
                headword = query,
                partOfSpeech = "noun",
                matchKind = MatchKind.PREFIX,
                score = 0,
            ),
        )
    }

    override suspend fun entry(entryId: Long): Entry? = Entry(
        packId = packId,
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

/** Un `abrirPacks` que el test decide cuando completar. */
class PackDiferido {
    private val listo = CompletableDeferred<PackSet>()

    suspend fun abrir(onExtracting: () -> Unit): PackSet {
        onExtracting()
        return listo.await()
    }

    fun completarCon(resultado: PackSet) {
        listo.complete(resultado)
    }
}

/** Azucar: un PackSet listo con estos packs, el primero activo. */
fun listos(vararg packs: FakeDictionary): PackSet {
    val handles = packs.map { PackHandle.Abierto(it) }
    return PackSet.Ready(handles.first(), handles)
}
