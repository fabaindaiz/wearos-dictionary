package cl.fadiaz.dictionary.tile

import cl.fadiaz.dictionary.data.Visita
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
internal sealed interface TileContenido {

    /** Las ultimas entradas abiertas, en el orden en que las dejo el ViewModel. */
    data class Filas(val visitas: List<Visita>) : TileContenido

    /** La palabra de hoy. */
    data class Palabra(val visita: Visita) : TileContenido

    /**
     * No hay nada que mostrar, y el tile tiene que decirlo.
     *
     * **Nunca dibujar un tile en blanco**: en el carrusel se ve roto, no vacio. El adaptador pone
     * una invitacion a abrir la app, que ademas es la unica forma de que deje de estar vacio.
     */
    data object Vacio : TileContenido
}

internal object ContenidoDeTiles {

    /**
     * Cuantas filas entran.
     *
     * Coincide con el tope del historial (D-085) y con el presupuesto de pantalla (D-073), pero
     * es un parametro y no una constante enterrada: el reloj del proyecto mide **234 dp y no
     * 192**, y cuando eso se confirme dentro de la app puede entrar una cuarta fila.
     */
    const val FILAS_MAX: Int = 3

    /**
     * Cuantos dias de palabra del dia se precalculan.
     *
     * Siete y no uno porque la cache la escribe la app, y **el tile no puede rellenarla**: no
     * abre el pack. Con un solo dia, el tile queda vacio apenas pasa la medianoche sin que nadie
     * haya abierto la app. Con siete, se sostiene una semana, y ademas son las siete ventanas del
     * `Timeline` que dejan que el renderer cambie de palabra solo, sin un solo despertar.
     */
    const val DIAS_CACHEADOS: Int = 7

    /** Las ultimas entradas abiertas. No reordena: el move-to-front ya lo aplico el ViewModel. */
    fun historial(visitas: List<Visita>, max: Int = FILAS_MAX): TileContenido {
        val visibles = visitas.take(max)
        return if (visibles.isEmpty()) TileContenido.Vacio else TileContenido.Filas(visibles)
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
    fun palabraDelDia(desde: String?, palabras: List<Visita>, hoy: String?): TileContenido {
        if (palabras.isEmpty()) return TileContenido.Vacio
        val inicio = fecha(desde) ?: return TileContenido.Vacio
        val actual = fecha(hoy) ?: return TileContenido.Vacio

        val indice = ChronoUnit.DAYS.between(inicio, actual)
        if (indice < 0 || indice >= palabras.size) return TileContenido.Vacio
        return TileContenido.Palabra(palabras[indice.toInt()])
    }

    /**
     * La fecha [dias] mas adelante, en el mismo formato ISO.
     *
     * Es el inverso de [palabraDelDia]: la app adelanta fechas para llenar la cache, el tile
     * calcula la diferencia para leerla. Vivir en el mismo archivo es lo que hace evidente que
     * los dos tienen que usar la misma aritmetica de calendario.
     */
    fun sumarDias(desde: String?, dias: Int): String? =
        fecha(desde)?.plusDays(dias.toLong())?.toString()

    private fun fecha(texto: String?): LocalDate? {
        if (texto.isNullOrBlank()) return null
        return try {
            LocalDate.parse(texto.trim())
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
