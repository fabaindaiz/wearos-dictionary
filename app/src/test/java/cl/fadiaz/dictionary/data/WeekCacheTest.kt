package cl.fadiaz.dictionary.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cl.fadiaz.dictionary.core.DiscoverTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * The week of words the tiles read, and the one thing that can go stale about it.
 *
 * It needs Robolectric because the cache is `SharedPreferences`: the rule being tested is about
 * the disk, which is a contract with something outside this process.
 */
@RunWith(RobolectricTestRunner::class)
class WeekCacheTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun visit(headword: String, lang: String? = "es") = Visit(
        packId = "es-full",
        entryId = headword.hashCode().toLong(),
        headword = headword,
        partOfSpeech = "noun",
        lang = lang,
    )

    @Test
    fun `lo que se guarda es lo que se lee`() {
        val semana = listOf(visit("lunes"), visit("martes"))
        PackStore.rememberWeekWords(context, "2026-09-28", semana)
        val (since, palabras) = PackStore.weekWords(context)
        assertEquals("2026-09-28", since)
        assertEquals(semana.map { it.headword }, palabras.map { it.headword })
        assertEquals(listOf("es", "es"), palabras.map { it.lang }, "el idioma viaja al tile")
    }

    @Test
    fun `una semana escrita por OTRO build se descarta`() {
        // ⚠️ **Esto no es una precaución, es un bug que pasó** (2026-09-28). La app sólo reescribe
        // esta caché cuando cambia la fecha o el conjunto de packs — correcto para su CONTENIDO y
        // ciego a su FORMA. Un build que empezó a guardar un campo más por fila —el idioma, para
        // que el tile dijera `interj. · ES` como toda otra fila (D-152)— encontró la caché de ayer
        // todavía válida, la conservó, y dibujó filas sin el campo nuevo. En la superficie que
        // nadie abre a propósito, así que nadie habría conectado la actualización con la etiqueta
        // faltante.
        //
        // Igualdad y no `>=`, como el memo de packs (D-225) y los overrides: sideloadear mueve el
        // número en los dos sentidos.
        PackStore.rememberWeekWords(context, "2026-09-28", listOf(visit("lunes")))
        Shadows.shadowOf(context.packageManager)
            .getInternalMutablePackageInfo(context.packageName)
            .longVersionCode = 999L

        val (since, palabras) = PackStore.weekWords(context)
        assertEquals(null, since, "una semana de otro build no se lee")
        assertTrue(palabras.isEmpty())
    }

    @Test
    fun `the tier the week was drawn in is read back, and expires with the build`() {
        // i-a2f271-96caa4. The marker is the third half of the week's key, so it lives and dies
        // with the week: one from another build is not trusted any more than its words.
        assertEquals(null, PackStore.weekTier(context), "sin marca no hay nivel")
        PackStore.rememberWeekWords(context, "2026-10-26", listOf(visit("lunes")))
        PackStore.rememberWeekTier(context, DiscoverTier.CURIOUS)
        assertEquals(DiscoverTier.CURIOUS, PackStore.weekTier(context))

        Shadows.shadowOf(context.packageManager)
            .getInternalMutablePackageInfo(context.packageName)
            .longVersionCode = 999L
        assertEquals(null, PackStore.weekTier(context), "una marca de otro build no se lee")
    }

    @Test
    fun `sin nada guardado no hay semana, y eso no es un error`() {
        // El estado más probable de todos: la app recién instalada. El tile dibuja su estado
        // vacío, que es lo correcto — nunca uno en blanco, que en el carrusel se lee como roto.
        val (since, palabras) = PackStore.weekWords(context)
        assertEquals(null, since)
        assertTrue(palabras.isEmpty())
    }
}
