package cl.fadiaz.dictionary.core

/**
 * Donde la cascada cuenta lo que hizo, para que alguien lo pueda mirar por `adb logcat`.
 *
 * Existe porque este modulo **no puede** loguear: `ArchitectureTest` prohibe `import java.*` y
 * `System.` en `:dict-core` (D-017), asi que ni `android.util.Log` ni una marca de tiempo entran
 * aca. Lo que se puede hacer es **reportar**, en Kotlin puro, y que el llamador decida si eso se
 * escribe, se mide o se tira.
 *
 * ⚠️ **El que paga es el llamador, y por eso esta [enabled].** Armar el reporte de un peldaño
 * cuesta recorrer la lista de resultados; con [None] eso no debe pasar. El contrato es: quien
 * reporta pregunta primero.
 */
interface SearchTrace {

    /**
     * ¿Hay alguien escuchando? Si es `false`, el llamador **no arma** el reporte.
     *
     * Es una propiedad y no una constante porque quien la implemente en `:app` la ata a
     * `Log.isLoggable`, que el usuario enciende con `setprop` sin reinstalar nada.
     */
    val enabled: Boolean

    /**
     * Un pack lanzo al consultarlo y la cascada **siguio sin el**.
     *
     * ⚠️ Este es el evento que justifica todo el archivo. `SearchRepository.recolectar` se traga
     * la excepcion a proposito --un pack corrupto no puede dejar al usuario sin buscador-- y el
     * comentario decia que *"un pack que nunca devuelve nada es visible"*. Lo es en la pantalla;
     * **no lo era en ningun log**. Un pack que revienta en cada consulta parecia uno vacio.
     */
    fun packFailed(packId: String, error: String)

    /**
     * Una busqueda termino. [byKind] dice **cuantas filas puso cada peldaño** en el resultado.
     *
     * ⚠️ **Cuenta lo que el peldaño produjo, no si corrio.** `FUZZY` ausente significa "no aporto
     * filas", que puede ser porque no se intento --solo corre si lo anterior no dio nada-- o
     * porque se intento y no encontro. Distinguirlo obligaria a instrumentar `DictionarySource`,
     * y lo que se quiere observar --que un peldaño aporte lo que no deberia-- ya se ve asi.
     */
    fun searched(query: String, byKind: Map<MatchKind, Int>, fallback: Boolean, returned: Int)

    /** No hay nadie escuchando. El caso por defecto y el de produccion sin `setprop`. */
    object None : SearchTrace {
        override val enabled: Boolean = false
        override fun packFailed(packId: String, error: String) = Unit
        override fun searched(
            query: String,
            byKind: Map<MatchKind, Int>,
            fallback: Boolean,
            returned: Int,
        ) = Unit
    }
}
