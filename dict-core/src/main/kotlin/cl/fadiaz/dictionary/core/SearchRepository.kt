package cl.fadiaz.dictionary.core

/**
 * Searches **several packs at once** and merges the answers into one list (D-136).
 *
 * The layer the roadmap kept naming and that did not exist. Two different things were waiting on
 * it, and telling them apart matters because only the first is built here:
 *
 *  - **Coexistence.** Two *base* packs of the same language, from different sources, installed
 *    together. The value is the **union of headwords**: a source has a word the other lacks.
 *    Asked for as *"varios packs para un mismo idioma de distintas fuentes que puedan convivir"*.
 *  - **Composition.** An *auxiliary* pack adding fields to another pack's entry, joined by
 *    [Entry.uid] (D-055). That needs this layer underneath and a granularity decision that is
 *    still open — `uid` is per entry and a synonym is per sense. **Not built here.**
 *
 * ⚠️ **This is deliberately NOT a [DictionarySource], and the reason is a bug class.** Half of
 * that interface is addressed by `entryId`, which is a **rowid local to one pack** (`schema.sql`):
 * answering `entry(7)` over a set of packs means choosing one, and choosing silently is D-080 —
 * the app shows a *different word* with no error anywhere. A composite implementing the whole
 * interface would have to either throw on half its methods or guess. The narrow surface makes it
 * unrepresentable instead: everything here returns [Suggestion], which carries its `packId`, and
 * opening an entry stays the caller's per-pack path.
 *
 * **The fan-out is sequential on purpose.** The installed packs are one or two, and `:dict-data`
 * already serialises each pack's queries onto its own single-threaded dispatcher (D-050), so
 * `async` would buy little and would cost `:dict-core` a dependency it does not have today.
 */
/**
 * Hasta donde llega una busqueda: un idioma, o todos los instalados.
 *
 * ⚠️ **[STRICT] es el defecto y eso REVIERTE lo que media D-172.** El pedido fue *«que en los
 * resultados filtrados por idioma solo aparezcan resultados de ese idioma»*, con la reversion
 * declarada: *«esto va en contra de lo que habia decidido antes pero creo que es mejor»*.
 *
 * **El costo es el numero que justificaba el respaldo**, y no cambio al revertirlo: de 400 lemas
 * ingleses comunes, **321 (80 %)** lo disparaban con español activo. Esos 321 ahora no aparecen
 * hasta cambiar de idioma. Se acepta porque una lista que mezcla idiomas sin pedirlo es peor de
 * leer que una lista corta.
 *
 * **Por que un enum y no borrar el respaldo.** El umbral de `needsFallback` se midio sobre los
 * packs reales y esa medicion no se puede reconstruir leyendo el codigo. Como valor, el "modo
 * auto" que el pedido nombra para mas adelante es cambiar [STRICT] por [FALLBACK] en un sitio;
 * borrado, seria volver a derivar el umbral.
 */
enum class LanguageScope {
    /** Solo el idioma activo contesta. El defecto. */
    STRICT,

    /** Los otros idiomas contestan cuando el activo no tuvo nada parecido. El "modo auto". */
    FALLBACK,
}

class SearchRepository(
    private val packs: List<DictionarySource>,
    /**
     * Los packs de los **otros** idiomas, que contestan sólo cuando el activo no tuvo nada.
     *
     * Pedido: *«que evite generar conflictos cuando la palabra que busco está en inglés pero por
     * error seleccioné español como idioma principal»*, con la condición explícita de *«que no se
     * sobrecargue la búsqueda en varios packs innecesariamente»*. Las dos mitades están en
     * [needsFallback].
     */
    private val otherLanguages: List<DictionarySource> = emptyList(),
    /**
     * Si los packs de [otherLanguages] pueden contestar. Por defecto **no**.
     *
     * Ver [LanguageScope]: el respaldo no se borro, se volvio un valor.
     */
    private val scope: LanguageScope = LanguageScope.STRICT,
    /**
     * El idioma en el que se busca, o `null` para no filtrar.
     *
     * ⚠️ **Hace falta desde que un pack puede tener entradas de DOS idiomas.** Elegir el pack ya
     * no elige el idioma: un bidireccional habla los dos, asi que sin esto una lista filtrada a
     * español traeria sus lemas ingleses. Viaja hasta el `WHERE lang = ?` de cada peldaño, donde
     * sale del mismo indice de cobertura y no cuesta una fila de mas.
     */
    private val lang: String? = null,
) {

    /** Los ids del idioma activo, para el desempate de [orderFor]. */
    private val activos: Set<String> = packs.map { it.metadata.packId }.toSet()

    /**
     * Los packs cuyo `rank` sale de la frecuencia de uso real. Ver [RankBasis].
     *
     * Se calcula una vez y no por consulta: es una propiedad del pack abierto, no de la busqueda.
     * Incluye tambien los de otros idiomas, porque el desempate vale igual entre ellos.
     */
    private val porFrecuencia: Set<String> =
        (packs + otherLanguages)
            .filter { it.metadata.rankBasis == RankBasis.FREQUENCY }
            .map { it.metadata.packId }
            .toSet()

    /**
     * The normal search, across every pack.
     *
     * `limit` applies to the **merged** list, not to each pack: asking for three and getting
     * three per pack would fill a watch screen with whichever pack answered first.
     */
    suspend fun suggest(query: String, limit: Int = DEFAULT_LIMIT): List<Suggestion> {
        val propias = recolectar(packs) { it.suggest(query, limit, lang) }
        val ajenas = if (needsFallback(query, propias)) {
            recolectar(otherLanguages) { it.suggest(query, limit) }
        } else {
            emptyList()
        }
        return ordenar(propias + ajenas, limit, query)
    }

    /**
     * ¿Hay que preguntarle a los otros idiomas?
     *
     * **Sólo si el idioma activo no devolvió nada que se parezca a lo escrito**: ni una
     * coincidencia exacta, ni una sola fila en la banda de cobertura máxima. La condición se
     * calcula del texto escrito y del lema, **sin mirar un número de ningún pack**, igual que
     * [coverageBand] -- así que un pack mal calibrado no puede ni disparar el respaldo ni
     * taparlo.
     *
     * ⚠️ **El umbral se midió antes de elegirlo, sobre los dos packs reales.** De **400 lemas
     * españoles comunes, 0** disparan el respaldo: el caso normal no paga absolutamente nada. De
     * **400 lemas ingleses comunes, 321 (80 %)** lo disparan, que es exactamente el caso para el
     * que existe. Los 79 que no lo disparan --`break`, `man`, `go`, `line`, `bear`-- **están de
     * verdad en el pack español**, así que no llegar al respaldo es la respuesta correcta.
     */
    private fun needsFallback(query: String, propias: List<Suggestion>): Boolean {
        if (scope == LanguageScope.STRICT) return false
        if (otherLanguages.isEmpty() || query.isEmpty()) return false
        return propias.none {
            query.equals(it.headword, ignoreCase = true) || coverageBand(query, it.headword) == 0
        }
    }

    /** The free-text search over definitions (D-084). Same merge, same order. */
    suspend fun searchDefinitions(query: String, limit: Int = DEFAULT_LIMIT): List<Suggestion> =
        // Sin `query` para la banda: acá lo escrito no es un prefijo del lema sino una palabra
        // de la definición, así que la cobertura no significa nada. Ver [coverageBand].
        //
        // ⚠️ **Y por lo mismo no hay respaldo entre idiomas acá**: el umbral de [needsFallback]
        // se calcula con esa cobertura, y sin ella no hay forma de decidir cuándo el idioma
        // activo "no tuvo nada" sin inventar un criterio.
        ordenar(recolectar(packs) { it.searchDefinitions(query, limit, lang) }, limit, query = null)

    private fun ordenar(todas: List<Suggestion>, limit: Int, query: String?): List<Suggestion> =
        todas
            .sortedWith(orderFor(query, activos, porFrecuencia))
            .distinctBy { it.headword to it.partOfSpeech }
            .take(limit)

    private suspend fun recolectar(
        fuentes: List<DictionarySource>,
        consultar: suspend (DictionarySource) -> List<Suggestion>,
    ): List<Suggestion> {
        val todas = mutableListOf<Suggestion>()
        for (pack in fuentes) {
            // ⚠️ A broken pack must not take the search down with it. A corrupt or truncated
            // file opens fine and fails when queried; with two installed, one falling over
            // cannot leave the user with no dictionary at all. Same criterion the word of the
            // day already applies. The failure is not swallowed into a wrong answer -- the
            // other packs still answer, and a pack that never returns anything is visible.
            @Suppress("TooGenericExceptionCaught", "SwallowedException")
            val suyas = try {
                consultar(pack)
            } catch (e: Exception) {
                continue
            }
            todas.addAll(suyas)
        }
        return todas
    }

    private companion object {
        const val DEFAULT_LIMIT = 30

        /**
         * How much of the headword the user actually typed, in **coarse bands**.
         *
         * ⚠️ **The one signal in the whole ordering that trusts no pack at all** (D-142). It is a
         * function of the typed text and of the headword string: no `rank`, no `score`, nothing a
         * pack computed. That is exactly what makes it survive a badly calibrated —or hostile—
         * community pack, because `score` is *the position inside that pack's own list*, so a
         * pack with a broken internal order hands its garbage over at position 0 and the merge
         * treats it as the equal of the good pack's best row.
         *
         * **Bands and not the raw ratio, deliberately.** Coarse buckets put `casa` above
         * `castigar` without overriding a *good* pack inside a bucket: two words of similar
         * length keep the order the pack chose, which beats any heuristic when the pack is sane.
         *
         * Measured over the two real Spanish packs: typing "cas" returned `castigar, castreño,
         * cascar` and **did not return `casa` at all**; with this it returns `casa, casar,
         * cascar`. "per" goes from `percibir, perder` to `perro, persa`. "arb" from `árbitro` to
         * `árbol`. It fixes a visible ordering bug that was there with **one** pack too (D-067).
         *
         * ⚠️ Cross-pack agreement —ranking a headword higher because several packs returned it—
         * was measured next to this and **rejected**: it improved "cas" and made "tomat" worse,
         * and it makes the order depend on which *other* dictionaries happen to be installed.
         */
        internal fun coverageBand(query: String, headword: String): Int {
            if (headword.isEmpty()) return LAST_BAND
            val coverage = query.length.toDouble() / headword.length
            return when {
                coverage >= 0.85 -> 0   // practically typed the whole word
                coverage >= 0.60 -> 1
                coverage >= 0.40 -> 2
                else -> LAST_BAND
            }
        }

        private const val LAST_BAND = 3

        /**
         * [ORDEN], with the calibration-free band in front when there is a query to measure
         * against.
         *
         * The band applies **only to prefix matches**. For `FUZZY`, `score` is the edit distance
         * —which we compute, so it is already pack-independent— and the coverage of a word that
         * is *not* a prefix of the headword means nothing.
         */
        private fun orderFor(
            query: String?,
            activos: Set<String>,
            porFrecuencia: Set<String>,
        ): Comparator<Suggestion> {
            if (query.isNullOrEmpty()) return ORDEN
            return compareBy<Suggestion> { it.matchKind.ordinal }
                .thenBy { if (it.matchKind == MatchKind.PREFIX) demoteProperNoun(query, it) else 0 }
                .thenBy { if (it.matchKind == MatchKind.PREFIX) coverageBand(query, it.headword) else 0 }
                // ⚠️ **El idioma activo desempata, y va DESPUÉS de la calidad y no antes.** Si el
                // respaldo se limitara a ir al final de la lista, una respuesta exacta en el otro
                // idioma quedaría debajo de diez parecidos fonéticos del activo -- fuera de
                // pantalla, que es lo mismo que no haberla buscado. Y al revés: a igualdad de
                // todo lo demás manda el idioma que el usuario eligió, porque lo eligió.
                .thenBy { if (it.packId in activos) 0 else 1 }
                .thenBy { it.matchKind.ordinal }
                .thenBy { it.score }
                // ⚠️ **A igual posición manda el pack mejor calibrado, y es el desempate más
                // débil que sirve.** La fusión ya es ordinal --`score` es la posición dentro del
                // propio pack-- así que la escala de `rank` se cancela sola. Lo que eso NO
                // arregla: un pack mal calibrado pone la palabra equivocada en la posición 0 y
                // al interlevar recibe el mismo peso que uno bien calibrado. Medido sobre los
                // packs reales: `es-def-wikc` tiene rank 668..1997 y `es-def-wd` 911..997, el
                // mismo idioma con fórmulas distintas, y nada se lo decía a la app.
                //
                // Va DESPUÉS de `score` a propósito: desempata, no reordena. Ponerlo antes
                // hundiría al otro pack entero y con él sus lemas exclusivos, que son justo la
                // ganancia que D-136 midió.
                .thenBy { if (it.packId in porFrecuencia) 0 else 1 }
                .thenBy { it.headword }
                .thenBy { it.packId }
                .thenBy { it.entryId }
        }

        /**
         * Los `pos` que la fuente usa para un nombre propio.
         *
         * Dos vocabularios porque hay dos fuentes: kaikki dice `name` y `sources/toy.py` dice
         * `proper noun`. Mirar uno solo deja pasar el otro, y ya paso una vez (D-116).
         */
        private val NOMBRES_PROPIOS = setOf("name", "proper noun")

        /**
         * Un nombre propio va **debajo** de una palabra comun, salvo que sea lo que escribiste.
         *
         * ⚠️ **El castigo del builder no alcanzaba, y eso se midio.** `CASTIGO_NOMBRE_PROPIO`
         * (D-134) ya los pone al fondo del `rank`, pero la banda de cobertura va **delante** del
         * rank en esta mezcla: un toponimo corto le gana a una palabra comun larga. Sobre el pack
         * real, escribir "ital" devolvia `Italia` primero y `italiano` segundo.
         *
         * ⚠️ **La excepcion es la mitad que lo vuelve util.** "Fez", "Car" y "Peru" normalizan a
         * exactamente lo escrito, o casi: quien escribe la palabra entera puede estar buscando la
         * ciudad, y castigarla ahi convertiria la mejora en una perdida. Por eso el castigo se
         * aplica **solo** cuando no es exacto y ademas no esta en la banda de cobertura maxima.
         *
         * Se calcula del `pos` y del texto escrito, sin mirar ningun numero del pack -- la misma
         * propiedad que hace confiable a [coverageBand] frente a un pack mal calibrado.
         */
        private fun demoteProperNoun(query: String, suggestion: Suggestion): Int {
            if (suggestion.partOfSpeech !in NOMBRES_PROPIOS) return 0
            val exacto = query.equals(suggestion.headword, ignoreCase = true)
            return if (exacto || coverageBand(query, suggestion.headword) == 0) 0 else 1
        }

        /**
         * The order across packs, and the reason it is one comparator and not a concatenation.
         *
         * [MatchKind] first because it is declared in quality order: a **prefix** hit in the
         * second pack has to beat a **fuzzy** hit in the first, or typing a word correctly that
         * only the second pack has would rank it below the first pack's typos.
         *
         * ⚠️ **`packId` and `entryId` at the end are not cosmetic.** Without a total order two
         * identical queries can return the same rows in a different order, and D-128 measured
         * what that does: the list restructures, the text field is recomposed, and **the
         * keyboard closes**. Intermittently, with no visible cause.
         *
         * ⚠️ **What this order does NOT promise**: `score` derives from `rank`, which each pack
         * computes against its own dump with its own profile (D-063). Comparing it across packs
         * from different sources is an approximation nobody has measured. It is good enough to
         * keep exact matches above fuzzy ones; it is not good enough to claim the first row is
         * the best of the two packs. Measuring that needs two real same-language packs.
         */
        val ORDEN: Comparator<Suggestion> = compareBy(
            { it.matchKind.ordinal },
            { it.score },
            { it.headword },
            { it.packId },
            { it.entryId },
        )
    }
}
