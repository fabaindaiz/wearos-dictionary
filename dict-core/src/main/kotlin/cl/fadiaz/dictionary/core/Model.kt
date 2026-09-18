package cl.fadiaz.dictionary.core

/** Que clase de diccionario trae un pack. Se declara en `meta.kind`. */
enum class PackKind(val id: String) {
    /** Par de idiomas con traducciones: "correr" -> "to run". */
    BILINGUAL("bilingual"),

    /** Un solo idioma con definiciones: "correr" -> "moverse rapidamente...". */
    MONOLINGUAL("monolingual"),
    ;

    companion object {
        fun fromId(id: String): PackKind =
            entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("meta.kind desconocido: $id")
    }
}

/**
 * Contenido de la tabla `meta` de un pack. Se lee completa al abrirlo, una vez.
 *
 * [schemaVersion] y [normVersion] se validan contra lo que soporta la app: un pack con
 * normVersion distinta esta indexado con otras reglas de normalizacion y hay que rechazarlo,
 * porque no fallaria, simplemente devolveria menos resultados de los que corresponde.
 */
data class PackMetadata(
    val packId: String,
    val schemaVersion: Int,
    val normVersion: Int,
    val kind: PackKind,
    val name: String,
    val langSource: String,
    val langTarget: String?,
    val fuzzyProfile: FuzzyProfile,
    val entryCount: Int,
    val dataVersion: Int,
    val license: String,
    val attribution: String,
) {
    init {
        require(kind != PackKind.BILINGUAL || langTarget != null) {
            "un pack bilingue debe declarar meta.lang_dst"
        }
    }
}

/** Como se llego a un resultado. Ordena la lista antes que cualquier otro criterio. */
enum class MatchKind {
    /** El lema empieza con lo escrito. El camino normal. */
    PREFIX,

    /** Coincide una forma flexionada: se escribio "corriendo" y el lema es "correr". */
    INFLECTED_FORM,

    /** Coincide del lado de la traduccion: se escribio "run" en un pack es->en. */
    TRANSLATION,

    /** Coincide la clave tolerante a errores. Solo se intenta si lo anterior no dio nada. */
    FUZZY,

    /** Coincide el texto de la definicion. Solo por accion explicita del usuario. */
    DEFINITION,
}

/**
 * Una fila de la lista de resultados. Se sirve entera desde el indice de cobertura, sin leer
 * ni descomprimir el payload: el cuerpo de la entrada se busca solo cuando el usuario la abre.
 */
data class Suggestion(
    val packId: String,
    val entryId: Long,
    val headword: String,
    val partOfSpeech: String?,
    val matchKind: MatchKind,
    /** Menor es mejor. Distancia de edicion en FUZZY; posicion relativa en el resto. */
    val score: Int,
)

/**
 * La cabecera barata de una entrada: lo que se puede saber **sin descomprimir el payload**.
 *
 * Existe porque hay decisiones que necesitan `rank` y `pos` de una entrada concreta y no su
 * cuerpo --elegir la palabra del dia es la primera--, y abrir el payload para eso seria pagar un
 * inflate por candidato descartado.
 *
 * No es un [Suggestion]: ese es una fila de resultado y lleva `matchKind` y `score`, que aca no
 * significan nada. Y lleva `rank`, que [Suggestion] deliberadamente no expone.
 */
data class EntrySummary(
    val entryId: Long,
    val headword: String,
    val partOfSpeech: String?,
    val rank: Int,
)

/** Una acepcion de una entrada. */
data class Sense(
    val gloss: String,
    val examples: List<String> = emptyList(),
    val translations: List<String> = emptyList(),
)

/** El cuerpo completo de una entrada, tal como sale del payload descomprimido. */
data class Entry(
    val packId: String,
    val entryId: Long,
    /**
     * Identidad **logica** de la entrada: estable entre reconstrucciones del pack, y la clave
     * por la que un pack auxiliar (sinonimos, traducciones) le suma informacion a esta misma
     * entrada.
     *
     * [entryId] no sirve para eso: es el rowid local y se corre entero cuando el pack se
     * reconstruye con datos nuevos.
     *
     * Lo calcula el builder; la app **nunca** lo recalcula, solo lo lee. Esa es la diferencia
     * con `norm`/`fuzzy`, y es lo que evita que sea un segundo contrato entre dos lenguajes.
     *
     * No esta en [Suggestion] a proposito: la lista se sirve entera desde el covering index sin
     * tocar la tabla, y agregar el uid ahi obligaria a leer cada fila. La composicion ocurre al
     * abrir una entrada, no al listarla.
     */
    val uid: Long,
    val headword: String,
    val partOfSpeech: String?,
    val senses: List<Sense>,
)
