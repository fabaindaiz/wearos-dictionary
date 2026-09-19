package cl.fadiaz.dictionary.tile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.material3.materialScope
import cl.fadiaz.dictionary.data.Visita
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertNotNull

/**
 * Que los layouts de los dos tiles **se construyan**.
 *
 * `TileContenidoTest` prueba la decision --que fila se muestra, que dia toca, cuando no mostrar
 * nada-- en la JVM y dentro del gate. Lo que no puede probar es el paso siguiente: que esa
 * decision se convierta en un arbol de protolayout sin tirar. Un builder de protolayout que
 * rechaza algo lo hace **en tiempo de ejecucion**, y un tile que tira dentro de `onTileRequest`
 * se ve en el carrusel como un cuadro vacio, sin un error que alguien vaya a leer.
 *
 * Es instrumentado y **no entra al gate**, igual que `PantallasTest`: `materialScope` necesita un
 * Context y los recursos de Android.
 *
 * Se probo `androidx.wear.tiles:tiles-testing:1.6.2`, que existe, y **se descarto**: arrastra
 * Robolectric 4.16.1, y meter un runner nuevo en un gate de segundos es un cambio mucho mas
 * grande que lo que compra. Con un Context de verdad alcanza para lo que hay que comprobar.
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

    private fun visita(lema: String, id: Long = 1) =
        Visita(packId = "es-def-wikc", entryId = id, headword = lema, partOfSpeech = "noun")

    @Test
    fun elHistorialLlenoSeDibuja() {
        val layout = materialScope(context, dispositivo) {
            filasDeHistorial(context, listOf(visita("perro"), visita("gato", 2), visita("sol", 3)))
        }
        assertNotNull(layout)
    }

    @Test
    fun unHistorialDeUnaSolaFilaSeDibuja() {
        val layout = materialScope(context, dispositivo) {
            filasDeHistorial(context, listOf(visita("perro")))
        }
        assertNotNull(layout)
    }

    @Test
    fun laPalabraDelDiaSeDibuja() {
        val layout = materialScope(context, dispositivo) {
            tarjetaDePalabra(context, visita("corriente"), "sustantivo")
        }
        assertNotNull(layout)
    }

    @Test
    fun unaPalabraSinCategoriaSeDibuja() {
        // `partOfSpeech` es nullable en Visita y el pack real trae entradas sin pos.
        val layout = materialScope(context, dispositivo) {
            tarjetaDePalabra(context, visita("corriente"), null)
        }
        assertNotNull(layout)
    }

    @Test
    fun elEstadoVacioSeDibuja() {
        // El caso mas probable de todos: app recien instalada. Un tile en blanco en el carrusel
        // no se lee como "vacio" sino como "roto".
        val layout = materialScope(context, dispositivo) { tileVacio(context, "Buscá una palabra") }
        assertNotNull(layout)
    }

    @Test
    fun unLemaLarguisimoNoRompeElLayout() {
        // Los lemas salen del Wikcionario y hay refranes enteros como lema.
        val largo = visita("mas corre el galgo que el mastin pero no en cuesta arriba")
        val layout = materialScope(context, dispositivo) {
            filasDeHistorial(context, listOf(largo, largo, largo))
        }
        assertNotNull(layout)
    }
}
