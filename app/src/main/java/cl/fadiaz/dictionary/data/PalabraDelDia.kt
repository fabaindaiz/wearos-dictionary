package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.EntrySummary

/**
 * Que palabra se muestra hoy.
 *
 * DETERMINISTA POR FECHA, Y POR QUE ESO IMPORTA
 *
 * La misma fecha y el mismo pack dan siempre la misma palabra: cambia sola a medianoche, no hace
 * falta guardar nada, no hay red, no hay WorkManager y por lo tanto **no se paga bateria**. La
 * fecha entra por parametro --nunca un reloj de sistema aca dentro-- y por eso esta politica
 * corre entera en la JVM, dentro del gate (D-072).
 *
 * POR QUE NO ALCANZA CON ELEGIR AL AZAR, MEDIDO
 *
 * Los `id` de `entry` son densos (1..entry_count, verificado en los dos packs reales), asi que
 * un id al azar es una entrada valida y cuesta una lectura por clave primaria. Pero lo que sale
 * es inservible: diez dias consecutivos dieron *Eyaralar, piscigranja, Ynda, desquiciador* en
 * espanol y *Voorschoten, Negerhollands, nonparaxiality* en ingles. Nombres propios y terminos
 * que no conoce nadie.
 *
 * La causa es que `rank` esta **aplastado**: mediana 992 sobre un maximo de 997, y todas las
 * entradas por debajo de 3000. Discrimina solo en la cola --los mejores son "hacer, venir,
 * salir, correr" y "water, woman, take, break"-- y ahi se cuelan nombres propios con paginas
 * ricas, que es lo que `rank` mide en realidad (D-067): `Ivanivka` tiene rank 529.
 *
 * De ahi que no se elija un candidato sino **el mejor de [CANDIDATOS]**, saltando nombres
 * propios. Cuesta [CANDIDATOS] lecturas de una fila por clave primaria --nada, y la pantalla de
 * inicio se arma una vez-- y sobre todo **no necesita un umbral por idioma**: la primera version
 * usaba uno, y estaba medido que el bueno era 912 en espanol y 978 en ingles. Un tercer idioma
 * habria necesitado otro numero a mano, y esa es exactamente la clase de constante que se
 * degrada en silencio cuando nadie la vuelve a medir.
 */
internal object PalabraDelDia {

    /**
     * Cuantos candidatos se prueban antes de quedarse con el mejor.
     *
     * 32 y no 8 porque `rank` esta aplastado: con pocas muestras casi todas caen en la mediana y
     * la palabra del dia vuelve a ser rara. 32 lecturas de una fila cuestan nada y la pantalla de
     * inicio se arma una sola vez.
     */
    const val CANDIDATOS: Int = 32

    /**
     * Los `pos` que nunca son palabra del dia.
     *
     * **Esto ya NO es el mecanismo principal: es defensa en profundidad.** Desde D-111 los packs
     * reales se construyen sin nombres propios, asi que en `es-def-wikc` y `en-def-wikt` este
     * filtro no descarta nada. Se queda igual, y sacarlo seria un error, por tres razones:
     *
     *  - **un pack no se actualiza cuando se actualiza la app.** Los packs son artefactos
     *    aparte, y el reloj puede tener instalado uno construido antes de D-111. Sin el filtro,
     *    la palabra del dia se rompe ahi, en silencio;
     *  - **el pack de juguete conserva nombres propios a proposito** (`sources/toy.py`) y es el
     *    unico fixture vivo de esta rama;
     *  - los afijos y las abreviaturas se van por otra razon y el builder **no** los saca:
     *    "-ito" o "EE. UU." no son palabras que alguien quiera aprender hoy. O sea que este
     *    conjunto no es un subconjunto de la poda del pack.
     *
     * Estan los DOS nombres del mismo concepto --los packs de kaikki dicen `name`, el de juguete
     * dice `proper noun`-- y eso no es redundancia: excluir solo uno deja pasar nombres propios
     * en el otro vocabulario, y un fixture de un solo pack no lo muestra. `verify_pack.py`
     * comprueba los dos por el mismo motivo.
     */
    private val POS_EXCLUIDOS = setOf(
        "name", "proper noun",
        "prefix", "suffix", "abbrev", "num",
    )

    /**
     * La categoria que se prefiere cada dia.
     *
     * Existe por un sesgo MEDIDO sobre el pack real: sin esto, 28 dias seguidos daban **28
     * verbos** en espanol. No es que sobren verbos --el pack es 29,2 % sustantivos contra 28,5 %
     * verbos, casi empatados-- sino que `rank` mide riqueza de pagina (D-067) y en espanol las
     * paginas de verbos son las mas ricas porque traen las conjugaciones. Rotando la categoria,
     * la misma muestra da sustantivo 12, verbo 8, adjetivo 7, adverbio 1.
     *
     * Es una **preferencia, no un filtro**: un pack sin adverbios no puede quedarse sin palabra
     * del dia el dia que toca adverbio.
     */
    private val ROTACION_DE_CATEGORIA = listOf("noun", "verb", "adj", "adv")

    /** Un indice de intento que no colisiona con los de los candidatos (0 hasta `candidatos`). */
    private const val INTENTO_DE_CATEGORIA = -1

    /**
     * La entrada de hoy, o null si el pack esta vacio.
     *
     * Devuelve la de **menor rank** --mas comun-- entre los candidatos que no son nombre propio.
     * Si todos lo fueran, devuelve igual la mejor: un hueco en la pantalla es peor que una
     * palabra rara.
     */
    suspend fun elegir(
        fecha: String,
        packId: String,
        entradas: Int,
        leer: suspend (Long) -> EntrySummary?,
        candidatos: Int = CANDIDATOS,
    ): EntrySummary? {
        if (entradas <= 0) return null

        val categoriaDelDia = ROTACION_DE_CATEGORIA[
            moduloPositivo(
                semilla(fecha, packId, INTENTO_DE_CATEGORIA),
                ROTACION_DE_CATEGORIA.size.toLong(),
            ).toInt(),
        ]

        var mejorDeLaCategoria: EntrySummary? = null
        var mejor: EntrySummary? = null
        var mejorAunqueSeaNombre: EntrySummary? = null
        for (intento in 0 until candidatos) {
            // Los id son densos y arrancan en 1, asi que el modulo cae siempre en una entrada
            // que existe. Si alguna vez dejaran de serlo, `leer` devuelve null y se sigue.
            val id = 1L + moduloPositivo(semilla(fecha, packId, intento), entradas.toLong())
            val candidato = leer(id) ?: continue

            val previoCualquiera = mejorAunqueSeaNombre
            if (previoCualquiera == null || candidato.rank < previoCualquiera.rank) {
                mejorAunqueSeaNombre = candidato
            }
            if (esExcluido(candidato.partOfSpeech)) continue
            // Estricto, no `<=`: con empate gana el primero, y asi el resultado tambien es
            // determinista cuando varios candidatos comparten rank --que con esta distribucion
            // pasa seguido--.
            val previo = mejor
            if (previo == null || candidato.rank < previo.rank) mejor = candidato

            if (candidato.partOfSpeech == categoriaDelDia) {
                val previoDeLaCategoria = mejorDeLaCategoria
                if (previoDeLaCategoria == null || candidato.rank < previoDeLaCategoria.rank) {
                    mejorDeLaCategoria = candidato
                }
            }
        }
        // El orden de las tres redes: la categoria del dia, cualquiera valida, y en ultimo
        // extremo una excluida. Un hueco en la pantalla es peor que las tres.
        return mejorDeLaCategoria ?: mejor ?: mejorAunqueSeaNombre
    }

    private fun esExcluido(pos: String?): Boolean = pos != null && pos in POS_EXCLUIDOS

    /**
     * FNV-1a sobre (fecha, pack, intento) con un mezclador final tipo splitmix64.
     *
     * Escrito a mano y no con `String.hashCode()` ni `MessageDigest`: el primero no garantiza
     * estabilidad entre versiones del runtime --y una palabra del dia que cambia al actualizar
     * Android deja de ser del dia-- y el segundo es una API de plataforma para algo que no
     * necesita ser criptografico.
     */
    private fun semilla(fecha: String, packId: String, intento: Int): Long {
        var h = -0x340d631b7bdddcdbL // offset basis de FNV-1a, 64 bits
        for (caracter in fecha) h = (h xor caracter.code.toLong()) * PRIMO
        h = (h xor SEPARADOR) * PRIMO
        for (caracter in packId) h = (h xor caracter.code.toLong()) * PRIMO
        h = (h xor intento.toLong()) * PRIMO

        // Sin este mezclado, intentos consecutivos dan ids vecinos y el rechazo recorreria una
        // franja contigua del diccionario en vez de muestrearlo entero.
        var z = h
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    private fun moduloPositivo(valor: Long, modulo: Long): Long {
        val resto = valor % modulo
        return if (resto < 0) resto + modulo else resto
    }

    private const val PRIMO = 0x100000001b3L
    private const val SEPARADOR = 0x7CL // '|', para que ("ab","c") y ("a","bc") no colisionen
}
