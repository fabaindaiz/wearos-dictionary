package cl.fadiaz.dictionary.presentation

import cl.fadiaz.dictionary.core.PackKind
import cl.fadiaz.dictionary.data.PackHandle

/**
 * Que ofrece el menu de una palabra.
 *
 * Vive aparte de `MainActivity` y sin una sola referencia a Android para que se pueda testear en
 * la JVM, dentro del gate: **cual accion se ofrece** es una decision, y la decision es lo que se
 * equivoco (D-072).
 *
 * POR QUE "VER TRADUCCION" CASI NUNCA APARECE
 *
 * La version anterior la ofrecia siempre que hubiera otro pack abierto, y lo que hacia era buscar
 * **el mismo lema** en el otro diccionario. Con dos packs monolingues eso casi nunca encuentra
 * nada: "house" no es una palabra espanola. Encontraba prestamos y nombres propios --"chocolate",
 * "Madrid"-- y en todo lo demas era un boton que no hacia nada, que es peor que no tenerlo.
 *
 * Traducir de verdad necesita que el pack **traiga** las traducciones, y hoy los dos reales son
 * monolingues (D-034): `trans` esta vacia y `MatchKind.TRANSLATION` no devuelve una sola fila.
 * Asi que la accion se ofrece **solo si hay un pack bilingue abierto**, que es lo unico que
 * declara `lang_dst` y por lo tanto lo unico que puede traducir. Hoy: nunca.
 */
internal fun accionesDeLaPalabra(
    esFavorita: Boolean,
    onAlternarFavorita: () -> Unit,
    packDeTraduccion: PackHandle.Abierto?,
    onVerTraduccion: (PackHandle.Abierto) -> Unit,
    onCopiar: () -> Unit,
): List<AccionDeEntrada> = buildList {
    add(
        AccionDeEntrada(
            etiqueta = if (esFavorita) "Quitar de guardadas" else "Guardar",
            onClick = onAlternarFavorita,
        ),
    )
    if (packDeTraduccion != null) {
        add(
            AccionDeEntrada(
                etiqueta = "Ver traducción",
                onClick = { onVerTraduccion(packDeTraduccion) },
            ),
        )
    }
    add(AccionDeEntrada(etiqueta = "Copiar", onClick = onCopiar))
}

/**
 * El pack que puede traducir esta entrada, o null.
 *
 * Bilingue y distinto del que estamos mirando. `PackKind.BILINGUAL` es la senal correcta y no el
 * nombre ni el idioma: es lo unico que obliga a declarar `lang_dst` (ver `PackMetadata.init`).
 */
internal fun packDeTraduccion(
    abiertos: List<PackHandle>,
    packDeLaEntrada: String,
): PackHandle.Abierto? =
    abiertos.filterIsInstance<PackHandle.Abierto>()
        .firstOrNull { it.packId != packDeLaEntrada && it.metadata.kind == PackKind.BILINGUAL }
