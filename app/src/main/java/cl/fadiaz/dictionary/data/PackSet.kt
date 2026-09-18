package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.DictionarySource
import cl.fadiaz.dictionary.core.PackMetadata

/**
 * Un pack abierto y consultable.
 *
 * Es una interfaz sellada de un solo caso **a proposito**: hubo un segundo, `Disponible`, para
 * los packs que venian en el APK sin extraer. Se fue con ellos. Cuando exista el instalador va a
 * volver a hacer falta algo asi --un pack del catalogo que todavia no se bajo-- y se agrega
 * entonces, con el caso de uso delante y no antes.
 */
sealed interface PackHandle {
    val packId: String

    data class Abierto(
        val source: DictionarySource,
        /**
         * Vino del APK, no lo instalo nadie.
         *
         * El pack de demostracion existe para que la app recien instalada tenga algo que
         * mostrar (D-081), asi que **nunca puede ganarle a un diccionario de verdad**. Sin esta
         * marca lo decidia el orden alfabetico, y "demo-" gana a "es-": con los dos instalados,
         * la app abria las 28 entradas de juguete en vez de las 146.194 reales.
         */
        val esDemo: Boolean = false,
    ) : PackHandle {
        override val packId: String get() = source.metadata.packId
        val metadata: PackMetadata get() = source.metadata
    }
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
     * Hay al menos un pack usable. [activo] es en el que se busca.
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

    /** No hay ningun `.db` en `filesDir/packs/`. El APK no trae ninguno: hay que instalarlo. */
    data object NoPack : PackSet

    /** Habia archivos y **ninguno** sirve. */
    data class Unusable(val reason: String) : PackSet
}
