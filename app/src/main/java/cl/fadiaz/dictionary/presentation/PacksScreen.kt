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
import androidx.compose.ui.res.stringResource
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
import cl.fadiaz.dictionary.R
import cl.fadiaz.dictionary.data.Catalog
import cl.fadiaz.dictionary.data.CatalogOffer
import cl.fadiaz.dictionary.data.CatalogState
import cl.fadiaz.dictionary.data.CatalogStatus
import cl.fadiaz.dictionary.data.CatalogPack
import cl.fadiaz.dictionary.data.DownloadPhase
import cl.fadiaz.dictionary.data.PackDownload
import cl.fadiaz.dictionary.data.PackHandle
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import cl.fadiaz.dictionary.data.asHumanSize

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
    /**
     * The `.db` files that are on disk and **do not load**.
     *
     * ⚠️ **It is the only screen that shows them, and that is the point.** They used to go to the
     * attribution one --the credits screen-- where they credited content nobody was reading. Here
     * there is something to do about them: they take space and they can be deleted.
     */
    rejected: List<PackHandle.Incompatible> = emptyList(),
    onDelete: (String) -> Unit,
    /**
     * The download catalog. **[CatalogState.Idle] until somebody presses the button.**
     *
     * ⚠️ Entering this screen queries **nothing**. That was the explicit request, and it matches
     * D-029: the official Wear OS guidance puts network access above turning the screen on.
     */
    catalog: CatalogState = CatalogState.Idle,
    onCheckCatalog: () -> Unit = {},
    /** What WorkManager says about each download, by `packId`. */
    downloads: Map<String, PackDownload> = emptyMap(),
    onDownload: (CatalogPack) -> Unit = {},
    /**
     * Stopping a download in flight and **freeing what it already fetched**.
     *
     * It takes the whole [CatalogPack] and not just the `packId` because cancelling has to be able
     * to find the `.gz.part`, and that name comes from the url the catalog published.
     */
    onCancel: (CatalogPack) -> Unit = {},
) {
    val listState = rememberTransformingLazyColumnState()
    val focusRequester = remember { FocusRequester() }
    // ⚠️ **A candidate and not a `PackHandle`, because there are now TWO classes of deletable
    // row** and only one has `metadata`. A `PackHandle` would force the dialog to ask which class
    // it is in order to know what to call it, which is exactly what `PackHandle.Incompatible`
    // cannot answer: its pretty name lives inside the pack and reading it would be loading it.
    var pendingDelete by remember { mutableStateOf<Borrable?>(null) }

    val installed = packs.filterIsInstance<PackHandle.Open>()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            contentPadding = withScreenMargins(contentPadding),
            state = listState,
            modifier = Modifier.rotaryScrollable(
                RotaryScrollableDefaults.behavior(listState),
                focusRequester,
            ).focusRequester(focusRequester).requestFocusOnHierarchyActive(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "cabecera-instalados") { ListHeader { Text(stringResource(R.string.packs_on_watch)) } }

            items(count = installed.size, key = { "pack:${installed[it].packId}" }) { index ->
                val pack = installed[index]
                PackRow(
                    name = pack.metadata.name,
                    // Type, size and language. The type arrived with D-125: the name was shortened
                    // --"Español"-- and what the other half said now comes from `kind`.
                    //
                    // ⚠️ **A bundled pack says it is one, in place of the size.** It could already
                    // not be deleted --it would come back on restart-- but the row did not say so,
                    // and a button missing with no explanation reads as a bug. The size is exactly
                    // the datum that is redundant there: it only serves to decide whether deleting
                    // is worth it.
                    // ⚠️ **Without the language code, and type and size on ONE line.** Asked for:
                    // *"the language codes ES, EN, etc. should not appear. And I want the type and
                    // size on a single line"*. The language was redundant: the pack's **name**
                    // already says it --"Español", "Español ↔ English"-- so the code repeated in
                    // abbreviation what the line above says in full, and it was the third thing
                    // competing for a width that was already being clipped.
                    detail = if (pack.isBundled) {
                        "${packTypeLabel(pack.metadata.kind)} · " +
                            stringResource(R.string.packs_bundled)
                    } else {
                        "${packTypeLabel(pack.metadata.kind)} · ${asHumanSize(pack.bytes)}"
                    },
                    // The bundled one cannot be deleted: it would come back on restart.
                    onDelete = if (pack.isBundled) {
                        null
                    } else {
                        { pendingDelete = Borrable(pack.packId, pack.metadata.name, pack.bytes) }
                    },
                )
            }

            // The incompatible ones go AFTER the usable ones and before the catalog: that is the
            // order in which they matter. With no header of their own: the row already says it is
            // incompatible, and a header for what in the normal case is zero rows is a line wasted
            // on a screen measured in dp.
            items(count = rejected.size, key = { "roto:${rejected[it].fileName}" }) { index ->
                val pack = rejected[index]
                PackRow(
                    // ⚠️ **The FILE NAME, and there is no alternative**: the pretty name lives
                    // inside the pack, and reading it would be loading it --precisely what cannot
                    // be done--. It is also what the user needs in order to know which of theirs
                    // it is.
                    name = pack.fileName,
                    // ⚠️ **The reason ALONE, without the size, and that was decided by looking at
                    // the watch.** With the `4,4 MB · ` prefix the reason was clipped at the third
                    // word --`4,4 MB · Another f…`-- and the reason is precisely the datum this
                    // row exists for: without it, a dictionary that does not load reads as a bug.
                    //
                    // The size is not lost: the delete dialog shows it, which is the moment it is
                    // useful --how much space comes back--. In the row it only competed for a
                    // width that was already insufficient.
                    detail = stringResource(packRejectionLabelRes(pack.rejection)),
                    incompatible = true,
                    // It can be deleted: the only possible action on a pack that does not load.
                    onDelete = {
                        pendingDelete = Borrable(pack.fileName, pack.fileName, pack.bytes)
                    },
                )
            }

            item(key = "cabecera-descargar") { ListHeader { Text(stringResource(R.string.packs_to_download)) } }

            // The button. `Pill` with `onClick = null` is "present but not tappable", which is
            // exactly the "querying" state: the pill stays where it is --the list does not jump--
            // and a second tap does not fire a second query.
            item(key = "consultar") {
                val consultando = catalog is CatalogState.Checking
                Pill(
                    text = when {
                        consultando -> stringResource(R.string.packs_catalog_checking)
                        catalog is CatalogState.Idle -> stringResource(R.string.packs_catalog_check)
                        else -> stringResource(R.string.packs_catalog_recheck)
                    },
                    onClick = if (consultando) null else onCheckCatalog,
                )
            }

            when (catalog) {
                // The cable explanation is kept: a bare "coming soon" helps nobody, and until the
                // user asks, this is the only thing there is to say.
                CatalogState.Idle, CatalogState.Checking -> item(key = "wip") { Aviso(stringResource(R.string.packs_wip)) }

                is CatalogState.Failed -> item(key = "fallo") {
                    Aviso(stringResource(R.string.packs_catalog_failed, catalog.reason))
                }

                is CatalogState.Ready -> {
                    val porEstado = catalog.offers.groupBy { it.status }
                    val hayAlgo = CATEGORIAS.any { !porEstado[it.first].isNullOrEmpty() }
                    // ⚠️ **It is said once, not pack by pack.** The packs this version does not
                    // open are not even in the list; the only useful thing to say about the gap is
                    // that there is a newer app, and that is a sentence, not a section.
                    if (catalog.needsAppUpdate) {
                        item(key = "app-vieja") {
                            Aviso(stringResource(R.string.packs_catalog_app_outdated))
                        }
                    }
                    if (!hayAlgo) {
                        item(key = "nada") { Aviso(stringResource(R.string.packs_catalog_nothing)) }
                    } else {
                        // ⚠️ The categories in a FIXED order and not the map's: update before
                        // download before incompatible. What you already have and can improve is
                        // what the user came for.
                        for ((estado, titulo) in CATEGORIAS) {
                            val ofertas = porEstado[estado].orEmpty()
                            if (ofertas.isEmpty()) continue
                            item(key = "cabecera-$estado") { ListHeader { Text(stringResource(titulo)) } }
                            items(count = ofertas.size, key = { "oferta:$estado:${ofertas[it].pack.packId}" }) { i ->
                                val oferta = ofertas[i]
                                val bajando = downloads[oferta.pack.packId]
                                PackRow(
                                    name = oferta.pack.name,
                                    detail = detalleDeOferta(oferta, bajando),
                                    // ⚠️ **The same button that deletes, cancelling.** It is not
                                    // laziness: to the user they are the same action --*"get this
                                    // off me"*-- and at 234 dp the row has no room for a third
                                    // control. What changes is the confirmation: deleting a pack
                                    // asks for a dialog because it costs ~90 s to get it back
                                    // (D-104); cancelling a download that has not finished
                                    // destroys nothing the watch cannot ask for again, so it goes
                                    // straight through.
                                    onDelete = bajando
                                        ?.takeIf { it.phase != DownloadPhase.DONE }
                                        ?.let { { onCancel(oferta.pack) } },
                                    // While it downloads it cannot be tapped, so as not to requeue
                                    // over itself. The incompatible no longer reaches here: it is
                                    // filtered in `Catalog.classify` and never listed.
                                    onClick = if (bajando != null) null else { { onDownload(oferta.pack) } },
                                )
                            }
                        }
                        item(key = "nota-instalar") {
                            Aviso(stringResource(R.string.packs_catalog_install_note))
                        }
                    }
                }
            }
        }
    }

    val candidate = pendingDelete
    AlertDialog(
        visible = candidate != null,
        onDismissRequest = { pendingDelete = null },
        title = { Text(stringResource(R.string.packs_delete_question, candidate?.label.orEmpty())) },
    ) {
        item {
            Text(
                // The cost of undoing it, before doing it. It is the only action in the app that
                // cannot be reversed from inside the app.
                text = stringResource(R.string.packs_delete_cost, asHumanSize(candidate?.bytes ?: 0)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        item {
            Pill(
                text = stringResource(R.string.packs_delete),
                background = MaterialTheme.colorScheme.error,
                ink = MaterialTheme.colorScheme.onError,
                margin = 8.dp,
                onClick = {
                    candidate?.let { onDelete(it.id) }
                    pendingDelete = null
                },
            )
        }
        item {
            Pill(
                text = stringResource(R.string.packs_cancel),
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
    onDelete: (() -> Unit)?,
    /**
     * Whether this row is a dictionary that **does not load**.
     *
     * The only thing that changes is the second line's colour, which becomes `error`. ⚠️ **And the
     * colour is the only thing that changes, on purpose**: the row keeps its shape, its height and
     * its delete button, because what the user has to be able to do is exactly the same. A
     * separate visual treatment --an icon, a border-- would cost width on the line that is already
     * clipped and would add nothing the text does not say.
     *
     * ⚠️ **The colour is not the only signal**, and that matters on a watch read in sunlight: the
     * reason is written on the same line. Somebody who cannot tell the red apart still reads why.
     */
    incompatible: Boolean = false,
    /**
     * What tapping the row does, or `null` if it does nothing.
     *
     * The catalog uses it: an offer is downloaded by tapping it. The installed rows remain
     * non-tappable, because choosing the dictionary in use lives in the home's selector (D-111)
     * and having it here too would be a second door to the same thing.
     */
    onClick: (() -> Unit)? = null,
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
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .heightIn(min = TOUCH_TARGET)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ⚠️ **No check and no activation, and that CHANGES what this screen does.** The row
            // used to choose the dictionary in use and reserved 20 dp on the left for the check,
            // drawn or not. Asked for: *"removing the selected-language check entirely and letting
            // that be done only from the home screen"*.
            //
            // Choosing a language lives in the home's selector (D-111), so having it here too was
            // a second door to the same thing, two levels deeper in. What is left is management:
            // what is installed, how much it takes and how to delete it.
            //
            // What it buys: **26 dp of width** --the check's 20 and its 6 of separation-- for the
            // line that was being clipped.
            Column(
                modifier = Modifier.weight(1f).padding(top = 6.dp, bottom = 6.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    // TWO lines and not one. Eyeballed on the watch: after the paddings and the
                    // 48 dp delete button, the name has ~165 dp left, and "Español —
                    // definiciones" is 22 characters. On one line it was always cut off.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (incompatible) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    // ⚠️ **TWO lines when the reason is what has to be read.** The other rows use
                    // one --type and size fit easily-- but a rejection's reason does not: at ~140
                    // dp it was clipped at `Another format ve…`, seen on the emulator. The row
                    // grows ~14 dp in a case that is normally zero rows, and in exchange the only
                    // information that row holds is read in full.
                    maxLines = if (incompatible) 2 else 1,
                    // ⚠️ **ONE line, and now it does fit.** It took two because it read
                    // `definiciones · 315,9` with the `MB · EN` clipped; removing the language code
                    // freed what was missing, and the request is explicit: *"that the type and
                    // size be on a single line (the second line)"*.
                    overflow = TextOverflow.Ellipsis,
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
                    contentDescription = stringResource(R.string.packs_delete_named, name),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The catalog's categories, **in a fixed order**.
 *
 * And fixed on purpose: what you already have and can improve is what the user came for, so it
 * goes first. Taking `groupBy`'s order would let the screen's order depend on the order of the
 * server's JSON, which nobody controls.
 *
 * ⚠️ `INSTALLED` **is not here**: an up-to-date pack is not an offer. It appears above, in the
 * list of what is on the watch, which is where the user looks for it.
 */
private val CATEGORIAS = listOf(
    CatalogStatus.UPDATE to R.string.packs_catalog_update,
    CatalogStatus.DOWNLOAD to R.string.packs_catalog_download,
)

/**
 * A centred explanatory text, the width of the screen.
 *
 * It exists because the same block was repeated four times --the cable notice, the failure, the
 * "nothing new" and the install note-- and four copies of the same `Text` with the same five
 * parameters is how they stop resembling each other.
 */
@Composable
private fun Aviso(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

/**
 * The second line of a catalog row: **the size, and when it is from**.
 *
 * ⚠️ [CatalogOffer.pack]'s `bytes` is shown, which is the `.gz` that travels, and not `dbBytes`:
 * what the user decides here is whether to pay for that download. The size on disk matters
 * afterwards, and the row above --the installed one-- already says it.
 *
 * ⚠️ **It used to show BOTH `data_version` values raw and that was a mistake, seen on the
 * emulator.** The row read `3,0 MB · v202609211912, you have v202609211911`: two twelve-digit
 * numbers differing in the last one, taking 390 px of the ~459 usable at that height. Nothing gets
 * decided with that. **What informs is the date**, and that there is something newer is already
 * said by the section's header. If the number is not a date --somebody else's pack can put
 * whatever it likes-- the row keeps just the size, which is the datum that is never missing.
 */
@Composable
private fun detalleDeOferta(oferta: CatalogOffer, bajando: PackDownload?): String {
    val tamano = asHumanSize(oferta.pack.bytes)
    // While it downloads, the download's state REPLACES the date: what the user wants to know at
    // that moment is whether something is happening, not when the pack is from.
    when (bajando?.phase) {
        DownloadPhase.WAITING -> return stringResource(R.string.packs_dl_waiting)
        DownloadPhase.RUNNING -> return stringResource(
            R.string.packs_dl_running,
            asHumanSize(bajando.done),
            asHumanSize(if (bajando.total > 0) bajando.total else oferta.pack.bytes),
        )
        DownloadPhase.DONE -> return stringResource(R.string.packs_dl_done)
        DownloadPhase.FAILED -> return stringResource(R.string.packs_dl_failed)
        // ⚠️ **Cancelled is treated as if there were no download**, on purpose: the row goes back
        // to being an offer with its size and its date. Saying "cancelled" would leave a dead
        // state on screen that cannot be dismissed, and what the user wants after cancelling is to
        // be able to ask again.
        DownloadPhase.CANCELLED -> Unit
        null -> Unit
    }
    val fecha = Catalog.dataVersionDate(oferta.pack.dataVersion)
        ?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    val base = if (fecha != null) "$tamano · $fecha" else tamano
    // ⚠️ It says the row is tappable. A tappable row that does not look it is a feature nobody
    // finds, and on a watch there is no hover and no cursor to hint at it.
    return stringResource(R.string.packs_dl_tap, base)
}

/**
 * What the delete dialog needs to know, for the two classes of deletable row.
 *
 * [id] is what gets passed to `onDelete`: an open dictionary's `packId` and an incompatible one's
 * file name. Both resolve to the same file on the other side, and the one who resolves is the
 * ViewModel, which is the only thing holding both lists.
 */
private data class Borrable(val id: String, val label: String, val bytes: Long)
