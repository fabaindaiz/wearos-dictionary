package cl.fadiaz.dictionary.presentation

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gap that pushes the first item clear of the clock, as a fraction of the screen (D-133).
 *
 * The second axis along which this app adapts to resolution, after [rowsThatFit]. It is not a
 * design constant: **Wear Compose Material3 declares the vertical content padding as 10 % of the
 * screen**, read straight out of `compose-material3-1.6.2.aar`
 * (`PaddingDefaults.verticalContentPaddingPercentage = 10.0f`, alongside the horizontal 5.2 %).
 *
 * ⚠️ **The 20 dp that used to be hardcoded here IS 10 % of 192 dp**, which is the width this repo
 * historically assumed. So this is not a change of intent: the number always was a fraction, only
 * frozen against the wrong screen. The first test below is what proves that claim, and it is the
 * reason this refactor is safe to do without eyes on a watch.
 */
class ClockGapTest {

    @Test
    fun laConstanteVieja_ERA_el_diez_por_ciento_de_192() {
        // The load-bearing test. If this stops holding, the percentage story is false and the
        // whole function has to go back to being a hand-picked number.
        assertEquals(20.dp, clockGap(192), "20 dp era el 10 % de la pantalla asumida")
    }

    @Test
    fun elRelojDelProyectoRecibeUnPocoMas() {
        // 234 dp * 10 % = 23,4 -> 24. Bigger screen, proportionally bigger gap, and it errs in
        // the direction that was asked for ("mover el escribir un poco para abajo").
        assertEquals(24.dp, clockGap(234), "el reloj del proyecto (SM-L715F)")
    }

    @Test
    fun crecerLaPantallaNuncaQuitaEspacio() {
        // Monotone, same property that makes rowsThatFit adaptable instead of a chain of ifs.
        var previo = 0.dp
        for (dp in 120..520 step 2) {
            val gap = clockGap(dp)
            assertTrue(gap >= previo, "$dp dp bajo de $previo a $gap")
            previo = gap
        }
    }

    @Test
    fun unaPantallaAbsurdaNoBorraElEspacioNiSeLoComeTodo() {
        // Floor: without it a tiny or mis-reported screen would put the text field under the
        // clock, which is the bug this gap exists to fix. Ceiling: a 10 % gap on a big screen
        // would waste the only scarce resource a watch has.
        assertTrue(clockGap(0) >= 16.dp, "dio ${clockGap(0)}")
        assertTrue(clockGap(1) >= 16.dp, "dio ${clockGap(1)}")
        assertTrue(clockGap(520) <= 32.dp, "dio ${clockGap(520)}")
    }
}
