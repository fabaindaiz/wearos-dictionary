package cl.fadiaz.dictionary.presentation

import androidx.annotation.StringRes
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cl.fadiaz.dictionary.R
import cl.fadiaz.dictionary.core.MatchKind

/**
 * Como se NOMBRA en pantalla lo que el pack guarda como codigo.
 *
 * Vivia dentro de `SearchScreen.kt` y salio de ahi porque **no es una pantalla**: `posLabel`
 * tambien lo usa un tile, y un Tile importando un simbolo de un archivo de pantalla es una
 * dependencia al reves. Aca la importacion dice lo que pasa de verdad -- dos superficies
 * distintas resolviendo la misma etiqueta.
 *
 * La traduccion vive en la UI y no en el pack a proposito: meterla en el `.db` lo ataria a un
 * idioma de interfaz y costaria bytes por entrada, con 152.281 de ellas.
 */
/**
 * El `pos` que guarda el pack es el codigo de kaikki (`noun`, `verb`). Traducirlo es cosa de la
 * UI: meterlo en el pack lo ataria a un idioma de interfaz y costaria bytes por entrada.
 *
 * Devuelve el **id de recurso** y no el texto porque esto lo usan dos superficies distintas: las
 * pantallas, que resuelven con `stringResource`, y los tiles, que tienen `Context` y resuelven
 * con `getString`. Un codigo que no conocemos devuelve null y se muestra crudo, que es mejor que
 * esconderlo.
 */
@StringRes
internal fun posLabelRes(pos: String): Int? = when (pos) {
    "noun" -> R.string.pos_noun
    "verb" -> R.string.pos_verb
    "adj" -> R.string.pos_adj
    "adv" -> R.string.pos_adv
    "name" -> R.string.pos_name
    "phrase" -> R.string.pos_phrase
    "intj" -> R.string.pos_intj
    "pron" -> R.string.pos_pron
    "prep" -> R.string.pos_prep
    "conj" -> R.string.pos_conj
    "num" -> R.string.pos_num
    "suffix" -> R.string.pos_suffix
    "prefix" -> R.string.pos_prefix
    "proverb" -> R.string.pos_proverb
    "abbrev" -> R.string.pos_abbrev
    else -> null
}

/** [posLabelRes] resuelto en el idioma del reloj; el codigo crudo si no se conoce. */
@Composable
internal fun posLabel(pos: String): String = posLabelRes(pos)?.let { stringResource(it) } ?: pos

/**
 * El tipo ESCRITO ENTERO, para la ficha de una palabra.
 *
 * Existe al lado de [posLabel] y no en vez de el: son dos lugares con presupuestos distintos. En
 * una fila de 234 dp el lema es lo unico que importa y "sustantivo" le come el ancho; en la ficha
 * no compite con nada y "sust." es una abreviatura que alguien tiene que descifrar.
 *
 * Cae a la abreviatura --y no al codigo crudo-- si falta la clave larga: un pack puede traer un
 * `pos` que no conocemos, y media etiqueta es mejor que `intj`.
 */
@Composable
internal fun posLabelFull(pos: String): String = when (pos) {
    "noun" -> stringResource(R.string.pos_full_noun)
    "verb" -> stringResource(R.string.pos_full_verb)
    "adj" -> stringResource(R.string.pos_full_adj)
    "adv" -> stringResource(R.string.pos_full_adv)
    "name" -> stringResource(R.string.pos_full_name)
    "phrase" -> stringResource(R.string.pos_full_phrase)
    "intj" -> stringResource(R.string.pos_full_intj)
    "pron" -> stringResource(R.string.pos_full_pron)
    "prep" -> stringResource(R.string.pos_full_prep)
    "conj" -> stringResource(R.string.pos_full_conj)
    "num" -> stringResource(R.string.pos_full_num)
    "suffix" -> stringResource(R.string.pos_full_suffix)
    "prefix" -> stringResource(R.string.pos_full_prefix)
    "proverb" -> stringResource(R.string.pos_full_proverb)
    "abbrev" -> stringResource(R.string.pos_full_abbrev)
    else -> posLabel(pos)
}

/** [posLabelRes] para quien tiene `Context` y no composicion: los tiles. */
internal fun posLabel(context: Context, pos: String): String =
    posLabelRes(pos)?.let(context::getString) ?: pos

/**
 * Solo se etiquetan los niveles que sorprenden.
 *
 * Que un resultado salga por prefijo es lo esperado y no merece una palabra en una pantalla de
 * reloj. Que salga por una forma flexionada o por parecido si: explica por que aparece algo que
 * el usuario no escribio.
 */
@Composable
internal fun matchLabel(kind: MatchKind): String? = when (kind) {
    MatchKind.PREFIX -> null
    MatchKind.INFLECTED_FORM -> stringResource(R.string.match_inflected)
    MatchKind.TRANSLATION -> stringResource(R.string.match_translation)
    MatchKind.FUZZY -> stringResource(R.string.match_fuzzy)
    MatchKind.DEFINITION -> stringResource(R.string.match_definition)
}
