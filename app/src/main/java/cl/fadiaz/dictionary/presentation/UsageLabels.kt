package cl.fadiaz.dictionary.presentation

import cl.fadiaz.dictionary.R

/**
 * A sense's usage label, key -> string (d-a2f271-1f80cd). The pack stores `colloquial` and
 * `chile`; the card writes *coloq. · Chile* or *colloq. · Chile* in the reader's language.
 *
 * ⚠️ **A key missing here is skipped, not shown raw**: it comes from a pack built with a newer
 * vocabulary, and `zz_nueva` on a card reads as a bug. The builder side is held to this table
 * by `test_payload.EtiquetasTest`, which reads `strings.xml` for every key it can emit.
 */
private val LABELS: Map<String, Int> = mapOf(
    "colloquial" to R.string.label_colloquial,
    "slang" to R.string.label_slang,
    "vulgar" to R.string.label_vulgar,
    "derogatory" to R.string.label_derogatory,
    "outdated" to R.string.label_outdated,
    "formal" to R.string.label_formal,
    "literary" to R.string.label_literary,
    "jocular" to R.string.label_jocular,
    "childish" to R.string.label_childish,
    "chile" to R.string.label_chile,
    "argentina" to R.string.label_argentina,
    "mexico" to R.string.label_mexico,
    "venezuela" to R.string.label_venezuela,
    "colombia" to R.string.label_colombia,
    "spain" to R.string.label_spain,
    "peru" to R.string.label_peru,
    "rio_de_la_plata" to R.string.label_rio_de_la_plata,
    "uruguay" to R.string.label_uruguay,
    "honduras" to R.string.label_honduras,
    "bolivia" to R.string.label_bolivia,
    "cuba" to R.string.label_cuba,
    "el_salvador" to R.string.label_el_salvador,
    "ecuador" to R.string.label_ecuador,
    "nicaragua" to R.string.label_nicaragua,
    "america" to R.string.label_america,
    "puerto_rico" to R.string.label_puerto_rico,
    "costa_rica" to R.string.label_costa_rica,
    "guatemala" to R.string.label_guatemala,
    "paraguay" to R.string.label_paraguay,
    "dominican_republic" to R.string.label_dominican_republic,
    "andalusia" to R.string.label_andalusia,
    "panama" to R.string.label_panama,
    "central_america" to R.string.label_central_america,
    "canary_islands" to R.string.label_canary_islands,
    "us" to R.string.label_us,
    "uk" to R.string.label_uk,
    "australia" to R.string.label_australia,
    "scotland" to R.string.label_scotland,
    "canada" to R.string.label_canada,
    "ireland" to R.string.label_ireland,
    "india" to R.string.label_india,
    "new_zealand" to R.string.label_new_zealand,
    "south_africa" to R.string.label_south_africa,
    "philippines" to R.string.label_philippines,
)

/** The string of a label key, or null for a key this app does not know. */
internal fun usageLabel(key: String): Int? = LABELS[key]

/**
 * A word's stress type, key -> string (d-a2f271-b4adc7): `oxytone` is *aguda* in Spanish. Same
 * rule as [usageLabel]: a key this app does not know is left out.
 */
private val STRESS: Map<String, Int> = mapOf(
    "oxytone" to R.string.stress_oxytone,
    "paroxytone" to R.string.stress_paroxytone,
    "proparoxytone" to R.string.stress_proparoxytone,
    "superproparoxytone" to R.string.stress_superproparoxytone,
    "monosyllable" to R.string.stress_monosyllable,
)

/** The string of a stress key, or null for a key this app does not know. */
internal fun stressLabel(key: String): Int? = STRESS[key]

/**
 * The line under the word: IPA, syllables and stress, those present, joined by ` · `
 * (d-a2f271-b4adc7). Null when none is, so the card draws no empty row.
 */
internal fun soundLine(pronunciation: String?, syllables: String?, stress: String?): String? =
    listOfNotNull(pronunciation, syllables, stress).filter { it.isNotBlank() }
        .joinToString(" · ").ifEmpty { null }
