package cl.fadiaz.dictionary.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
private const val ACEPCIONES_VISIBLES = 3

@Composable
fun EntryScreen(
    entryId: Long,
    // Sin default: una palabra pintada como tocable que no navega a ningun lado es peor que no
    // pintarla, y no se distingue de una que funciona (mismo criterio que D-084).
    onOpenPalabra: (Long) -> Unit,
    onVolverABuscar: () -> Unit = {},
    resolver: suspend (Set<String>) -> Map<String, Long> = { emptyMap() },
    // Va ultimo para que siga siendo el lambda final: es como lo llaman las pantallas y los tests.
    cargar: suspend (Long) -> Entry?,
) {
    var entry by remember(entryId) { mutableStateOf<Entry?>(null) }
    var fallo by remember(entryId) { mutableStateOf(false) }
    var desplegada by remember(entryId) { mutableStateOf(false) }
    var enlaces by remember(entryId) { mutableStateOf(emptyMap<String, Long>()) }

    LaunchedEffect(entryId) {
        // Sin el try, cualquier cosa que tire `cargar` --una SQLiteException, un inflate sobre un
        // pack truncado-- sube por la corrutina de composicion y mata el proceso. Un pack ilegible
        // tiene que degradar al mensaje que ya existe abajo, igual que una entrada que no esta.
        entry = runCatching { cargar(entryId) }.getOrNull()
        fallo = entry == null
    }

    // Que palabras de las glosas son lema del pack, en UNA consulta para toda la pantalla y no
    // una por palabra. Se pintan solo las que existen, asi el color dice de antemano que lleva a
    // algun lado. Va en su propio efecto porque depende de la entrada ya cargada.
    LaunchedEffect(entry) {
        val cargada = entry ?: return@LaunchedEffect
        val claves = cargada.senses.flatMap { GlossTokenizer.tokenize(it.gloss) }
            .map { it.norm }
            .toSet()
        enlaces = runCatching { resolver(claves) }
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
            contentPadding = contentPadding,
            state = listState,
            // La corona es el scroll principal de un reloj: el dedo tapa justamente lo que se
            // esta leyendo. No viene cableada por defecto.
            modifier = Modifier.rotaryScrollable(
                RotaryScrollableDefaults.behavior(listState),
                focusRequester,
            ).focusRequester(focusRequester).requestFocusOnHierarchyActive(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val actual = entry
            // La salida rapida a la busqueda, y vive como PRIMERA FILA DEL SCROLL y no como
            // chrome fijo: arriba de todo se llega con la corona, y cuesta cero dp de pantalla
            // permanente. Un boton fijo costaria 48 dp, que es justo lo que D-084 rechazo.
            // Hace falta porque tocar palabras apila entradas: sin esto, volver al inicio desde
            // tres palabras de profundidad son tres gestos.
            item(key = "volver-a-buscar") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(percent = 50))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable(onClick = onVolverABuscar)
                        .heightIn(min = TOUCH_TARGET)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Buscar",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "encabezado") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Text(
                        // El lema NO se trunca aca, al reves que en la lista: esta pantalla
                        // existe justamente para leer la palabra entera, refranes incluidos.
                        text = actual?.headword ?: "…",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    actual?.partOfSpeech?.let { pos ->
                        Text(
                            text = posEnEspanol(pos),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        )
                    }
                }
            }

            if (fallo) {
                item(key = "fallo") {
                    Text(
                        text = "Esa entrada ya no está en el diccionario.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
            }

            val todas = actual?.senses.orEmpty()
            val visibles = if (desplegada) todas else todas.take(ACEPCIONES_VISIBLES)
            val ocultas = todas.size - visibles.size

            // Con `key` el item conserva identidad al desplegarse; sin el, el lazy layout los
            // identifica por posicion. Es la misma causa que en la busqueda se llevaba el foco
            // del campo de texto, y aca ademas deja `visibles` capturado por closure.
            items(count = visibles.size, key = { indice -> "s:$indice" }) { indice ->
                // getOrNull y no [indice]: el lambda del item y el conteo los consume el layout
                // en frames distintos. Saltar una acepcion un frame es aceptable; tirar no.
                visibles.getOrNull(indice)?.let { sense ->
                    Acepcion(
                        numero = indice + 1,
                        sense = sense,
                        enlaces = enlaces,
                        onOpenPalabra = onOpenPalabra,
                    )
                }
            }

            if (ocultas > 0) {
                item(key = "ver-mas") {
                    Text(
                        text = "Ver más ($ocultas)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable { desplegada = true }
                            .heightIn(min = 48.dp)
                            .padding(vertical = 14.dp),
                    )
                }
            }
        }
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
private fun Acepcion(
    numero: Int,
    sense: Sense,
    enlaces: Map<String, Long>,
    onOpenPalabra: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = glosaAnotada("$numero. ", sense.gloss, enlaces, onOpenPalabra),
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
private fun glosaAnotada(
    prefijo: String,
    glosa: String,
    enlaces: Map<String, Long>,
    onOpenPalabra: (Long) -> Unit,
): AnnotatedString {
    val estilo = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary))
    return remember(prefijo, glosa, enlaces, estilo) {
        buildAnnotatedString {
            append(prefijo)
            var cursor = 0
            for (palabra in GlossTokenizer.tokenize(glosa)) {
                val destino = enlaces[palabra.norm] ?: continue
                if (palabra.start > cursor) append(glosa.substring(cursor, palabra.start))
                withLink(
                    LinkAnnotation.Clickable("palabra:$destino", estilo) { onOpenPalabra(destino) },
                ) {
                    append(glosa.substring(palabra.start, palabra.end))
                }
                cursor = palabra.end
            }
            if (cursor < glosa.length) append(glosa.substring(cursor))
        }
    }
}
