package cl.fadiaz.dictionary.data

import android.util.Log
import cl.fadiaz.dictionary.core.MatchKind
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Lo que hay que probar de una fachada de logs no es que escriba, es **que no cueste cuando esta
 * apagada**.
 *
 * El modo de fallo real no es un log que falta: es `Log.d(TAG, "resultados=" + lista.joinToString())`
 * armando el String en cada pulsacion de tecla en produccion, donde nadie lo lee. Por eso los
 * mensajes son lambdas, y por eso el test central es el del contador.
 */
@RunWith(RobolectricTestRunner::class)
class DictLogTest {

    private fun lineas() = ShadowLog.getLogsForTag(DictLog.TAG)

    @Test
    fun `INFO se ve por defecto, sin tocar ninguna propiedad`() {
        ShadowLog.clear()
        DictLog.i { "packs abiertos: 3" }
        assertEquals(1, lineas().size, "INFO tendria que verse sin setprop: ${lineas()}")
        assertEquals("packs abiertos: 3", lineas().single().msg)
    }

    @Test
    fun `DEBUG esta apagado por defecto`() {
        ShadowLog.clear()
        DictLog.d { "no deberia aparecer" }
        assertTrue(lineas().isEmpty(), "DEBUG tendria que estar apagado: ${lineas()}")
    }

    @Test
    fun `DEBUG se enciende desde afuera, como lo hara setprop`() {
        ShadowLog.clear()
        ShadowLog.setLoggable(DictLog.TAG, Log.DEBUG)
        try {
            DictLog.d { "ahora si" }
            assertEquals(listOf("ahora si"), lineas().map { it.msg })
        } finally {
            ShadowLog.setLoggable(DictLog.TAG, Log.INFO)
        }
    }

    @Test
    fun `con el log apagado el mensaje NO SE CONSTRUYE`() {
        // ⚠️ Este es el test que paga el archivo. Si un dia alguien cambia `d { "..." }` por
        // `d("...")`, este test lo caza y los otros tres no.
        ShadowLog.clear()
        var veces = 0
        DictLog.d { veces++; "carisimo" }
        assertEquals(0, veces, "el lambda se evaluo con el log apagado")

        ShadowLog.setLoggable(DictLog.TAG, Log.DEBUG)
        try {
            DictLog.d { veces++; "ahora si" }
            assertEquals(1, veces, "con el log encendido el lambda tiene que evaluarse una vez")
        } finally {
            ShadowLog.setLoggable(DictLog.TAG, Log.INFO)
        }
    }

    @Test
    fun `un pack que falla se escribe AUNQUE el detalle este apagado`() {
        // ⚠️ El test central del otro lado. `SearchRepository.recolectar` se traga la excepcion a
        // proposito, asi que este WARN es lo unico que distingue "pack roto" de "pack vacio". Si
        // dependiera de `setprop`, seguiria invisible en el caso normal.
        ShadowLog.clear()
        assertFalse(LogSearchTrace.enabled, "el detalle tendria que estar apagado por defecto")
        LogSearchTrace.packFailed("es-def-wikc", "database disk image is malformed")
        val linea = lineas().single()
        assertEquals(Log.WARN, linea.type)
        assertTrue(linea.msg.contains("es-def-wikc"), linea.msg)
        assertTrue(linea.msg.contains("malformed"), linea.msg)
    }

    @Test
    fun `el resumen por consulta NO se escribe con el detalle apagado`() {
        ShadowLog.clear()
        LogSearchTrace.searched("cas", mapOf(MatchKind.PREFIX to 2), fallback = false, returned = 2)
        assertTrue(lineas().isEmpty(), "no deberia escribirse sin setprop: ${lineas()}")
    }

    @Test
    fun `los peldanos salen en el orden de la cascada, no del mapa`() {
        ShadowLog.clear()
        ShadowLog.setLoggable(DictLog.TAG, Log.DEBUG)
        try {
            assertTrue(LogSearchTrace.enabled)
            // A proposito en el orden INVERSO al de la cascada: lo que se lee tiene que salir
            // ordenado igual siempre, o comparar dos lineas no sirve de nada.
            LogSearchTrace.searched(
                query = "cas",
                byKind = linkedMapOf(
                    MatchKind.FUZZY to 1,
                    MatchKind.TRANSLATION to 4,
                    MatchKind.PREFIX to 2,
                ),
                fallback = true,
                returned = 7,
            )
            val msg = lineas().single().msg
            assertEquals(
                "buscar 'cas' -> 7 (prefix=2 translation=4 fuzzy=1) RESPALDO",
                msg,
            )
        } finally {
            ShadowLog.setLoggable(DictLog.TAG, Log.INFO)
        }
    }

    @Test
    fun `el error lleva su excepcion`() {
        ShadowLog.clear()
        val boom = IllegalStateException("pack corrupto")
        DictLog.e(boom) { "no se pudo abrir" }
        val linea = lineas().single()
        assertEquals("no se pudo abrir", linea.msg)
        assertEquals(boom, linea.throwable)
    }
}
