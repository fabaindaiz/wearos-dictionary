package cl.fadiaz.dictionary.core

/**
 * Formato del cuerpo de una entrada (columna `entry.payload`).
 *
 * ESTE ARCHIVO TIENE UN ESPEJO: tools/packbuilder/payload.py
 *
 * El payload va comprimido con deflate crudo y un diccionario precargado compartido por todo
 * el pack, guardado en `meta.payload_dict`. Las entradas son de unos cientos de bytes, o sea
 * demasiado cortas para que deflate encuentre redundancia por si solo; el diccionario le da la
 * ventana ya primada con los fragmentos frecuentes del corpus.
 *
 * Se eligio deflate y no zstd aunque comprime menos, porque deflate esta en `java.util.zip`
 * (plataforma Android, sin .so extra) y en el `zlib` de la stdlib de Python. zstd obligaria a
 * una libreria nativa en el reloj ADEMAS de la de SQLite, y a una dependencia de pip en el
 * builder, para ganar unos puntos de compresion.
 *
 * Una vez descomprimido es texto UTF-8, una linea por campo:
 *
 *     P<TAB>verb                     (part of speech, opcional, antes de cualquier S)
 *     S<TAB>moverse rapidamente      (abre una acepcion)
 *     E<TAB>corrio hasta la esquina  (ejemplo de la acepcion abierta)
 *     T<TAB>to run                   (traduccion de la acepcion abierta)
 *     Y<TAB>bobo                     (sinonimo de la acepcion abierta)
 *     S<TAB>dicho del tiempo...      (abre la siguiente acepcion)
 *
 * Se usa texto delimitado en vez de JSON o CBOR a proposito: se parsea sin ninguna
 * dependencia en los dos lenguajes, se puede leer con la vista al depurar un pack, y despues
 * de comprimir la diferencia de tamano con un formato binario es ruido.
 *
 * Los tags desconocidos se ignoran, asi un builder mas nuevo puede agregar campos sin romper
 * una app vieja.
 *
 * Aun asi [CODEC_ID] sube cuando cambia el formato del texto, porque `PackFile.open` lo compara
 * con `!=` y rechaza el pack. Hoy eso cuesta reconstruir y volver a sideloadear, y nada mas.
 * **Cuando exista el instalador, un tag ADITIVO no lo sube** (D-119): forzar a redescargar
 * 300 MB por un campo que el lector viejo ignora tiraria justamente esta propiedad.
 */
object PayloadCodec {

    /** Sube cuando cambia el formato. Se escribe en `meta.payload_codec` como "deflate-v<n>". */
    const val PAYLOAD_VERSION: Int = 2

    const val CODEC_ID: String = "deflate-v2"

    private const val TAG_PART_OF_SPEECH = 'P'
    private const val TAG_SENSE = 'S'
    private const val TAG_EXAMPLE = 'E'
    private const val TAG_TRANSLATION = 'T'
    private const val TAG_SYNONYM = 'Y'

    /**
     * Antonimo de esta acepcion (D-126).
     *
     * **No sube `CODEC_ID` y eso es deliberado.** D-119 dejo escrito que un tag aditivo no debe
     * subirlo: para eso existe que los tags desconocidos se ignoren. Un pack con antonimos
     * abierto por un lector viejo muestra la entrada sin ellos.
     */
    private const val TAG_ANTONYM = 'A'

    /**
     * Palabra relacionada de esta acepcion (D-132): hiperonimo, hiponimo o pariente morfologico.
     * Aditivo igual que [TAG_ANTONYM], asi que **tampoco sube [CODEC_ID]**.
     *
     * Tag propio y no reusar [TAG_SYNONYM]: `galo` es related de "frances", no equivalente.
     */
    private const val TAG_RELATED = 'R'

    /**
     * Where the example above it was quoted from. Additive like [TAG_ANTONYM] and [TAG_RELATED],
     * so it **does not bump [CODEC_ID]** either.
     *
     * ⚠️ **A tag and not a `` suffix on `E`**, even though the suffix mechanism already
     * exists and translations use it: bolting it onto a tag that shipped without it would make
     * an older app paint the raw separator byte inside the example. A new tag degrades to
     * nothing; a new suffix degrades to garbage.
     *
     * ⚠️ **It only names the example written immediately above it** — see [parse]. The strict
     * rule is mirrored character for character in `payload.py`, because if the two sides
     * disagreed the same pack would show different attributions depending on who read it.
     */
    private const val TAG_CITATION = 'C'

    /**
     * Traducciones de la PALABRA, sin acepcion atribuida.
     *
     * Aditivo igual que [TAG_ANTONYM] y [TAG_RELATED], asi que **tampoco sube [CODEC_ID]**: un
     * lector viejo lo cae por el `else -> Unit` y muestra la entrada sin la lista, que es la
     * degradacion correcta.
     *
     * ⚠️ **Existe para que la opcion deshonesta deje de ser la barata.** Con `T` solo --que vive
     * dentro de una acepcion-- un builder con una traduccion que la fuente no atribuyo podia
     * tirarla o colgarla de la primera acepcion, y lo segundo se lee plausible y no lo agarra
     * nadie (D-117). Medido: es el **37,7 %** de las traducciones del dump español.
     */
    private const val TAG_WORD_TRANSLATION = 'W'

    /**
     * A **principal part** of the word: gerund, participle, plural or feminine.
     *
     * ⚠️ **The value is `key:form`, and the key is deliberately neutral.** Storing the label
     * already translated --"gerundio"-- would put the interface's language *inside* the pack, and
     * the same pack is shared by a user running the app in Spanish and one running it in English.
     * The app maps the key to its localized string, which is where translation belongs.
     *
     * ⚠️ **Few and chosen, not the whole conjugation.** `correr` carries 137 forms in the source
     * and 202 rows in `form`; dumping those into the card would be unreadable on a watch and
     * expensive in bytes -- `form` is already 42 % of the Spanish pack. What travels here are the
     * parts the rest derive from, which is what a printed dictionary puts beside the headword.
     *
     * ⚠️ **And it is NOT the `form` table.** That one stores `norm(form)` --`corrais`, not
     * `corráis`-- because its job is to be a search key. A card that showed `corrais` would be
     * misspelled, and that is exactly why this channel had to exist at all.
     */
    private const val TAG_FORM = 'F'

    /** Separates the key from the form inside a [TAG_FORM]. Mirrors `payload.FORM_SEPARATOR`. */
    private const val FORM_SEPARATOR = ':'

    /** El cuerpo decodificado, sin los datos que ya vienen en las columnas de `entry`. */
    data class Body(
        val partOfSpeech: String?,
        val senses: List<Sense>,
        /** Traducciones de la palabra entera, sin acepcion. Ver [TAG_WORD_TRANSLATION]. */
        val wordTranslations: List<String> = emptyList(),
        /**
         * The word's principal parts, in the order the builder chose. See [TAG_FORM].
         *
         * Empty for every pack built before this channel existed, which is the degradation: the
         * card simply has no forms section. Nothing fails and nothing is left blank.
         */
        val forms: List<InflectedForm> = emptyList(),
    )

    /**
     * One principal part: a neutral [key] and the form with its own spelling.
     *
     * [key] is a token the pack chose (`ger`, `part`, `pl`, `fem`) and the app localizes. An
     * unknown key is shown without a label rather than dropped: a form the reader can see is
     * worth more than a label the app happens to know.
     */
    data class InflectedForm(val key: String, val form: String)

    /**
     * Separa un item de traduccion en `(termino, acepcion a la que apunta)`.
     *
     * ⚠️ **Las tres partes de una referencia `(pack, palabra, acepcion)` viven en lugares
     * distintos, y ese reparto es el diseño**: el **pack no se nombra** --el destino se declara
     * por IDIOMA en `meta.translations_to`, así cualquier pack instalado de ese idioma lo
     * resuelve y el enlace no muere con el núcleo en vez del completo (D-180)--, la
     * **palabra** es el termino que ya se muestra, y la **acepcion** es este sufijo opcional,
     * porque solo existe cuando la fuente la supo.
     *
     * De ahi sale la propiedad que importa: **una traduccion sin acepcion ya es un enlace a la
     * palabra, y no cuesta un solo byte extra**.
     */
    fun splitRef(value: String): Pair<String, String?> {
        val cut = value.indexOf(REF_SEPARATOR)
        return if (cut < 0) value to null
        else value.substring(0, cut) to value.substring(cut + 1).ifEmpty { null }
    }

    /** El mismo juntador que usa `stable_uid()`. El builder lo saca de cualquier dato de fuente. */
    private const val REF_SEPARATOR = '\u001f'

    /**
     * Hash del diccionario precargado, a comparar contra `meta.payload_dict_sha256` UNA VEZ al
     * abrir el pack (no por entrada).
     *
     * Hace falta porque deflate NO detecta un diccionario equivocado: si tiene el largo
     * suficiente descomprime sin lanzar nada y devuelve texto corrupto. Esta verificado que
     * "moverse rapidamente" sale como " nadrse rapidamente" sin ninguna excepcion. Sin este
     * chequeo, un pack con el diccionario mal llenaria la pantalla de basura sin una sola pista
     * del motivo, y el usuario lo leeria como "la app esta rota".
     */
    fun dictionaryDigest(dictionary: ByteArray): String = sha256Hex(dictionary)

    /**
     * Pliega una glosa para decidir si dos fuentes escribieron **la misma** acepcion.
     *
     * ⚠️ **ESPEJO de `payload.fold_gloss`.**
     *
     * El plegado de caja **sigue el estandar**: [CaseFolding.fold] implementa `toCaseFold()`, la
     * regla R4 de la seccion 3.13 del Estandar Unicode, que es la operacion que UAX #31 define
     * para *caseless matching*. `lowercase()` es la equivocada -- el estandar separa las dos:
     * case mapping para MOSTRAR, case folding para COMPARAR.
     *
     * ⚠️ **Lo que si es una regla NUESTRA y versionada es quitar la puntuacion final**: ningun
     * estandar lo hace, es una decision de contenido, y cambiarla invalida todos los enlaces ya
     * escritos.
     *
     * Ligero a proposito y **no** saca acentos: `publico` y `público` son palabras distintas.
     *
     * ⚠️ **El espacio se enumera a mano y NO se usa `\s`**: en Python `\s` sobre `str` es
     * Unicode y en Java es ASCII, asi que un espacio duro (U+00A0) se colapsaria de un lado y
     * del otro no, y los dos codigos de la misma acepcion quedarian distintos **sin error y sin
     * log**.
     */
    fun foldGloss(gloss: String): String =
        ESPACIO.replace(CaseFolding.fold(toNfc(gloss).trim()), " ").trim(*CIERRE)

    private val ESPACIO = Regex("[ \t\n\r\u000C\u000B]+")
    private val CIERRE = charArrayOf(' ', '.', ';', ':', ',')

    /** Cuantos caracteres hex nombran una acepcion. Espejo de `SENSE_CODE_LENGTH`. */
    const val SENSE_CODE_LENGTH = 12

    /**
     * Nombra una acepcion **sin nombrar un pack**: unico para `(idioma, palabra, acepcion)`.
     *
     * ⚠️ **ESTE ES UN ESPEJO de `payload.sense_code`**, y si los dos calculan distinto los
     * enlaces entre packs apuntan a la nada **sin error y sin log** -- el modo de falla central
     * de este repo. Lo fija el mismo vector en los dos lados: `sense_code(1, "casa")` es
     * `8ec316909e48`.
     *
     * El idioma y la palabra ya estan dentro de [Entry.uid] --`stable_uid(lang, headword, pos,
     * sense_key)`-- asi que alcanza con combinarlo con la glosa. De ahi salen las tres
     * propiedades que se pidieron:
     *
     * 1. **No nombra un pack**, asi que cualquier pack instalado de ese idioma puede resolverlo:
     *    el enlace no muere porque el usuario tenga el nucleo en vez del completo.
     * 2. **El nucleo y el completo lo comparten.** Verificado sobre los packs reales: los 21.534
     *    codigos del nucleo español son identicos en el completo, porque `build_core.py` copia
     *    el uid en vez de recalcularlo (D-175).
     * 3. **Degrada a la palabra**: el codigo es un sufijo del termino, no lo reemplaza, asi que
     *    si ningun pack tiene la acepcion pero alguno tiene la palabra, el enlace sigue sirviendo.
     *
     * ⚠️ **Sobre la glosa CRUDA en NFC y NO sobre `norm()`**, que es el precedente de D-055: si
     * pasara por `norm()`, un bump de `NORM_VERSION` --permitido por D-005 en cualquier momento--
     * cambiaria todos los codigos y romperia cada enlace de cada pack ya construido.
     */
    fun senseCode(uid: Long, gloss: String): String =
        sha256Hex("$uid\u001f${foldGloss(gloss)}".encodeToByteArray()).take(SENSE_CODE_LENGTH)

    /**
     * Descomprime y parsea el payload.
     *
     * @param dictionary el diccionario precargado del pack (`meta.payload_dict`). Tiene que ser
     *   exactamente el que uso el builder: con otro, deflate falla o produce basura.
     */
    fun decode(compressed: ByteArray, dictionary: ByteArray): Body =
        parse(inflateRaw(compressed, dictionary).decodeToString())

    /** Solo lo usa el lado de tests: en produccion los packs se construyen con Python. */
    fun encode(body: Body, dictionary: ByteArray): ByteArray =
        deflateRaw(render(body).encodeToByteArray(), dictionary)

    fun parse(text: String): Body {
        var partOfSpeech: String? = null
        val senses = mutableListOf<MutableSense>()
        val wordTranslations = mutableListOf<String>()
        val forms = mutableListOf<InflectedForm>()

        // La acepcion cuyo ULTIMO ejemplo todavia puede recibir una cita, o null. La pone un
        // `E` y la borra cualquier otra linea: un `C` que no venga pegado a su `E` se descarta
        // en vez de elegirle un ejemplo. Ver [TAG_CITATION].
        var citable: MutableSense? = null

        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            val separator = line.indexOf('\t')
            // Una linea sin tab esta corrupta o es de un formato futuro: se ignora en vez de
            // tirar la entrada completa.
            if (separator != 1) continue
            val value = line.substring(separator + 1)
            if (value.isEmpty()) continue

            if (line[0] == TAG_CITATION) {
                citable?.let { it.examples[it.examples.lastIndex] = it.examples.last().copy(citation = value) }
                citable = null
                continue
            }
            citable = null

            when (line[0]) {
                TAG_PART_OF_SPEECH -> if (partOfSpeech == null) partOfSpeech = value
                TAG_SENSE -> senses.add(MutableSense(value))
                // Un ejemplo o traduccion antes de la primera acepcion no tiene donde colgar.
                TAG_EXAMPLE -> senses.lastOrNull()?.let {
                    it.examples.add(Example(value))
                    citable = it
                }
                TAG_TRANSLATION -> senses.lastOrNull()?.translations?.add(value)
                TAG_SYNONYM -> senses.lastOrNull()?.synonyms?.add(value)
                TAG_ANTONYM -> senses.lastOrNull()?.antonyms?.add(value)
                TAG_RELATED -> senses.lastOrNull()?.related?.add(value)
                // Sin guarda de `senses`: es de la ENTRADA, asi que su posicion en el texto no
                // decide nada. Si decidiera, un `W` mal ubicado se volveria traduccion de
                // acepcion -- la atribucion inventada que este canal existe para evitar.
                TAG_WORD_TRANSLATION -> wordTranslations.add(value)
                // Sin guarda de `senses`, por lo mismo que `W`: describe la ENTRADA.
                TAG_FORM -> {
                    val cut = value.indexOf(FORM_SEPARATOR)
                    // Una linea sin separador se ignora: perder una forma es barato, y lanzar
                    // aqui perderia la entrada entera por una linea mal escrita.
                    if (cut > 0 && cut < value.length - 1) {
                        forms.add(
                            InflectedForm(value.substring(0, cut), value.substring(cut + 1)),
                        )
                    }
                }
                else -> Unit
            }
        }

        return Body(
            partOfSpeech = partOfSpeech,
            wordTranslations = wordTranslations.toList(),
            forms = forms.toList(),
            senses = senses.map {
                Sense(
                    it.gloss,
                    it.examples.toList(),
                    it.translations.toList(),
                    it.synonyms.toList(),
                    it.antonyms.toList(),
                    it.related.toList(),
                )
            },
        )
    }

    /**
     * Vuelve a texto un [Body], para el ida y vuelta de los tests.
     *
     * ⚠️ **NO deduplica las listas, y el lado Python SI, y la asimetria es deliberada.**
     * `payload.render` es el paso por el que pasan todos los packs **al construirse**, asi que
     * ahi la deduplicacion decide que se guarda. Aca se esta LEYENDO un pack que ya existe:
     * alterar en silencio lo que el archivo trae escondería que un pack ajeno viene con la misma
     * palabra dos veces, en vez de dejarlo a la vista. Mostrar lo que el pack dice es la
     * respuesta correcta para un lector.
     */
    fun render(body: Body): String {
        val out = StringBuilder()
        body.partOfSpeech?.let { out.append(TAG_PART_OF_SPEECH).append('\t').append(it).append('\n') }
        for (sense in body.senses) {
            out.append(TAG_SENSE).append('\t').append(sense.gloss).append('\n')
            for (example in sense.examples) {
                out.append(TAG_EXAMPLE).append('\t').append(example.text).append('\n')
                // Pegada a su ejemplo, que es lo que [parse] exige para aceptarla.
                example.citation?.let {
                    out.append(TAG_CITATION).append('\t').append(it).append('\n')
                }
            }
            for (translation in sense.translations) {
                out.append(TAG_TRANSLATION).append('\t').append(translation).append('\n')
            }
            for (synonym in sense.synonyms) {
                out.append(TAG_SYNONYM).append('\t').append(synonym).append('\n')
            }
            for (antonym in sense.antonyms) {
                out.append(TAG_ANTONYM).append('\t').append(antonym).append('\n')
            }
            for (related in sense.related) {
                out.append(TAG_RELATED).append('\t').append(related).append('\n')
            }
        }
        return out.toString()
    }

    private class MutableSense(val gloss: String) {
        val examples = mutableListOf<Example>()
        val translations = mutableListOf<String>()
        val synonyms = mutableListOf<String>()
        val antonyms = mutableListOf<String>()
        val related = mutableListOf<String>()
    }

}
