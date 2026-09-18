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
