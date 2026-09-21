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
    val packId: String,
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
internal fun languageChips(packs: List<PackHandle>, activo: String?): List<LanguageChip> {
    val activoLang = packs.filterIsInstance<PackHandle.Open>()
        .firstOrNull { it.packId == activo }?.metadata?.langSource
    return representativePacks(packs, activo).map { representante ->
        LanguageChip(
            label = representante.metadata.langSource.uppercase(),
            packId = representante.packId,
            active = representante.metadata.langSource == activoLang,
        )
    }
}

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
internal fun representativePacks(packs: List<PackHandle>, activo: String?): List<PackHandle.Open> =
    packs.filterIsInstance<PackHandle.Open>()
        .groupBy { it.metadata.langSource }
        .toSortedMap()
        .map { (_lang, delIdioma) ->
            delIdioma.firstOrNull { it.packId == activo }
                ?: delIdioma.maxByOrNull { it.metadata.entryCount }
                ?: delIdioma.first()
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
internal fun resultTags(packs: List<PackHandle>): Map<String, String> =
    packs.filterIsInstance<PackHandle.Open>()
        .associate { handle -> handle.packId to handle.metadata.langSource.uppercase() }