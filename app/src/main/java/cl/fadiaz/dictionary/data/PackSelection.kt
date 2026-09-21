package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.DictionarySource
import cl.fadiaz.dictionary.core.PackKind
import cl.fadiaz.dictionary.core.PackMetadata
import cl.fadiaz.dictionary.core.PackTier
import cl.fadiaz.dictionary.core.speaks

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

/**
 * Cuál pack queda **activo**, que es la otra mitad de las mismas reglas.
 *
 * ⚠️ **Elegir el activo aparte de [packsToQuery] era un agujero, y uno que se anulaba solo.** La
 * regla sacaba el build viejo de la lista a consultar, pero el activo se elegía del listado del
 * directorio —que no promete orden— y después se agregaba a la consulta **siempre**. Si caía el
 * viejo, se consultaba el viejo por ser activo y el nuevo por estar en la lista: **los dos builds
 * contestando**, que es exactamente el defecto que la regla venía a cerrar.
 *
 * El orden de preferencias, y cada uno tiene su motivo:
 *
 * 1. **Sólo entre los que se consultan.** Elegir a mano un pack que otro contiene no puede
 *    devolverlo: no se le va a preguntar nada.
 * 2. **Un diccionario real le gana a uno de demostración** (D-081). El demo existe para que una
 *    app recién instalada muestre algo; ganarle a lo que el usuario instaló sería al revés.
 * 3. **Lo que el usuario eligió la última vez**, si sigue estando.
 * 4. Cualquiera, con tal de que sea estable — la lista ya viene ordenada por las reglas.
 */
internal fun activePack(opened: List<PackHandle.Open>, preferred: String?): PackHandle.Open? {
    val consultables = packsToQuery(opened.map { it.source }).toSet()
    val vivos = opened.filter { it.source in consultables }
    val candidatos = vivos.filterNot { it.isBundled }.ifEmpty { vivos }
    return candidatos.firstOrNull { it.packId == preferred } ?: candidatos.firstOrNull()
}

/**
 * Si este pack tiene algo que decir cuando el idioma activo es [idioma].
 *
 * ⚠️ **Un pack declara sus idiomas como PARES, y uno bidireccional contesta por los dos.** Antes
 * se elegía con `langSource == idioma` y el bilingüe declaraba `es`: con **inglés activo** caía
 * en "otros idiomas", que desde D-189 no contestan nunca, así que el único pack con traducciones
 * quedaba invisible justo en la dirección `en → es` y `dog` dejaba de devolver `perro`.
 *
 * ⚠️ **Ahora la respuesta sale del artefacto y no de una inferencia.** Desde `schema_version` 4
 * cada entrada lleva su `entry.lang` y el pack declara `meta.langs`: que el bilingüe conteste por
 * inglés no es una regla de la app sino un hecho del archivo —tiene 164.249 lemas ingleses—, y
 * `verify_pack.py` comprueba que lo declarado y lo que hay coinciden.
 *
 * Puro y sin Android, para que el gate lo cubra en la JVM (D-072).
 */
internal fun answersFor(pack: DictionarySource, idioma: String?): Boolean =
    pack.metadata.speaks(idioma)

/**
 * Si de este pack puede salir una **palabra del día**.
 *
 * ⚠️ **Vive acá y no en el ViewModel porque la regla ya divergió una vez**: valía en la pantalla
 * y no en el tile (D-203), y tenerla escrita dos veces es exactamente cómo vuelve a pasar. Los
 * tres caminos —la pantalla, la caché del tile y el cálculo— consultan esta.
 *
 * Quedan fuera dos clases, por motivos distintos:
 *
 * - **Los de traducción** (D-200): una entrada inversa no tiene acepciones (D-196), así que la
 *   ficha diría *«se dice `perro`»* y nada más.
 * - **Los núcleos**: son las **8.000 palabras más frecuentes**, y elegir la de mejor rank da *la
 *   más común de las más comunes* — siempre una palabra funcional. Visto en el emulador con sólo
 *   los núcleos del APK: `my` y `un`.
 *
 * ⚠️ **Pregunta por lo que el pack DECLARA** —`kind` y `tier` (D-198)— y nunca por su nombre ni
 * por cuántas entradas tiene: un pack ajeno puede llamarse como quiera, y lo único que la app
 * puede creer es lo que el artefacto declara y `verify_pack.py` comprueba.
 *
 * ⚠️ **Consecuencia, escrita para que nadie la redescubra**: una instalación recién hecha lleva
 * sólo los núcleos del APK, así que **no muestra palabra del día hasta instalar un diccionario
 * completo**. Es la degradación correcta — mejor sin palabra que con una que no enseña.
 */
internal fun givesWordOfTheDay(meta: PackMetadata): Boolean =
    meta.kind != PackKind.BILINGUAL && meta.tier != PackTier.CORE
