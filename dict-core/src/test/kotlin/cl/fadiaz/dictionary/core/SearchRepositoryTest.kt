package cl.fadiaz.dictionary.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Searching across several packs at once (D-136).
 *
 * The piece the roadmap kept calling `SearchRepository` and that did not exist. It is what two
 * different things were both waiting on:
 *
 *  - **coexistence**, asked for as *"varios packs para un mismo idioma de distintas fuentes que
 *    puedan convivir"*: two base packs, and the union of their headwords is the coverage gain;
 *  - **composition** (the roadmap item): an auxiliary pack adding fields to another's entry,
 *    joined by `uid`. That one needs this layer first, and is not built here.
 *
 * It is deliberately **not** a `DictionarySource`. Half of that interface is addressed by
 * `entryId`, which is a **rowid local to one pack** (`schema.sql`): answering `entry(7)` over a
 * set of packs means picking a pack, and picking one silently is D-080 -- showing a different
 * word, with no error. The narrow surface is what makes that unrepresentable.
 */
class SearchRepositoryTest {

    /** A pack that answers from a fixed list. Enough: what is under test is the merge. */
    private class FakePack(
        private val id: String,
        private val rows: List<Suggestion> = emptyList(),
        private val definitions: List<Suggestion> = emptyList(),
        private val fails: Boolean = false,
        /** El idioma, para los tests del respaldo. El resto de la suite es toda `es`. */
        private val lang: String = "es",
        /** Como calculo su `rank` este pack. Ver [PackMetadata.rankBasis]. */
        private val rankBasis: RankBasis = RankBasis.PAGE_RICHNESS,
    ) : DictionarySource {
        /** Cuantas veces se consulto. El respaldo se mide por lo que NO pregunta. */
        var consultas = 0
            private set
        override val metadata = PackMetadata(
            packId = id, schemaVersion = 3, normVersion = 2, kind = PackKind.MONOLINGUAL,
            name = id, description = null, langs = listOf(lang),
            fuzzyProfiles = listOf(FuzzyProfile.SPANISH), entryCount = rows.size, dataVersion = 1,
            license = "CC-BY-SA-4.0", attribution = id, rankBasis = rankBasis,
        )
        override suspend fun suggest(query: String, limit: Int, lang: String?): List<Suggestion> {
            consultas++
            if (fails) throw IllegalStateException("este pack esta roto")
            return rows.take(limit)
        }
        override suspend fun searchDefinitions(query: String, limit: Int, lang: String?): List<Suggestion> {
            if (fails) throw IllegalStateException("este pack esta roto")
            return definitions.take(limit)
        }
        override suspend fun entry(entryId: Long): Entry? = null
        override suspend fun resolveHeadwords(norms: Set<String>): Map<String, Long> = emptyMap()
        override suspend fun summary(entryId: Long): EntrySummary? = null
        override fun close() = Unit
        override fun toString() = id
    }

    private fun row(
        pack: String,
        headword: String,
        kind: MatchKind = MatchKind.PREFIX,
        score: Int = 100,
        pos: String? = "noun",
        id: Long = 1,
    ) = Suggestion(pack, id, headword, pos, kind, score)

    @Test
    fun `la union de dos packs es la ganancia de cobertura`() = runTest {
        // El punto entero de la convivencia: una fuente tiene una palabra que la otra no.
        val repo = SearchRepository(listOf(
            FakePack("wikc", listOf(row("wikc", "casa"), row("wikc", "cascada"))),
            FakePack("otra", listOf(row("otra", "casa"), row("otra", "casete"))),
        ))
        val got = repo.suggest("cas").map { it.headword }
        assertEquals(listOf("casa", "cascada", "casete"), got.sorted())
    }

    @Test
    fun `el mismo lema de dos packs sale UNA vez`() = runTest {
        // En una pantalla de reloj "casa · casa" no comunica que hay dos fuentes: comunica que
        // la lista esta rota. Ver las dos definiciones a la vez es composicion, no esto.
        val repo = SearchRepository(listOf(
            FakePack("wikc", listOf(row("wikc", "casa", score = 300))),
            FakePack("otra", listOf(row("otra", "casa", score = 100))),
        ))
        val got = repo.suggest("casa")
        assertEquals(1, got.size)
        assertEquals("otra", got[0].packId, "gana el mejor score, no el primer pack de la lista")
    }

    @Test
    fun `mismo lema con pos distinto son entradas distintas`() = runTest {
        // "fantasma" es sustantivo y adjetivo, y son dos entradas de verdad en el pack real.
        val repo = SearchRepository(listOf(
            FakePack("wikc", listOf(row("wikc", "fantasma", pos = "noun"),
                                    row("wikc", "fantasma", pos = "adj"))),
        ))
        assertEquals(2, repo.suggest("fant").size)
    }

    @Test
    fun `un prefijo de OTRO pack le gana a un fuzzy del propio`() = runTest {
        // El orden cruza los packs en vez de concatenarlos. Si no, escribir bien una palabra
        // que solo esta en el segundo pack la deja debajo de los errores de tipeo del primero.
        val repo = SearchRepository(listOf(
            FakePack("wikc", listOf(row("wikc", "aser", MatchKind.FUZZY, score = 1))),
            FakePack("otra", listOf(row("otra", "hacer", MatchKind.PREFIX, score = 900))),
        ))
        assertEquals(listOf("hacer", "aser"), repo.suggest("hacer").map { it.headword })
    }

    @Test
    fun `empatados, el orden NO depende de en que orden vengan los packs`() = runTest {
        // ⚠️ No es cosmetico, y la primera version de este test pasaba por la razon equivocada.
        //
        // Repetir la misma consulta ya da el mismo orden sin hacer nada: `sortedWith` de Kotlin
        // es estable y conserva el orden de entrada. Lo que NO es estable es **el orden de
        // entrada**: los packs salen de listar `filesDir/packs`, y un listado de directorio no
        // promete orden. Si eso cambia entre arranques --o al instalar un pack-- la lista de
        // resultados se reordena sola.
        //
        // Y eso importa por lo que D-128 dejo medido: si la LISTA se reestructura mientras se
        // escribe, el campo de texto se recompone y **el teclado se cierra**. Intermitente y sin
        // causa visible. El desempate total del comparador es lo que lo impide.
        val zeta = FakePack("zeta", listOf(row("zeta", "casa", score = 100, id = 9)))
        val alfa = FakePack("alfa", listOf(row("alfa", "casa", score = 100, id = 4)))
        val unOrden = SearchRepository(listOf(zeta, alfa)).suggest("ca")
        val elOtro = SearchRepository(listOf(alfa, zeta)).suggest("ca")
        assertEquals(unOrden.map { it.packId }, elOtro.map { it.packId })
    }

    // --- El orden que NO depende de la calibracion de ningun pack (D-142) -------------------

    @Test
    fun `lo que se escribio COMPLETO va antes que una palabra larga mejor rankeada`() = runTest {
        // ⚠️ La señal resiliente. `score` es la posicion dentro de su pack, asi que un pack con
        // el orden interno mal calibrado --o malicioso-- pone su basura en la posicion 0 y la
        // mezcla la trata como el mejor resultado del otro pack.
        //
        // La banda de cobertura se calcula de **lo que el usuario escribio y del lema**, sin
        // mirar un solo dato del pack, asi que no hay calibracion que la envenene.
        //
        // Medido sobre los dos packs reales: escribir "cas" devolvia `castigar, castreño,
        // cascar` y **no devolvia `casa`**; con esto devuelve `casa, casar, cascar`. "per" pasa
        // de `percibir, perder` a `perro, persa`. "arb" de `árbitro` a `árbol`.
        val repo = SearchRepository(listOf(
            FakePack("uno", listOf(
                row("uno", "castigar", score = 0),
                row("uno", "casa", score = 40),
            )),
        ))
        assertEquals(listOf("casa", "castigar"), repo.suggest("cas").map { it.headword })
    }

    @Test
    fun `dentro de una banda manda el orden del pack`() = runTest {
        // No reemplaza al rank: lo acota. Dos palabras de largo parecido siguen ordenandose por
        // lo que el pack sabe, que con un pack bien calibrado es mejor que cualquier heuristica.
        val repo = SearchRepository(listOf(
            FakePack("uno", listOf(
                row("uno", "casar", score = 5),
                row("uno", "casta", score = 1),
            )),
        ))
        assertEquals(listOf("casta", "casar"), repo.suggest("cas").map { it.headword })
    }

    @Test
    fun `un pack con el orden interno ROTO no se lleva la primera fila`() = runTest {
        // El escenario que preocupa: un pack de la comunidad con el rank mal calibrado. Su
        // "mejor" resultado es una palabra larga y rara; el pack bueno tiene la corta.
        val repo = SearchRepository(listOf(
            FakePack("roto", listOf(row("roto", "casuisticamente", score = 0))),
            FakePack("bueno", listOf(row("bueno", "casa", score = 3))),
        ))
        assertEquals("casa", repo.suggest("cas").first().headword)
    }

    // --- Un nombre propio baja, salvo que sea lo que escribiste (D-154) --------------------

    @Test
    fun UN_NOMBRE_PROPIO_NO_LE_GANA_A_UNA_PALABRA_COMUN() = runTest {
        // Medido sobre el pack real: escribir "ital" devolvia `Italia` primero y `italiano`
        // segundo, porque la banda de cobertura premia lo corto. Un toponimo casi nunca es lo
        // que alguien busca en un diccionario.
        val repo = SearchRepository(listOf(
            FakePack("uno", listOf(
                row("uno", "Italia", pos = "name", score = 0),
                row("uno", "italiano", pos = "noun", score = 4),
            )),
        ))
        assertEquals(listOf("italiano", "Italia"), repo.suggest("ital").map { it.headword })
    }

    @Test
    fun PERO_SI_ES_EXACTO_MANTIENE_SU_LUGAR() = runTest {
        // ⚠️ La mitad que hace util la regla. "Fez" y "Car" normalizan EXACTAMENTE a lo escrito,
        // y eso es evidencia legitima: quien escribe "fez" entero puede estar buscando la ciudad.
        // Castigarlos ahi convertiria una mejora en una perdida.
        val repo = SearchRepository(listOf(
            FakePack("uno", listOf(
                row("uno", "Fez", pos = "name", score = 2),
                row("uno", "fezandero", pos = "noun", score = 0),
            )),
        ))
        assertEquals(listOf("Fez", "fezandero"), repo.suggest("fez").map { it.headword })
    }

    @Test
    fun y_si_es_CASI_exacto_tambien() = runTest {
        // Cobertura maxima: escribiste casi toda la palabra. "a menos que hagan match exactos o
        // muy parecidos" -- el "muy parecidos" es esta banda.
        val repo = SearchRepository(listOf(
            FakePack("uno", listOf(
                row("uno", "Perú", pos = "name", score = 3),
                row("uno", "peruanizar", pos = "noun", score = 0),
            )),
        ))
        assertEquals("Perú", repo.suggest("peru").first().headword)
    }

    @Test
    fun el_castigo_NO_cambia_el_orden_entre_dos_nombres_propios() = runTest {
        // Entre iguales sigue mandando lo de siempre: si los dos bajan, el orden relativo es el
        // que el pack y la cobertura ya decidian.
        val repo = SearchRepository(listOf(
            FakePack("uno", listOf(
                row("uno", "Medellín", pos = "name", score = 0),
                row("uno", "Medelona", pos = "name", score = 1),
            )),
        ))
        assertEquals(listOf("Medellín", "Medelona"), repo.suggest("medel").map { it.headword })
    }

    @Test
    fun `en FUZZY la banda no se aplica`() = runTest {
        // Ahi `score` es distancia de edicion, que la calculamos nosotros y ya es resiliente. La
        // cobertura no significa nada cuando lo escrito NO es prefijo del lema.
        val repo = SearchRepository(listOf(
            FakePack("uno", listOf(
                row("uno", "haber", MatchKind.FUZZY, score = 1),
                row("uno", "aser", MatchKind.FUZZY, score = 3),
            )),
        ))
        assertEquals(listOf("haber", "aser"), repo.suggest("acer").map { it.headword })
    }

    @Test
    fun `un pack roto no se lleva puesta la busqueda de los otros`() = runTest {
        // Un pack corrupto o truncado se abre y falla al consultarlo. Con dos instalados, que
        // uno se caiga no puede dejar al usuario sin buscador: es el mismo criterio que la
        // palabra del dia, que ya sigue si un pack falla.
        val repo = SearchRepository(listOf(
            FakePack("roto", fails = true),
            FakePack("sano", listOf(row("sano", "casa"))),
        ))
        assertEquals(listOf("casa"), repo.suggest("cas").map { it.headword })
    }

    @Test
    fun `si fallan TODOS la lista queda vacia y no lanza`() = runTest {
        val repo = SearchRepository(listOf(FakePack("a", fails = true),
                                           FakePack("b", fails = true)))
        assertTrue(repo.suggest("cas").isEmpty())
    }

    @Test
    fun `el limite se respeta DESPUES de mezclar, no por pack`() = runTest {
        // Pedir 3 y recibir 3 por pack llenaria la pantalla con el primero. El limite es de la
        // lista que se muestra.
        val repo = SearchRepository(listOf(
            FakePack("a", (1..10).map { row("a", "a$it", score = it) }),
            FakePack("b", (1..10).map { row("b", "b$it", score = it) }),
        ))
        assertEquals(3, repo.suggest("x", limit = 3).size)
    }

    @Test
    fun `la busqueda por definicion se mezcla igual`() = runTest {
        val repo = SearchRepository(listOf(
            FakePack("a", definitions = listOf(row("a", "guanaco", MatchKind.DEFINITION))),
            FakePack("b", definitions = listOf(row("b", "vicuña", MatchKind.DEFINITION))),
        ))
        assertEquals(setOf("guanaco", "vicuña"),
                     repo.searchDefinitions("camélido").map { it.headword }.toSet())
    }

    @Test
    fun `sin packs no lanza y devuelve vacio`() = runTest {
        assertTrue(SearchRepository(emptyList()).suggest("casa").isEmpty())
    }

    // ---------------------------------------------------------------- respaldo entre idiomas

    @Test
    fun `con el alcance estricto los otros idiomas no contestan NUNCA`() = runTest {
        // ⚠️ **Esto REVIERTE el respaldo por defecto, y el usuario lo pidio con el precio
        // sobre la mesa**: *«que en los resultados filtrados por idioma solo aparezcan
        // resultados de ese idioma, esto va en contra de lo que habia decidido antes»*.
        //
        // El costo esta medido y es el mismo numero que justificaba el respaldo: de **400 lemas
        // ingleses comunes, 321 (80 %)** lo disparaban. Con [LanguageScope.STRICT] esos 321
        // dejan de aparecer mientras el idioma activo sea el otro.
        //
        // No se borra la capacidad: queda como [LanguageScope.FALLBACK], que es el "modo auto"
        // que el pedido nombra para mas adelante. Borrarla habria obligado a re-derivar el
        // umbral y su medicion.
        val ingles = FakePack("en", listOf(row("en", "wardrobe")), lang = "en")
        val repo = SearchRepository(
            listOf(FakePack("es", listOf(row("es", "guardarropa")))),
            otherLanguages = listOf(ingles),
            scope = LanguageScope.STRICT,
        )
        val got = repo.suggest("wardrobe").map { it.headword }
        assertTrue("wardrobe" !in got, "la respuesta del otro idioma NO puede aparecer: $got")
        assertEquals(0, ingles.consultas, "el pack del otro idioma ni se toco")
    }

    @Test
    fun `con una buena respuesta en el idioma activo NO se consulta a los demas`() = runTest {
        // ⚠️ **Lo que se mide es lo que NO pregunta.** El punto medio pedido era *«que no se
        // sobrecargue la busqueda en varios packs innecesariamente»*: el caso normal no puede
        // pagar nada por una funcion que existe para el caso raro.
        //
        // Medido sobre el pack real antes de escribir esto: de **400 lemas españoles comunes,
        // 0 disparan el respaldo**.
        val ingles = FakePack("en", listOf(row("en", "house")), lang = "en")
        val repo = SearchRepository(
            listOf(FakePack("es", listOf(row("es", "casa")))),
            otherLanguages = listOf(ingles),
        )
        assertEquals(listOf("casa"), repo.suggest("casa").map { it.headword })
        assertEquals(0, ingles.consultas, "el pack del otro idioma ni se toco")
    }

    @Test
    fun `sin nada parecido en el idioma activo, contestan los otros`() = runTest {
        // El caso que existe para resolver: escribiste una palabra inglesa con español activo.
        // Medido: **321 de 400 lemas ingleses comunes** llegan aca.
        val repo = SearchRepository(
            listOf(FakePack("es", listOf(row("es", "guardarropa")))),
            otherLanguages = listOf(FakePack("en", listOf(row("en", "wardrobe")), lang = "en")),
            scope = LanguageScope.FALLBACK,
        )
        val got = repo.suggest("wardrobe").map { it.headword }
        assertTrue("wardrobe" in got, "la respuesta correcta tiene que aparecer: $got")
    }

    @Test
    fun `una respuesta exacta de otro idioma le gana a la basura del activo`() = runTest {
        // ⚠️ **Y por eso el respaldo NO va simplemente "despues".** Si el idioma activo devolvio
        // diez resultados por parecido fonetico, poner la respuesta correcta abajo de todos la
        // deja fuera de pantalla -- que es lo mismo que no haberla buscado.
        val repo = SearchRepository(
            listOf(FakePack("es", listOf(row("es", "guardarropa", kind = MatchKind.FUZZY)))),
            otherLanguages = listOf(FakePack("en", listOf(row("en", "wardrobe")), lang = "en")),
            scope = LanguageScope.FALLBACK,
        )
        assertEquals("wardrobe", repo.suggest("wardrobe").first().headword)
    }

    @Test
    fun `a igualdad de calidad manda el idioma que elegiste`() = runTest {
        // El desempate va DESPUES de la calidad, no antes: el idioma activo es una preferencia,
        // no una razon para mostrar algo peor.
        val repo = SearchRepository(
            listOf(FakePack("es", listOf(row("es", "faro", score = 100)))),
            otherLanguages = listOf(FakePack("en", listOf(row("en", "farol", score = 100)),
                lang = "en")),
            scope = LanguageScope.FALLBACK,
        )
        assertEquals("faro", repo.suggest("far").first().headword)
    }

    @Test
    fun `la busqueda por definicion NO hace respaldo`() = runTest {
        // Ahi lo escrito es una palabra de la definicion, no un prefijo del lema, asi que la
        // cobertura no significa nada y el umbral no se puede calcular (ver coverageBand).
        val ingles = FakePack("en", definitions = listOf(row("en", "house")), lang = "en")
        val repo = SearchRepository(
            listOf(FakePack("es", definitions = listOf(row("es", "casa")))),
            otherLanguages = listOf(ingles),
        )
        repo.searchDefinitions("vivienda")
        assertEquals(0, ingles.consultas)
    }

    @Test
    fun `a igual posicion gana el pack cuyo rank es frecuencia real`() = runTest {
        // ⚠️ **Lo que esto cierra, y por que necesitaba una clave nueva en el pack.**
        //
        // La fusion entre packs ya es ORDINAL: `score` es la posicion dentro del propio pack, asi
        // que la escala de `rank` se cancela sola y comparar no exige escalas comparables. Pero
        // hay algo que la fusion ordinal NO arregla: **un pack mal calibrado pone la palabra
        // equivocada en la posicion 0**, y al interlevar recibe el mismo peso que uno bien
        // calibrado.
        //
        // Medido: `es-def-wikc` tiene rank 668..1997 y `es-def-wd` 911..997 -- mismo idioma,
        // formulas distintas (`sources/wikidata.py` tiene la suya), y nada se lo decia a la app.
        //
        // La regla es la mas debil que sirve: **a igual posicion manda el mejor calibrado**. No
        // hunde al otro pack --eso perderia sus lemas exclusivos, que son la razon de D-136--
        // sino que desempata donde antes desempataba el orden alfabetico del `headword`.
        // ⚠️ **`casar` va en el pack de frecuencia y `casa` en el de riqueza, a proposito.**
        // El desempate alfabetico viene despues y pondria `casa` primero, asi que si este test
        // se escribiera al reves pasaria **sin el desempate puesto** -- y la primera version lo
        // hacia. Lo destapo mutar el codigo: se quito la linea y el test siguio verde.
        // Los dos caen en la banda 1 (`cas` cubre 0,60 de `casar` y 0,75 de `casa`), asi que la
        // banda no decide.
        val porFrecuencia = FakePack(
            "es-freq", listOf(row("es-freq", "casar", score = 0)),
            rankBasis = RankBasis.FREQUENCY,
        )
        val porRiqueza = FakePack("es-rich", listOf(row("es-rich", "casa", score = 0)))
        val repo = SearchRepository(listOf(porRiqueza, porFrecuencia))
        assertEquals(
            listOf("casar", "casa"),
            repo.suggest("cas").map { it.headword },
            "a igual posicion tiene que ganar el pack cuyo rank es frecuencia",
        )
    }

    @Test
    fun `una posicion mejor le gana igual a un pack mejor calibrado`() = runTest {
        // La regla desempata, NO reordena: si el pack de riqueza puso algo en la posicion 0 y el
        // de frecuencia en la 1, manda la posicion. Hundir al otro pack perderia sus lemas
        // exclusivos, que son justo la ganancia que D-136 midio.
        // Aca el alfabetico SI coincide con lo esperado, pero lo que se fija es otra cosa: que
        // la posicion siga mandando sobre la calibracion. `casa` esta en la 0 y `casar` en la 1.
        val porFrecuencia = FakePack(
            "es-freq", listOf(row("es-freq", "casar", score = 1)),
            rankBasis = RankBasis.FREQUENCY,
        )
        val porRiqueza = FakePack("es-rich", listOf(row("es-rich", "casa", score = 0)))
        val repo = SearchRepository(listOf(porFrecuencia, porRiqueza))
        assertEquals(
            listOf("casa", "casar"),
            repo.suggest("cas").map { it.headword },
            "la posicion manda: hundir el otro pack perderia sus lemas exclusivos",
        )
    }

}
