package cl.fadiaz.dictionary.core

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * THE ONLY FILE IN :dict-core THAT USES JVM APIs.
 *
 * Everything else in the module is pure Kotlin. That separation is not cosmetic: it is what makes
 * moving to Kotlin Multiplatform mechanical rather than a rewrite. The conversion would be:
 *
 *   1. apply the kotlin("multiplatform") plugin in dict-core/build.gradle.kts
 *   2. move this file to src/jvmMain/, the rest to src/commonMain/
 *   3. declare these same four functions as `expect` in commonMain
 *      and mark the ones here as `actual`
 *
 * The signatures are written with that in mind: types that exist on every target (String,
 * ByteArray), no streams and no platform types crossing the boundary.
 *
 * ArchitectureTest checks automatically that no other file in the module imports `java.*` or
 * `javax.*`, so the separation does not decay on its own over time.
 *
 * Multiplatform equivalents already verified for when they are needed:
 *   - NFD     -> ktecma262 (pure Kotlin, its own Unicode tables)
 *   - deflate -> KFlate (pure Kotlin, supports a preloaded dictionary, which is what we use)
 *   - sha256  -> kotlincrypto
 */

/**
 * Canonical decomposition (NFD).
 *
 * It is still delegated to the platform on purpose, and that is safe: Java 26's NFD (Unicode 16)
 * was compared against Python 3.9's (Unicode 13) over the 133,730 code points of the pinned
 * repertoire and there are ZERO differences. Unicode's stability policy guarantees that a
 * character's decomposition does not change once it is assigned.
 *
 * Code point classification is another story and is therefore NOT delegated: see
 * [UnicodeRepertoire].
 */
internal fun decomposeToNfd(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)

/**
 * NFC: the **composed** form, the one that fixes a string's identity across sources.
 *
 * It lives here and not loose because of D-017: every JVM API lives in this file. It is delegated
 * to the platform just like NFD, per D-004 -- measured, 0 differences over the 133,730 code points
 * of the pinned repertoire.
 */
internal fun toNfc(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFC)

/**
 * Raw deflate (no zlib header) with a preloaded dictionary.
 *
 * Raw on purpose: the zlib header carries a DICTID that forces the reader to wait for
 * `needsDictionary()`, and with no header both sides pin the input dictionary.
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

/** The inverse of [deflateRaw]. Throws if the stream is truncated or asks for another dictionary. */
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
                    throw DataFormatException("the payload asks for a different dictionary")
                }
                if (inflater.needsInput()) {
                    throw DataFormatException("truncated payload")
                }
            }
            out.write(buffer, 0, produced)
        }
        return out.toByteArray()
    } finally {
        inflater.end()
    }
}

/** SHA-256 in lowercase hexadecimal. */
internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
