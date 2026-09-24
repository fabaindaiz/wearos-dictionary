package cl.fadiaz.dictionary.presentation

import cl.fadiaz.dictionary.core.FuzzyProfile
import cl.fadiaz.dictionary.core.PackKind
import cl.fadiaz.dictionary.core.PackMetadata
import cl.fadiaz.dictionary.data.PackHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El selector elige **un idioma**, no un archivo (D-147).
 *
 * ⚠️ **Esto cierra una incoherencia que D-136 introdujo y no terminó.** Esa decisión dejó escrito
 * que *el selector pasa a elegir un idioma, no un archivo* —porque `SearchRepository` consulta
 * todos los packs del idioma activo— pero la pantalla siguió listando **packs**. Con dos
 * diccionarios de español el inicio mostraba **dos chips "ES"**, los dos activables, y tocarlos
 * no cambiaba lo que se buscaba: ya se buscaba en los dos.
 *
 * La lógica vive acá y no en la pantalla porque es una **decisión**, no un dibujo: qué idiomas
 * hay, en qué orden, y cuál pack representa a cada uno. Así la cubre el gate en la JVM en vez de
 * Robolectric.
 */
class LanguageChipsTest {

    private fun pack(
        packId: String,
        lang: String,
        entries: Int = 1,
        demo: Boolean = false,
        subsetOf: String? = null,
    ): PackHandle = PackHandle.Open(
        source = FakeDictionary(
            packId = packId, lang = lang, entryCount = entries, subsetOf = subsetOf,
        ),
        isBundled = demo,
    )

    // --- El representante de un idioma, que ahora usan tres pantallas (D-151) ---------------

    @Test
    fun hayUN_REPRESENTANTE_POR_IDIOMA() {
        val reps = representativePacks(
            listOf(pack("es-def-wikc", "es"), pack("es-def-wd", "es"), pack("en-def", "en")),
            null,
        )
        assertEquals(listOf("en", "es"), reps.map { it.metadata.langs.first() })
    }

    @Test
    fun LA_PALABRA_DEL_DIA_DEJA_DE_REPETIR_IDIOMA() {
        // ⚠️ El bug que D-145 tapó fundiendo packs en vez de arreglarlo: con dos diccionarios de
        // español el inicio calculaba DOS palabras del día del mismo idioma. Fundir Wikidata lo
        // escondió; vuelve en cuanto alguien instale un pack propio.
        val reps = representativePacks(
            listOf(pack("es-a", "es"), pack("es-b", "es"), pack("es-c", "es")),
            null,
        )
        assertEquals(1, reps.size)
    }

    @Test
    fun elRepresentanteEsElMasCompletoSiNoHayActivo() {
        val reps = representativePacks(
            listOf(pack("es-chico", "es", entries = 15_000),
                   pack("es-grande", "es", entries = 150_000)),
            null,
        )
        assertEquals("es-grande", reps.single().packId)
    }

    @Test
    fun unPackEnSOMBRA_no_representa_a_su_idioma() {
        // ⚠️ The bug this closes is not visible in a passing app: `packsToQuery` already drops a
        // shadowed pack, and `representativePacks` did not. They agreed only because an absorber
        // has more entries than its subset --arithmetic, not a rule-- so nothing failed the day a
        // subset declared more. Here `en-main` declares MORE entries than the `en-full` that
        // absorbs it, which is the case the coincidence never covered.
        val reps = representativePacks(
            listOf(
                pack("en-full", "en", entries = 100),
                pack("en-main", "en", entries = 999, subsetOf = "en-full"),
            ),
            null,
        )
        assertEquals("en-full", reps.single().packId)
    }

    @Test
    fun siTODOS_estan_en_sombra_el_idioma_igual_tiene_representante() {
        // A language that loses its representative disappears from the selector, which is worse
        // than representing it with an absorbed pack.
        //
        // ⚠️ **A cycle does NOT reach this branch and the first version of this test used one**:
        // in a cycle nobody absorbs, so `packsToQuery` keeps both and the fallback never runs. It
        // passed while guarding nothing. What reaches it is a pack absorbed by one that does not
        // speak its language -- a declaration nothing forbids, since `subset_of` is written by
        // whoever built the pack.
        val absorbente = pack("es-full", "es")
        val sombra = pack("en-main", "en", subsetOf = "es-full")
        val reps = representativePacks(listOf(absorbente, sombra), null)
        assertEquals(listOf("en-main", "es-full"), reps.map { it.packId }.sorted())
    }

    @Test
    fun elRepresentanteRESPETA_al_activo() {
        val reps = representativePacks(
            listOf(pack("es-chico", "es", entries = 15_000),
                   pack("es-grande", "es", entries = 150_000)),
            "es-chico",
        )
        assertEquals("es-chico", reps.single().packId)
    }

    // --- De qué idioma vino un resultado --------------------------------------------------

    @Test
    fun laEtiquetaEsELIDIOMA_ACTIVO_y_no_una_propiedad_del_pack() {
        // ⚠️ **Esto reemplaza al mapa `packId -> etiqueta`, y el motivo es el pack
        // bidireccional.** Un pack con entradas de dos idiomas habría tenido que devolver `ES`
        // para unas filas y `EN` para otras, así que la etiqueta dejó de ser una propiedad del
        // pack. Lo que la decide es el idioma en el que se buscó: la lista está filtrada a él,
        // tanto entre packs (D-189) como dentro de uno (`WHERE lang = ?`).
        assertEquals("ES", resultTag("es"))
        assertEquals("EN", resultTag("en"))
    }

    @Test
    fun sinIdiomaActivo_no_hay_etiqueta_que_afirmar() {
        // Inventar una sería afirmar una procedencia que nadie comprobó, que es la familia de
        // D-080: mejor sin etiqueta que con la equivocada.
        assertEquals(null, resultTag(null))
    }

    @Test
    fun unPackPorIdiomaDaUnChipPorIdioma() {
        val chips = languageChips(listOf(pack("es-def", "es"), pack("en-def", "en")), null)
        assertEquals(listOf("EN", "ES"), chips.map { it.label })
    }

    @Test
    fun DOS_PACKS_DEL_MISMO_IDIOMA_DAN_UN_SOLO_CHIP() {
        // El bug reportado desde el reloj: dos diccionarios de español mostraban dos "ES".
        val chips = languageChips(
            listOf(pack("es-def-wikc", "es"), pack("es-def-wd", "es"), pack("en-def", "en")),
            null,
        )
        assertEquals(listOf("EN", "ES"), chips.map { it.label })
    }

    @Test
    fun elChipDeUnIdiomaCON_VARIOS_PACKS_apunta_al_mas_completo() {
        // Tocar el chip activa UN pack, y ese decide la atribución que se muestra, la palabra del
        // día del tile y qué pack va primero al desempatar. El más completo es el mejor default:
        // es el que más veces va a tener la palabra.
        val chips = languageChips(
            listOf(pack("es-chico", "es", entries = 15_000),
                   pack("es-grande", "es", entries = 150_000)),
            null,
        )
        assertEquals("es", chips.single().lang)
    }

    @Test
    fun SI_YA_HAY_UNO_ACTIVO_DE_ESE_IDIOMA_SE_RESPETA() {
        // ⚠️ Sin esto, tocar otro idioma y volver te cambiaría el diccionario elegido por debajo.
        val chips = languageChips(
            listOf(pack("es-chico", "es", entries = 15_000),
                   pack("es-grande", "es", entries = 150_000)),
            activoLang = "es",
        )
        assertEquals("es", chips.single().lang)
    }

    @Test
    fun elOrdenNoDependeDelOrDEN_DEL_DIRECTORIO() {
        // Los packs salen de listar `filesDir/packs`, que no promete orden. Si el orden de los
        // chips lo siguiera, cambiarían de lugar entre arranques -- y un control que se mueve
        // solo se toca por error. Mismo criterio que el desempate de D-136.
        val unOrden = languageChips(listOf(pack("z", "es"), pack("a", "en")), null)
        val elOtro = languageChips(listOf(pack("a", "en"), pack("z", "es")), null)
        assertEquals(unOrden.map { it.label }, elOtro.map { it.label })
    }

    @Test
    fun conUnSoloIdiomaNoHaySelector() {
        // Un selector de una opción es puro cromo, y en un reloj el cromo se paga en resultados.
        assertTrue(languageChips(listOf(pack("es-def", "es")), null).size < 2)
    }

    @Test
    fun elChipSabeSiEsElIdiomaACTIVO() {
        val chips = languageChips(listOf(pack("es-def", "es"), pack("en-def", "en")), "es")
        assertEquals(mapOf("ES" to true, "EN" to false), chips.associate { it.label to it.active })
    }
}
