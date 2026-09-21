package cl.fadiaz.dictionary.data

/**
 * How much the text grows **on top of** the system scale.
 *
 * On top of and not instead of: the Wear OS quality list (WO-V1) requires the app to respect the
 * system font size, so this multiplies that scale and never replaces it. Someone who already
 * raised the font size across the whole watch keeps seeing it raised.
 *
 * **Three fixed steps and not a continuous slider**: a continuous one cannot be tested against
 * the density budget (D-073), which defines how many rows fit on screen. With a closed set, the
 * density test can be parameterised and still means something.
 *
 * ⚠️ **The order of the entries IS the order on screen** (`TextScale.entries` drives the list),
 * so they go smallest to largest. [NORMAL] is not first and that is deliberate: it stays the
 * default, and the default is stored **by name**, so adding a step never reinterprets what an
 * older install saved.
 */
enum class TextScale(val factor: Float) {
    /**
     * Added on request: *«en text size me interesa incluir el tamaño pequeño junto con el normal
     * y grande»*.
     *
     * 0.85 and not less: below that the 234 dp screen stops gaining rows --the row height is
     * [TOUCH_TARGET][cl.fadiaz.dictionary.presentation.TOUCH_TARGET], a touch area the Wear OS
     * guidance fixes at 48 dp, not a text measurement-- so all a smaller number buys is text
     * that is harder to read inside a row that does not shrink.
     */
    SMALL(0.85f),
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
