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

    /** Los `pos` que nunca son palabra del dia. Un nombre propio no se "aprende". */
    private val POS_EXCLUIDOS = setOf("name")

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
        }
        return mejor ?: mejorAunqueSeaNombre
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
