package cl.fadiaz.dictionary.data

import android.util.Log
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import kotlin.test.assertEquals
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
    fun `el error lleva su excepcion`() {
        ShadowLog.clear()
        val boom = IllegalStateException("pack corrupto")
        DictLog.e(boom) { "no se pudo abrir" }
        val linea = lineas().single()
        assertEquals("no se pudo abrir", linea.msg)
        assertEquals(boom, linea.throwable)
    }
}
