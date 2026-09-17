package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.DictionarySource

/**
 * El resultado de intentar abrir un pack.
 *
 * Vive en su propio archivo y **sin una sola referencia a Android** a proposito: es la frontera
 * por la que `SearchViewModel` se deja testear en la JVM, en milisegundos y dentro del gate.
 * Si alguna vez aparece un `Context` aca, esos tests se van al dispositivo con el.
 *
 * Los tres casos no son defensivos de mas: cada uno se ve distinto en pantalla y el usuario
 * puede hacer algo distinto con cada uno.
 */
sealed interface PackLoad {

    /** Hay diccionario. */
    data class Ready(val source: DictionarySource) : PackLoad

    /** No hay pack instalado ni asset del que sacarlo: el APK se armo sin diccionario. */
    data object NoPack : PackLoad

    /**
     * Hay un archivo y no sirve. El mensaje va a la pantalla, en español.
     *
     * Es importante que sea un caso aparte de [NoPack]: un pack de otra `schema_version` o de
     * otra `norm_version` devolveria MENOS resultados de los que tiene, sin ningun error
     * (D-001, D-006). Abrirlo igual seria peor que no abrirlo.
     */
    data class Unusable(val reason: String) : PackLoad
}
