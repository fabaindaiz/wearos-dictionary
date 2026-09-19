package cl.fadiaz.dictionary.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * El codec de los ajustes, que es donde esto puede corromperse en silencio.
 *
 * Mismo contrato que `VisitaTest`: se guarda como texto en SharedPreferences, asi que el formato
 * es un acuerdo con el disco. Una version vieja tras una actualizacion no puede tumbar la app.
 */
class SettingsTest {

    @Test
    fun loQueSeGuardaSeRecupera() {
        for (scale in TextScale.entries) {
            val original = Settings(textScale = scale)
            assertEquals(original, parseSettings(serializeSettings(original)))
        }
    }

    @Test
    fun unTextoVacioDaLosAjustesDeFabrica() {
        assertEquals(Settings(), parseSettings(""))
    }

    @Test
    fun unValorQueYaNoExisteCaeAlDeFabrica() {
        // El caso de una escala que se quito en una version nueva. No puede tumbar el arranque.
        assertEquals(Settings(), parseSettings("escala=ENORME"))
    }

    @Test
    fun basuraSinFormatoNoTumbaNada() {
        assertEquals(Settings(), parseSettings("=\nsin igual\n\n===="))
    }

    @Test
    fun laEscalaNormalNoCambiaNada() {
        // Si NORMAL no fuera exactamente 1, respetar la escala del sistema (WO-V1) dejaria de
        // ser cierto para quien no toco nada.
        assertEquals(1.0f, TextScale.NORMAL.factor)
    }
}
