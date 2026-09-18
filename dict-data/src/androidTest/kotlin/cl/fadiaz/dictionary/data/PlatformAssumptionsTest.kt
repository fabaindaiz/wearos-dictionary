package cl.fadiaz.dictionary.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprueba en Android lo que hasta ahora solo estaba verificado en escritorio.
 *
 * Cada test de aca corresponde a una decision cuya columna "Enforced in" decia que el codigo no
 * existia todavia. Son las asunciones sobre las que descansa el formato de pack entero.
 */
class PlatformAssumptionsTest {

    private lateinit var packPath: String
    private var pack: PackFile? = null

    @Before
    fun copiarPackDesdeAssets() {
        // Los assets viven comprimidos dentro del APK; SQLite necesita un archivo real.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val destino = File(instrumentation.targetContext.cacheDir, "toy-es-en.db")
        instrumentation.context.assets.open("toy-es-en.db").use { input ->
            destino.outputStream().use { output -> input.copyTo(output) }
        }
        packPath = destino.absolutePath
    }

    @After
    fun cerrar() {
        pack?.close()
    }

    @Test
    fun elSqliteEmpacadoTraeFts5() {
        // D-002. Es la razon de empacar SQLite propio: FTS5 no esta garantizado en el del
        // sistema. Si esto falla, el formato de pack entero no funciona en este dispositivo.
        conConexion { connection ->
            val opciones = mutableListOf<String>()
            connection.prepare("PRAGMA compile_options").use { statement ->
                while (statement.step()) opciones += statement.getText(0)
            }
            assertTrue(
                "el SQLite empacado no trae FTS5; opciones: $opciones",
                opciones.contains("ENABLE_FTS5"),
            )
            // THREADSAFE=2 es multi-thread, no serialized: por eso cada conexion necesita su
            // dispatcher de un solo hilo. Si algun dia cambiara, esa regla se puede relajar.
            assertTrue(
                "THREADSAFE cambio; revisar la regla de una conexion por dispatcher",
                opciones.contains("THREADSAFE=2"),
            )
        }
    }

    @Test
    fun elPackAbreYValida() {
        val abierto = PackFile.open(packPath)
        pack = abierto
        assertEquals("toy-es-en", abierto.metadata.packId)
        assertEquals(28, abierto.metadata.entryCount)
        assertTrue("el diccionario de payload llego vacio", abierto.payloadDictionary.isNotEmpty())
    }

    @Test
    fun elUidEsUnicoYNoLlevaIndice() {
        // D-055. Dos cosas que solo se pueden comprobar sobre el pack ya construido: que la
        // identidad logica no se repita --un uid duplicado hace que el pack auxiliar le pegue
        // a dos entradas a la vez-- y que NO exista un indice sobre uid, que es lo que hace
        // que la columna cueste ~2% y no ~7%.
        val abierto = PackFile.open(packPath)
        pack = abierto
        val connection = abierto.connection()

        assertEquals(
            "hay uids repetidos",
            abierto.metadata.entryCount,
            contar(connection, "SELECT COUNT(DISTINCT uid) FROM entry"),
        )
        assertEquals(
            "ninguna entrada puede tener uid nulo o no positivo",
            0,
            contar(connection, "SELECT COUNT(*) FROM entry WHERE uid IS NULL OR uid <= 0"),
        )
        assertEquals(
            "aparecio un indice sobre uid: el join es a la hora de abrir una entrada, no al listar",
            0,
            contar(
                connection,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND sql LIKE '%uid%'",
            ),
        )
    }

    @Test
    fun elPrefijoUsaElIndiceDeCobertura() {
        // D-012. Es la afirmacion central del diseno de latencia, y hasta ahora solo estaba
        // verificada con el SQLite de escritorio. Si el plan cambia a un scan de tabla, la
        // busqueda incremental deja de cumplir su presupuesto y nada mas lo notaria.
        val abierto = PackFile.open(packPath)
        pack = abierto
        val plan = explicar(
            abierto.connection(),
            "SELECT id, headword, pos FROM entry WHERE norm >= 'cor' AND norm < 'cos'" +
                " ORDER BY norm, rank LIMIT 30",
        )
        assertTrue("el prefijo no usa el covering index. Plan: $plan",
            plan.contains("COVERING INDEX idx_entry_norm"))
        assertTrue("el prefijo escanea la tabla. Plan: $plan", !plan.contains("SCAN entry"))
    }

    @Test
    fun lasCincoConsultasDevuelvenLoEsperado() {
        val abierto = PackFile.open(packPath)
        pack = abierto
        val connection = abierto.connection()

        assertTrue("prefijo", contar(connection,
            "SELECT COUNT(*) FROM (SELECT id FROM entry WHERE norm >= 'corr' AND norm < 'cors'" +
                " ORDER BY norm, rank LIMIT 30)") > 0)

        assertTrue("forma flexionada", contar(connection,
            "SELECT COUNT(*) FROM form f JOIN entry e ON e.id = f.entry_id" +
                " WHERE f.norm = 'corriendo'") > 0)

        // La inversa DEBE deduplicar: el rango matchea varias claves de la misma entrada
        // ("to", "to run", "to pass") y sin esto sale repetida.
        // La cota se escribe como la calcula PrefixRange.upperBound: incrementando el ultimo
        // code point del prefijo ("run" -> "ruo"), no agregandole una letra.
        assertEquals("la inversa devolvio la entrada repetida", 1, contar(connection,
            "SELECT COUNT(*) FROM entry e WHERE e.id IN" +
                " (SELECT entry_id FROM trans WHERE norm >= 'run' AND norm < 'ruo')"))

        // "kore" es el prefijo fuzzy de "correr", cuya clave es "korer". La cota es "korf":
        // con "koref" el rango excluye justo "korer", porque 'r' > 'f'.
        assertTrue("vecindario tolerante", contar(connection,
            "SELECT COUNT(*) FROM (SELECT id FROM entry WHERE fuzzy >= 'kore' AND fuzzy < 'korf'" +
                " LIMIT 200)") > 0)

        // D-011: fts_def es contentless y su rowid ES entry.id. Si se desalinean, la busqueda
        // de texto libre apunta a entradas equivocadas.
        val rowid = contar(connection,
            "SELECT rowid FROM fts_def WHERE fts_def MATCH '\"rapidamente\"' LIMIT 1")
        val esperado = contar(connection, "SELECT id FROM entry WHERE headword = 'correr'")
        assertEquals("fts_def.rowid no corresponde a entry.id", esperado, rowid)
    }

    private fun conConexion(block: (SQLiteConnection) -> Unit) {
        val connection = BundledSQLiteDriver().open(":memory:")
        try {
            block(connection)
        } finally {
            connection.close()
        }
    }

    private fun explicar(connection: SQLiteConnection, sql: String): String {
        val partes = mutableListOf<String>()
        connection.prepare("EXPLAIN QUERY PLAN $sql").use { statement ->
            while (statement.step()) {
                partes += statement.getText(statement.getColumnCount() - 1)
            }
        }
        return partes.joinToString(" | ")
    }

    private fun contar(connection: SQLiteConnection, sql: String): Int =
        connection.prepare(sql).use { statement ->
            if (statement.step()) statement.getInt(0) else -1
        }
}
