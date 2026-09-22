package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.DictionarySource
import cl.fadiaz.dictionary.core.PackMetadata
import cl.fadiaz.dictionary.core.PackRejection

/**
 * A `.db` in `filesDir/packs`: either open and queryable, or rejected and named as such.
 *
 * ⚠️ **La segunda variante existe porque un pack rechazado tiene que poder VERSE.** Antes era
 * una cadena suelta en `PackSet.problems` que sólo llegaba a la pantalla de atribución --el peor
 * lugar posible: es la de los créditos, y un pack que no se carga no acredita nada--. Ahora es
 * una fila más de la pantalla de diccionarios, con su motivo en una línea y su botón de borrar,
 * que es la única acción que alguien puede tomar al respecto.
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

    /**
     * Un `.db` que está en el disco y **no se carga**, con el motivo como dato.
     *
     * ⚠️ **No tiene `metadata`, y eso es la garantía y no una carencia.** El pedido era que un
     * pack incompatible *«no se cargue de ninguna forma, por ejemplo que no se muestre en los
     * créditos»*. La manera de cumplirlo no es acordarse de filtrarlo en cada pantalla —eso se
     * olvida en la siguiente— sino que **no exista nada que mostrar**: sin `PackMetadata` no hay
     * nombre, ni licencia, ni fuentes, ni idioma, así que ninguna pantalla puede incluirlo
     * aunque quiera. El compilador lo impone, no la disciplina.
     *
     * [packId] es el **nombre del archivo** por la misma razón: el `pack_id` vive dentro del
     * pack y leerlo ya sería cargarlo.
     */
    data class Incompatible(
        val fileName: String,
        val bytes: Long,
        val rejection: PackRejection,
    ) : PackHandle {
        override val packId: String get() = fileName
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
     * ⚠️ **Los rechazados viajan en [all] como [PackHandle.Incompatible]**, y ya no en una lista
     * aparte de cadenas. Así un pack que no se carga sigue siendo visible --no desaparece en
     * silencio, que es lo que esa lista protegía-- pero ahora aparece **donde se puede hacer
     * algo con él**: la pantalla de diccionarios, con su motivo y su botón de borrar.
     */
    data class Ready(
        val active: PackHandle.Open,
        val all: List<PackHandle>,
    ) : PackSet

    /** No `.db` in `filesDir/packs/`. The APK ships none: one has to be installed. */
    data object NoPack : PackSet

    /** There were files and **none** of them is usable. */
    data class Unusable(val reason: String) : PackSet
}
