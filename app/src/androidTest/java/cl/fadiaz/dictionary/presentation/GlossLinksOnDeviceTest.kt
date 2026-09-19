package cl.fadiaz.dictionary.presentation

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import cl.fadiaz.dictionary.core.Entry
import cl.fadiaz.dictionary.core.Sense
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lo unico de `PantallasTest` que NO se puede correr en la JVM.
 *
 * Las otras 46 pantallas corren con Robolectric dentro del gate, en segundos. Esta no: tocar una
 * palabra dentro de una glosa exige que el toque caiga sobre el **rectangulo de una palabra
 * dentro de un parrafo**, y eso depende del layout de texto real. Bajo Robolectric el nodo del
 * enlace existe y se encuentra, el click se despacha, y el callback **no se dispara** --el test
 * falla con `expected:<77> but was:<null>`--. No es un bug del producto: es la medida exacta de
 * hasta donde llega el runtime simulado.
 *
 * Si algun dia Robolectric resuelve hit-testing de texto, este archivo se funde de vuelta.
 */
@RunWith(AndroidJUnit4::class)
class GlossLinksOnDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private fun entry(glosa: String) = Entry(
        packId = "test",
        entryId = 1,
        uid = 1,
        headword = "perro",
        partOfSpeech = "noun",
        senses = listOf(Sense(glosa)),
    )

    @Test
    fun tappingAKnownWordInTheGlossOpensItsEntry() {
        var abierta: Long? = null
        compose.setContent {
            EntryScreen(
                entryId = 1,
                onOpenPalabra = { abierta = it },
                resolveIn = { mapOf("cera" to 77L) },
            ) { entry("cilindro de cera con mecha") }
        }
        compose.waitForIdle()

        // El nodo del enlace NO tiene semantica de texto propia --Compose le pone solo OnClick
        // sobre el rectangulo de la palabra-- asi que se identifica por el texto que lo contiene.
        compose.onNode(
            hasClickAction() and hasAnyAncestor(hasText("cilindro de cera", substring = true)),
            useUnmergedTree = true,
        ).performClick()

        assertEquals(77L, abierta)
    }
}
