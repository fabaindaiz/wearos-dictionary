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
 * Las piezas que comparten las pantallas.
 *
 * Existe porque el mismo molde --recortar en pildora, pintar el fondo, hacerlo tocable y no bajar
 * de 48 dp-- estaba copiado en seis lugares de dos archivos. Seis copias de una regla son seis
 * lugares donde alguien baja el alto tocable en uno solo y nadie se entera.
 *
 * NO se unifican los seis. Tres son pildoras de texto a todo el ancho y salen de [Pildora]; los
 * otros tres son distintos de verdad --dos chips del tamano de su contenido y una fila con
 * icono-- y meterlos en el mismo composable pediria media docena de parametros opcionales, que
 * es peor que la duplicacion que vino a arreglar. Lo que sí comparten los seis es [FORMA_PILDORA]
 * y [TOUCH_TARGET], que es donde estaba el riesgo real.
 */

/**
 * El minimo tocable que pide la guia de Wear OS.
 *
 * Vive en una constante con nombre y no como `48.dp` suelto por una razon concreta: si alguien lo
 * baja para meter una fila mas, el test de densidad **sigue pasando** y lo que se rompe es el area
 * tocable, que ningun test ve (D-073).
 */
internal val TOUCH_TARGET: Dp = 48.dp

/**
 * La pildora. El clip y el borde tienen que ser la MISMA forma: si se separan, el borde se dibuja
 * recto sobre las esquinas redondeadas.
 */
internal val PILL_SHAPE = RoundedCornerShape(percent = 50)

/**
 * Para lo que tiene mas de una linea.
 *
 * La pildora al 50 % recorta las esquinas con un radio de media altura: con dos renglones eso se
 * come el principio y el final del texto. Un radio fijo no crece con el alto.
 */
internal val CARD_SHAPE = RoundedCornerShape(24.dp)

/**
 * Cuanto se puede scrollear **despues** del ultimo item.
 *
 * Sin esto el ultimo renglon queda pegado al borde, y en una pantalla REDONDA el borde de abajo
 * se curva hacia adentro: el texto se ve cortado y no hay forma de bajar mas. Pasaba en las seis
 * pantallas porque las seis pasaban el `contentPadding` del `ScreenScaffold` tal cual.
 */
private val BOTTOM_MARGIN: Dp = 32.dp

/**
 * El `contentPadding` del scaffold, con lugar para respirar al final.
 *
 * Una funcion y no 32.dp repetido en seis archivos: el dia que el numero cambie --o que alguien
 * mida cuanto se come de verdad la curva-- hay un solo lugar donde tocarlo.
 */
@Composable
internal fun withBottomMargin(base: PaddingValues): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = base.calculateStartPadding(direction),
        top = base.calculateTopPadding(),
        end = base.calculateEndPadding(direction),
        bottom = base.calculateBottomPadding() + BOTTOM_MARGIN,
    )
}

/**
 * Una pildora de texto a todo el ancho, tocable o no.
 *
 * `onClick` nulo significa **presente pero no tocable**, que no es lo mismo que ausente: es el
 * estado "buscando en las definiciones…", donde la pildora sigue en pantalla para que la lista no
 * salte, pero volver a tocarla no puede disparar una segunda consulta.
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
 * El molde de una fila tocable: 48 dp, una linea, el lema manda y el detalle acompaña.
 *
 * No usa `Button` de Wear Compose a proposito: su alto minimo es 52 dp y con el encabezado no
 * entraban cuatro filas. 48 dp es el minimo que pide la guia de Wear OS para un area tocable, y
 * bajar de ahi seria ganar densidad rompiendo algo peor.
 */
@Composable
internal fun ListRow(lema: String, detail: String?, onClick: () -> Unit) {
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
            text = lema,
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

/** Abriendo el pack, o extrayendolo la primera vez. */
@Composable
internal fun LoadingMessage(mensaje: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator()
        Text(
            text = mensaje,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}
