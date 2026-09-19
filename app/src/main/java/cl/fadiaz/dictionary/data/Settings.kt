package cl.fadiaz.dictionary.data

/**
 * How much the text grows **on top of** the system scale.
 *
 * On top of and not instead of: the Wear OS quality list (WO-V1) requires the app to respect the
 * system font size, so this multiplies that scale and never replaces it. Someone who already
 * raised the font size across the whole watch keeps seeing it raised.
 *
 * Two values and not a continuous slider: a continuous one cannot be tested against the density
 * budget (D-073), which defines how many rows fit on screen. With two, the density test can be
 * parameterised and still means something.
 */
enum class TextScale(val factor: Float) {
    NORMAL(1.0f),
    LARGE(1.15f),
}

/** What the user chose. No Android: the policy is tested on the JVM (D-072). */
data class Settings(
    val textScale: TextScale = TextScale.NORMAL,
)

private const val KEY_SCALE = "escala"

internal fun serializeSettings(settings: Settings): String =
    "$KEY_SCALE=${settings.textScale.name}"

/**
 * Reads the stored settings, **falling back to the factory ones** instead of failing.
 *
 * Same rule as [parseVisits]: this is read at startup, and an old format after an update or a
 * corrupt byte cannot stop the app from opening. Losing a preference is acceptable; not starting
 * is not.
 */
internal fun parseSettings(text: String): Settings {
    val values = text.lineSequence()
        .mapNotNull { line ->
            val cut = line.indexOf('=')
            if (cut <= 0) null else line.substring(0, cut) to line.substring(cut + 1)
        }
        .toMap()
    val scale = values[KEY_SCALE]
        ?.let { name -> TextScale.entries.firstOrNull { it.name == name } }
        ?: TextScale.NORMAL
    return Settings(textScale = scale)
}
