package cl.fadiaz.dictionary.tile

import cl.fadiaz.dictionary.data.Visit
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * Lo que un tile tiene para dibujar, ya decidido.
 *
 * Existe para que la decision viva sin Android y entre al gate (D-072): cada `TileService` queda
 * siendo un adaptador que traduce esto a protolayout, y todo lo que puede salir mal --una cache
 * vencida, el reloj corrido, un texto corrupto-- se prueba en la JVM en milisegundos.
 *
 * Reusa [Visita] para las dos superficies, igual que ya lo hacen las palabras guardadas (D-102):
 * son la misma forma de dato --`packId`, `entryId`, lema y categoria-- y un segundo tipo seria un
 * segundo formato que puede divergir del que ya esta en disco.
 */
internal sealed interface TileContent {

    /** Las ultimas entradas abiertas, en el orden en que las dejo el ViewModel. */
    data class ListRows(val visits: List<Visit>) : TileContent

    /** La palabra de hoy. */
    data class Word(val visit: Visit) : TileContent

    /**
     * No hay nada que mostrar, y el tile tiene que decirlo.
     *
     * **Nunca dibujar un tile en blanco**: en el carrusel se ve roto, no vacio. El adaptador pone
     * una invitacion a abrir la app, que ademas es la unica forma de que deje de estar vacio.
     */
    data object Empty : TileContent
}

internal object TileContents {

    /**
     * Cuantas filas entran.
     *
     * Coincide con el tope del historial (D-085) y con el presupuesto de pantalla (D-073), pero
     * es un parametro y no una constante enterrada: el reloj del proyecto mide **234 dp y no
     * 192**, y cuando eso se confirme dentro de la app puede entrar una cuarta fila.
     */
    const val MAX_ROWS: Int = 3

    /**
     * Cuantos dias de palabra del dia se precalculan.
     *
     * Siete y no uno porque la cache la escribe la app, y **el tile no puede rellenarla**: no
     * abre el pack. Con un solo dia, el tile queda vacio apenas pasa la medianoche sin que nadie
     * haya abierto la app. Con siete, se sostiene una semana, y ademas son las siete ventanas del
     * `Timeline` que dejan que el renderer cambie de palabra solo, sin un solo despertar.
     */
    const val CACHED_DAYS: Int = 7

    /** Las ultimas entradas abiertas. No reordena: el move-to-front ya lo aplico el ViewModel. */
    fun history(visits: List<Visit>, max: Int = MAX_ROWS): TileContent {
        val visibleOnes = visits.take(max)
        return if (visibleOnes.isEmpty()) TileContent.Empty else TileContent.ListRows(visibleOnes)
    }

    /**
     * La palabra que le toca a [hoy], de las que la app dejo cacheadas desde [desde].
     *
     * **Fuera de rango devuelve [TileContenido.Vacio] y eso es el punto**, no una guarda
     * defensiva: si la app no se abre en mas de [DIAS_CACHEADOS] dias la cache se queda corta, y
     * seguir mostrando la ultima seria una "palabra del dia" equivocada todos los dias, en una
     * superficie que nadie abre a proposito y por lo tanto **donde nadie lo reportaria**.
     *
     * Las fechas son las mismas cadenas ISO que usa la app (`LocalDate.toString()`), y si alguna
     * no se puede leer se descarta en vez de tirar: esto se lee de SharedPreferences, que es un
     * contrato con el disco, y corre en el hilo principal.
     */
    fun wordOfTheDay(since: String?, words: List<Visit>, today: String?): TileContent {
        if (words.isEmpty()) return TileContent.Empty
        val start = date(since) ?: return TileContent.Empty
        val current = date(today) ?: return TileContent.Empty

        val index = ChronoUnit.DAYS.between(start, current)
        if (index < 0 || index >= words.size) return TileContent.Empty
        return TileContent.Word(words[index.toInt()])
    }

    /**
     * La fecha [dias] mas adelante, en el mismo formato ISO.
     *
     * Es el inverso de [palabraDelDia]: la app adelanta fechas para llenar la cache, el tile
     * calcula la diferencia para leerla. Vivir en el mismo archivo es lo que hace evidente que
     * los dos tienen que usar la misma aritmetica de calendario.
     */
    fun plusDays(since: String?, days: Int): String? =
        date(since)?.plusDays(days.toLong())?.toString()

    private fun date(text: String?): LocalDate? {
        if (text.isNullOrBlank()) return null
        return try {
            LocalDate.parse(text.trim())
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
