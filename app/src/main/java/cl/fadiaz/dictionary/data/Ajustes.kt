package cl.fadiaz.dictionary.data

/**
 * Cuanto se agranda el texto **por encima** de la escala del sistema.
 *
 * Por encima y no en lugar de: la lista de calidad de Wear OS (WO-V1) exige que la app respete
 * el tamano de fuente del sistema, asi que esto multiplica sobre esa escala, nunca la reemplaza.
 * Quien ya subio la letra en todo el reloj la sigue viendo subida.
 *
 * Dos valores y no un slider continuo: un continuo no se puede testear contra el presupuesto de
 * densidad (D-073), que define cuantas filas entran en pantalla. Con dos, el test de densidad se
 * parametriza y sigue significando algo.
 */
enum class EscalaDeTexto(val factor: Float) {
    NORMAL(1.0f),
    GRANDE(1.15f),
}

/** Lo que el usuario eligio. Sin Android: la politica se testea en la JVM (D-072). */
data class Ajustes(
    val escalaDeTexto: EscalaDeTexto = EscalaDeTexto.NORMAL,
)

private const val CLAVE_ESCALA = "escala"

internal fun serializarAjustes(ajustes: Ajustes): String =
    "$CLAVE_ESCALA=${ajustes.escalaDeTexto.name}"

/**
 * Lee los ajustes guardados, **cayendo a los de fabrica** en vez de fallar.
 *
 * Mismo criterio que [parsearVisitas]: esto se lee al arrancar, y un formato viejo tras una
 * actualizacion o un byte corrupto no pueden impedir que la app abra. Perder una preferencia es
 * aceptable; no arrancar, no.
 */
internal fun parsearAjustes(texto: String): Ajustes {
    val valores = texto.lineSequence()
        .mapNotNull { linea ->
            val corte = linea.indexOf('=')
            if (corte <= 0) null else linea.substring(0, corte) to linea.substring(corte + 1)
        }
        .toMap()
    val escala = valores[CLAVE_ESCALA]
        ?.let { nombre -> EscalaDeTexto.entries.firstOrNull { it.name == nombre } }
        ?: EscalaDeTexto.NORMAL
    return Ajustes(escalaDeTexto = escala)
}
