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
/**
 * Cómo calculó su `rank` un pack, o sea **qué significa el número** con el que ordena.
 *
 * ⚠️ **Existe porque la fusión entre packs no puede saberlo de otra forma, y eso costaba orden.**
 * La fusión ya es **ordinal** --`Suggestion.score` es la posición dentro del propio pack, así que
 * la escala de `rank` se cancela sola-- y eso la hace inmune a que un pack use 0..1000 y otro
 * 900..1000. Lo que **no** arregla: un pack mal calibrado pone la palabra equivocada en la
 * posición 0, y al interlevar recibe el mismo peso que uno bien calibrado.
 *
 * Medido sobre los packs reales: `es-def-wikc` tiene rank 668..1997 y `es-def-wd` 911..997 --el
 * mismo idioma con fórmulas distintas, porque `sources/wikidata.py` tiene la suya-- y nada se lo
 * decía a la app.
 *
 * Se lee con `meta[...]`: un pack anterior no la trae y por defecto se asume [PAGE_RICHNESS], que
 * es lo que todos eran.
 */
enum class RankBasis(val id: String) {
    /** `rank` sale de la frecuencia de uso real, en escala Zipf. El calibrado bueno. */
    FREQUENCY("frequency-zipf-v1"),

    /**
     * `rank` sale de la riqueza de la página del dump: acepciones, ejemplos, **formas**.
     *
     * Medido: correlaciona **-0,250** con la frecuencia real de uso, donde se esperaría -1,
     * porque un verbo español trae hasta 222 formas y las cuenta todas.
     */
    PAGE_RICHNESS("page-richness-v1"),
    ;

    companion object {
        /** Un id desconocido **no lanza**: un pack más nuevo puede traer una base que no leemos. */
        fun fromId(id: String?): RankBasis = entries.firstOrNull { it.id == id } ?: PAGE_RICHNESS
    }
}

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
    /**
     * El idioma de las traducciones que el pack lleva en su payload, o null si no lleva.
     *
     * ⚠️ **Es una CAPACIDAD, y por eso vive aparte de [kind].** `kind` contesta *«en qué idioma
     * están las definiciones»*; esto contesta *«¿traduce?»*. Son preguntas distintas y el pack
     * español es la prueba: es `MONOLINGUAL` --define en español-- y **traduce al inglés**.
     * Mientras la app preguntaba por `kind`, la acción de traducir no aparecía nunca sobre él.
     *
     * Opcional y leída con `meta[...]`: un pack anterior no la trae y tiene que seguir abriendo.
     */
    val translationsTo: String? = null,
    /** Qué significa el `rank` de este pack. Ver [RankBasis]. */
    val rankBasis: RankBasis = RankBasis.PAGE_RICHNESS,
    /**
     * Los idiomas del pack, **como pares y en orden de declaracion**.
     *
     * ⚠️ **Reemplaza a `langSource`/`langTarget`, y el cambio es conceptual antes que
     * mecanico.** Aquel par decia que un idioma era el de origen y otro el destino, que es
     * cierto de un pack que traduce EN UNA direccion. Un pack bidireccional tiene entradas de
     * los dos --`casa` y `house` en el mismo archivo, cada una con su `entry.lang`-- y ninguno
     * es el principal. Pedido: *«que declares a la par ambos idiomas y no uno como principal»*.
     *
     * Un pack monolingue declara uno solo y nada cambia para el. **El orden es declaracion, no
     * jerarquia**: lo unico que decide es cual usa una entrada que no declare el suyo.
     */
    val langs: List<String>,
    /**
     * El perfil de plegado de cada idioma, **posicional contra [langs]**.
     *
     * Existe porque el plegado tolerante a errores SI depende del idioma --`ce`→`se` es una
     * regla del español-- mientras que `norm()` no. Ver [fuzzyProfileFor].
     */
    val fuzzyProfiles: List<FuzzyProfile>,
    /**
     * Que clase de pack es: `full` o `core`.
     *
     * ⚠️ **Lo DECLARA el artefacto en vez de inferirse del nombre.** Un nucleo se hace a un lado
     * cuando el completo esta instalado, y hasta aca eso salia de `subsetOf` --que nombra al
     * otro pack-- o de que el `pack_id` terminara en `-core`, que es adivinar del nombre lo que
     * D-138 decidio que se declara. `subsetOf` contesta *«soy parte de ESE»* y esto contesta
     * *«soy un nucleo»*, que es lo que hace falta sin conocer al otro.
     */
    val tier: PackTier = PackTier.FULL,
    /**
     * Donde termina la banda de `rank` que tiene senal de frecuencia, si el pack la declara.
     *
     * ⚠️ **Declarado y no copiado, y eso evita un tercer contrato cruzado.** `rank` son dos
     * bandas disjuntas cuando [rankBasis] es frecuencia (D-185); la app necesita el corte --la
     * palabra del dia lo usa-- y la alternativa era copiar el `500` del builder en Kotlin, que
     * es exactamente la clase de constante que se desincroniza en silencio, como `norm()` y
     * `sense_code`. Nulo = el pack no lo dice, y entonces no hay banda que respetar.
     */
    val rankSignalBoundary: Int? = null,
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
        require(langs.isNotEmpty()) { "un pack declara al menos un idioma en meta.langs" }
        require(fuzzyProfiles.size == langs.size) {
            "meta.fuzzy_profiles trae ${fuzzyProfiles.size} perfiles para ${langs.size} idiomas"
        }
        // ⚠️ Un bilingue declara DOS, como pares. Antes exigia `lang_dst`, que presuponia un
        // origen y un destino; ahora lo que se exige es que haya dos y ninguno sea el principal.
        require(kind != PackKind.BILINGUAL || langs.size >= 2) {
            "un pack bilingue declara sus dos idiomas en meta.langs (declara $langs)"
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
    /**
     * Si este lema cae en la banda de `rank` que tiene **frecuencia de uso real** (D-185).
     *
     * ⚠️ **Es un booleano y NO el `rank`, y esa forma es la decisión.** El `rank` crudo no es
     * comparable entre packs --cada uno lo calcula contra su propio volcado con su propia
     * fórmula (D-187)-- así que exponerlo invitaría justo a la comparación que no vale. Cada
     * pack resuelve la señal contra **su** `meta.rank_signal_boundary` (D-198) y lo que cruza la
     * frontera es la respuesta, no la escala.
     *
     * ⚠️ **Existe por un defecto medido en inglés.** `coverageBand` premia los lemas cortos
     * --teclear `wat` cubre `wat` al 100 % y `water` al 60 %-- y el Wiktionary inglés está lleno
     * de fragmentos de tres letras: interjecciones, siglas, formas ligadas. La posición media de
     * la palabra obvia era **5,1** sobre una primera pantalla de tres filas.
     *
     * `false` para un pack que no declara la frontera, que es como se comportaban todos.
     */
    val hasFrequencySignal: Boolean = false,
)

/**
 * Que clase de pack es, de las dos que la app trata distinto.
 *
 * `fromId` no lanza ante un id desconocido: un pack mas nuevo puede traer una clase que esta
 * version no conoce, y tratarlo como completo es la degradacion segura -- se consulta de mas,
 * que es trabajo, no un resultado equivocado.
 */
enum class PackTier(val id: String) {
    FULL("full"),
    CORE("core"),
    ;

    companion object {
        fun fromId(id: String?): PackTier = entries.firstOrNull { it.id == id } ?: FULL
    }
}

/**
 * El perfil de plegado del idioma dado, o el del primero si el pack no lo conoce.
 *
 * ⚠️ **Nunca lanza, y esa es la decision.** Un idioma que el pack no declara es un bug del
 * builder o de quien llama, pero fallar aca dejaria la busqueda muerta; caer al primer perfil
 * devuelve resultados ligeramente peores en el peldaño tolerante, que es el ultimo de la
 * cascada y el menos confiable de todos modos.
 */
fun PackMetadata.fuzzyProfileFor(lang: String?): FuzzyProfile {
    val indice = langs.indexOf(lang)
    return fuzzyProfiles.getOrNull(indice) ?: fuzzyProfiles.firstOrNull() ?: FuzzyProfile.GENERIC
}

/** Si el pack tiene entradas de este idioma. Un pack bidireccional contesta `true` por los dos. */
fun PackMetadata.speaks(lang: String?): Boolean = lang != null && lang in langs

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
    /**
     * El idioma de ESTA entrada, que en un pack bidireccional no es el del pack.
     *
     * ⚠️ **Es lo que hace honesta la etiqueta de la ficha (D-190).** La fila de resultados puede
     * deducir el idioma de la lista --esta filtrada a uno-- pero la ficha no: se llega a ella
     * tocando una traduccion, y entonces la entrada abierta es **del otro idioma**. Sin este
     * campo la etiqueta afirmaria el idioma equivocado, que es peor que no ponerla.
     *
     * Nulo = un pack anterior a `schema_version` 4. No puede pasar --la app rechaza esos packs--
     * pero el tipo lo dice en vez de confiar.
     */
    val lang: String? = null,
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
