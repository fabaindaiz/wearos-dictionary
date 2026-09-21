package cl.fadiaz.dictionary.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Un pack con las claves mal calculadas se rechaza al abrir (D-142).
 *
 * ⚠️ **Esto cubre el modo de falla central del repo, del lado que faltaba.** `norm_version` es un
 * número que el pack **se pone a sí mismo**: un pack generado por la comunidad puede declarar la
 * versión correcta y haber construido `entry.norm` con otras reglas —otra versión de ICU,
 * minúsculas dependientes del locale, NFC donde va NFD—. El síntoma no es un error: es que
 * **falta una palabra en los resultados**, meses después, sin nada en el stack trace.
 *
 * Hasta ahora eso solo lo cubría `verify_pack.py`, que corre **al construir**. Un pack que no
 * pasó por nuestro builder nunca lo vio.
 *
 * Va en los tests instrumentados y no en el gate porque necesita el SQLite y el ICU **del
 * dispositivo**, que es justo lo que está en discusión: en la JVM del escritorio el bug no se
 * reproduce.
 */
class PackKeySampleTest {

    private fun copiaDelToy(nombre: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val destino = File(instrumentation.targetContext.cacheDir, nombre)
        instrumentation.context.assets.open("toy-es-en.db").use { input ->
            destino.outputStream().use { output -> input.copyTo(output) }
        }
        return destino
    }

    @Test
    fun elPackDeJugueteAbreSinProblemas() {
        // El control. Sin esto, un test que sólo comprueba el rechazo pasaría igual si `open`
        // rechazara TODO.
        PackFile.open(copiaDelToy("claves-ok.db").absolutePath).close()
    }

    @Test
    fun unPackConUnNormMalCalculadoSeRECHAZA() {
        val archivo = copiaDelToy("claves-rotas.db")
        // Se rompe UNA fila, como lo haría una diferencia sutil de normalización: la entrada
        // sigue estando, se ve bien, y simplemente no se encuentra al escribirla.
        BundledSQLiteDriver().open(archivo.absolutePath).use { conexion ->
            conexion.prepare("UPDATE entry SET norm = norm || 'x' WHERE id = 1").use { it.step() }
        }
        try {
            PackFile.open(archivo.absolutePath).close()
            fail("un pack con entry.norm mal calculado tiene que rechazarse al abrir")
        } catch (esperado: PackFile.IncompatibleException) {
            assertTrue(
                "el mensaje tiene que decir qué palabra y qué se esperaba: ${esperado.message}",
                esperado.message.orEmpty().contains("entry.norm"),
            )
        }
    }

    @Test
    fun conVerifyKeysApagadoSeSALTEALaMuestraPeroNoElResto() {
        // El ahorro de arranque: un pack que ya pasó la muestra no la vuelve a pagar mientras el
        // archivo sea el mismo (`PackVerification`, del lado de :app). Acá se comprueba que el
        // interruptor **sólo** apaga la muestra.
        val archivo = copiaDelToy("saltea-la-muestra.db")
        BundledSQLiteDriver().open(archivo.absolutePath).use { conexion ->
            conexion.prepare("UPDATE entry SET norm = norm || 'x' WHERE id = 1").use { it.step() }
        }
        // Con la muestra apagada, esa fila rota ya no se ve: es exactamente el riesgo que se
        // acepta a cambio del arranque, y por eso la huella incluye NORM_VERSION.
        PackFile.open(archivo.absolutePath, verifyKeys = false).close()
    }

    @Test
    fun conVerifyKeysApagadoUnNormVersionDISTINTOSigueRechazandose() {
        // ⚠️ La mitad que no se puede perder. Saltear la muestra no puede volverse "abrir
        // cualquier cosa": las tres validaciones baratas --schema, norm_version, payload_codec--
        // y el sha256 del diccionario siguen corriendo siempre.
        val archivo = copiaDelToy("otra-norm-version.db")
        BundledSQLiteDriver().open(archivo.absolutePath).use { conexion ->
            conexion.prepare("UPDATE meta SET value = '9999' WHERE key = 'norm_version'")
                .use { it.step() }
        }
        try {
            PackFile.open(archivo.absolutePath, verifyKeys = false).close()
            fail("norm_version distinta tiene que rechazarse aunque la muestra esté apagada")
        } catch (esperado: PackFile.IncompatibleException) {
            assertTrue(esperado.message.orEmpty().contains("norm_version"))
        }
    }

    @Test
    fun unPackConUnFuzzyMalCalculadoSeRECHAZA() {
        val archivo = copiaDelToy("fuzzy-roto.db")
        BundledSQLiteDriver().open(archivo.absolutePath).use { conexion ->
            conexion.prepare(
                "UPDATE entry SET fuzzy = fuzzy || 'x' WHERE id = 1 AND fuzzy IS NOT NULL",
            ).use { it.step() }
        }
        try {
            PackFile.open(archivo.absolutePath).close()
            fail("un pack con entry.fuzzy mal calculado tiene que rechazarse al abrir")
        } catch (esperado: PackFile.IncompatibleException) {
            assertTrue(esperado.message.orEmpty().contains("entry.fuzzy"))
        }
    }
}
