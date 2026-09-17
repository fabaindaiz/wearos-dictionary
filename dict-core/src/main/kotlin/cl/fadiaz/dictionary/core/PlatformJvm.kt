package cl.fadiaz.dictionary.core

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * EL UNICO ARCHIVO DE :dict-core QUE USA APIS DE LA JVM.
 *
 * Todo lo demas del modulo es Kotlin puro. Esa separacion no es estetica: es lo que hace que
 * pasar a Kotlin Multiplatform sea mecanico en vez de una reescritura. La conversion seria:
 *
 *   1. aplicar el plugin kotlin("multiplatform") en dict-core/build.gradle.kts
 *   2. mover este archivo a src/jvmMain/, el resto a src/commonMain/
 *   3. declarar estas mismas cuatro funciones como `expect` en commonMain
 *      y marcar las de aca como `actual`
 *
 * Las firmas estan escritas pensando en eso: tipos que existen en todos los targets
 * (String, ByteArray), nada de streams ni de tipos de la plataforma cruzando el limite.
 *
 * ArchitectureTest comprueba automaticamente que ningun otro archivo del modulo importe
 * `java.*` o `javax.*`, para que la separacion no se degrade sola con el tiempo.
 *
 * Equivalentes multiplataforma ya verificados para cuando haga falta:
 *   - NFD     -> ktecma262 (Kotlin puro, tablas Unicode propias)
 *   - deflate -> KFlate (Kotlin puro, soporta diccionario precargado, que es lo que usamos)
 *   - sha256  -> kotlincrypto
 */

/**
 * Descomposicion canonica (NFD).
 *
 * Se sigue delegando en la plataforma a proposito, y es seguro: se comparo la NFD de Java 26
 * (Unicode 16) contra la de Python 3.9 (Unicode 13) sobre los 133.730 code points del
 * repertorio fijado y hay CERO diferencias. La politica de estabilidad de Unicode garantiza
 * que la descomposicion de un caracter no cambia una vez asignado.
 *
 * La clasificacion de code points es otra historia y por eso NO se delega: ver
 * [UnicodeRepertoire].
 */
internal fun decomposeToNfd(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)

/**
 * Deflate crudo (sin encabezado zlib) con diccionario precargado.
 *
 * Crudo a proposito: el encabezado zlib trae un DICTID que obliga al lector a esperar
 * `needsDictionary()`, y sin encabezado los dos lados fijan el diccionario de entrada.
 */
internal fun deflateRaw(data: ByteArray, dictionary: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
    try {
        if (dictionary.isNotEmpty()) deflater.setDictionary(dictionary)
        deflater.setInput(data)
        deflater.finish()

        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer))
        }
        return out.toByteArray()
    } finally {
        deflater.end()
    }
}

/** Inverso de [deflateRaw]. Lanza si el stream esta truncado o pide otro diccionario. */
internal fun inflateRaw(compressed: ByteArray, dictionary: ByteArray): ByteArray {
    val inflater = Inflater(true)
    try {
        if (dictionary.isNotEmpty()) inflater.setDictionary(dictionary)
        inflater.setInput(compressed)

        val out = ByteArrayOutputStream(compressed.size * 4)
        val buffer = ByteArray(8 * 1024)
        while (!inflater.finished()) {
            val produced = inflater.inflate(buffer)
            if (produced == 0) {
                if (inflater.needsDictionary()) {
                    throw DataFormatException("el payload pide un diccionario distinto")
                }
                if (inflater.needsInput()) {
                    throw DataFormatException("payload truncado")
                }
            }
            out.write(buffer, 0, produced)
        }
        return out.toByteArray()
    } finally {
        inflater.end()
    }
}

/** SHA-256 en hexadecimal minuscula. */
internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
