package cl.fadiaz.dictionary.data

/**
 * Una entrada que el usuario abrio, guardada para poder volver a ella de un toque.
 *
 * Guarda lo que hace falta para **abrirla**, no para volver a buscarla: con `packId` y `entryId`
 * se navega directo a la entrada, en el diccionario correcto. Volver no re-ejecuta una consulta.
 *
 * Se guarda la entrada ABIERTA y no la consulta escrita porque abrir es la señal de que el
 * resultado sirvio; una consulta puede ser un tipeo a medias, y un historial lleno de "per",
 * "perr", "perro" no le ahorra un gesto a nadie.
 *
 * Sin una sola referencia a Android, igual que [PackLoad] y [PackSet]: es lo que deja que la
 * politica de recencia se testee en la JVM, dentro del gate (D-072).
 */
data class Visita(
    val packId: String,
    val entryId: Long,
    val headword: String,
    val partOfSpeech: String?,
)

/**
 * El separador de campos: UNIT SEPARATOR (U+001F).
 *
 * No es un tab ni un `|` a proposito. Los lemas salen del Wikcionario y ahi hay refranes con
 * comillas, y un tab perdido en una glosa **ya rompio algo en este proyecto** --por eso
 * `payload.sanitize()` existe--. Un caracter de control C0 no puede aparecer en un lema.
 */
internal const val SEPARADOR: String = ""

/** Marca un `pos` nulo. No puede confundirse con un pos real: ninguno esta vacio. */
private const val SIN_POS = ""

internal fun serializarVisitas(visitas: List<Visita>): String =
    visitas.joinToString("\n") { visita ->
        listOf(
            visita.packId,
            visita.entryId.toString(),
            visita.headword,
            visita.partOfSpeech ?: SIN_POS,
        ).joinToString(SEPARADOR)
    }

/**
 * Lee el historial guardado, **descartando lo que no entiende** en vez de fallar.
 *
 * Es deliberado: esto se lee al arrancar, y un formato viejo tras una actualizacion o un byte
 * corrupto no pueden impedir que la app abra. Perder el historial es aceptable; no arrancar, no.
 */
internal fun parsearVisitas(texto: String): List<Visita> =
    texto.lineSequence()
        .mapNotNull { linea ->
            val campos = linea.split(SEPARADOR)
            if (campos.size != 4) return@mapNotNull null
            val entryId = campos[1].toLongOrNull() ?: return@mapNotNull null
            if (campos[0].isEmpty() || campos[2].isEmpty()) return@mapNotNull null
            Visita(
                packId = campos[0],
                entryId = entryId,
                headword = campos[2],
                partOfSpeech = campos[3].ifEmpty { null },
            )
        }
        .toList()

/**
 * A donde lleva una visita guardada, cuando su `entryId` puede haber caducado.
 *
 * `entry.id` es la identidad FISICA del pack --el rowid-- y **no sobrevive a reconstruirlo**:
 * una palabra nueva en el medio corre todas las siguientes (D-055). El historial y las guardadas
 * se respaldan con `entryId`, asi que tras un rebuild apuntan a OTRA palabra: sin error, sin log,
 * y con el lema correcto en la lista.
 */
internal sealed interface DestinoDeVisita {
    /** El `entryId` sigue siendo el de esa palabra. El caso normal. */
    data class Directo(val entryId: Long) : DestinoDeVisita

    /** El pack se reconstruyo y el id se corrio; se corrigio por el lema. */
    data class Reresuelto(val entryId: Long) : DestinoDeVisita

    /** La palabra ya no esta en este pack. */
    data object Perdido : DestinoDeVisita
}

/**
 * Decide si el `entryId` guardado todavia sirve, y si no, por donde reemplazarlo.
 *
 * Pura y sin Android para que entre al gate (D-072): **cual entrada se abre** es la decision, y
 * la decision es lo que se equivoca.
 *
 * @param headwordEnElId el lema que hoy vive en `guardada.entryId`, o `null` si ese id ya no
 *   existe --el pack puede haber ENCOGIDO, que es lo que hizo D-116--.
 * @param reresuelto el id que da buscar el lema por `idx_entry_norm`, o `null` si la palabra ya
 *   no esta en el pack.
 */
internal fun destinoDeVisita(
    guardada: Visita,
    headwordEnElId: String?,
    reresuelto: Long?,
): DestinoDeVisita = when {
    // Sin lema no hay con que corregir: la unica pregunta que queda es si ese id existe. Pasa
    // con el deep link de un tile, que arma la visita desde los extras del intent.
    guardada.headword.isEmpty() ->
        if (headwordEnElId != null) DestinoDeVisita.Directo(guardada.entryId) else DestinoDeVisita.Perdido
    // El caso normal: el pack no se reconstruyo. Cuesta la lectura que igual hay que hacer para
    // mostrar la entrada, y NINGUNA consulta extra.
    headwordEnElId == guardada.headword -> DestinoDeVisita.Directo(guardada.entryId)
    // Se corrio el id --o el pack encogio--. El lema es lo unico que sobrevive, y entra por
    // indice. Se pierde precision entre homografos del MISMO pos (3.024 pares en español, los
    // que separa `sense_key` en `entry.uid`): se abre el mejor rankeado. Es estrictamente mejor
    // que abrir una palabra sin relacion, que es lo que pasaba antes.
    reresuelto != null -> DestinoDeVisita.Reresuelto(reresuelto)
    // La palabra ya no esta. Se pierde la fila en vez de abrir cualquier otra.
    else -> DestinoDeVisita.Perdido
}
