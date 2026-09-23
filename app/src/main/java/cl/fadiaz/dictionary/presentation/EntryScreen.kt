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
import cl.fadiaz.dictionary.R
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
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
import cl.fadiaz.dictionary.data.TextScale
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import cl.fadiaz.dictionary.core.TextNormalizer

/**
 * The body of an entry. It is the only screen that decompresses a payload: the list is served
 * entirely from the covering index, without touching the table (D-012).
 *
 * WHY IT CUTS OFF AT THREE SENSES
 *
 * Measured over the 3,000 best-ranked entries, which are the ones most likely to be opened: the
 * median is **3 senses**, p90 is 7 and the maximum is **47**. Cutting at three leaves half the
 * entries untouched --no extra button, no extra gesture-- and keeps "justicia", with its ten,
 * from turning into a scroll where the useful sense sits below nine nobody was looking for.
 */
private const val VISIBLE_SENSES = 3

/**
 * A dónde lleva un enlace: **qué pack y qué entrada**.
 *
 * ⚠️ **El `packId` no es decorativo y lo obligó la traducción.** Antes un enlace era un `Long`
 * suelto y `onOpenWord` lo abría en el MISMO pack, deliberadamente: mandar un `entryId` a otro
 * pack abre **otra palabra, sin error** (D-080). Mientras todos los enlaces eran palabras de la
 * misma glosa eso alcanzaba. Una traducción va necesariamente a otro diccionario, así que el
 * destino tiene que decir a cuál — y el caso de siempre pasa a ser el mismo tipo con el pack
 * propio, no una excepción.
 */
data class WordLink(val packId: String, val entryId: Long)

/**
 * De dónde salió un término que se va a resolver, que es lo que decide **en qué idioma** buscarlo.
 *
 * ⚠️ **La distinción no existía y produjo un bug que el usuario reportó.** Hasta que un pack tuvo
 * los dos idiomas en un archivo, «resolver en el mismo pack» implicaba «en el mismo idioma»; al
 * volverlo bidireccional esa equivalencia se rompió en silencio. `pie` es español —parte del
 * cuerpo— **e** inglés —pastel—, así que tocar la traducción `pie` de `foot` abría el `pie`
 * inglés: una traducción que devuelve al idioma del que uno venía.
 *
 * Medido sobre el pack real: **8,30 %** de las traducciones de entradas inglesas (7.757 de
 * 93.473) resolvían al idioma equivocado.
 */
enum class TermSource {
    /** Una palabra de la glosa: está en el idioma **de la entrada**. */
    GLOSS,

    /** Un término de la lista de traducciones: está en **el otro** idioma. */
    TRANSLATION,
}

@Composable
fun EntryScreen(
    entryId: Long,
    // No default: a word painted as tappable that navigates nowhere is worse than not painting
    // it, and it is indistinguishable from one that works (same rule as D-084).
    onOpenWord: (WordLink) -> Unit,
    onBackToSearch: () -> Unit = {},
    /**
     * The menu actions, built from the already loaded entry.
     *
     * A function and not a list: "save" or "remove from saved" depends on the concrete word, and
     * the headword is needed to copy it. Returning empty = the menu is not offered, which is not
     * the same as offering an empty menu.
     */
    actions: (Entry) -> List<EntryAction> = { emptyList() },
    /**
     * El tamaño de texto actual, o `null` para no ofrecer el selector.
     *
     * ⚠️ **Vive en el menú de la ficha y no sólo en Ajustes, y el motivo es cuándo se nota.**
     * Que la letra sea chica se descubre **leyendo una definición**, no navegando ajustes: pedir
     * que el lector salga de la palabra, cruce dos pantallas y vuelva es exactamente la clase de
     * viaje que la guía de Wear OS pide evitar. Acá cuesta un toque y se ve el efecto en el
     * texto que está debajo del diálogo.
     */
    textScale: TextScale? = null,
    onTextScaleChange: (TextScale) -> Unit = {},
    /**
     * Qué términos de esta pantalla son lemas, y a qué entrada llevan.
     *
     * El segundo argumento es el idioma **de la entrada abierta** y el tercero dice si el término
     * está en ese idioma o en el otro. Ver [TermSource]: sin eso, una traducción puede resolver a
     * una palabra del idioma del que uno venía.
     */
    resolveIn: suspend (Set<String>, String?, TermSource) -> Map<String, WordLink> =
        { _, _, _ -> emptyMap() },
    // It goes last so it stays the trailing lambda: that is how the screens and tests call it.
    cargar: suspend (Long) -> Entry?,
) {
    var entry by remember(entryId) { mutableStateOf<Entry?>(null) }
    var failure by remember(entryId) { mutableStateOf(false) }
    var expanded by remember(entryId) { mutableStateOf(false) }
    var links by remember(entryId) { mutableStateOf(emptyMap<String, WordLink>()) }
    var menuOpen by remember(entryId) { mutableStateOf(false) }
    val actionsFor = entry?.let(actions).orEmpty()

    LaunchedEffect(entryId) {
        // Without the try, anything `load` throws --a SQLiteException, an inflate over a
        // truncated pack-- rises through the composition coroutine and kills the process. An
        // unreadable pack has to degrade to the message that already exists below, just like an
        // entry that is not there.
        entry = runCatching { cargar(entryId) }.getOrNull()
        failure = entry == null
    }

    // Qué palabras de la pantalla son lemas del pack, **en dos consultas para toda la ficha** y
    // no una por palabra. Sólo se pintan las que existen, así que el color promete de antemano
    // que lleva a algún lado. Vive en su propio efecto porque depende de la entrada cargada.
    //
    // ⚠️ **Dos consultas y no una, y el motivo es un tope duro**: `MAX_PALABRAS_POR_CONSULTA` es
    // 64 porque cada clave es un parámetro enlazado de SQLite. Las glosas ya promedian 39,9
    // claves en español y 58,8 en inglés, así que meter además los sinónimos, antónimos y
    // relacionadas de cada acepción desbordaría el tope y **recortaría en silencio** -- y lo
    // recortado serían justo los términos, que son los que más valen como enlace. Separadas,
    // cada una tiene su propio tope. Medido: 0,18 ms cada una en español.
    LaunchedEffect(entry) {
        val loaded = entry ?: return@LaunchedEffect
        val deLaGlosa = loaded.senses.flatMap { GlossTokenizer.tokenize(it.gloss) }
            .map { it.norm }
            .toSet()
        // ⚠️ **Sinónimos, antónimos y relacionadas van con la GLOSA, no con las traducciones.**
        // Están en el idioma de la entrada --un sinónimo de `casa` es español-- y meterlos en la
        // bolsa de traducciones los habría resuelto en el idioma equivocado, que es el mismo bug
        // al revés.
        val propios = loaded.senses
            .flatMap { it.synonyms + it.antonyms + it.related }
            .map { TextNormalizer.norm(it) }
            .filterNot { it.isEmpty() }
            .toSet()
        val deLasTraducciones = (loaded.senses.flatMap { it.translations } +
            loaded.wordTranslations)
            .map { TextNormalizer.norm(it) }
            .filterNot { it.isEmpty() }
            .toSet()
        // ⚠️ **Dos llamadas con idiomas DISTINTOS, y eso es la mitad del arreglo.** Las palabras
        // de la glosa están en el idioma de la entrada; los términos de las listas de traducción,
        // en el otro. Resolverlos todos igual es lo que mandaba `pie` al `pie` inglés.
        // ⚠️ **Tres consultas y no dos**, y el motivo es el mismo tope duro de antes:
        // `MAX_PALABRAS_POR_CONSULTA` es 64 y las glosas ya promedian 39,9 claves en español y
        // 58,8 en inglés, así que juntarlas desbordaría y **recortaría en silencio**. Cada una
        // costó 0,18 ms medidos.
        links = runCatching {
            resolveIn(deLaGlosa, loaded.lang, TermSource.GLOSS) +
                resolveIn(propios, loaded.lang, TermSource.GLOSS) +
                resolveIn(deLasTraducciones, loaded.lang, TermSource.TRANSLATION)
        }
            .getOrDefault(emptyMap())
            // Un enlace a la entrada que ya estás leyendo no lleva a ningún lado.
            .filterValues { it.entryId != entryId }
    }

    // Anchored at 1 and not 0: item 0 is the shortcut to the search, and the screen has to open
    // showing THE WORD. This way the shortcut is one scroll up and costs none of the rows that
    // are visible -- which is the condition it came in under (D-084). Measured: without this,
    // "Show more" with three short senses no longer fit on screen.
    val listState = rememberTransformingLazyColumnState(initialAnchorItemIndex = 1)
    val focusRequester = remember { FocusRequester() }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            contentPadding = withScreenMargins(contentPadding),
            state = listState,
            // The crown is a watch's primary scroll: the finger covers exactly what is being
            // read. It is not wired up by default.
            modifier = Modifier.rotaryScrollable(
                RotaryScrollableDefaults.behavior(listState),
                focusRequester,
            ).focusRequester(focusRequester).requestFocusOnHierarchyActive(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val current = entry
            // The quick way out to the search, living as the FIRST ROW OF THE SCROLL and not as
            // fixed chrome: the very top is one crown turn away, and it costs zero dp of
            // permanent screen. A fixed button would cost 48 dp, which is exactly what D-084
            // rejected. It is needed because tapping words stacks entries: without it, getting
            // back to the start from three words deep is three gestures.
            item(key = "acciones") {
                EntryActionsMenu(
                    onBackToSearch = onBackToSearch,
                    // Null while the entry has not loaded: a menu button that opens nothing is
                    // worse than a button that is not there yet.
                    onOpenMenu = if (actionsFor.isEmpty()) null else { { menuOpen = true } },
                )
            }
            item(key = "encabezado") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Text(
                        // The headword is NOT truncated here, unlike in the list: this screen
                        // exists precisely to read the whole word, sayings included.
                        text = current?.headword ?: "…",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val pos = current?.partOfSpeech
                    // ⚠️ **El idioma sale de la ENTRADA CARGADA, no de un parametro.** Se llega
                    // a esta pantalla tocando una traduccion, y entonces la entrada abierta es
                    // del OTRO idioma: tomarlo del pack --que en un bidireccional habla dos--
                    // afirmaria el idioma equivocado, que es peor que no poner nada.
                    //
                    // Es el IDIOMA y nunca la fuente (D-190): *«solo debe ser EN, ES. No me
                    // gusta que haya un ENWIK... porque solo me interesa conocer el idioma de
                    // proveniencia»*.
                    val languageTag = current?.lang?.uppercase()
                    if (pos != null || languageTag != null) {
                        Text(
                            // Entero, no abreviado: esta pantalla no compite por el ancho con
                            // nada, y es donde el tipo de palabra se lee de verdad. El idioma
                            // va detras, con el mismo separador que usa la fila de resultados.
                            text = listOfNotNull(pos?.let { posLabelFull(it) }, languageTag)
                                .joinToString(stringResource(R.string.entry_list_separator)),
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
                        text = stringResource(R.string.entry_gone),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
            }

            // ⚠️ **Las traducciones de la PALABRA van acá, fuera de `SenseBlock`, y ese lugar
            // es la mitad del diseño.** Una lista dibujada bajo una acepción **afirma** que le
            // pertenece, y lo que cae acá es justo lo que la fuente no pudo atribuir: juntarlas
            // desharía en la pantalla lo que el formato separó (D-117), y el error se leería
            // perfectamente plausible.
            //
            // ⚠️ **Van ANTES de las acepciones, y eso invierte lo que decía este comentario.**
            // Estaban después, razonando que "las definiciones son a lo que el lector entró".
            // Lo desmiente la medición que ya estaba acá al lado: el **48,6 %** de las entradas
            // con traducción tienen **sólo** éstas, así que para la mitad de los casos la
            // sección que iba al final era la respuesta entera, y quedaba debajo de un
            // `Ver más (12)` que hay que tocar para llegar. Pedido: *«que la traducción por
            // palabra en caso de estar disponible sin acepciones aparezca al inicio»*.
            //
            // Sólo el 3,0 % muestra las dos secciones a la vez, así que el costo de empujar las
            // acepciones hacia abajo lo paga una entrada de cada treinta.
            //
            // Con `prominent`: no cuelga de ninguna acepción, así que no se dibuja subordinada
            // a una.
            val wordTranslations = current?.wordTranslations.orEmpty()
            if (wordTranslations.isNotEmpty()) {
                item(key = "traducciones-palabra") {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        TermList(
                            R.string.entry_word_translations_title,
                            wordTranslations,
                            links,
                            onOpenWord,
                            prominent = true,
                        )
                    }
                }
            }

            val allSenses = current?.senses.orEmpty()
            val visibleOnes = if (expanded) allSenses else allSenses.take(VISIBLE_SENSES)
            val hidden = allSenses.size - visibleOnes.size

            // With `key` the item keeps its identity when expanding; without it, the lazy
            // layout identifies them by position. It is the same cause that stole the text
            // field's focus on the search, and here it also leaves `visibleOnes` captured by
            // closure.
            items(count = visibleOnes.size, key = { index -> "s:$index" }) { index ->
                // getOrNull and not [index]: the item lambda and the count are consumed by the
                // layout on different frames. Skipping a sense for one frame is acceptable;
                // throwing is not.
                visibleOnes.getOrNull(index)?.let { sense ->
                    SenseBlock(
                        number = index + 1,
                        sense = sense,
                        links = links,
                        onOpenWord = onOpenWord,
                    )
                }
            }

            if (hidden > 0) {
                item(key = "ver-mas") {
                    Pill(
                        text = stringResource(R.string.entry_show_more, hidden),
                        background = MaterialTheme.colorScheme.surfaceContainer,
                        ink = MaterialTheme.colorScheme.onSurfaceVariant,
                        margin = 24.dp,
                        onClick = { expanded = true },
                    )
                }
            }
        }

    // The options menu. Wear's `AlertDialog` and not a hand-rolled one: it is the one that keeps
    // the system's swipe-to-dismiss, which the quality list requires on nearly every screen
    // (WO-V3), and it also avoids adding a navigation level --the guidance asks for at most two--.
    //
    // Wear Material3 ships NO dropdown menu and no overflow, verified against the API reference:
    // the two supported shapes are this dialog or pushing a list screen.
    AlertDialog(
        visible = menuOpen && actionsFor.isNotEmpty(),
        onDismissRequest = { menuOpen = false },
        title = { Text(stringResource(R.string.entry_options)) },
    ) {
        if (textScale != null) {
            item {
                // ⚠️ **Los botones MUESTRAN el tamaño que aplican en vez de nombrarlo**, que es
                // lo que pidió el usuario: *«un selector de 3 botones con distintos tamaños de
                // letra para representar este selector»*. Una `A` chica, una mediana y una
                // grande se entienden sin leer, que en un reloj vale más que una etiqueta — y
                // además no hay que traducirlas.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextScale.entries.forEach { opcion ->
                        val elegida = opcion == textScale
                        Text(
                            text = stringResource(R.string.settings_scale_sample),
                            // El tamaño del botón ES la escala que representa, aplicada sobre el
                            // cuerpo de la ficha: lo que se ve es lo que se va a obtener.
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize * opcion.factor,
                            fontWeight = if (elegida) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            color = if (elegida) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .weight(1f)
                                .clip(PILL_SHAPE)
                                .background(
                                    if (elegida) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainer
                                    },
                                )
                                .clickable { onTextScaleChange(opcion) }
                                .heightIn(min = TOUCH_TARGET)
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }
        items(actionsFor.size) { index ->
            val action = actionsFor[index]
            Pill(
                text = stringResource(action.label),
                background = MaterialTheme.colorScheme.surfaceContainer,
                ink = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = {
                    // Close first: the action may navigate, and a dialog left open on top of the
                    // new screen is orphaned.
                    menuOpen = false
                    action.onClick()
                },
            )
        }
    }
    }
}


/** An action in a word's menu. The state --e.g. whether it is already saved-- is decided above. */
data class EntryAction(@get:StringRes val label: Int, val onClick: () -> Unit)

/**
 * The two buttons up top: back to the search, and the menu.
 *
 * In ONE row and not stacked, and the arithmetic is the reason: side by side they cost 48 dp
 * --the touch minimum-- and stacked they would cost 96, which on a 234 dp screen is one sense
 * less. Built with `Row` and not Wear Material3's `ButtonGroup` for the same reason as the
 * language selector: with `allWarningsAsErrors`, an API deprecated in the next bump breaks the
 * build, and here there is nothing gained that justifies that risk.
 */
@Composable
private fun EntryActionsMenu(onBackToSearch: () -> Unit, onOpenMenu: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconPill(
            icono = Icons.Filled.Search,
            description = stringResource(R.string.entry_back_to_search),
            onClick = onBackToSearch,
            modifier = Modifier.weight(1f),
        )
        if (onOpenMenu != null) {
            IconPill(
                icono = Icons.Filled.MoreVert,
                description = stringResource(R.string.entry_options),
                onClick = onOpenMenu,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun IconPill(
    icono: ImageVector,
    description: String,
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
            // Not null as in a decorative icon: here the icon IS the label, so without this the
            // button has no name for anyone using a screen reader.
            contentDescription = description,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One sense: the number carries the order, the gloss is the content and the example follows.
 *
 * The example is secondary and smaller for a measured reason: the median is 64 characters but the
 * maximum is **917** --sixteenth-century chronicles the Wiktionary cites as usage-- and at the
 * same visual weight as the gloss, a single one buries the next sense.
 *
 * **The citation is tertiary, and it is what makes those chronicles legible.** Measured on the
 * English dump, **86,5 %** of the examples are quotations lifted from a published text, so
 * without it the reader gets a sentence out of an 1897 novel with nothing saying so -- which is
 * exactly what sent a user to Wiktionary by hand to find out. It is trimmed to year and author
 * in the builder (average 31 bytes), and capped at two lines here because the p99 is 147 bytes
 * and the maximum 352: the pack keeps the data, the screen decides how much of it fits.
 */
@Composable
private fun SenseBlock(
    number: Int,
    sense: Sense,
    links: Map<String, WordLink>,
    onOpenWord: (WordLink) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val glosa = annotatedGloss("$number. ", sense.gloss, links)
        LinkedText(
            text = glosa.text,
            targets = glosa.targets,
            onOpenWord = onOpenWord,
            style = MaterialTheme.typography.bodyMedium,
        )
        sense.examples.forEach { ejemplo ->
            Text(
                text = ejemplo.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 10.dp),
            )
            ejemplo.citation?.let { cita ->
                Text(
                    text = stringResource(R.string.entry_example_citation, cita),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
        // Las tres listas, con su categoría arriba y las palabras abajo. La categoría iba antes
        // como prefijo --`sin.`, `ant.`, `rel.`-- y ahora va escrita entera, que es la misma
        // regla de D-159: en una fila se abrevia porque el lema necesita el ancho; acá no
        // compite con nada.
        // La traducción va PRIMERA de las cuatro, y es la única de las listas que no es un
        // complemento de la glosa sino **otra respuesta a la misma pregunta**: quien abre una
        // entrada buscando cómo se dice en el otro idioma quiere eso, no la cuarta línea.
        //
        // ⚠️ **Va dentro de `SenseBlock`, y eso AFIRMA que pertenece a esta acepción.** Sólo
        // entra acá lo que la fuente atribuyó con `sense_index`; lo que no se puede atribuir se
        // descarta en el builder en vez de colgarse de la primera, que es la regla de D-117.
        // Su lugar honesto es un canal de nivel de entrada que todavía no existe (roadmap
        // §Naming a sense from another pack).
        //
        // ⚠️ **Sí se resuelven, y por eso un enlace lleva `packId`.** Antes iban con el mapa
        // vacío porque `links` sólo sabía de ESTE pack y un término del otro idioma nunca
        // resolvía. Ahora el destino dice a qué diccionario va, así que `house` puede llevar a
        // la entrada del pack inglés — y si ese pack no está instalado, no resuelve y se muestra
        // sin pintar, que es la misma promesa de siempre (D-084).
        TermList(R.string.entry_translations_title, sense.translations, links, onOpenWord)
        TermList(R.string.entry_synonyms_title, sense.synonyms, links, onOpenWord)
        // Los antónimos, debajo y con el mismo peso visual (D-126). ⚠️ **La categoría no es
        // opcional**: las tres listas se ven idénticas, y lo único que separa "otra forma de
        // decirlo" de "lo contrario" es esa palabra.
        TermList(R.string.entry_antonyms_title, sense.antonyms, links, onOpenWord)
        // Las relacionadas, últimas, porque son la afirmación más débil de las tres: ni otra
        // forma de decirlo ni lo contrario, sólo una vecina. Existen sobre todo para las
        // entradas FLACAS --una acepción, sin ejemplo-- que son el 70,4 % del pack español, así
        // que en la práctica esta lista es lo único que hay bajo la glosa (D-132).
        TermList(R.string.entry_related_title, sense.related, links, onOpenWord)
    }
}

/**
 * Una de las listas de la acepción: la categoría arriba, las palabras abajo y **tocables**.
 *
 * Pedido: *«mejorar la vista de sinónimos y antónimos, primero mostrando la categoría y abajo las
 * palabras pudiendo hacerles click para ir a ellas»*.
 *
 * ⚠️ **Cuesta una línea más por lista, y en 234 dp eso se paga.** Se acepta porque la línea que
 * agrega es la que dice de qué lista estás leyendo, que es la información que D-126 y D-132
 * dicen que no puede faltar; y se abarata sin padding vertical entre el título y sus palabras,
 * así que las dos líneas juntas ocupan menos que una fila de lista.
 *
 * ⚠️ **El título NO va del color de los enlaces, y eso corrige un error de diseño.** Iba en
 * `primary`, que en esta pantalla es exactamente el color con que se pinta una palabra que
 * navega: el encabezado prometía un toque que nunca existió. Pedido: *«los prefijos que indican
 * cosas como traducción, sinónimos y así deben estar más destacados y visibles y no ser
 * clickeables como hipervínculos»*. Ahora se destaca por **peso** --negrita sobre `onSurface`--
 * que es lo que distingue un encabezado de un enlace sin competir con él.
 *
 * ⚠️ **Sólo se pinta como enlace lo que existe en el pack**, igual que en la glosa: el color es
 * la promesa de que lleva a algún lado, y una palabra pintada que no navega es peor que una sin
 * pintar.
 */
@Composable
private fun TermList(
    @StringRes title: Int,
    terms: List<String>,
    links: Map<String, WordLink>,
    onOpenWord: (WordLink) -> Unit,
    /**
     * Si la sección pesa lo mismo que una acepción en vez de colgar de una.
     *
     * Pedido: *«que estas secciones individuales tengan la misma relevancia que una acepción»*.
     * Lo que cambia es el cuerpo del texto y la sangría: una lista de nivel de entrada no está
     * subordinada a nada, así que arranca en el margen y no indentada bajo una glosa.
     */
    prominent: Boolean = false,
) {
    if (terms.isEmpty()) return
    val indent = if (prominent) 0.dp else 10.dp
    Text(
        text = stringResource(title),
        style = if (prominent) {
            MaterialTheme.typography.bodyMedium
        } else {
            MaterialTheme.typography.labelMedium
        },
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 6.dp, start = indent),
    )
    val contenido = linkedTerms(terms, links)
    LinkedText(
        text = contenido.text,
        targets = contenido.targets,
        onOpenWord = onOpenWord,
        style = if (prominent) {
            MaterialTheme.typography.bodyMedium
        } else {
            MaterialTheme.typography.labelSmall
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = indent),
    )
}

/** Los términos separados por el separador de siempre, con los conocidos como enlace. */
@Composable
private fun linkedTerms(
    terms: List<String>,
    links: Map<String, WordLink>,
): LinkedContent {
    val separator = stringResource(R.string.entry_list_separator)
    val color = MaterialTheme.colorScheme.primary
    return remember(terms, links, separator, color) {
        val targets = mutableListOf<Pair<IntRange, WordLink>>()
        val text = buildAnnotatedString {
            terms.forEachIndexed { index, term ->
                if (index > 0) append(separator)
                val target = links[TextNormalizer.norm(term)]
                if (target == null) {
                    append(term)
                } else {
                    val desde = length
                    withStyle(SpanStyle(color = color)) { append(term) }
                    targets += (desde until length) to target
                }
            }
        }
        LinkedContent(text, targets)
    }
}

/**
 * A text whose linked words are resolved **by proximity**, not by hitting the glyph.
 *
 * ⚠️ **This replaces `LinkAnnotation.Clickable`, and the reason is geometry.** That API makes the
 * touch area exactly the painted glyph: measured on these packs, a word of average length is
 * about 40 x 14 dp at `labelSmall` on 234 dp, against Android's 48 x 48 minimum. The height is
 * 3.4x under, and a mis-tap was the normal case rather than the exception.
 *
 * The decision of *which* word a tap meant is [GlossTap], which is pure and covered by the gate.
 * What lives here is only the measuring: turning each linked range into the rectangles it
 * occupies, which needs a `TextLayoutResult` and therefore a device.
 *
 * ⚠️ **A word that wraps produces one box per line**, not one box spanning both. A single
 * rectangle around a wrapped word would cover the whole width of the paragraph between its two
 * halves, and every tap in that band would snap to it.
 */
@Composable
private fun LinkedText(
    text: AnnotatedString,
    targets: List<Pair<IntRange, WordLink>>,
    onOpenWord: (WordLink) -> Unit,
    style: TextStyle,
    // `modifier` first among the optionals: lint requires it, and lint breaks the build here.
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = text,
        style = style,
        color = color,
        onTextLayout = { layout = it },
        // ⚠️ **Keyed on `targets` ALONE, never on `layout`.** The first version added `layout`
        // and that is a recomposition loop: the layout arrives after the first pass, re-keys
        // `pointerInput`, which recomposes, which lays out again. Robolectric reported it as
        // *"Compose did not get idle after 60 SECONDS"* on a screen that has nothing to do with
        // gestures. The lambda reads the current `layout` through the state it captured, so it
        // does not need to be a key to be up to date.
        modifier = modifier.pointerInput(targets) {
            detectTapGestures { position ->
                val resultado = layout ?: return@detectTapGestures
                if (targets.isEmpty()) return@detectTapGestures
                val cajas = buildList {
                    targets.forEachIndexed { index, (rango, _) ->
                        val primera = resultado.getLineForOffset(rango.first)
                        val ultima = resultado.getLineForOffset(rango.last)
                        for (linea in primera..ultima) {
                            val izquierda = if (linea == primera) {
                                resultado.getHorizontalPosition(rango.first, usePrimaryDirection = true)
                            } else {
                                resultado.getLineLeft(linea)
                            }
                            val derecha = if (linea == ultima) {
                                resultado.getHorizontalPosition(rango.last + 1, usePrimaryDirection = true)
                            } else {
                                resultado.getLineRight(linea)
                            }
                            add(
                                GlossTap.LinkBox(
                                    index = index,
                                    line = linea,
                                    left = minOf(izquierda, derecha),
                                    top = resultado.getLineTop(linea),
                                    right = maxOf(izquierda, derecha),
                                    bottom = resultado.getLineBottom(linea),
                                ),
                            )
                        }
                    }
                }
                // The radius comes from the REAL line height, not a dp constant: it is the
                // only form that survives the text-scale setting. See GlossTap.radiusFor.
                val alto = if (resultado.lineCount > 0) {
                    resultado.getLineBottom(0) - resultado.getLineTop(0)
                } else {
                    0f
                }
                val elegido = GlossTap.linkAt(
                    cajas,
                    position.x,
                    position.y,
                    GlossTap.radiusFor(alto),
                ) ?: return@detectTapGestures
                onOpenWord(targets[elegido].second)
            }
        },
    )
}

/**
 * The gloss with its known words turned into links.
 *
 * The ranges come from [GlossTokenizer], which decides what a word is by delegating to `norm()`
 * --the same function the index was built with-- so the span that gets painted is exactly the one
 * that was queried. Words that are not headwords stay plain text: the colour is the promise that
 * tapping leads somewhere.
 */
@Composable
private fun annotatedGloss(
    prefijo: String,
    gloss: String,
    links: Map<String, WordLink>,
): LinkedContent {
    val color = MaterialTheme.colorScheme.primary
    return remember(prefijo, gloss, links, color) {
        val targets = mutableListOf<Pair<IntRange, WordLink>>()
        val text = buildAnnotatedString {
            append(prefijo)
            var cursor = 0
            for (word in GlossTokenizer.tokenize(gloss)) {
                val target = links[word.norm] ?: continue
                if (word.start > cursor) append(gloss.substring(cursor, word.start))
                val desde = length
                withStyle(SpanStyle(color = color)) {
                    append(gloss.substring(word.start, word.end))
                }
                targets += (desde until length) to target
                cursor = word.end
            }
            if (cursor < gloss.length) append(gloss.substring(cursor))
        }
        LinkedContent(text, targets)
    }
}

/** A text and where its linked words are, so the tap can be resolved by proximity. */
private data class LinkedContent(
    val text: AnnotatedString,
    val targets: List<Pair<IntRange, WordLink>>,
)
