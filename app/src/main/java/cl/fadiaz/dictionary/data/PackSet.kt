package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.DictionarySource
import cl.fadiaz.dictionary.core.PackMetadata

/**
 * Un pack que la app conoce: o esta abierto, o esta en el APK esperando que lo elijan.
 *
 * La extraccion es **perezosa**: un pack de 69 MB no se copia a disco hasta que alguien lo va a
 * usar. Un idioma que nunca se selecciona nunca gasta disco, y en una muñeca el disco es el
 * recurso escaso.
 */
sealed interface PackHandle {
    val packId: String

    /** Abierto y consultable. */
    data class Abierto(val source: DictionarySource) : PackHandle {
        override val packId: String get() = source.metadata.packId
        val metadata: PackMetadata get() = source.metadata
    }

    /**
     * Esta en el APK y todavia no se extrajo.
     *
     * Solo se conoce lo que el nombre del asset dice, porque leer la metadata obligaria a
     * extraerlo -- que es exactamente lo que se esta difiriendo.
     */
    data class Disponible(override val packId: String, val asset: String, val etiqueta: String) :
        PackHandle
}

/**
 * El resultado de mirar que diccionarios hay.
 *
 * Los tres casos no son defensivos de mas: cada uno se ve distinto en pantalla y el usuario
 * puede hacer algo distinto con cada uno.
 *
 * Vive sin una sola referencia a Android, igual que [PackLoad]: es la frontera por la que
 * `SearchViewModel` se deja testear en la JVM dentro del gate (D-072).
 */
sealed interface PackSet {

    /**
     * Hay al menos un pack usable. [activo] esta abierto; [todos] incluye los que faltan extraer.
     *
     * [problemas] son los packs que estaban y **no** abrieron. Existe para que un pack rechazado
     * no desaparezca en silencio de la lista: se muestran en la pantalla de atribucion, que no
     * le cuesta un solo dp a la busqueda.
     */
    data class Ready(
        val activo: PackHandle.Abierto,
        val todos: List<PackHandle>,
        val problemas: List<String> = emptyList(),
    ) : PackSet

    /** Ni assets ni instalados: el APK se armo sin diccionarios. */
    data object NoPack : PackSet

    /** Habia archivos y **ninguno** sirve. */
    data class Unusable(val reason: String) : PackSet
}
