package cl.fadiaz.dictionary.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * El codec de los ajustes, que es donde esto puede corromperse en silencio.
 *
 * Mismo contrato que `VisitaTest`: se guarda como texto en SharedPreferences, asi que el formato
 * es un acuerdo con el disco. Una version vieja tras una actualizacion no puede tumbar la app.
 */
class AjustesTest {

    @Test
    fun loQueSeGuardaSeRecupera() {
        for (escala in EscalaDeTexto.entries) {
            val original = Ajustes(escalaDeTexto = escala)
            assertEquals(original, parsearAjustes(serializarAjustes(original)))
        }
    }

    @Test
    fun unTextoVacioDaLosAjustesDeFabrica() {
        assertEquals(Ajustes(), parsearAjustes(""))
    }

    @Test
    fun unValorQueYaNoExisteCaeAlDeFabrica() {
        // El caso de una escala que se quito en una version nueva. No puede tumbar el arranque.
        assertEquals(Ajustes(), parsearAjustes("escala=ENORME"))
    }

    @Test
    fun basuraSinFormatoNoTumbaNada() {
        assertEquals(Ajustes(), parsearAjustes("=\nsin igual\n\n===="))
    }

    @Test
    fun laEscalaNormalNoCambiaNada() {
        // Si NORMAL no fuera exactamente 1, respetar la escala del sistema (WO-V1) dejaria de
        // ser cierto para quien no toco nada.
        assertEquals(1.0f, EscalaDeTexto.NORMAL.factor)
    }
}
