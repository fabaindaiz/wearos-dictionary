package cl.fadiaz.dictionary.core

/**
 * Reglas de plegado fonetico por idioma, aplicadas en orden sobre la salida de
 * [TextNormalizer.norm].
 *
 * ESTE ARCHIVO TIENE UN ESPEJO: tools/packbuilder/normalize.py
 *
 * Cada pack declara su perfil en `meta.fuzzy_profile`. El orden de las reglas importa y es
 * parte del contrato: "ce" -> "se" tiene que correr antes que "c" -> "k", si no "cerrar"
 * termina en "kerar" y deja de colisionar con "serrar".
 *
 * Son heuristicas ortograficas, no un algoritmo fonetico completo. El objetivo es que las
 * confusiones frecuentes de cada idioma (y los errores tipicos del dictado por voz) caigan
 * en la misma clave, no transcribir pronunciacion.
 */
enum class FuzzyProfile(val id: String, val rules: List<Pair<String, String>>) {

    /** Sin plegado fonetico: solo el colapso de letras repetidas que hace [TextNormalizer.fuzzy]. */
    GENERIC("generic", emptyList()),

    /**
     * Espanol. Cubre seseo (c/z/s), b/v, y/ll, h muda y u muda de que/qui/gue/gui.
     *
     * "ch" se protege con un marcador numerico antes de borrar la h y se restaura al final;
     * si no, "chico" perderia la h y colisionaria con cosas que no corresponden.
     *
     * Limitacion conocida: "mexico" -> "mesiko" y "mejico" -> "mejiko" no colisionan. La x
     * del espanol de Mexico suena como j, pero tratarla asi romperia "examen" -> "esamen",
     * que es el caso mucho mas frecuente. Lo cubre el reordenamiento por distancia de edicion.
     */
    SPANISH(
        "es",
        listOf(
            "ch" to "8",
            "qu" to "k",
            "gue" to "ge",
            "gui" to "gi",
            "h" to "",
            "ll" to "y",
            "v" to "b",
            "z" to "s",
            "ce" to "se",
            "ci" to "si",
            "c" to "k",
            "y" to "i",
            "x" to "s",
            "w" to "b",
            "8" to "ch",
        ),
    ),

    /** Ingles. Digrafos mudos (kn, wr, gh), ph/f, c dura y blanda, x/ks, y/i. */
    ENGLISH(
        "en",
        listOf(
            "ck" to "k",
            "ph" to "f",
            "wh" to "w",
            "kn" to "n",
            "wr" to "r",
            "gh" to "",
            "qu" to "kw",
            "ce" to "se",
            "ci" to "si",
            "cy" to "si",
            "c" to "k",
            "x" to "ks",
            "y" to "i",
        ),
    ),

    /** Aleman. sch/s, v/f, w/v, z/ts y digrafos con h. La ss de la eszett ya la produjo norm. */
    GERMAN(
        "de",
        listOf(
            "sch" to "s",
            "ck" to "k",
            "ph" to "f",
            "th" to "t",
            "dt" to "t",
            "v" to "f",
            "w" to "v",
            "z" to "ts",
            "y" to "i",
        ),
    ),
    ;

    companion object {
        /**
         * Resuelve el perfil declarado en `meta.fuzzy_profile`. Un id desconocido cae en
         * [GENERIC] en vez de fallar: un pack construido con un perfil mas nuevo sigue
         * siendo utilizable, solo pierde tolerancia a errores.
         */
        fun fromId(id: String?): FuzzyProfile =
            entries.firstOrNull { it.id == id } ?: GENERIC
    }
}
