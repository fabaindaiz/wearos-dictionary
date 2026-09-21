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
    /**
     * El nombre CORTO, para una fila de reloj. "Español", no "Español - definiciones".
     *
     * Medido a ojo sobre el reloj: en la fila de la pantalla de diccionarios, despues del check
     * reservado, los paddings y el boton de borrar, al nombre le quedan ~140 dp. "Español -
     * definiciones" son 22 caracteres y se cortaba en los cuatro lugares donde se muestra. Lo
     * que el nombre largo decia --que trae definiciones-- ahora sale de [kind], que es un dato
     * y no una cadena que hay que leer.
     */
    val name: String,
    /**
     * El texto largo, para la pantalla de atribucion. Null en un pack anterior a D-125.
     *
     * Opcional a proposito: se lee con `meta[...]` y no con `getValue`, asi que un pack viejo
     * sigue abriendo. El formato no tiene migraciones (D-001) pero eso aplica a
     * `schema_version`; una clave nueva y aditiva es justo lo que la tolerancia existe para
     * soportar.
     */
    val description: String?,
    val langSource: String,
    val langTarget: String?,
    val fuzzyProfile: FuzzyProfile,
    val entryCount: Int,
    /**
     * Que build del pack es esto: `AAAAMMDDHHMM`, del reloj del build.
     *
     * ⚠️ **`Long` y no `Int`, y no es un detalle de estilo**: `202609211432` pasa el tope de un
     * `Int` de 32 bits, asi que con `Int` el pack revienta al ABRIR en el reloj con un
     * NumberFormatException que no nombra la clave (D-070). Hay un test del builder que lo fija
     * desde el otro lado.
     *
     * **No es la fecha del dump**, que es `meta.source_date` y es informativa. Esto ordena: un
     * instalador compara dos numeros para saber cual pack es mas nuevo, y dos builds del mismo
     * dump tienen que dar numeros distintos o un pack mejor no se propaga.
     */
    val dataVersion: Long,
    /**
     * El `pack_id` del diccionario que **contiene a éste**, o null.
     *
     * Es una afirmación de CONTENIDO, no de tamaño ni de versión: *«todo lo que yo tengo, ése lo
     * tiene»*. Eso es justo lo que no se puede deducir en el reloj —comparar 150.000 lemas
     * costaría más que la búsqueda— y por eso se declara, igual que `pack_id` declara la
     * identidad en vez de adivinarla del nombre del archivo (D-138).
     *
     * ⚠️ **`entry_count` NO sirve para esto.** Dice cuál es más grande, que es otra cosa: dos
     * packs de fuentes distintas pueden ser los dos grandes sin que ninguno contenga al otro, y
     * ahí consultarlos a los dos es exactamente lo que se quiere (D-136).
     *
     * Null en cualquier pack anterior a esto, que es el caso de todos los de hoy.
     */
    val subsetOf: String? = null,
    val license: String,
    val attribution: String,
    /**
     * The sources this pack declares, each with **its own** licence (D-138).
     *
     * ⚠️ **[license] and [attribution] stay, and they are not redundant.** They are the
     * collection's governing licence and its one-paragraph credit, which is what a pack built
     * before this field has and all a small screen can show at a glance. This list is the
     * itemised version: with definitions under CC BY-SA 4.0 and corpus sentences under CC BY
     * 2.0 FR, one name for the whole pack either over-claims or under-credits.
     *
     * Empty for a pack built before D-138, and that is handled rather than rejected: it reads
     * from `meta["sources"]`, not `getValue`, so an older pack still opens (same tolerance as
     * `description` in D-125).
     */
    val sources: List<PackSource> = emptyList(),
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
    /**
     * Sinonimos de ESTA acepcion, no de la entrada (D-117).
     *
     * La distincion importa: "domingo" tiene `mesada, paga` en una acepcion y `pollerudo,
     * calzonazos` en otra. Juntos no significan nada.
     *
     * **Los dos idiomas los traen** (D-124). El español los declara con `sense_index` y el
     * ingles los sirve anidados dentro de cada acepcion; las dos formas dan la misma atribucion.
     *
     * Va ultimo a proposito: los diez call sites existentes son posicionales de tres argumentos
     * o menos, y asi compilan sin tocarse.
     */
    val synonyms: List<String> = emptyList(),
    /**
     * Antonimos de ESTA acepcion (D-126).
     *
     * Misma regla que [synonyms] y **peor consecuencia si se atribuye mal**: un sinonimo en la
     * acepcion equivocada se lee como raro, un antonimo se lee como lo contrario de otra cosa.
     *
     * A diferencia de los sinonimos, **no entran a `fts_def`**: buscar "frio" para encontrar
     * "caliente" no es lo que nadie hace, y meterlos al indice de texto libre solo agregaria
     * ruido a una busqueda que ya tiene el orden como deuda abierta (D-067).
     */
    val antonyms: List<String> = emptyList(),
    /**
     * Palabras **relacionadas** de esta acepcion: hiperonimo, hiponimo o pariente morfologico
     * (D-132). No son sinonimos y la lista separada es toda la diferencia: "frances" trae `galo`,
     * que no es equivalente sino vecino, y presentarlo como sinonimo seria afirmar algo falso.
     *
     * Existen por las **entradas flacas**, que son el 70,4 % del pack español: una acepcion sola
     * sin ejemplo. Medido, 2.142 de 29.817 flacas ganan algo por aca (7,2 %).
     *
     * **Solo vienen llenas cuando la entrada tiene una sola acepcion**, porque la fuente las
     * declara a nivel de entrada y sin `sense_index`: con varias no hay dato de a cual pertenecen.
     * Ver `sources/kaikki._relacionadas` en el builder.
     *
     * Tampoco entran a `fts_def`, por la misma razon que los antonimos: nadie busca "camelido"
     * esperando "guanaco", y el orden de resultados ya es deuda abierta (D-067).
     */
    val related: List<String> = emptyList(),
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
    /**
     * Traducciones de la palabra entera, que la fuente **no** pudo atribuir a una acepcion.
     *
     * Van aparte de [Sense.translations] y no mezcladas: una lista dibujada bajo una acepcion
     * **afirma** que pertenece a esa acepcion, y colgar ahi lo no atribuido es el error de
     * D-117 --se lee plausible y no lo agarra nadie. Medido, son el 37,7 % del dato.
     */
    val wordTranslations: List<String> = emptyList(),
)
