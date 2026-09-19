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
 * Gestion de diccionarios: cuales estan, cual se usa, cuanto ocupan y como sacarlos.
 *
 * Existe porque el selector del inicio responde "en cual busco" y nada mas. No dice **cuanto
 * ocupa** cada uno --que es la unica cifra que importa cuando hay que hacer lugar-- ni deja
 * sacar ninguno.
 *
 * El pack de demostracion aparece pero **sin boton de borrar**: viene dentro del APK y se
 * re-extrae al reabrir la app, asi que el boton no haria nada y el pack volveria solo.
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
                    // Tipo, tamaño e idioma. El tipo entro con D-125: el nombre paso a ser
                    // corto --"Español"-- y lo que decia la otra mitad sale ahora de `kind`.
                    detail = "${packTypeLabel(pack.metadata.kind)} · " +
                        "${asHumanSize(pack.bytes)} · ${pack.metadata.langSource.uppercase()}",
                    active = pack.packId == active,
                    onActivate = { onActivate(pack.packId) },
                    // El de demostracion no se puede borrar: volveria solo.
                    onDelete = if (pack.isDemo) null else { { pendingDelete = pack } },
                )
            }

            item(key = "cabecera-descargar") { ListHeader { Text("Para descargar") } }
            item(key = "wip") {
                Text(
                    // Dice que falta y que va a hacer. Un "proximamente" a secas no le sirve a
                    // nadie; esto ademas explica por que hoy los diccionarios entran por cable.
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
                // El costo de deshacerlo, antes de hacerlo. Es la unica accion de la app que no
                // se puede revertir desde la app.
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
 * Un diccionario: si es el que se usa, como se llama, cuanto ocupa y --si se puede-- como sacarlo.
 *
 * El check y el boton de borrar son **dos areas tocables distintas en la misma fila**, como el
 * `ButtonGroup` de la entrada: apiladas costarian el doble de alto, y aca hay una fila por
 * diccionario.
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
            // El espacio del check se reserva siempre: si apareciera y desapareciera, el nombre
            // se correria al cambiar de diccionario.
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
                    // DOS lineas y no una. Medido a ojo sobre el reloj: despues del check
                    // reservado, los paddings y el boton de borrar de 48 dp, al nombre le quedan
                    // ~140 dp, y "Español — definiciones" son 22 caracteres. En una linea se
                    // cortaba siempre.
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
