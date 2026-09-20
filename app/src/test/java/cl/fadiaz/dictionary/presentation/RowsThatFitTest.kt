package cl.fadiaz.dictionary.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cuantas filas de 48 dp entran en una pantalla, sin hardcodear un reloj.
 *
 * El repo cotizo cinco decisiones contra **192 dp** (D-073, D-075, D-078, D-084, D-085) y el
 * reloj del proyecto entrega **234**. Poner 234 en su lugar seria cambiar un numero equivocado
 * por otro: la app tiene que funcionar en los dos, y en el que venga.
 *
 * ⚠️ **Los dos anclajes salen de una medicion, no de una formula.** `ScreensTest` compone la
 * pantalla a 192 dp y a 234 dp con `@Config(qualifiers)` y cuenta: **2 y 3**. Esta funcion
 * interpola entre esos dos puntos; los tests de abajo los fijan, asi que si alguien cambia el
 * calculo y deja de reproducirlos, falla aca antes de llegar a un reloj.
 */
class RowsThatFitTest {

    @Test
    fun losDosAnclajesMedidos() {
        assertEquals(2, rowsThatFit(192), "el reloj generico")
        assertEquals(3, rowsThatFit(234), "el reloj del proyecto (SM-L715F)")
    }

    @Test
    fun nuncaBajaDeDos() {
        // Una pantalla absurda no puede dejar la lista en una fila --o en cero--: con una sola
        // fila la busqueda deja de ser util, y es mejor que haya que scrollear.
        for (dp in listOf(0, 1, 120, 160)) {
            assertTrue(rowsThatFit(dp) >= 2, "$dp dp dio ${rowsThatFit(dp)}")
        }
    }

    @Test
    fun crecerLaPantallaNuncaQuitaFilas() {
        // La propiedad que hace que esto sea adaptable y no un if: monotona.
        var previo = 0
        for (dp in 120..520 step 2) {
            val filas = rowsThatFit(dp)
            assertTrue(filas >= previo, "$dp dp bajo de $previo a $filas")
            previo = filas
        }
    }

    @Test
    fun elTileBajaEnUnRelojChicoYNoSUBEEnUNOGrande() {
        // La regla del tile (D-131), escrita aca porque es la unica parte testeable sin Context:
        // se topea en el numero MEDIDO y solo puede bajar. El chrome de un tile no esta medido,
        // asi que dejarlo crecer seria afirmar algo que nadie comprobo; bajar en un reloj chico,
        // en cambio, solo puede evitar que se desborde.
        val medido = 3
        assertEquals(2, minOf(rowsThatFit(160), medido), "un reloj chico muestra menos")
        assertEquals(2, minOf(rowsThatFit(192), medido), "el generico, dos")
        assertEquals(3, minOf(rowsThatFit(234), medido), "el del proyecto, el tope medido")
        assertEquals(3, minOf(rowsThatFit(400), medido), "y nunca mas que el tope medido")
    }

    @Test
    fun unaPantallaGrandeNoDaFilasAbsurdas() {
        // Un tope: sin el, una tablet daria veinte filas y la lista dejaria de ser una lista de
        // reloj. Wear OS no pasa de ~250 dp hoy, asi que esto es una red, no un caso real.
        assertTrue(rowsThatFit(520) <= 8, "dio ${rowsThatFit(520)}")
    }
}
