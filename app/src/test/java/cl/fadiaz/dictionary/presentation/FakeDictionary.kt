package cl.fadiaz.dictionary.presentation

import cl.fadiaz.dictionary.data.PackHandle
import cl.fadiaz.dictionary.data.PackSet
import cl.fadiaz.dictionary.core.DictionarySource
import cl.fadiaz.dictionary.core.Entry
import cl.fadiaz.dictionary.core.EntrySummary
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
    /**
     * Cuantas entradas dice tener.
     *
     * Era 1 fijo, y eso hacia que la palabra del dia eligiera SIEMPRE el id 1 --el modulo no
     * tiene de donde elegir-- asi que dos packs distintos parecian elegir lo mismo y el test que
     * lo miraba fallaba por el fake, no por el codigo.
     */
    private val entryCount: Int = 1,
) : DictionarySource {

    val queries = mutableListOf<String>()

    /** Las consultas que entraron por el camino de texto libre, aparte de las incrementales. */
    val definitionMode = mutableListOf<String>()
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
        // null: ejercita el camino de un pack anterior a D-125, que no trae la clave.
        description = null,
        langSource = lang,
        langTarget = null,
        fuzzyProfile = FuzzyProfile.SPANISH,
        entryCount = entryCount,
        dataVersion = 1,
        license = "CC0-1.0",
        attribution = "sin atribucion: es un fake",
    )

    override suspend fun suggest(query: String, limit: Int): List<Suggestion> {
        queries += query
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

    /** Las cabeceras que este fake conoce, por entryId. Vacio = ninguna entrada existe. */
    var summaries: Map<Long, EntrySummary> = emptyMap()

    override suspend fun summary(entryId: Long): EntrySummary? = summaries[entryId]

    /** Lo que este fake sabe resolver: las palabras que nombra `lemasConocidos`. */
    var knownHeadwords: Map<String, Long> = emptyMap()

    override suspend fun resolveHeadwords(norms: Set<String>): Map<String, Long> {
        resueltas += norms
        return knownHeadwords.filterKeys { it in norms }
    }

    /** Con que conjuntos se pidio resolver. Sirve para contar consultas, no solo resultados. */
    val resueltas = mutableListOf<Set<String>>()

    override suspend fun searchDefinitions(query: String, limit: Int): List<Suggestion> {
        definitionMode += query
        if (demora > 0) delay(demora)
        return listOf(
            Suggestion(
                packId = packId,
                entryId = -query.length.toLong(),
                headword = "def:$query",
                partOfSpeech = "noun",
                matchKind = MatchKind.DEFINITION,
                score = 0,
            ),
        )
    }

    override fun close() {
        cerrado = true
    }
}

/** Un `abrirPacks` que el test decide cuando completar. */
class DeferredPack {
    private val listo = CompletableDeferred<PackSet>()

    suspend fun openFile(onExtracting: () -> Unit): PackSet {
        onExtracting()
        return listo.await()
    }

    fun padWith(result: PackSet) {
        listo.complete(result)
    }
}

/** Azucar: un PackSet listo con estos packs, el primero activo. */
fun listos(vararg packs: FakeDictionary, demos: Set<String> = emptySet()): PackSet {
    val handles = packs.map {
        PackHandle.Open(
            source = it,
            isDemo = it.metadata.packId in demos,
            fileName = "${it.metadata.packId}.db",
            bytes = 1024,
        )
    }
    return PackSet.Ready(handles.first(), handles)
}
