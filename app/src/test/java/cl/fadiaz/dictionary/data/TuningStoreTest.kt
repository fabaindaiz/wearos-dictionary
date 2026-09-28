package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.SearchRepository
import cl.fadiaz.dictionary.core.SearchTuning
import cl.fadiaz.dictionary.core.Tuning
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `assets/tuning.json` is the file somebody edits by hand, so everything here is about a file
 * written **badly**: that is the only failure mode a configuration file really has.
 */
@RunWith(RobolectricTestRunner::class)
class TuningStoreTest {

    @Test
    fun `los defaults del data class son los mismos numeros que las constantes`() {
        // ⚠️ **Son dos copias de los mismos números y no pueden dejar de serlo.** `SearchTuning`
        // vive en `:dict-core` y no puede leer `:dict-data`, donde están las constantes: la
        // dependencia corre al revés. Así que la única forma de que no se separen en silencio es
        // compararlas, y el día que alguien mueva una de las dos esto lo dice.
        val d = SearchTuning()
        assertEquals(SqlitePackSource.FUZZY_TRIGGER, d.fuzzyTrigger)
        assertEquals(SqlitePackSource.PREFIX_OVERFETCH, d.prefixOverfetch)
        assertEquals(SqlitePackSource.FUZZY_CANDIDATES, d.fuzzyCandidates)
        assertEquals(SearchRepository.DEFAULT_LIMIT, d.limit)
    }

    @Test
    fun `el asset que se despacha se lee y dice lo que dice`() {
        // ⚠️ **Esto lee el archivo REAL, no uno de prueba**, y es la mitad que importa: un JSON de
        // fixture demuestra que el parser anda y no demuestra que lo que viaja en el APK sea
        // legible. Un `tuning.json` con una coma de más degrada a los valores por defecto sin que
        // nada en pantalla cambie — la app anda, con otros números, para siempre.
        val leido = TuningStore.parse(File("src/main/assets/${TuningStore.ASSET}").readText())
        assertEquals(
            listOf("ind1s", "ind3s", "pret1s", "pret3s", "sub1s", "part"),
            leido.display.forms,
        )
        assertEquals(6, leido.display.forms.size, "se pidieron seis formas, no diez")
        assertEquals(30, leido.search.limit)
        assertEquals(200, leido.search.fuzzyCandidates)
    }

    @Test
    fun `las claves que empiezan con guion bajo son comentarios y no molestan`() {
        // JSON no tiene comentarios y el archivo se edita a mano. Que `_` no rompa nada es lo que
        // permite dejar escrito al lado de un valor qué significa.
        val leido = TuningStore.parse(
            """{"_": "una nota", "search": {"_": "otra", "limit": 7}}""",
        )
        assertEquals(7, leido.search.limit)
    }

    @Test
    fun `un archivo ilegible no es un numero cero, es el default`() {
        // Lo que NO puede pasar: que un archivo roto deje la búsqueda devolviendo nada. Un
        // `limit` de 0 no lanza ninguna excepción — devuelve cero resultados, para siempre, sin
        // un error en ningún lado. Por eso los no positivos caen al default en vez de obedecerse.
        assertEquals(Tuning().search.limit, TuningStore.parse("""{"search": {"limit": 0}}""").search.limit)
        assertEquals(Tuning().search.limit, TuningStore.parse("""{"search": {"limit": -3}}""").search.limit)
        assertEquals(
            Tuning().display.glossLineHeightInFonts,
            TuningStore.parse("""{"display": {"glossLineHeightInFonts": 0}}""").display.glossLineHeightInFonts,
        )
    }

    @Test
    fun `una lista de formas vacia se lee como un error y no como una orden`() {
        // ⚠️ La asimetría es deliberada: `"forms": []` parece un typo mucho más seguido que
        // *no muestres ninguna forma*, y obedecerlo deja una sección que nunca dibuja sin que
        // nada lo explique. Quien de verdad no quiera ninguna, borra la sección del código.
        assertEquals(Tuning().display.forms, TuningStore.parse("""{"display": {"forms": []}}""").display.forms)
    }

    @Test
    fun `una clave ausente no arrastra a las demas`() {
        // Cada campo cae por su cuenta. Si un `display` mal escrito se llevara puesto al `search`
        // de al lado, cambiar una cosa rompería otra sin decirlo.
        val leido = TuningStore.parse("""{"display": {"visibleSenses": 1}}""")
        assertEquals(1, leido.display.visibleSenses)
        assertEquals(Tuning().display.forms, leido.display.forms)
        assertEquals(Tuning().search, leido.search)
    }

    @Test
    fun `las claves de forma que se despachan son claves que la ficha sabe dibujar`() {
        // ⚠️ **Una clave mal escrita no falla: no dibuja.** `ind1S` en vez de `ind1s` deja la fila
        // afuera y la sección se ve más corta, que es indistinguible de un verbo al que le faltan
        // formas en el pack. Esta lista es la de `formTypeLabel`.
        val conocidas = setOf(
            "ger", "part", "ind1s", "ind2s", "ind3s", "ind1p", "ind3p",
            "pret1s", "pret3s", "sub1s", "past", "pl", "fem",
        )
        val leido = TuningStore.parse(File("src/main/assets/${TuningStore.ASSET}").readText())
        val desconocidas = leido.display.forms - conocidas
        assertTrue(desconocidas.isEmpty(), "el asset pide formas que nadie sabe etiquetar: $desconocidas")
    }
}
