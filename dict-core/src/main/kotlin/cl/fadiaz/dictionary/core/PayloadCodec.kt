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

    /** El cuerpo decodificado, sin los datos que ya vienen en las columnas de `entry`. */
    data class Body(val partOfSpeech: String?, val senses: List<Sense>)

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

        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            val separator = line.indexOf('\t')
            // Una linea sin tab esta corrupta o es de un formato futuro: se ignora en vez de
            // tirar la entrada completa.
            if (separator != 1) continue
            val value = line.substring(separator + 1)
            if (value.isEmpty()) continue

            when (line[0]) {
                TAG_PART_OF_SPEECH -> if (partOfSpeech == null) partOfSpeech = value
                TAG_SENSE -> senses.add(MutableSense(value))
                // Un ejemplo o traduccion antes de la primera acepcion no tiene donde colgar.
                TAG_EXAMPLE -> senses.lastOrNull()?.examples?.add(value)
                TAG_TRANSLATION -> senses.lastOrNull()?.translations?.add(value)
                TAG_SYNONYM -> senses.lastOrNull()?.synonyms?.add(value)
                else -> Unit
            }
        }

        return Body(
            partOfSpeech = partOfSpeech,
            senses = senses.map {
                Sense(
                    it.gloss,
                    it.examples.toList(),
                    it.translations.toList(),
                    it.synonyms.toList(),
                )
            },
        )
    }

    fun render(body: Body): String {
        val out = StringBuilder()
        body.partOfSpeech?.let { out.append(TAG_PART_OF_SPEECH).append('\t').append(it).append('\n') }
        for (sense in body.senses) {
            out.append(TAG_SENSE).append('\t').append(sense.gloss).append('\n')
            for (example in sense.examples) {
                out.append(TAG_EXAMPLE).append('\t').append(example).append('\n')
            }
            for (translation in sense.translations) {
                out.append(TAG_TRANSLATION).append('\t').append(translation).append('\n')
            }
            for (synonym in sense.synonyms) {
                out.append(TAG_SYNONYM).append('\t').append(synonym).append('\n')
            }
        }
        return out.toString()
    }

    private class MutableSense(val gloss: String) {
        val examples = mutableListOf<String>()
        val translations = mutableListOf<String>()
        val synonyms = mutableListOf<String>()
    }

}
