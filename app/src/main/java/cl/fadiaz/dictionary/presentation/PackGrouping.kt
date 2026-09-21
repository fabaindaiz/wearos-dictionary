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
 * Que etiqueta lleva cada resultado: el **idioma**, o la **fuente** cuando el idioma no alcanza.
 *
 * Con un diccionario del idioma activo, `sust. · ES` dice todo lo que hay que decir. Con dos,
 * `ES · ES` no desambigua nada: lo que separa dos diccionarios del mismo idioma es **de donde
 * salieron**, y el `pack_id` lo lleva en su tercer segmento por la gramatica que `verify_pack.py`
 * verifica (D-138): `<idioma>-<tipo>-<fuente>[-variante]`.
 *
 * ⚠️ **Solo cuentan los packs del idioma activo.** Desde D-136 se busca unicamente ahi, asi que un
 * pack ingles no puede hacer que una fila española muestre su fuente.
 *
 * ⚠️ **Y un `pack_id` que no cumpla la gramatica cae al idioma** en vez de inventarle una fuente:
 * un pack anterior a D-138 no la lleva, y partir su nombre daria una etiqueta falsa.
 */
internal fun resultTags(packs: List<PackHandle>, idiomaActivo: String?): Map<String, String> {
    val delIdioma = packs.filterIsInstance<PackHandle.Open>()
        .filter { it.metadata.langSource == idiomaActivo }
    val ambiguo = delIdioma.size > 1
    return packs.filterIsInstance<PackHandle.Open>().associate { handle ->
        val idioma = handle.metadata.langSource.uppercase()
        val fuente = handle.packId.split('-').getOrNull(SEGMENTO_DE_FUENTE)
        handle.packId to if (ambiguo && !fuente.isNullOrBlank()) fuente.uppercase() else idioma
    }
}

/** El tercer segmento del `pack_id`: `<idioma>-<tipo>-<FUENTE>[-variante]` (D-138). */
private const val SEGMENTO_DE_FUENTE = 2