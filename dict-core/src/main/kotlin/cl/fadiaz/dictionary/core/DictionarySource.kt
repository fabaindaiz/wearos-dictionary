package cl.fadiaz.dictionary.core

/**
 * Un pack de idioma consultable. Es la unica superficie por la que la app llega a los datos:
 * ni la UI ni los ViewModels ven SQL.
 *
 * Que la interfaz viva en un modulo Kotlin puro y no mencione SQLite es lo que permite
 * cambiar la implementacion de almacenamiento (hoy SQLite empacado) sin tocar nada arriba,
 * y testear la capa de busqueda con implementaciones en memoria.
 *
 * Todas las funciones de consulta son cancelables: la busqueda se dispara en cada pulsacion y
 * la consulta anterior se descarta, asi que una implementacion debe chequear cancelacion
 * mientras itera filas y liberar sus recursos al salir.
 */
interface DictionarySource {

    val metadata: PackMetadata

    /**
     * Resultados para lo que el usuario lleva escrito, en el orden en que se muestran.
     *
     * Combina, en este orden de prioridad: prefijo del lema, forma flexionada y lado de la
     * traduccion. Recurre a la clave tolerante a errores solo si lo anterior devuelve muy
     * poco, porque es el camino caro y sus resultados son los menos confiables.
     */
    suspend fun suggest(query: String, limit: Int = 30): List<Suggestion>

    /** El cuerpo de una entrada. Aca si se lee y descomprime el payload. */
    suspend fun entry(entryId: Long): Entry?

    /**
     * Busqueda de texto libre dentro de las definiciones (FTS5).
     *
     * Es una accion explicita del usuario, nunca se dispara mientras escribe: recorre un
     * indice mucho mas grande que el de lemas y no cumple el presupuesto de latencia de la
     * busqueda incremental.
     */
    suspend fun searchDefinitions(query: String, limit: Int = 30): List<Suggestion>

    /** Cierra la conexion subyacente. El pack queda inutilizable. */
    fun close()
}
