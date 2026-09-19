package cl.fadiaz.dictionary.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import cl.fadiaz.dictionary.core.Entry
import cl.fadiaz.dictionary.core.GlossTokenizer
import cl.fadiaz.dictionary.core.Sense

/**
 * El cuerpo de una entrada. Es la unica pantalla que descomprime un payload: la lista se sirve
 * entera desde el covering index, sin tocar la tabla (D-012).
 *
 * POR QUE SE CORTA EN TRES ACEPCIONES
 *
 * Medido sobre las 3.000 entradas de mejor rank, que son las que mas se van a abrir: la mediana
 * es **3 acepciones**, el p90 es 7 y el maximo es **47**. Cortar en tres deja la mitad de las
 * entradas intactas --sin boton ni gesto de mas-- y evita que "justicia", con sus diez, se
 * convierta en un rollo donde la acepcion util queda debajo de nueve que nadie buscaba.
 */
private const val VISIBLE_SENSES = 3

@Composable
fun EntryScreen(
    entryId: Long,
    // Sin default: una palabra pintada como tocable que no navega a ningun lado es peor que no
    // pintarla, y no se distingue de una que funciona (mismo criterio que D-084).
    onOpenPalabra: (Long) -> Unit,
    onVolverABuscar: () -> Unit = {},
    /**
     * Las acciones del menu, construidas a partir de la entrada ya cargada.
     *
     * Funcion y no lista: "guardar" o "quitar de favoritas" depende de la palabra concreta, y el
     * lema hace falta para copiarlo. Devolver vacio = no se ofrece el menu, que es distinto de
     * ofrecer un menu vacio.
     */
    actions: (Entry) -> List<EntryAction> = { emptyList() },
    resolveIn: suspend (Set<String>) -> Map<String, Long> = { emptyMap() },
    // Va ultimo para que siga siendo el lambda final: es como lo llaman las pantallas y los tests.
    cargar: suspend (Long) -> Entry?,
) {
    var entry by remember(entryId) { mutableStateOf<Entry?>(null) }
    var failure by remember(entryId) { mutableStateOf(false) }
    var expanded by remember(entryId) { mutableStateOf(false) }
    var links by remember(entryId) { mutableStateOf(emptyMap<String, Long>()) }
    var menuOpen by remember(entryId) { mutableStateOf(false) }
    val actionsFor = entry?.let(actions).orEmpty()

    LaunchedEffect(entryId) {
        // Sin el try, cualquier cosa que tire `cargar` --una SQLiteException, un inflate sobre un
        // pack truncado-- sube por la corrutina de composicion y mata el proceso. Un pack ilegible
        // tiene que degradar al mensaje que ya existe abajo, igual que una entrada que no esta.
        entry = runCatching { cargar(entryId) }.getOrNull()
        failure = entry == null
    }

    // Que palabras de las glosas son lema del pack, en UNA consulta para toda la pantalla y no
    // una por palabra. Se pintan solo las que existen, asi el color dice de antemano que lleva a
    // algun lado. Va en su propio efecto porque depende de la entrada ya cargada.
    LaunchedEffect(entry) {
        val loaded = entry ?: return@LaunchedEffect
        val keys = loaded.senses.flatMap { GlossTokenizer.tokenize(it.gloss) }
            .map { it.norm }
            .toSet()
        links = runCatching { resolveIn(keys) }
            .getOrDefault(emptyMap())
            // Un enlace a la entrada que ya estamos mirando no lleva a ningun lado.
            .filterValues { it != entryId }
    }

    // Ancla en 1 y no en 0: el item 0 es el atajo a la busqueda, y la pantalla tiene que abrir
    // mostrando LA PALABRA. Asi el atajo esta un scroll hacia arriba y no gasta una fila de las
    // que se ven -- que es la condicion con la que entro (D-084). Medido: sin esto, "Ver mas"
    // con tres acepciones cortas ya no entraba en pantalla.
    val listState = rememberTransformingLazyColumnState(initialAnchorItemIndex = 1)
    val focusRequester = remember { FocusRequester() }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            contentPadding = withBottomMargin(contentPadding),
            state = listState,
            // La corona es el scroll principal de un reloj: el dedo tapa justamente lo que se
            // esta leyendo. No viene cableada por defecto.
            modifier = Modifier.rotaryScrollable(
                RotaryScrollableDefaults.behavior(listState),
                focusRequester,
            ).focusRequester(focusRequester).requestFocusOnHierarchyActive(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val current = entry
            // La salida rapida a la busqueda, y vive como PRIMERA FILA DEL SCROLL y no como
            // chrome fijo: arriba de todo se llega con la corona, y cuesta cero dp de pantalla
            // permanente. Un boton fijo costaria 48 dp, que es justo lo que D-084 rechazo.
            // Hace falta porque tocar palabras apila entradas: sin esto, volver al inicio desde
            // tres palabras de profundidad son tres gestos.
            item(key = "acciones") {
                EntryActionsMenu(
                    onVolverABuscar = onVolverABuscar,
                    // Null mientras la entrada no cargo: un boton de menu que abre nada es peor
                    // que un boton que todavia no esta.
                    onAbrirMenu = if (actionsFor.isEmpty()) null else { { menuOpen = true } },
                )
            }
            item(key = "encabezado") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Text(
                        // El lema NO se trunca aca, al reves que en la lista: esta pantalla
                        // existe justamente para leer la palabra entera, refranes incluidos.
                        text = current?.headword ?: "…",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    current?.partOfSpeech?.let { pos ->
                        Text(
                            text = posInSpanish(pos),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        )
                    }
                }
            }

            if (failure) {
                item(key = "fallo") {
                    Text(
                        text = "Esa entrada ya no está en el diccionario.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
            }

            val allSenses = current?.senses.orEmpty()
            val visibleOnes = if (expanded) allSenses else allSenses.take(VISIBLE_SENSES)
            val hidden = allSenses.size - visibleOnes.size

            // Con `key` el item conserva identidad al desplegarse; sin el, el lazy layout los
            // identifica por posicion. Es la misma causa que en la busqueda se llevaba el foco
            // del campo de texto, y aca ademas deja `visibles` capturado por closure.
            items(count = visibleOnes.size, key = { index -> "s:$index" }) { index ->
                // getOrNull y no [indice]: el lambda del item y el conteo los consume el layout
                // en frames distintos. Saltar una acepcion un frame es aceptable; tirar no.
                visibleOnes.getOrNull(index)?.let { sense ->
                    SenseBlock(
                        numero = index + 1,
                        sense = sense,
                        links = links,
                        onOpenPalabra = onOpenPalabra,
                    )
                }
            }

            if (hidden > 0) {
                item(key = "ver-mas") {
                    Pill(
                        text = "Ver más ($hidden)",
                        background = MaterialTheme.colorScheme.surfaceContainer,
                        ink = MaterialTheme.colorScheme.onSurfaceVariant,
                        margin = 24.dp,
                        onClick = { expanded = true },
                    )
                }
            }
        }

    // El menu de opciones. `AlertDialog` de Wear y no uno hecho a mano: es el que conserva el
    // swipe-para-volver del sistema, que la lista de calidad exige en casi toda pantalla (WO-V3),
    // y ademas evita agregar un nivel de navegacion --la guia pide como mucho dos--.
    //
    // Wear Material3 NO trae menu desplegable ni overflow, verificado contra la referencia de
    // API: las dos formas soportadas son este dialogo o empujar una pantalla de lista.
    AlertDialog(
        visible = menuOpen && actionsFor.isNotEmpty(),
        onDismissRequest = { menuOpen = false },
        title = { Text("Opciones") },
    ) {
        items(actionsFor.size) { index ->
            val action = actionsFor[index]
            Pill(
                text = action.label,
                background = MaterialTheme.colorScheme.surfaceContainer,
                ink = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = {
                    // Cerrar primero: la accion puede navegar, y un dialogo abierto encima de
                    // la pantalla nueva queda huerfano.
                    menuOpen = false
                    action.onClick()
                },
            )
        }
    }
    }
}


/** Una accion del menu de una palabra. El estado --p.ej. si ya es favorita-- lo decide arriba. */
data class EntryAction(val label: String, val onClick: () -> Unit)

/**
 * Los dos botones de arriba: volver a buscar, y el menu.
 *
 * En UNA fila y no apilados, y la aritmetica es la razon: lado a lado cuestan 48 dp --el minimo
 * tocable-- y apilados costarian 96, que en 234 dp de pantalla es una acepcion menos. Se
 * construye con `Row` y no con `ButtonGroup` de Wear Material3 por lo mismo que el selector de
 * idioma: con `allWarningsAsErrors`, una API que se deprecie en el proximo bump rompe el build,
 * y aca no se gana nada que justifique ese riesgo.
 */
@Composable
private fun EntryActionsMenu(onVolverABuscar: () -> Unit, onAbrirMenu: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconPill(
            icono = Icons.Filled.Search,
            descripcion = "Buscar",
            onClick = onVolverABuscar,
            modifier = Modifier.weight(1f),
        )
        if (onAbrirMenu != null) {
            IconPill(
                icono = Icons.Filled.MoreVert,
                descripcion = "Opciones",
                onClick = onAbrirMenu,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun IconPill(
    icono: ImageVector,
    descripcion: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(PILL_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .heightIn(min = TOUCH_TARGET),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icono,
            // No es null como en un icono decorativo: aca el icono ES la etiqueta, asi que sin
            // esto el boton no tiene nombre para quien usa lector de pantalla.
            contentDescription = descripcion,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Una acepcion: el numero manda el orden, la glosa es el contenido y el ejemplo acompaña.
 *
 * El ejemplo va en secundario y mas chico por una razon medida: la mediana es de 64 caracteres
 * pero el maximo son **917** --cronicas del siglo XVI que el Wikcionario cita como uso-- y a
 * igual peso visual que la glosa, uno solo entierra la acepcion siguiente.
 */
@Composable
private fun SenseBlock(
    numero: Int,
    sense: Sense,
    links: Map<String, Long>,
    onOpenPalabra: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = annotatedGloss("$numero. ", sense.gloss, links, onOpenPalabra),
            style = MaterialTheme.typography.bodyMedium,
        )
        sense.examples.forEach { ejemplo ->
            Text(
                text = ejemplo,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 10.dp),
            )
        }
        // Los sinonimos van en una sola linea y despues del ejemplo: son una ayuda, no la
        // definicion. El tope de cuatro ya viene del payload (MAX_SYNONYMS_PER_SENSE), asi
        // que aca no hace falta cortar nada.
        if (sense.synonyms.isNotEmpty()) {
            Text(
                text = "sin. " + sense.synonyms.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 10.dp),
            )
        }
        // Los antonimos, debajo y con el mismo peso visual (D-126). El prefijo NO es opcional y
        // no puede parecerse a "sin.": las dos listas se ven igual y la unica diferencia entre
        // "otra forma de decirlo" y "lo contrario" son esas cuatro letras.
        if (sense.antonyms.isNotEmpty()) {
            Text(
                text = "ant. " + sense.antonyms.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 10.dp),
            )
        }
    }
}

/**
 * La glosa con sus palabras conocidas convertidas en enlaces.
 *
 * Los rangos salen de [GlossTokenizer], que decide que es una palabra delegando en `norm()` --la
 * misma funcion con la que se construyo el indice--, asi que el tramo que se pinta es exactamente
 * el que se consulto. Las palabras que no son lema quedan como texto plano: el color es la
 * promesa de que tocarlo lleva a algun lado.
 */
@Composable
private fun annotatedGloss(
    prefijo: String,
    glosa: String,
    links: Map<String, Long>,
    onOpenPalabra: (Long) -> Unit,
): AnnotatedString {
    val style = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary))
    return remember(prefijo, glosa, links, style) {
        buildAnnotatedString {
            append(prefijo)
            var cursor = 0
            for (word in GlossTokenizer.tokenize(glosa)) {
                val target = links[word.norm] ?: continue
                if (word.start > cursor) append(glosa.substring(cursor, word.start))
                withLink(
                    LinkAnnotation.Clickable("palabra:$target", style) { onOpenPalabra(target) },
                ) {
                    append(glosa.substring(word.start, word.end))
                }
                cursor = word.end
            }
            if (cursor < glosa.length) append(glosa.substring(cursor))
        }
    }
}
