package cl.fadiaz.dictionary.data

import androidx.test.platform.app.InstrumentationRegistry
import cl.fadiaz.dictionary.core.MatchKind
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La cascada de cinco consultas, contra el pack de juguete, en un dispositivo real.
 *
 * Los casos salen de `tools/packbuilder/sources/toy.py`, que esta escrito justamente para
 * ejercitar los cinco caminos. Si cambia ese archivo, estos tests son los que avisan.
 */
class SqlitePackSourceTest {

    private lateinit var source: SqlitePackSource

    @Before
    fun abrir() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val destino = File(instrumentation.targetContext.cacheDir, "source-toy.db")
        instrumentation.context.assets.open("toy-es-en.db").use { input ->
            destino.outputStream().use { output -> input.copyTo(output) }
        }
        source = SqlitePackSource(PackFile.open(destino.absolutePath))
    }

    @After
    fun cerrar() {
        source.close()
    }

    @Test
    fun prefijoDelLema() = runTest {
        val resultados = source.suggest("corr")
        val lemas = resultados.map { it.headword }
        assertTrue("no encontro 'correr' con el prefijo 'corr': $lemas", lemas.contains("correr"))
        assertTrue("no encontro 'corriente': $lemas", lemas.contains("corriente"))
        assertTrue(
            "el prefijo deberia resolverse por PREFIX, no por otro nivel",
            resultados.first { it.headword == "correr" }.matchKind == MatchKind.PREFIX,
        )
    }

    @Test
    fun elPrefijoIgnoraAcentosYMayusculas() = runTest {
        // norm() pliega los dos antes de comparar; el headword conserva la forma de display.
        assertTrue(source.suggest("ACCION").any { it.headword == "acción" })
        assertTrue(source.suggest("arbol").any { it.headword == "árbol" })
    }

    @Test
    fun formaFlexionadaLlegaAlLema() = runTest {
        val resultados = source.suggest("corriendo")
        val correr = resultados.firstOrNull { it.headword == "correr" }
        assertNotNull("'corriendo' no llego a 'correr': ${resultados.map { it.headword }}", correr)
        assertEquals(MatchKind.INFLECTED_FORM, correr!!.matchKind)
    }

    @Test
    fun traduccionInversaYSinRepetidos() = runTest {
        val resultados = source.suggest("run")
        val ids = resultados.map { it.entryId }
        assertTrue("'run' no llego a ninguna entrada", ids.isNotEmpty())
        assertEquals("la inversa devolvio entradas repetidas", ids.size, ids.toSet().size)
        assertTrue(resultados.any { it.headword == "correr" })
    }

    @Test
    fun elNivelTolerantePescaUnTipeoQueElPrefijoNoEncuentra() = runTest {
        // "coreer" no es prefijo de nada ni forma de nada: solo lo alcanza el nivel fuzzy.
        val resultados = source.suggest("coreer")
        assertTrue(
            "el nivel tolerante no encontro 'correr' desde 'coreer'",
            resultados.any { it.headword == "correr" },
        )
        assertEquals(MatchKind.FUZZY, resultados.first { it.headword == "correr" }.matchKind)
    }

    @Test
    fun elNivelToleranteNoSeDisparaSiElPrefijoYaEncontroSuficiente() = runTest {
        // "c" da 7 resultados en el pack de juguete, por encima de FUZZY_TRIGGER. Agregar
        // candidatos por distancia de edicion cuando ya hay resultados buenos solo ensucia.
        //
        // El prefijo se eligio contra el contenido real del pack, no al azar: "cor" da 4, que
        // esta POR DEBAJO del umbral, y este test pasaria por el motivo equivocado.
        val resultados = source.suggest("c")
        assertTrue(
            "el pack de juguete deberia dar mas de FUZZY_TRIGGER resultados para 'c'",
            resultados.size >= SqlitePackSource.FUZZY_TRIGGER,
        )
        assertTrue(
            "con un prefijo productivo no deberia haber resultados FUZZY",
            resultados.none { it.matchKind == MatchKind.FUZZY },
        )
    }

    @Test
    fun busquedaDeTextoLibreEnLasDefiniciones() = runTest {
        val resultados = source.searchDefinitions("rapidamente")
        assertTrue(
            "FTS no encontro 'correr' por su definicion: ${resultados.map { it.headword }}",
            resultados.any { it.headword == "correr" },
        )
        assertEquals(MatchKind.DEFINITION, resultados.first().matchKind)
    }

    @Test
    fun elOrdenDeRelevanciaDeFtsNoSePierde() = runTest {
        // FTS5 ordena por bm25 y el resultado se resolvia con `WHERE id IN (...)`, que sale en
        // orden de ROWID: el ranking se calculaba y se tiraba. Es la misma clase de bug que hacia
        // que el prefijo "per" no devolviera "perro".
        //
        // El fixture tiene la trampa: "cantera" menciona "mineral" una vez en una glosa larga y
        // tiene el rowid menor; "cuarzo" lo repite en una corta y tiene el mayor. bm25 premia a
        // "cuarzo"; el rowid, a "cantera".
        val lemas = source.searchDefinitions("mineral").map { it.headword }
        assertTrue("las dos entradas de la trampa tienen que salir: $lemas", lemas.size >= 2)
        assertEquals("bm25 pone 'cuarzo' primero, y ese orden no se puede tirar", "cuarzo", lemas.first())
    }

    @Test
    fun elTextoLibreNoSeRompeConSintaxisDeFts() = runTest {
        // Un usuario escribiendo comillas o un OR no debe hacer fallar la consulta.
        for (entrada in listOf("\"", "correr OR casa", "NEAR(a b)", "*", "a( b")) {
            source.searchDefinitions(entrada) // no debe lanzar
        }
    }

    @Test
    fun abrirUnaEntradaDescomprimeSuPayload() = runTest {
        val sugerencia = source.suggest("correr").first { it.headword == "correr" }
        val entrada = source.entry(sugerencia.entryId)

        assertNotNull("no se pudo abrir la entrada", entrada)
        assertEquals("correr", entrada!!.headword)
        assertEquals("verb", entrada.partOfSpeech)
        assertEquals("correr tiene dos acepciones en el pack de juguete", 2, entrada.senses.size)
        assertTrue(entrada.senses[0].gloss.contains("rapidamente"))
        assertTrue(entrada.senses[0].translations.contains("to run"))
        assertTrue(entrada.senses[0].examples.isNotEmpty())
    }

    @Test
    fun laEntradaTraeSuIdentidadLogicaYNoEsElRowid() = runTest {
        // D-055. El uid es lo que un pack auxiliar va a usar para sumarle informacion a esta
        // misma entrada; si llegara en cero o igual al rowid, la composicion apuntaria mal y
        // no habria ningun error que lo delate.
        val correr = source.entry(source.suggest("correr").first { it.headword == "correr" }.entryId)
        val cosa = source.entry(source.suggest("cosa").first { it.headword == "cosa" }.entryId)

        assertNotNull(correr); assertNotNull(cosa)
        assertTrue("el uid llego vacio", correr!!.uid > 0L)
        assertTrue("el uid coincide con el rowid: no es una identidad propia",
            correr.uid != correr.entryId)
        assertTrue("dos entradas distintas comparten uid", correr.uid != cosa!!.uid)
    }

    @Test
    fun unaEntradaInexistenteDevuelveNull() = runTest {
        assertEquals(null, source.entry(999_999L))
    }

    @Test
    fun laConsultaVaciaNoDevuelveNada() = runTest {
        assertTrue(source.suggest("").isEmpty())
        assertTrue(source.suggest("   ").isEmpty())
        assertTrue(source.suggest("!!!").isEmpty())
    }

    @Test
    fun seRespetaElLimite() = runTest {
        assertEquals(2, source.suggest("c", limit = 2).size)
    }

    @Test
    fun laCoincidenciaExactaEncabezaAunqueRankeePeor() = runTest {
        // "sol" rankea 500 y "soler" rankea 50, asi que por rank solo "soler" iria primero.
        // Escribir una palabra entera y no verla es el peor resultado posible de un buscador.
        // En el pack real este caso esta a escala: "per" enterraba "perro" en la posicion 619.
        val lemas = source.suggest("sol").map { it.headword }
        assertEquals("la coincidencia exacta tiene que encabezar: $lemas", "sol", lemas.first())
        assertTrue("y 'soler' tiene que seguir estando: $lemas", lemas.contains("soler"))
    }

    @Test
    fun elPrefijoOrdenaPorRankYNoAlfabeticamente() = runTest {
        // Con prefijo "c" el pack tiene siete lemas. El orden tiene que ser por rank --menor es
        // mas comun-- y no alfabetico: "correr" (10) antes que "casa" (15) antes que "cazar"
        // (200). Ordenado alfabeticamente, "casa" y "cazar" encabezarian.
        val lemas = source.suggest("c", limit = 7).map { it.headword }
        assertEquals("correr", lemas.first())
        assertTrue("'cazar' es el menos comun y deberia ir ultimo: $lemas", lemas.last() == "cazar")
    }

    @Test
    fun noRepiteElMismoLemaConElMismoPos() = runTest {
        // "vela" esta dos veces como sustantivo, separadas solo por etimologia: son entradas
        // legitimas y uid las distingue, pero la lista no puede mostrar "vela" dos veces. Es el
        // caso "hacer" del Wikcionario, que aparece cinco veces.
        val velas = source.suggest("vela").filter { it.headword == "vela" }
        assertEquals("'vela' tiene que aparecer una sola vez: $velas", 1, velas.size)
    }

    @Test
    fun losHomografosSalenComoEntradasDistintas() = runTest {
        // "bajo" esta dos veces en el pack de juguete, adjetivo y preposicion. La lista tiene
        // que poder distinguirlos, que es para lo que existe la columna pos.
        val bajo = source.suggest("bajo").filter { it.headword == "bajo" }
        assertEquals("los dos homografos deberian salir por separado", 2, bajo.size)
        assertEquals(setOf("adjective", "preposition"), bajo.mapNotNull { it.partOfSpeech }.toSet())
    }
}
