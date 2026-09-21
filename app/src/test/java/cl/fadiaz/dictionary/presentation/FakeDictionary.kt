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
 * A fake dictionary that counts what it was asked.
 *
 * It deliberately does not return plausible results: what these tests watch is **when** it is
 * queried and **with what**, not that the search works. That is already covered by the 25
 * instrumented tests in :dict-data against a real pack.
 */
class FakeDictionary(
    /** Tells two packs apart in the selector tests. */
    private val packId: String = "fake",
    private val lang: String = "es",
    /** Los idiomas del pack. `null` = solo [lang]; con dos, el pack es bidireccional. */
    private val langs: List<String>? = null,
    /** How long each `suggest` takes. Useful to keep one query alive when another arrives. */
    private val demora: Long = 0,
    /**
     * How many entries it claims to have.
     *
     * It was a fixed 1, and that made the word of the day ALWAYS pick id 1 --the modulus has
     * nothing to choose from-- so two different packs appeared to pick the same thing and the
     * test watching for it failed because of the fake, not the code.
     */
    private val entryCount: Int = 1,
) : DictionarySource {

    val queries = mutableListOf<String>()

    /** The queries that came in through the free-text path, separate from the incremental ones. */
    val definitionMode = mutableListOf<String>()
    var cerrado = false
        private set

    /** The queries that started and did **not** finish: the ones `mapLatest` cancelled. */
    val canceladas = mutableListOf<String>()

    override val metadata: PackMetadata = PackMetadata(
        packId = packId,
        schemaVersion = 3,
        normVersion = 1,
        kind = PackKind.MONOLINGUAL,
        name = "Diccionario $packId",
        // null: exercises the path of a pack older than D-125, which does not carry the key.
        description = null,
        langs = langs ?: listOf(lang),
        fuzzyProfiles = List((langs ?: listOf(lang)).size) { FuzzyProfile.SPANISH },
        entryCount = entryCount,
        dataVersion = 1,
        license = "CC0-1.0",
        attribution = "sin atribucion: es un fake",
    )

    override suspend fun suggest(query: String, limit: Int, lang: String?): List<Suggestion> {
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

    /** The headwords this fake knows, by entryId. Empty = no entry exists. */
    var summaries: Map<Long, EntrySummary> = emptyMap()

    override suspend fun summary(entryId: Long): EntrySummary? = summaries[entryId]

    /** What this fake can resolve: the words named by `knownHeadwords`. */
    var knownHeadwords: Map<String, Long> = emptyMap()

    override suspend fun resolveHeadwords(norms: Set<String>, lang: String?): Map<String, Long> {
        resueltas += norms
        return knownHeadwords.filterKeys { it in norms }
    }

    /** Which sets it was asked to resolve. Useful for counting queries, not just results. */
    val resueltas = mutableListOf<Set<String>>()

    override suspend fun searchDefinitions(query: String, limit: Int, lang: String?): List<Suggestion> {
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

/** An `openPacks` the test decides when to complete. */
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

/** Sugar: a ready PackSet with these packs, the first one active. */
fun listos(vararg packs: FakeDictionary, demos: Set<String> = emptySet()): PackSet {
    val handles = packs.map {
        PackHandle.Open(
            source = it,
            isBundled = it.metadata.packId in demos,
            fileName = "${it.metadata.packId}.db",
            bytes = 1024,
        )
    }
    return PackSet.Ready(handles.first(), handles)
}
