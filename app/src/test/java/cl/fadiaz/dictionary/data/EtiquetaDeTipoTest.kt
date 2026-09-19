package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.PackKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La etiqueta que reemplaza a la mitad larga del nombre del pack (D-125).
 *
 * El test que importa no es la traduccion de cada caso sino que **haya uno por cada `PackKind`**:
 * el dia que aparezca un tercer tipo --un pack de sinonimos aparte, por ejemplo-- el `when`
 * exhaustivo no compila, pero si alguien lo "arregla" con un `else` el pack nuevo se anuncia con
 * la etiqueta equivocada y nada falla.
 */
class EtiquetaDeTipoTest {

    @Test
    fun cadaTipoTieneSuEtiqueta() {
        assertEquals("definiciones", etiquetaDeTipo(PackKind.MONOLINGUAL))
        assertEquals("traducción", etiquetaDeTipo(PackKind.BILINGUAL))
    }

    @Test
    fun ningunTipoSeQuedaSinEtiqueta() {
        for (kind in PackKind.entries) {
            assertTrue(etiquetaDeTipo(kind).isNotBlank(), "sin etiqueta: $kind")
        }
    }

    @Test
    fun lasEtiquetasSonDistintasEntreSi() {
        // Dos tipos con la misma etiqueta no distinguen nada, que es todo el punto de mostrarla.
        val etiquetas = PackKind.entries.map { etiquetaDeTipo(it) }
        assertEquals(etiquetas.size, etiquetas.toSet().size, "hay etiquetas repetidas: $etiquetas")
    }

    @Test
    fun entranEnUnaFilaDeReloj() {
        // El motivo por el que el nombre se acorto. Al detalle de la fila le quedan ~140 dp
        // despues del check y el boton de borrar; una etiqueta larga repite el problema.
        for (kind in PackKind.entries) {
            assertTrue(etiquetaDeTipo(kind).length <= 14, "etiqueta muy larga: ${etiquetaDeTipo(kind)}")
        }
    }
}
