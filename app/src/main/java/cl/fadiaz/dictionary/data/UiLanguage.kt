package cl.fadiaz.dictionary.data

/**
 * The languages this app is translated into, for the picker in settings.
 *
 * It is an enum and not a list of tags so the picker cannot drift from the `values-*` folders in
 * silence: `check_ui_languages_match_resources` compares the two.
 */
enum class UiLanguage(val tag: String, val endonym: String) {
    EN("en", "English"),
    ES("es", "Español"),
    ;

    companion object {
        /**
         * The entry a BCP-47 tag selects, or `null` for automatic and for anything unsupported.
         *
         * Only the **primary subtag** is compared. The platform hands back a full tag --"es-CL",
         * "es-419", "en-US"-- for what the picker wrote as "es", and comparing the whole string
         * left the list with nothing marked, which reads as the choice having been forgotten.
         */
        fun of(tag: String?): UiLanguage? {
            val primary = tag?.substringBefore('-')?.lowercase().orEmpty()
            return entries.firstOrNull { it.tag == primary }
        }
    }
}
