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
import cl.fadiaz.dictionary.data.PackHandle
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
    onDelete: (String) -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val focusRequester = remember { FocusRequester() }
    var pendingDelete by remember { mutableStateOf<PackHandle.Open?>(null) }

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
                    // Tipo, tamaño e idioma. El tipo llegó con D-125: el nombre se acortó
                    // --"Español"-- y lo que decía la otra mitad ahora sale de `kind`.
                    //
                    // ⚠️ **Un pack incluido dice que lo es, en lugar del tamaño.** Ya no se podía
                    // borrar —volvería sola al reiniciar— pero la fila no lo decía, y un botón
                    // que falta sin explicación se lee como un bug. El tamaño es justo el dato
                    // que sobra ahí: sólo sirve para decidir si conviene borrarlo.
                    // ⚠️ **Sin la clave de idioma, y tipo y tamaño en UNA línea.** Pedido:
                    // *«no deben aparecer las claves de idioma ES, EN, etc. Y quiero que el tipo
                    // y tamaño estén en solo una línea»*. El idioma sobraba: el **nombre** del
                    // pack ya lo dice --«Español», «Español ↔ English»-- así que la sigla
                    // repetía en abreviado lo que la línea de arriba dice entero, y era la
                    // tercera cosa que competía por un ancho que ya se cortaba.
                    detail = if (pack.isBundled) {
                        "${packTypeLabel(pack.metadata.kind)} · " +
                            stringResource(R.string.packs_bundled)
                    } else {
                        "${packTypeLabel(pack.metadata.kind)} · ${asHumanSize(pack.bytes)}"
                    },
                    // El incluido no se puede borrar: volvería sola al reiniciar.
                    onDelete = if (pack.isBundled) null else { { pendingDelete = pack } },
                )
            }

            item(key = "cabecera-descargar") { ListHeader { Text(stringResource(R.string.packs_to_download)) } }
            item(key = "wip") {
                Text(
                    // It says what is missing and what it will do. A bare "coming soon" helps
                    // nobody; this also explains why dictionaries arrive over a cable today.
                    text = stringResource(R.string.packs_wip),
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
        title = { Text(stringResource(R.string.packs_delete_question, candidate?.metadata?.name.orEmpty())) },
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
                    candidate?.let { onDelete(it.packId) }
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
                .heightIn(min = TOUCH_TARGET)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ⚠️ **Ni tick ni activacion, y eso CAMBIA lo que esta pantalla hace.** Antes la
            // fila elegia el diccionario en uso y reservaba 20 dp a la izquierda para el tick,
            // que se dibujara o no. Pedido: *«quitando completamente el ticket de idioma
            // seleccionado y dejando que esto se haga solo desde la pantalla de inicio»*.
            //
            // Elegir idioma vive en el selector del inicio (D-111), asi que tenerlo tambien aca
            // era una segunda puerta a lo mismo, dos niveles mas adentro. Lo que queda es
            // gestion: que hay instalado, cuanto ocupa y como borrarlo.
            //
            // Lo que compra: **26 dp de ancho** --los 20 del tick y los 6 de su separacion--
            // para la linea que se estaba cortando.
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // ⚠️ **UNA línea, y ahora sí entra.** Llevaba dos porque se veía
                    // `definiciones · 315,9` con el `MB · EN` cortado; quitar la sigla de idioma
                    // liberó lo que faltaba, y el pedido es explícito: *«que el tipo y tamaño
                    // estén en solo una línea (la segunda línea)»*.
                    maxLines = 1,
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
