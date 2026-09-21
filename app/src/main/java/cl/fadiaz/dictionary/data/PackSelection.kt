package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.DictionarySource

/**
 * De todos los packs instalados, a cuáles se les pregunta.
 *
 * ## Por qué esto existe como una lista aparte
 *
 * ⚠️ **Instalado y consultado no son la misma cosa, y hasta acá sí lo eran.** Ajustes tiene que
 * seguir mostrando **todo** lo que ocupa disco —si no, un pack que no se consulta se vuelve
 * invisible y no hay forma de borrarlo— mientras que la búsqueda tiene que preguntarle sólo a lo
 * que puede aportar algo. `PackStore` sigue devolviendo todo; esto elige.
 *
 * ## Las dos reglas, y por qué son una sola función
 *
 * Contestan preguntas distintas y se aplican en orden:
 *
 * 1. **De cada `pack_id`, el `data_version` mayor.** Dos archivos con el mismo `pack_id` son el
 *    mismo diccionario (D-138); el build más viejo es estrictamente peor (D-170). Cierra un
 *    defecto de hoy: `PackStore` abre todos los `.db` del directorio, así que reinstalar un pack
 *    sin borrar el anterior deja **dos builds contestando**. No sale mal —la deduplicación por
 *    `(lema, tipo)` lo tapa— pero se paga el doble de consultas y el doble de disco, en silencio.
 * 2. **Un pack no se consulta si el que lo contiene también está.** Es la regla que el pack
 *    núcleo necesita: cada respuesta suya o ya vino del completo y se descarta al deduplicar, o
 *    es un lema que el completo no tiene, **lo cual no puede pasar si de verdad es un
 *    subconjunto**. Hoy **no la usa nadie**: ningún pack declara `subset_of`. Está escrita porque
 *    es la misma forma que la primera y separarlas serían dos recorridos con el mismo bug.
 *
 * El orden importa: primero se elige el build de cada diccionario y **después** se mira la
 * contención, para que un núcleo no sobreviva sólo porque el completo instalado es un build viejo.
 *
 * ## Sólo absorbe el que no es absorbido, y eso no es una sutileza
 *
 * ⚠️ **Un pack de la comunidad puede declarar cualquier cosa.** Si dos se declaran subconjunto
 * mutuamente, la versión obvia de esta regla —*«sacá a todo el que nombre a alguien presente»*—
 * **los saca a los dos y deja la búsqueda sin diccionario**. Es el peor resultado posible para
 * una declaración mal hecha, y lo encontró el test que se escribió para eso: mi primera versión
 * lo hacía.
 *
 * La regla que funciona: **un pack sólo se hace a un lado por otro que no se haya hecho a un
 * lado él mismo**. En un ciclo nadie califica de absorbente y sobreviven los dos.
 *
 * El costo es que la contención **no es transitiva**: con A ⊂ B ⊂ C se consultan A y C, y A
 * sobra. Se acepta a propósito — `subset_of` afirma una contención directa y nada más, y el peor
 * caso de no encadenar es trabajo de más; el peor caso de encadenar es quedarse sin nada.
 *
 * Es puro y sin Android para que el gate lo cubra en la JVM (D-072).
 */
internal fun packsToQuery(opened: List<DictionarySource>): List<DictionarySource> {
    val masNuevos = opened
        .groupBy { it.metadata.packId }
        .map { (_, versiones) -> versiones.maxBy { it.metadata.dataVersion } }
    val presentes = masNuevos.map { it.metadata.packId }.toSet()
    // Los que pueden absorber a otro: los que no están absorbidos ellos mismos. En un ciclo no
    // califica ninguno, y sobreviven todos.
    val absorbentes = masNuevos
        .filter { it.metadata.subsetOf == null || it.metadata.subsetOf !in presentes }
        .map { it.metadata.packId }
        .toSet()
    return masNuevos.filterNot { it.metadata.subsetOf in absorbentes }
}
