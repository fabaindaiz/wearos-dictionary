package cl.fadiaz.dictionary.presentation

import cl.fadiaz.dictionary.data.PackHandle

/**
 * De packs a idiomas: el modelo que ordena toda la convivencia de diccionarios.
 *
 * > Un pack no es un diccionario que el usuario elige. Es una **fuente** de un idioma. Lo que el
 * > usuario elige es el idioma; los packs de ese idioma se consultan todos (D-136).
 *
 * Las tres funciones de aca son la traduccion de esa frase a la pantalla, y estan fuera de
 * `SearchScreen.kt` porque **son decisiones, no dibujo**: el gate las cubre en la JVM con
 * `LanguageChipsTest` en vez de con Robolectric, que es mas lento y mide otra cosa.
 */
/**
 * Un idioma ofrecido por el selector, y el pack que lo representa.
 *
 * `packId` es a quién se activa al tocarlo: decide **la atribución que se muestra, la palabra del
 * día del tile y qué pack va primero al desempatar**, no en qué packs se busca — desde D-136 se
 * busca en **todos** los del idioma activo.
 */
internal data class LanguageChip(
    val label: String,
    /**
     * El idioma, no el pack.
     *
     * ⚠️ **Antes llevaba un `packId` y con packs bidireccionales eso dejo de alcanzar**: uno
     * solo habla dos idiomas, asi que elegirlo no dice en cual se busca. Es lo que D-147 ya
     * empujaba --*«un chip por idioma, no por archivo»*-- llevado hasta el final.
     */
    val lang: String,
    val active: Boolean,
)

/**
 * Agrupa los packs abiertos por idioma: **un chip por idioma, no por archivo** (D-147).
 *
 * ⚠️ **Cierra una incoherencia que D-136 introdujo y no terminó.** Esa decisión dejó escrito que
 * el selector pasa a elegir un idioma porque `SearchRepository` consulta todos los packs del
 * idioma activo — y la pantalla siguió listando packs. Con dos diccionarios de español el inicio
 * mostraba **dos chips "ES"**, los dos activables, y tocarlos no cambiaba en qué se buscaba.
 *
 * **El representante de un idioma** es el pack activo si ya lo es —tocar otro idioma y volver no
 * puede cambiarte el diccionario elegido por debajo— y si no, **el que más entradas tiene**: es
 * el que más veces va a tener la palabra.
 *
 * ⚠️ **El orden es por código de idioma y no el de `available`**, que sale de listar un
 * directorio y no promete orden: si los chips lo siguieran, cambiarían de lugar entre arranques y
 * un control que se mueve solo se toca por error. Mismo criterio que el desempate de D-136.
 */
internal fun languageChips(packs: List<PackHandle>, activoLang: String?): List<LanguageChip> =
    idiomasDisponibles(packs).map { lang ->
        LanguageChip(label = lang.uppercase(), lang = lang, active = lang == activoLang)
    }

/**
 * Los idiomas que se pueden buscar, ordenados por codigo.
 *
 * ⚠️ **Un pack aporta UNO POR CADA idioma que declara**, y eso es lo que hace que un bilingue
 * de dos chips: tiene entradas de los dos --`casa` y `house` en el mismo archivo-- asi que
 * elegir `EN` filtra a sus lemas ingleses sin abrir ningun otro pack. Pedido: *«que todas las
 * tareas y consultas se puedan hacer usando solo ese pack»*.
 *
 * ⚠️ **El orden es por codigo y no el de `packs`**, que sale de listar un directorio y no
 * promete orden: si los chips lo siguieran cambiarian de lugar entre arranques, y un control
 * que se mueve solo se toca por error.
 */
internal fun idiomasDisponibles(packs: List<PackHandle>): List<String> =
    packs.filterIsInstance<PackHandle.Open>()
        .flatMap { it.metadata.langs }
        .distinct()
        .sorted()

/**
 * **Un pack por idioma**: el que lo representa. Ordenados por codigo de idioma.
 *
 * Tres pantallas necesitan lo mismo y por eso vive suelto (D-151): el selector dibuja uno por
 * idioma, la **palabra del dia** se calcula una por idioma, y el tile cachea la del activo. Antes
 * la palabra del dia se calculaba **por pack**, asi que con dos diccionarios de español el inicio
 * mostraba dos palabras del dia del mismo idioma — el bug que D-145 tapo fundiendo los packs en
 * lugar de arreglarlo, y que vuelve en cuanto alguien instale un pack propio.
 *
 * **El representante** es el pack activo si ya lo es —elegir otro idioma y volver no puede
 * cambiarte el diccionario por debajo— y si no, **el que mas entradas tiene**: es el que mas veces
 * va a tener la palabra.
 *
 * ⚠️ **El orden es por codigo de idioma y no el de `packs`**, que sale de listar un directorio y
 * no promete orden. Mismo criterio que el desempate de D-136.
 */
internal fun representativePacks(packs: List<PackHandle>, activo: String?): List<PackHandle.Open> {
    val abiertos = packs.filterIsInstance<PackHandle.Open>()
    // ⚠️ **Se agrupa por CADA idioma que el pack declara, no por uno solo.** Un bilingue entra en
    // los dos grupos, asi que puede representar al ingles aunque tambien hable español -- que es
    // lo que hace falta cuando es el unico pack instalado.
    return idiomasDisponibles(packs).mapNotNull { lang ->
        val delIdioma = abiertos.filter { lang in it.metadata.langs }
        delIdioma.firstOrNull { it.packId == activo }
            ?: delIdioma.maxByOrNull { it.metadata.entryCount }
            ?: delIdioma.firstOrNull()
    }.distinctBy { it.packId }
}

/**
 * Que etiqueta lleva cada resultado: **el idioma, y nada mas**.
 *
 * ⚠️ **Antes mostraba la FUENTE cuando habia dos packs del mismo idioma** --`WIKC`, `ENWIKT`--
 * razonando que `ES · ES` no desambigua. Se revirtio a pedido, y el razonamiento que lo revierte
 * es mejor que el que lo puso: *«solo debe ser EN, ES. No me gusta que haya un ENWIK... porque
 * solo me interesa conocer el idioma de proveniencia»*.
 *
 * Desambiguar **dos packs** es una pregunta de catalogo y tiene su pantalla --gestion de
 * diccionarios--; la fila de resultados contesta otra cosa, que es en que idioma esta la palabra
 * que estoy por abrir. La sigla ademas no se entiende sin conocer el `pack_id`, asi que ocupaba
 * el mismo ancho para decir menos.
 *
 * Desde que la busqueda es estricta por idioma ([LanguageScope][cl.fadiaz.dictionary.core.LanguageScope]),
 * todas las filas de una consulta comparten etiqueta; se deja igual porque la ficha la muestra
 * tambien, y porque el modo auto futuro vuelve a mezclar idiomas sin tocar esto.
 */
internal fun resultTag(lang: String?): String? = lang?.uppercase()

/**
 * La etiqueta de una fila del HISTORIAL o de las guardadas, por `packId`.
 *
 * ⚠️ **Es un mecanismo distinto del de los resultados, y la diferencia es real.** Una fila de
 * resultados no puede ser de otro idioma: la busqueda filtra por el activo, entre packs (D-189)
 * y dentro de un pack bidireccional. Una fila del historial **si**: se guardo cuando habia otro
 * diccionario instalado, o con otro idioma elegido. Etiquetarla con el idioma activo seria
 * afirmar una procedencia que nadie comprobo -- la misma familia de falla que D-080.
 *
 * ⚠️ **Y un pack bidireccional no recibe etiqueta**, porque no hay una sola verdadera: `casa` y
 * `house` viven en el mismo archivo y `Visit` no guarda el idioma. Se prefiere **sin etiqueta**
 * antes que con la equivocada, que es la misma regla con la que se pintan los enlaces de una
 * glosa. Guardar el idioma en `Visit` lo arreglaria; no se hizo porque cambia lo que hay escrito
 * en las preferencias y eso merece su propia decision.
 */
internal fun historyTags(packs: List<PackHandle>): Map<String, String> =
    packs.filterIsInstance<PackHandle.Open>()
        .mapNotNull { handle ->
            handle.metadata.langs.singleOrNull()?.let { handle.packId to it.uppercase() }
        }
        .toMap()