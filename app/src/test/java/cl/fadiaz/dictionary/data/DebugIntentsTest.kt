package cl.fadiaz.dictionary.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El volcado de estado que se pide por `adb`.
 *
 * ⚠️ **Lo que se prueba es que el volcado DIGA lo que hace falta para contestar una pregunta**,
 * no que tenga tal formato. Cada aserto de acá corresponde a algo que esta sesión no pudo
 * comprobar en un dispositivo y tuvo que inferir o leer con `run-as`:
 *
 * - que el memo de verificación caducó al subir de `versionCode` (D-225),
 * - qué packs abrieron y con qué `data_version` (D-226),
 * - cuáles se rechazaron y por qué.
 *
 * Es puro y sin Android a propósito, así que entra al gate (D-072). El receiver en sí necesita un
 * dispositivo y no se prueba acá.
 */
class DebugIntentsTest {

    private fun volcado(
        opened: List<String> = listOf("es-core@202609231356"),
        active: String? = "es-core",
        rejected: List<String> = emptyList(),
        memo: String? = null,
        appVersion: Int = 5,
    ) = DebugIntents.dump(opened, active, rejected, memo, appVersion)

    @Test
    fun `el volcado lleva la version de la app, que es la que caduca el memo`() {
        // Sin este número no se puede comprobar D-225 en un dispositivo: la huella del memo
        // termina en `.aN` y hay que poder contrastar ese N con el APK que está corriendo.
        assertTrue(volcado(appVersion = 7).any { "versionCode=7" in it })
    }

    @Test
    fun `cada anotacion del memo va en su propia linea, entera`() {
        // ⚠️ **`logcat` corta los mensajes largos**, y un volcado truncado es peor que ninguno
        // porque parece completo. Con una anotación por línea, el corte se ve.
        val memo = "es-core.db\t50843648:1790170471792:n2.s4.deflate-v2.c2.a5\tok\n" +
            "en-core.db\t42856448:1790170471020:n2.s4.deflate-v2.c2.a5\tok"
        val lineas = volcado(memo = memo)
        assertTrue(lineas.any { "2 anotacion" in it })
        // La huella ENTERA: abreviarla dejaría incomprobable justo lo que se vino a comprobar.
        assertTrue(lineas.any { it.contains("n2.s4.deflate-v2.c2.a5") && it.contains("es-core.db") })
        assertTrue(lineas.any { it.contains("en-core.db") })
    }

    @Test
    fun `un memo vacio se dice, en vez de no aparecer`() {
        // La ausencia de una línea no distingue "el memo está vacío" de "el volcado no lo mira".
        // Es la misma razón por la que el brief de una sesión escribe `nada` en vez de omitir.
        assertTrue(volcado(memo = null).any { "0 anotacion" in it })
        assertTrue(volcado(memo = "\n\n").any { "0 anotacion" in it })
    }

    @Test
    fun `sin pack activo lo dice con palabras`() {
        // `activo=null` impreso tal cual se lee como un bug del volcado.
        assertTrue(volcado(active = null).any { "activo=(ninguno)" in it })
    }

    @Test
    fun `los rechazados salen con su motivo, que es la explicacion entera de un idioma que falta`() {
        val lineas = volcado(rejected = listOf("viejo.db:schema", "roto.db:damaged"))
        assertTrue(lineas.any { "rechazados=2" in it && "viejo.db:schema" in it })
    }

    @Test
    fun `el volcado se abre y se cierra, para poder recortarlo del logcat`() {
        val lineas = volcado()
        assertEquals("--- volcado ---", lineas.first())
        assertEquals("--- fin ---", lineas.last())
    }
}
