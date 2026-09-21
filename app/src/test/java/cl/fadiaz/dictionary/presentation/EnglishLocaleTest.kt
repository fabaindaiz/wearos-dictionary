package cl.fadiaz.dictionary.presentation

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import cl.fadiaz.dictionary.core.Entry
import cl.fadiaz.dictionary.core.FuzzyProfile
import cl.fadiaz.dictionary.core.PackKind
import cl.fadiaz.dictionary.core.PackMetadata
import cl.fadiaz.dictionary.core.Sense
import cl.fadiaz.dictionary.data.PackHandle
import cl.fadiaz.dictionary.data.Visit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * La base de recursos, que es **inglés**, dibujándose de verdad (D-153).
 *
 * ⚠️ **Existe porque `robolectric.properties` afirmaba una cobertura que no existía.** Ese archivo
 * fija `qualifiers=es` para que los ~40 tests que afirman texto literal sigan describiendo la
 * pantalla que el usuario ve (D-127), y su comentario decía que la base en inglés *"la cubren los
 * pocos tests que fijan `qualifiers = "en"` a mano"*. **No había ninguno.**
 *
 * El riesgo que eso dejaba abierto no es teórico y ya pasó una vez: D-140 encontró **seis textos
 * escritos a mano en el código** —«Guardadas», «Ajustes», «Opciones»…— que en un reloj en inglés
 * salían en español al lado de texto inglés. `check_no_hardcoded_translations` agarra ese caso
 * concreto; esto agarra el otro, que es una clave presente en `values-es/` y **ausente o vacía en
 * `values/`**: la paridad de claves la verifica el audit, pero que el valor inglés sea el que se
 * dibuja sólo lo prueba dibujarlo.
 *
 * Son pocos y sobre las pantallas con más texto, a propósito: duplicar la suite entera en dos
 * idiomas cuesta el doble y no agrega información — lo que puede romperse es el **cableado**, y
 * con una pantalla de cada tipo alcanza para verlo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class EnglishLocaleTest {

    @get:Rule
    val compose = createComposeRule()

    private fun meta() = PackMetadata(
        packId = "es-def-wikc", schemaVersion = 3, normVersion = 1, kind = PackKind.MONOLINGUAL,
        name = "Español", description = null, langSource = "es", langTarget = null,
        fuzzyProfile = FuzzyProfile.SPANISH, entryCount = 1, dataVersion = 1,
        license = "CC-BY-SA-4.0", attribution = "Wikcionario",
    )

    private fun handle() = PackHandle.Open(FakeDictionary(packId = "es-def-wikc", lang = "es"))

    @Test
    fun elInicioSeDibujaEnIngles() {
        compose.setContent {
            SearchScreen(
                state = SearchState(
                    status = SearchState.Status.Ready,
                    active = meta(),
                    available = listOf(handle()),
                    history = List(5) { Visit("es-def-wikc", it.toLong(), "w$it", "noun") },
                ),
                onQueryChange = {}, onSearchDefinitions = {}, onOpenEntry = {},
                onOpenAttribution = {}, onOpenWordOfTheDay = { _, _ -> },
            )
        }
        // Por contentDescription y no por texto: desde D-157 el botón de voz es un micrófono,
        // justamente para no depender del idioma. La cadena sigue siendo la misma y ahora es la
        // que lee un lector de pantalla, que es donde de verdad importa que esté traducida.
        compose.onNodeWithContentDescription("Say a word").assertExists()
        compose.onNodeWithText("Recent").assertExists()
        // El botón de D-148, que es de los últimos textos que se agregaron: si una clave nueva se
        // suma sólo a `values-es/`, acá se ve.
        compose.onNodeWithText("See more").assertExists()
    }

    @Test
    fun laEntradaSeDibujaEnIngles() {
        compose.setContent {
            EntryScreen(1, onOpenWord = {}) {
                Entry(
                    packId = "es-def-wikc", entryId = 1, uid = 1, headword = "guanaco",
                    partOfSpeech = "noun",
                    senses = listOf(
                        Sense(
                            "A South American camelid.",
                            synonyms = listOf("huanaco"),
                            antonyms = listOf("none"),
                            related = listOf("vicuña"),
                        ),
                    ),
                )
            }
        }
        // Las tres categorías de lista, que son lo único que separa "otra forma de decirlo" de
        // "lo contrario" y de "un vecino" (D-126, D-132). Desde que son títulos y no prefijos de
        // cuatro letras, son además tres cadenas nuevas que pueden faltar en un idioma.
        compose.onNodeWithText("Synonyms").assertExists()
        compose.onNodeWithText("Antonyms").assertExists()
        compose.onNodeWithText("Related").assertExists()
    }

    @Test
    fun laAtribucionSeDibujaEnIngles() {
        // La pantalla que existe por la licencia (D-031): si sale en el idioma equivocado sigue
        // cumpliendo, pero es la que más texto tiene y la que menos se mira.
        compose.setContent { AttributionScreen(packs = listOf(handle())) }
        compose.onNodeWithText("About this data").assertExists()
    }
}
