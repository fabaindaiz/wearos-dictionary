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
    ): PackHandle = PackHandle.Open(
        source = FakeDictionary(packId = packId, lang = lang, entryCount = entries),
        isBundled = demo,
    )

    // --- El representante de un idioma, que ahora usan tres pantallas (D-151) ---------------

    @Test
    fun hayUN_REPRESENTANTE_POR_IDIOMA() {
        val reps = representativePacks(
            listOf(pack("es-def-wikc", "es"), pack("es-def-wd", "es"), pack("en-def", "en")),
            null,
        )
        assertEquals(listOf("en", "es"), reps.map { it.metadata.langSource })
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
    fun elRepresentanteRESPETA_al_activo() {
        val reps = representativePacks(
            listOf(pack("es-chico", "es", entries = 15_000),
                   pack("es-grande", "es", entries = 150_000)),
            activo = "es-chico",
        )
        assertEquals("es-chico", reps.single().packId)
    }

    // --- De qué idioma vino un resultado --------------------------------------------------

    @Test
    fun laEtiquetaEsSIEMPRE_el_idioma() {
        val tags = resultTags(listOf(pack("es-def-wikc", "es"), pack("en-def-wikt", "en")))
        assertEquals("ES", tags["es-def-wikc"])
        assertEquals("EN", tags["en-def-wikt"])
    }

    @Test
    fun conDOS_PACKS_del_mismo_idioma_la_etiqueta_NO_cambia_a_la_fuente() {
        // ⚠️ **Esto invierte `conDOS_PACKS_del_idioma_la_etiqueta_es_la_FUENTE`, que existía
        // hasta hoy.** La regla anterior mostraba `WIKC` / `ENWIKT` para desambiguar dos
        // diccionarios del mismo idioma; el pedido la revierte: *«solo debe ser EN, ES. No me
        // gusta que haya un ENWIK... porque solo me interesa conocer el idioma de
        // proveniencia»*.
        //
        // Lo que se pierde: con `es-def-wikc` y `es-tr-enwikt` instalados a la vez, la fila no
        // dice de cuál de los dos salió. Se acepta porque esa pregunta la contesta la pantalla
        // de gestión de diccionarios, y la fila tiene un ancho que el lema ya disputa.
        val tags = resultTags(listOf(pack("es-def-wikc", "es"), pack("es-tr-enwikt", "es")))
        assertEquals("ES", tags["es-def-wikc"])
        assertEquals("ES", tags["es-tr-enwikt"])
    }

    @Test
    fun unPackIdSIN_LA_FORMA_esperada_igual_da_su_idioma() {
        // Un pack anterior a D-138 no cumple la gramática del `pack_id`. Ya no importa: la
        // etiqueta sale de `lang_source`, que es metadato y no se deriva partiendo el nombre.
        val tags = resultTags(listOf(pack("viejo", "es")))
        assertEquals("ES", tags["viejo"])
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
        assertEquals("es-grande", chips.single().packId)
    }

    @Test
    fun SI_YA_HAY_UNO_ACTIVO_DE_ESE_IDIOMA_SE_RESPETA() {
        // ⚠️ Sin esto, tocar otro idioma y volver te cambiaría el diccionario elegido por debajo.
        val chips = languageChips(
            listOf(pack("es-chico", "es", entries = 15_000),
                   pack("es-grande", "es", entries = 150_000)),
            activo = "es-chico",
        )
        assertEquals("es-chico", chips.single().packId)
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
        val chips = languageChips(listOf(pack("es-def", "es"), pack("en-def", "en")), "es-def")
        assertEquals(mapOf("ES" to true, "EN" to false), chips.associate { it.label to it.active })
    }
}
