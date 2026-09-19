package cl.fadiaz.dictionary.tile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.material3.materialScope
import cl.fadiaz.dictionary.data.Visit
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertNotNull

/**
 * That both tiles' layouts **get built**.
 *
 * `TileContentTest` proves the decision --which row shows, which day it is, when to show
 * nothing-- on the JVM and inside the gate. What it cannot prove is the next step: that the
 * decision turns into a protolayout tree without throwing. A protolayout builder that rejects
 * something does so **at runtime**, and a tile that throws inside `onTileRequest` looks in the
 * carousel like an empty square, with no error anyone is going to read.
 *
 * It is instrumented and **does not enter the gate**, same as `ScreensTest`: `materialScope`
 * needs a Context and Android resources.
 *
 * `androidx.wear.tiles:tiles-testing:1.6.2` was tried, it exists, and **was discarded**: it drags
 * in Robolectric 4.16.1, and putting a new runner into a gate that runs in seconds is a far
 * bigger change than what it buys. A real Context is enough for what has to be checked.
 */
@RunWith(AndroidJUnit4::class)
class TilesTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val dispositivo = DeviceParametersBuilders.DeviceParameters.Builder()
        .setScreenWidthDp(234)
        .setScreenHeightDp(234)
        .setScreenDensity(3.4f)
        .setScreenShape(DeviceParametersBuilders.SCREEN_SHAPE_ROUND)
        .build()

    private fun visit(headword: String, id: Long = 1) =
        Visit(packId = "es-def-wikc", entryId = id, headword = headword, partOfSpeech = "noun")

    @Test
    fun aFullHistoryRenders() {
        val layout = materialScope(context, dispositivo) {
            historyRows(context, listOf(visit("perro"), visit("gato", 2), visit("sol", 3)))
        }
        assertNotNull(layout)
    }

    @Test
    fun aSingleRowHistoryRenders() {
        val layout = materialScope(context, dispositivo) {
            historyRows(context, listOf(visit("perro")))
        }
        assertNotNull(layout)
    }

    @Test
    fun theWordOfTheDayRenders() {
        val layout = materialScope(context, dispositivo) {
            wordCard(context, visit("corriente"), "sustantivo")
        }
        assertNotNull(layout)
    }

    @Test
    fun aWordWithoutAPartOfSpeechRenders() {
        // `partOfSpeech` is nullable in Visit and the real pack carries entries with no pos.
        val layout = materialScope(context, dispositivo) {
            wordCard(context, visit("corriente"), null)
        }
        assertNotNull(layout)
    }

    @Test
    fun theEmptyStateRenders() {
        // The most likely case of all: a freshly installed app. A blank tile in the carousel
        // does not read as "empty" but as "broken".
        val layout = materialScope(context, dispositivo) { emptyTile(context, "Busca una palabra") }
        assertNotNull(layout)
    }

    @Test
    fun aVeryLongHeadwordDoesNotBreakTheLayout() {
        // Headwords come from Wiktionary and there are whole sayings used as headwords.
        val length = visit("mas corre el galgo que el mastin pero no en cuesta arriba")
        val layout = materialScope(context, dispositivo) {
            historyRows(context, listOf(length, length, length))
        }
        assertNotNull(layout)
    }
}
