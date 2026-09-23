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
     * Los `.db` que están en el disco y **no se cargan**.
     *
     * ⚠️ **Es la única pantalla que los muestra, y ése es el punto.** Antes iban a la de
     * atribución —la de los créditos— donde acreditaban contenido que nadie estaba leyendo.
     * Acá hay algo que hacer con ellos: ocupan lugar y se pueden borrar.
     */
    rejected: List<PackHandle.Incompatible> = emptyList(),
    onDelete: (String) -> Unit,
    /**
     * El catalogo de descarga. **[CatalogState.Idle] mientras nadie apriete el boton.**
     *
     * ⚠️ Entrar a esta pantalla **no** consulta nada. Fue el pedido explicito, y coincide con
     * D-029: la guia oficial de Wear OS pone el acceso a red por encima de encender la pantalla.
     */
    catalog: CatalogState = CatalogState.Idle,
    onCheckCatalog: () -> Unit = {},
    /** Lo que WorkManager dice de cada descarga, por `packId`. */
    downloads: Map<String, PackDownload> = emptyMap(),
    onDownload: (CatalogPack) -> Unit = {},
    /**
     * Parar una descarga en curso y **liberar lo que ya bajo**.
     *
     * Recibe el [CatalogPack] entero y no solo el `packId` porque cancelar tiene que poder
     * encontrar el `.gz.part`, y ese nombre sale de la url que publico el catalogo.
     */
    onCancel: (CatalogPack) -> Unit = {},
) {
    val listState = rememberTransformingLazyColumnState()
    val focusRequester = remember { FocusRequester() }
    // ⚠️ **Un candidato y no un `PackHandle`, porque ahora hay DOS clases de fila que se
    // borran** y sólo una tiene `metadata`. Un `PackHandle` obligaría al diálogo a preguntar de
    // qué clase es para saber cómo llamarlo, que es justo lo que `PackHandle.Incompatible` no
    // puede contestar: su nombre bonito vive dentro del pack y leerlo sería cargarlo.
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
                    onDelete = if (pack.isBundled) {
                        null
                    } else {
                        { pendingDelete = Borrable(pack.packId, pack.metadata.name, pack.bytes) }
                    },
                )
            }

            // Los incompatibles van DESPUÉS de los que sirven y antes del catálogo: es el orden
            // en que importan. Sin cabecera propia: la fila ya dice que es incompatible, y una
            // cabecera para lo que en el caso normal son cero filas es una línea desperdiciada
            // en una pantalla que se mide en dp.
            items(count = rejected.size, key = { "roto:${rejected[it].fileName}" }) { index ->
                val pack = rejected[index]
                PackRow(
                    // ⚠️ **El NOMBRE DEL ARCHIVO, y no hay alternativa**: el nombre bonito vive
                    // dentro del pack, y leerlo sería cargarlo —justo lo que no se puede hacer—.
                    // Es además lo que el usuario necesita para saber cuál de los suyos es.
                    name = pack.fileName,
                    // ⚠️ **El motivo SOLO, sin el tamaño, y eso se decidió mirando el reloj.**
                    // Con el prefijo `4,4 MB · ` el motivo se cortaba a la tercera palabra
                    // —`4,4 MB · Another f…`— y el motivo es justamente el dato por el que esta
                    // fila existe: sin él, un diccionario que no carga se lee como un bug.
                    //
                    // El tamaño no se pierde: el diálogo de borrado lo muestra, que es el
                    // momento en que sirve —cuánto espacio se recupera—. En la fila sólo
                    // competía por un ancho que ya no alcanzaba.
                    detail = stringResource(packRejectionLabelRes(pack.rejection)),
                    incompatible = true,
                    // Se puede borrar: es la única acción posible sobre un pack que no carga.
                    onDelete = {
                        pendingDelete = Borrable(pack.fileName, pack.fileName, pack.bytes)
                    },
                )
            }

            item(key = "cabecera-descargar") { ListHeader { Text(stringResource(R.string.packs_to_download)) } }

            // El boton. `Pill` con `onClick = null` es "presente pero no pulsable", que es
            // exactamente el estado "consultando": la pildora se queda donde esta --la lista no
            // salta-- y un segundo toque no dispara una segunda consulta.
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
                // Se mantiene la explicacion del cable: un "proximamente" a secas no ayuda a
                // nadie, y hasta que el usuario pregunte esto es lo unico que hay que decir.
                CatalogState.Idle, CatalogState.Checking -> item(key = "wip") { Aviso(stringResource(R.string.packs_wip)) }

                is CatalogState.Failed -> item(key = "fallo") {
                    Aviso(stringResource(R.string.packs_catalog_failed, catalog.reason))
                }

                is CatalogState.Ready -> {
                    val porEstado = catalog.offers.groupBy { it.status }
                    val hayAlgo = CATEGORIAS.any { !porEstado[it.first].isNullOrEmpty() }
                    // ⚠️ **Se dice una vez, no pack por pack.** Los packs que esta version no abre
                    // ni siquiera estan en la lista; lo unico util que se puede decir del hueco es
                    // que hay una app mas nueva, y eso es una frase, no una seccion.
                    if (catalog.needsAppUpdate) {
                        item(key = "app-vieja") {
                            Aviso(stringResource(R.string.packs_catalog_app_outdated))
                        }
                    }
                    if (!hayAlgo) {
                        item(key = "nada") { Aviso(stringResource(R.string.packs_catalog_nothing)) }
                    } else {
                        // ⚠️ Las categorias en un orden FIJO y no el del mapa: actualizar antes
                        // que descargar antes que incompatible. Lo que ya se tiene y se puede
                        // mejorar es lo que el usuario vino a buscar.
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
                                    // ⚠️ **El mismo boton que borra, cancelando.** No es pereza:
                                    // son la misma accion para el usuario --*«sacame esto»*-- y
                                    // en 234 dp la fila no tiene lugar para un tercer control.
                                    // Lo que cambia es la confirmacion: borrar un pack pide
                                    // dialogo porque cuesta ~90 s reponerlo (D-104); cancelar una
                                    // descarga que todavia no termino no destruye nada que el
                                    // reloj no pueda volver a pedir, asi que va directo.
                                    onDelete = bajando
                                        ?.takeIf { it.phase != DownloadPhase.DONE }
                                        ?.let { { onCancel(oferta.pack) } },
                                    // Mientras baja no se puede tocar, para no reencolar sobre
                                    // si misma. Lo incompatible ya no llega hasta aqui: se filtra
                                    // en `Catalog.classify` y no se lista.
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
     * Si esta fila es un diccionario que **no se carga**.
     *
     * Lo único que cambia es el color de la segunda línea, que pasa a `error`. ⚠️ **Y el color
     * es lo único que cambia a propósito**: la fila conserva su forma, su altura y su botón de
     * borrar, porque lo que el usuario tiene que poder hacer es exactamente lo mismo. Un
     * tratamiento visual aparte —un ícono, un borde— costaría ancho en la línea que ya se corta
     * y no agregaría nada que el texto no diga.
     *
     * ⚠️ **El color no es la única señal**, y eso importa en un reloj que se mira al sol: el
     * motivo va escrito en la misma línea. Quien no distinga el rojo lee igual por qué.
     */
    incompatible: Boolean = false,
    /**
     * Que hace tocar la fila, o `null` si no hace nada.
     *
     * Lo usa el catalogo: una oferta se descarga tocandola. Las filas de lo instalado siguen sin
     * ser pulsables, porque elegir el diccionario en uso vive en el selector del inicio (D-111) y
     * tenerlo tambien aca seria una segunda puerta a lo mismo.
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
                    color = if (incompatible) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    // ⚠️ **DOS líneas cuando el motivo es lo que hay que leer.** El resto de
                    // las filas van a una —tipo y tamaño entran de sobra— pero el motivo de un
                    // rechazo no: a ~140 dp se cortaba en `Another format ve…`, visto en el
                    // emulador. La fila crece ~14 dp en un caso que normalmente son cero filas,
                    // y a cambio la única información que esa fila tiene se lee entera.
                    maxLines = if (incompatible) 2 else 1,
                    // ⚠️ **UNA línea, y ahora sí entra.** Llevaba dos porque se veía
                    // `definiciones · 315,9` con el `MB · EN` cortado; quitar la sigla de idioma
                    // liberó lo que faltaba, y el pedido es explícito: *«que el tipo y tamaño
                    // estén en solo una línea (la segunda línea)»*.
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
 * Las categorias del catalogo, **en orden fijo**.
 *
 * Y fijo a proposito: lo que ya se tiene y se puede mejorar es lo que el usuario vino a buscar,
 * asi que va primero. Tomar el orden de `groupBy` dejaria que el orden de la pantalla dependa del
 * orden del JSON del servidor, que nadie controla.
 *
 * ⚠️ `INSTALLED` **no esta**: un pack al dia no es una oferta. Aparece arriba, en la lista de lo
 * que hay en el reloj, que es donde el usuario lo busca.
 */
private val CATEGORIAS = listOf(
    CatalogStatus.UPDATE to R.string.packs_catalog_update,
    CatalogStatus.DOWNLOAD to R.string.packs_catalog_download,
)

/**
 * Un texto explicativo centrado, del ancho de la pantalla.
 *
 * Existe porque el mismo bloque se repetia cuatro veces --el aviso del cable, el fallo, el "nada
 * nuevo" y la nota de instalar-- y cuatro copias del mismo `Text` con los mismos cinco parametros
 * es como dejan de parecerse entre si.
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
 * La segunda linea de una fila del catalogo: **el tamano, y de cuando es**.
 *
 * ⚠️ Se muestra [CatalogOffer.pack] `bytes`, que es el `.gz` que viaja, y no `dbBytes`: lo que el
 * usuario decide aca es si quiere pagar esa descarga. El tamano en disco importa despues, y la
 * fila de arriba --la de los instalados-- ya lo dice.
 *
 * ⚠️ **Antes se mostraban las DOS `data_version` en crudo y fue un error, visto en el emulador.**
 * La fila decia `3,0 MB · v202609211912, you have v202609211911`: dos numeros de doce digitos que
 * difieren en el ultimo, ocupando 390 px de los ~459 utiles a esa altura. Con eso no se decide
 * nada. **Lo que informa es la fecha**, y que hay algo mas nuevo ya lo dice la cabecera de la
 * seccion. Si el numero no es una fecha --un pack ajeno puede poner lo que quiera-- la fila se
 * queda con el tamano, que es el dato que nunca falta.
 */
@Composable
private fun detalleDeOferta(oferta: CatalogOffer, bajando: PackDownload?): String {
    val tamano = asHumanSize(oferta.pack.bytes)
    // Mientras baja, el estado de la descarga REEMPLAZA a la fecha: lo que el usuario quiere
    // saber en ese momento es si esta pasando algo, no de cuando es el pack.
    when (bajando?.phase) {
        DownloadPhase.WAITING -> return stringResource(R.string.packs_dl_waiting)
        DownloadPhase.RUNNING -> return stringResource(
            R.string.packs_dl_running,
            asHumanSize(bajando.done),
            asHumanSize(if (bajando.total > 0) bajando.total else oferta.pack.bytes),
        )
        DownloadPhase.DONE -> return stringResource(R.string.packs_dl_done)
        DownloadPhase.FAILED -> return stringResource(R.string.packs_dl_failed)
        // ⚠️ **Cancelada se trata como si no hubiera descarga**, a proposito: la fila vuelve a
        // ser una oferta con su tamano y su fecha. Decir "cancelada" dejaria un estado muerto en
        // pantalla que no se puede quitar, y lo que el usuario quiere despues de cancelar es
        // poder volver a pedirlo.
        DownloadPhase.CANCELLED -> Unit
        null -> Unit
    }
    val fecha = Catalog.dataVersionDate(oferta.pack.dataVersion)
        ?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    val base = if (fecha != null) "$tamano · $fecha" else tamano
    // ⚠️ Se dice que la fila se toca. Una fila pulsable que no lo parece es una funcion que nadie
    // encuentra, y en un reloj no hay hover ni cursor que lo insinue.
    return stringResource(R.string.packs_dl_tap, base)
}

/**
 * Lo que el diálogo de borrado necesita saber, para las dos clases de fila que se pueden borrar.
 *
 * [id] es lo que se le pasa a `onDelete`: el `packId` de un diccionario abierto y el nombre del
 * archivo de uno incompatible. Los dos resuelven al mismo archivo del otro lado, y quién resuelve
 * es el ViewModel, que es el único que tiene las dos listas.
 */
private data class Borrable(val id: String, val label: String, val bytes: Long)
