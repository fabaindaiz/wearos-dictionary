package cl.fadiaz.dictionary.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import cl.fadiaz.dictionary.data.PackHandle
import cl.fadiaz.dictionary.data.asHumanSize
import cl.fadiaz.dictionary.data.packTypeLabel

/**
 * Dictionary management: which ones are there, which one is in use, how much they take and how to
 * get rid of them.
 *
 * It exists because the home selector answers "which one am I searching in" and nothing else. It
 * does not say **how much each one takes** --the only figure that matters when room has to be
 * made-- and it does not let you remove any.
 *
 * The demo pack shows up but **with no delete button**: it comes inside the APK and is
 * re-extracted when the app reopens, so the button would do nothing and the pack would come back
 * on its own.
 */
@Composable
fun PacksScreen(
    packs: List<PackHandle>,
    active: String?,
    onActivate: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val focusRequester = remember { FocusRequester() }
    var pendingDelete by remember { mutableStateOf<PackHandle.Open?>(null) }

    val installed = packs.filterIsInstance<PackHandle.Open>()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            contentPadding = withBottomMargin(contentPadding),
            state = listState,
            modifier = Modifier.rotaryScrollable(
                RotaryScrollableDefaults.behavior(listState),
                focusRequester,
            ).focusRequester(focusRequester).requestFocusOnHierarchyActive(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "cabecera-instalados") { ListHeader { Text("En el reloj") } }

            items(count = installed.size, key = { "pack:${installed[it].packId}" }) { index ->
                val pack = installed[index]
                PackRow(
                    name = pack.metadata.name,
                    // Kind, size and language. The kind arrived with D-125: the name became
                    // short --"Español"-- and what the other half said now comes from `kind`.
                    detail = "${packTypeLabel(pack.metadata.kind)} · " +
                        "${asHumanSize(pack.bytes)} · ${pack.metadata.langSource.uppercase()}",
                    active = pack.packId == active,
                    onActivate = { onActivate(pack.packId) },
                    // The demo one cannot be deleted: it would come back on its own.
                    onDelete = if (pack.isDemo) null else { { pendingDelete = pack } },
                )
            }

            item(key = "cabecera-descargar") { ListHeader { Text("Para descargar") } }
            item(key = "wip") {
                Text(
                    // It says what is missing and what it will do. A bare "coming soon" helps
                    // nobody; this also explains why dictionaries arrive over a cable today.
                    text = "Todavía no. Hoy los diccionarios se instalan por cable, desde la " +
                        "computadora. Acá va a aparecer el catálogo para bajarlos desde el reloj.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
        }
    }

    val candidate = pendingDelete
    AlertDialog(
        visible = candidate != null,
        onDismissRequest = { pendingDelete = null },
        title = { Text("¿Borrar ${candidate?.metadata?.name.orEmpty()}?") },
    ) {
        item {
            Text(
                // The cost of undoing it, before doing it. It is the only action in the app that
                // cannot be reversed from inside the app.
                text = "Ocupa ${asHumanSize(candidate?.bytes ?: 0)}. Para recuperarlo hay " +
                    "que volver a instalarlo desde la computadora.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        item {
            Pill(
                text = "Borrar",
                background = MaterialTheme.colorScheme.error,
                ink = MaterialTheme.colorScheme.onError,
                margin = 8.dp,
                onClick = {
                    candidate?.let { onDelete(it.packId) }
                    pendingDelete = null
                },
            )
        }
        item {
            Pill(
                text = "Cancelar",
                background = MaterialTheme.colorScheme.surfaceContainer,
                ink = MaterialTheme.colorScheme.onSurfaceVariant,
                margin = 8.dp,
                onClick = { pendingDelete = null },
            )
        }
    }
}

/**
 * One dictionary: whether it is the one in use, what it is called, how much it takes and --if
 * possible-- how to remove it.
 *
 * The check and the delete button are **two separate touch areas on the same row**, like the
 * entry's `ButtonGroup`: stacked they would cost twice the height, and here there is one row per
 * dictionary.
 */
@Composable
private fun PackRow(
    name: String,
    detail: String,
    active: Boolean,
    onActivate: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(CARD_SHAPE)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(onClick = onActivate)
                .heightIn(min = TOUCH_TARGET)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The check's space is always reserved: if it appeared and disappeared, the name
            // would shift when switching dictionaries.
            Box(modifier = Modifier.width(20.dp), contentAlignment = Alignment.Center) {
                if (active) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "En uso",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f).padding(start = 6.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    // TWO lines and not one. Eyeballed on the watch: after the reserved check,
                    // the paddings and the 48 dp delete button, the name has ~140 dp left, and
                    // "Español — definiciones" is 22 characters. On one line it was always cut
                    // off.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        if (onDelete != null) {
            Box(
                modifier = Modifier
                    .clip(PILL_SHAPE)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable(onClick = onDelete)
                    .heightIn(min = TOUCH_TARGET)
                    .width(TOUCH_TARGET),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Borrar $name",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
