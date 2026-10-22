package cl.fadiaz.dictionary.presentation

import cl.fadiaz.dictionary.core.DictionarySource
import cl.fadiaz.dictionary.core.DiscoverBucket
import cl.fadiaz.dictionary.core.DiscoverDraw
import cl.fadiaz.dictionary.core.DiscoverPick
import cl.fadiaz.dictionary.core.DiscoverPos
import cl.fadiaz.dictionary.core.DiscoverTier
import cl.fadiaz.dictionary.core.EntrySummary
import cl.fadiaz.dictionary.core.RowPreview
import cl.fadiaz.dictionary.data.DictLog
import cl.fadiaz.dictionary.data.givesWordOfTheDay
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The list *see more words* shows: twenty words of one language, from the pack that answers it. */
data class DiscoverSection(
    val lang: String,
    val packId: String,
    val words: List<EntrySummary>,
    /** What each word's second line says, by entry id. Same channel as the results' (d-a2f271-0e93af). */
    val previews: Map<Long, RowPreview> = emptyMap(),
)

data class DiscoverState(
    /** Whether any installed language can answer: what decides that the home draws the row. */
    val available: Boolean = false,
    val tier: DiscoverTier = DiscoverTier.COMMON,
    /** `null` is every part of speech. */
    val pos: DiscoverPos? = null,
    /** The languages that can answer, the active one first: what the language filter steps through. */
    val languages: List<String> = emptyList(),
    /** The chosen language, or null when none can answer. */
    val lang: String? = null,
    /** The last draw, or null before the first one and after the packs change. */
    val section: DiscoverSection? = null,
)

/**
 * *See more words* (`docs/roadmap.md` i-a2f271-ec624b): random words to learn, filtered by tier,
 * part of speech and language, reloadable.
 *
 * ⚠️ **The language is a filter, not one list per language** -- asked for on 2026-10-22: *"el
 * idioma debería ser un filtro, según los packs disponibles"*. It steps only through the languages
 * that can answer, starts on the active one, and a draw reads only the chosen language's pack.
 *
 * Kept out of `SearchViewModel` on purpose, which is already the largest file in the app: this
 * owns nothing but its packs' pools and what this session has shown. It imports no `android.*`
 * (D-072), so it runs in the gate.
 *
 * - **Which pack answers a language**: the largest that has a `discover` table and defines words
 *   -- a translation pack never does (`givesWordOfTheDay`, the word of the day's same rule). A
 *   pack without the table is passed over rather than hiding its language: a core with the table
 *   answers while an older full one beside it has none.
 * - **What a session remembers**: every pick shown, per pack, in memory only -- the owner's choice
 *   on 2026-10-21 -- so a reload brings new words and closing the app forgets.
 * - **One operation at a time**: changing the packs, the filter or reloading run under one lock,
 *   so a reload never reads a pack a deletion already closed (D-104).
 */
class DiscoverModel(
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
) {
    private val _state = MutableStateFlow(DiscoverState())
    val state: StateFlow<DiscoverState> = _state.asStateFlow()

    private val lock = Mutex()
    private var answering: List<Answering> = emptyList()
    private val seen = HashMap<String, MutableSet<DiscoverPick>>()
    private var reloading: Job? = null

    private class Answering(
        val lang: String,
        val source: DictionarySource,
        val pools: Map<DiscoverBucket, Int>,
    )

    /**
     * The packs that are open now. Call it whenever they change, and with an empty list BEFORE
     * closing them: a reload in flight is cancelled and nothing reads them again.
     */
    fun setPacks(packs: List<DictionarySource>, activeLang: String?) {
        reloading?.cancel()
        scope.launch {
            lock.withLock {
                val withTable = packs
                    .filter { givesWordOfTheDay(it.metadata) }
                    .mapNotNull { source ->
                        val pools = runCatching { source.discoverPools() }.getOrNull()
                        pools?.takeIf { it.isNotEmpty() }?.let { source to it }
                    }
                val porIdioma = withTable
                    .groupBy { (source, _) -> source.metadata.langs.firstOrNull() ?: "" }
                    .map { (lang, candidates) ->
                        val (source, pools) = candidates.maxBy { it.first.metadata.entryCount }
                        Answering(lang, source, pools)
                    }
                    .sortedWith(compareBy({ it.lang != activeLang }, { it.lang }))
                answering = porIdioma
                seen.keys.retainAll(porIdioma.map { it.source.metadata.packId }.toSet())
                val languages = porIdioma.map { it.lang }
                _state.update {
                    it.copy(
                        available = languages.isNotEmpty(),
                        languages = languages,
                        // A choice survives the packs reopening; a language that left gives way
                        // to the first, which is the active one when it can answer.
                        lang = it.lang?.takeIf { l -> l in languages } ?: languages.firstOrNull(),
                        section = null,
                    )
                }
            }
        }
    }

    /** Changes the filter and draws again. `pos = null` is every part of speech. */
    fun select(tier: DiscoverTier, pos: DiscoverPos?) {
        _state.update { it.copy(tier = tier, pos = pos) }
        reload()
    }

    /** Changes the language and draws again. One the packs cannot answer is ignored. */
    fun selectLanguage(lang: String) {
        _state.update { if (lang in it.languages) it.copy(lang = lang) else it }
        reload()
    }

    /** A new list, with no word this session already showed while others remain. */
    fun reload() {
        reloading = scope.launch {
            lock.withLock {
                val tier = _state.value.tier
                val pos = _state.value.pos
                val pack = answering.firstOrNull { it.lang == _state.value.lang } ?: return@withLock
                val desde = System.nanoTime()
                val section = drawFor(pack, tier, pos)
                _state.update { it.copy(section = section) }
                // The readout of the one failure that shows as nothing: a list with zero words,
                // from an empty bucket or a pack that stopped answering. And the time, which only
                // a device measures -- twenty random reads across a large file.
                DictLog.i {
                    "descubrir: $tier/${pos ?: "todas"} ${section.packId}=${section.words.size}" +
                        " en ${(System.nanoTime() - desde) / 1_000_000} ms"
                }
            }
        }
    }

    private suspend fun drawFor(
        pack: Answering,
        tier: DiscoverTier,
        pos: DiscoverPos?,
    ): DiscoverSection {
        val packId = pack.source.metadata.packId
        val shown = seen.getOrPut(packId) { HashSet() }
        val draw = DiscoverDraw.draw(pack.pools, tier, pos, shown, random)
        if (draw.restarted) {
            // The pool ran out: forget what was shown of THESE buckets only, so another filter's
            // memory survives this one starting over.
            shown.removeAll { it.bucket.tier == tier && (pos == null || it.bucket.pos == pos) }
        }
        shown += draw.picks
        val words = runCatching { pack.source.discoverEntries(draw.picks) }.getOrDefault(emptyList())
        val previews = runCatching { pack.source.previews(words.map { it.entryId }) }
            .getOrDefault(emptyMap())
        return DiscoverSection(pack.lang, packId, words, previews)
    }
}
