package cl.fadiaz.dictionary.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import cl.fadiaz.dictionary.core.PackKind
import cl.fadiaz.dictionary.R
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/**
 * The pieces the screens share.
 *
 * It exists because the same mould --clip to a pill, paint the background, make it tappable and
 * never go below 48 dp-- was copied into six places across two files. Six copies of a rule are
 * six places where somebody lowers the touch height in one of them and nobody finds out.
 *
 * The six are NOT unified. Three are full-width text pills and come from [Pill]; the other three
 * are genuinely different --two chips sized to their content and a row with an icon-- and forcing
 * them into the same composable would ask for half a dozen optional parameters, which is worse
 * than the duplication it came to fix. What the six do share is [PILL_SHAPE] and [TOUCH_TARGET],
 * which is where the real risk was.
 */

/**
 * The minimum touch target the Wear OS guidance asks for.
 *
 * It lives in a named constant and not as a loose `48.dp` for a concrete reason: if somebody
 * lowers it to squeeze in one more row, the density test **still passes** and what breaks is the
 * touch area, which no test can see (D-073).
 */
internal val TOUCH_TARGET: Dp = 48.dp

/**
 * The pill. The clip and the border have to be the SAME shape: if they drift apart, the border is
 * drawn straight over the rounded corners.
 */
internal val PILL_SHAPE = RoundedCornerShape(percent = 50)

/**
 * For anything taller than one line.
 *
 * The 50 % pill clips the corners with a radius of half the height: with two lines that eats the
 * beginning and the end of the text. A fixed radius does not grow with the height.
 */
internal val CARD_SHAPE = RoundedCornerShape(24.dp)

/**
 * How far you can scroll **past** the last item.
 *
 * Without it the last line sits flush against the edge, and on a ROUND screen the bottom edge
 * curves inward: the text looks cut off and there is no way to scroll further. It happened on all
 * six screens because all six passed the `ScreenScaffold`'s `contentPadding` straight through.
 */
private val BOTTOM_MARGIN: Dp = 32.dp

/**
 * How far the home pushes its first item down, to clear the clock.
 *
 * `ScreenScaffold` draws `TimeText` as an overlay and its `contentPadding` does not reserve all
 * of it. On the five screens that open on a title that does not matter; on the home the first
 * item is the **search bar** (D-111), and a text field with the clock on top reads as broken.
 *
 * ⚠️ **It is a spacer ITEM and not `contentPadding`, and the difference is visible.** As padding
 * the bar still started inside the `TransformingLazyColumn`'s edge transform, which scales and
 * clips whatever is closest to the rim: the field moved down but **its rounded shape came out
 * cut**. An item of its own is laid out like any other and keeps its shape.
 */
internal val CLOCK_GAP: Dp = 20.dp

/**
 * The scaffold's `contentPadding`, with room to breathe at the end.
 *
 * A function and not 32.dp repeated across six files: the day the number changes --or somebody
 * measures how much the curve really eats-- there is a single place to touch.
 */
@Composable
internal fun withScreenMargins(base: PaddingValues): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = base.calculateStartPadding(direction),
        top = base.calculateTopPadding(),
        end = base.calculateEndPadding(direction),
        bottom = base.calculateBottomPadding() + BOTTOM_MARGIN,
    )
}

/**
 * A full-width text pill, tappable or not.
 *
 * A null `onClick` means **present but not tappable**, which is not the same as absent: it is the
 * "searching the definitions…" state, where the pill stays on screen so the list does not jump,
 * but tapping it again cannot fire a second query.
 */
@Composable
internal fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.primaryContainer,
    ink: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    style: TextStyle = MaterialTheme.typography.labelMedium,
    margin: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
) {
    Text(
        text = text,
        style = style,
        color = ink,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = margin)
            .clip(PILL_SHAPE)
            .background(background)
            .let { if (onClick == null) it else it.clickable(onClick = onClick) }
            .heightIn(min = TOUCH_TARGET)
            .padding(vertical = 14.dp),
    )
}

/**
 * The mould of a tappable row: 48 dp, one line, the headword leads and the detail follows.
 *
 * It deliberately does not use Wear Compose's `Button`: its minimum height is 52 dp and with the
 * header four rows did not fit. 48 dp is the minimum the Wear OS guidance asks for a touch area,
 * and going below that would buy density by breaking something worse.
 */
@Composable
internal fun ListRow(headword: String, detail: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PILL_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .heightIn(min = TOUCH_TARGET)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = headword,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** Opening the pack, or extracting it for the first time. */
@Composable
internal fun LoadingMessage(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator()
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Que clase de diccionario es, como **id de recurso**.
 *
 * Partido en dos a proposito. El mapeo es puro, asi que un test de la JVM puede exigir que
 * **haya uno por cada `PackKind` y que sean distintos** sin levantar Android; resolver el texto
 * necesita un `Context` y vive en [packTypeLabel].
 *
 * Existe porque el nombre del pack dejo de decirlo: era "Español - definiciones" --22 caracteres,
 * cortados en los cuatro lugares donde se muestra-- y paso a ser "Español" (D-125). Lo que el
 * nombre largo comunicaba sale ahora de `kind`, que es **un dato del pack** y no una cadena que
 * alguien tiene que acordarse de escribir bien en cada pack nuevo.
 *
 * ⚠️ Estaba en `data/PackSet.kt`, que el audit vigila para que no importe `android.*` (D-072).
 * Traducirlo lo habria roto: por eso se mudo a la capa que si puede (D-127).
 */
@StringRes
internal fun packTypeLabelRes(kind: PackKind): Int = when (kind) {
    PackKind.MONOLINGUAL -> R.string.pack_kind_monolingual
    PackKind.BILINGUAL -> R.string.pack_kind_bilingual
}

/** El texto de [packTypeLabelRes], en el idioma del reloj. */
@Composable
internal fun packTypeLabel(kind: PackKind): String = stringResource(packTypeLabelRes(kind))
