package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.DictionarySource
import cl.fadiaz.dictionary.core.PackMetadata

/**
 * An open, queryable pack.
 *
 * It is a sealed interface with a single case **on purpose**: there was a second one,
 * `Available`, for the packs that shipped inside the APK without being extracted. It left with
 * them. Once the installer exists something like it will be needed again --a catalogue pack that
 * has not been downloaded yet-- and it gets added then, with the use case in hand and not before.
 */
sealed interface PackHandle {
    val packId: String

    data class Open(
        val source: DictionarySource,
        /**
         * Vino **dentro del APK**; nadie lo instaló.
         *
         * ⚠️ **Se llamaba `isBundled` y el nombre mentía sobre lo que decide.** Lo que se pregunta
         * en todos los usos es *«¿este archivo lo trajo la app o lo puso el usuario?»* —de ahí
         * sale que no se pueda borrar (volvería sola al reiniciar) y que no le gane a un
         * diccionario de verdad—. Que hoy el pack incluido sea uno de juguete es una propiedad
         * de **este** build, no de la regla: cuando el núcleo ocupe ese lugar, las reglas son
         * exactamente las mismas y el nombre seguiría siendo falso.
         *
         * Sin este flag decidía el orden alfabético, y "demo-" le gana a "es-": con los dos
         * instalados, la app abría las 28 entradas de juguete en vez de las 146.194 reales.
         */
        val isBundled: Boolean = false,
        /**
         * The file in `filesDir/packs`, so it can be deleted.
         *
         * It is needed separately from `packId` because **they are not the same thing**: the id
         * comes from the metadata inside the pack and the name is chosen by whoever installed
         * it. Today `devpack.py` makes them match, but deriving one from the other would be an
         * assumption that deletes the wrong file the day they stop matching.
         */
        val fileName: String = "",
        /** What it takes on disk. The only figure that matters to someone deciding to delete. */
        val bytes: Long = 0,
    ) : PackHandle {
        override val packId: String get() = source.metadata.packId
        val metadata: PackMetadata get() = source.metadata
    }
}

/**
 * The result of looking at which dictionaries are there.
 *
 * The three cases are not over-defensive: each one looks different on screen and the user can do
 * something different about each one.
 *
 * It lives without a single reference to Android, same as [PackLoad]: it is the boundary that
 * lets `SearchViewModel` be tested on the JVM inside the gate (D-072).
 */
sealed interface PackSet {

    /**
     * There is at least one usable pack. [active] is the one being searched.
     *
     * [problems] are the packs that were there and did **not** open. It exists so a rejected pack
     * does not vanish from the list in silence: they are shown on the attribution screen, which
     * costs the search not a single dp.
     */
    data class Ready(
        val active: PackHandle.Open,
        val all: List<PackHandle>,
        val problems: List<String> = emptyList(),
    ) : PackSet

    /** No `.db` in `filesDir/packs/`. The APK ships none: one has to be installed. */
    data object NoPack : PackSet

    /** There were files and **none** of them is usable. */
    data class Unusable(val reason: String) : PackSet
}
