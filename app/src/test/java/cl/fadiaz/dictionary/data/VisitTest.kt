package cl.fadiaz.dictionary.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The history codec, which is where this can corrupt itself in silence.
 *
 * It is stored as text in SharedPreferences, so the format is a contract with the disk: an old
 * version after an update, or a headword with an unexpected character, cannot bring the app down
 * or show the wrong word. That is why `parseVisits` **discards** what it does not understand
 * instead of throwing.
 */
class VisitTest {

    private fun visit(headword: String, pack: String = "es-def", id: Long = 1, pos: String? = "noun") =
        Visit(packId = pack, entryId = id, headword = headword, partOfSpeech = pos)

    @Test
    fun whatIsStoredComesBack() {
        val original = listOf(visit("perro"), visit("house", "en-def", 7, "verb"))
        assertEquals(original, parseVisits(serializeVisits(original)))
    }

    @Test
    fun aHeadwordWithAccentsQuotesAndTabsSurvives() {
        // Headwords come from Wiktionary: there are sayings with quotes in them, and a stray
        // tab inside a gloss has already happened once in this project.
        val raro = visit("mas corre el galgo\tque el \"mastin\"")
        assertEquals(listOf(raro), parseVisits(serializeVisits(listOf(raro))))
    }

    @Test
    fun aNullPartOfSpeechSurvives() {
        // The toy pack has an entry with no pos, and so does Wiktionary.
        val withoutPos = visit("arbol", pos = null)
        assertEquals(listOf(withoutPos), parseVisits(serializeVisits(listOf(withoutPos))))
    }

    @Test
    fun anUnreadableLineIsDroppedWithoutLosingTheRest() {
        // This is the case of an old format after updating the app. Losing the history is
        // acceptable; the app failing to start is not.
        val bueno = serializeVisits(listOf(visit("perro")))
        assertEquals(listOf(visit("perro")), parseVisits("basura sin separadores\n" + bueno))
    }

    @Test
    fun emptyTextGivesEmptyHistory() {
        assertTrue(parseVisits("").isEmpty())
    }

    @Test
    fun aNonNumericEntryIdIsDropped() {
        val roto = listOf("es-def", "no-es-un-numero", "perro", "noun").joinToString(SEPARATOR)
        assertTrue(parseVisits(roto).isEmpty())
    }
}

/**
 * That the history survives rebuilding a pack.
 *
 * `entry.id` is the rowid and shifts when the source adds a word in the middle (D-055). The
 * failure mode is NOT a crash: it is opening *another word* with the right headword written in
 * the list, which is the class of bug this repo cannot see from the code.
 *
 * Why `entry.uid` is not backed up, even though it IS stable: it has no index (D-056), so
 * resolving it costs a scan of 114,619 rows in Spanish and 794,355 in English **per tap**, and
 * the app is forbidden from computing it (D-057). The headword, by contrast, goes through
 * `idx_entry_norm`.
 */
class VisitTargetTest {

    private val visit = Visit(packId = "es-def-wikc", entryId = 42, headword = "perro", partOfSpeech = "noun")

    @Test
    fun ifTheIdStillHoldsThatHeadwordItOpensDirectly() {
        // The normal case, and the one that has to cost ZERO extra queries.
        assertEquals(
            VisitTarget.Direct(42),
            visitTarget(visit, headwordAtId = "perro", relocated = 999),
        )
    }

    @Test
    fun ifTheIdNowPointsElsewhereItIsFixedByHeadword() {
        // Exactly what a rebuild does: 42 is now a different entry.
        assertEquals(
            VisitTarget.Relocated(777),
            visitTarget(visit, headwordAtId = "perpetuo", relocated = 777),
        )
    }

    @Test
    fun ifTheIdIsGoneItIsFixedByHeadword() {
        // The pack shrank --D-116 removed 31,575 Spanish entries-- and the id fell out of range.
        assertEquals(
            VisitTarget.Relocated(777),
            visitTarget(visit, headwordAtId = null, relocated = 777),
        )
    }

    @Test
    fun ifTheWordLeftThePackItIsGivenUpAsMissing() {
        // Also D-116: the pruned word exists in the history and no longer in the pack. The row
        // is lost, no other word is opened.
        assertEquals(
            VisitTarget.Missing,
            visitTarget(visit, headwordAtId = null, relocated = null),
        )
    }

    @Test
    fun withNoHeadwordToFixByTheIdIsTrustedIfItExists() {
        // A tile deep link can arrive without a headword --the `Visit` is built from the intent
        // extras, which is untrusted input--. With no headword there is nothing to re-resolve
        // by, so the only question left is whether that id exists.
        val withoutHeadword = visit.copy(headword = "")
        assertEquals(
            VisitTarget.Direct(42),
            visitTarget(withoutHeadword, headwordAtId = "cualquiera", relocated = null),
        )
    }

    @Test
    fun withNoHeadwordAndAMissingIdNothingOpens() {
        val withoutHeadword = visit.copy(headword = "")
        assertEquals(
            VisitTarget.Missing,
            visitTarget(withoutHeadword, headwordAtId = null, relocated = null),
        )
    }

    @Test
    fun aHeadwordPresentUnderAnotherIdIsNotMistakenForDirect() {
        // If re-resolving returns the SAME id, it is still direct: there is nothing to fix.
        assertEquals(
            VisitTarget.Direct(42),
            visitTarget(visit, headwordAtId = "perro", relocated = 42),
        )
    }
    @Test
    fun unaVISITA_CON_GLOSA_sobrevive_al_viaje_de_ida_y_vuelta() {
        // La palabra del día del tile muestra su primera acepción, así que la caché la guarda.
        val con = Visit("es-def", 7, "casa", "noun", "Edificación destinada a vivienda.")
        assertEquals(listOf(con), parseVisits(serializeVisits(listOf(con))))
    }

    @Test
    fun unaVISITA_VIEJA_DE_CUATRO_CAMPOS_se_sigue_leyendo() {
        // ⚠️ **El parser exigía EXACTAMENTE 4 campos**, así que agregar uno habría hecho que la
        // app rechazara sus propios registros nuevos — y al revés, una preferencia escrita antes
        // de este cambio tiene que seguir leyéndose. Es un contrato con el disco: lo que está
        // guardado en un reloj no se migra, se tolera.
        val vieja = listOf("es-def", "7", "casa", "noun").joinToString(SEPARATOR)
        assertEquals(
            listOf(Visit("es-def", 7, "casa", "noun", gloss = null)),
            parseVisits(vieja),
        )
    }

    @Test
    fun aVisitCarriesItsOwnLanguageThereAndBack() {
        // D-265. The language is a property of the VISIT: `atizar` and `stoke` both come out of
        // the bidirectional pack, so the pack cannot tell them apart and the row could not either.
        val con = Visit("es-tr-enwikt-freq", 7, "stoke", "verb", lang = "en")
        assertEquals(listOf(con), parseVisits(serializeVisits(listOf(con))))
    }

    @Test
    fun aVisitWrittenBeforeTheLanguageFieldStaysUntagged() {
        // ⚠️ **That IS the migration, and it was the whole decision.** A row written before the
        // field existed has no honest language to give it: deriving one from the pack is what
        // produced the defect, and from the active language is D-080's family. It draws with no
        // tag, which the screens already handle -- an unknown pack has always drawn that way.
        val cinco = listOf("es-def", "7", "casa", "noun", "Edificación.").joinToString(SEPARATOR)
        val cuatro = listOf("es-def", "7", "casa", "noun").joinToString(SEPARATOR)
        assertEquals(null, parseVisits(cinco).single().lang)
        assertEquals(null, parseVisits(cuatro).single().lang)
    }

    @Test
    fun unaGLOSA_CON_SALTO_DE_LINEA_no_parte_el_registro() {
        // El separador de REGISTROS es `\n`. Una glosa que lo trajera partiría la lista en dos
        // y la segunda mitad se descartaría en silencio — que es peor que perder la glosa.
        val sucia = Visit("es-def", 7, "casa", "noun", "linea uno\nlinea dos")
        val leida = parseVisits(serializeVisits(listOf(sucia))).single()
        assertEquals("casa", leida.headword)
        assertEquals(false, leida.gloss?.contains("\n"))
    }

}
