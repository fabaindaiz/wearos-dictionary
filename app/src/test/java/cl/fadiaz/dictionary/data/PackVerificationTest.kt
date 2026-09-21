package cl.fadiaz.dictionary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que decide si hay que volver a probar un pack.
 *
 * ⚠️ **Estos tests son el guardrail, no el ahorro.** Saltarse la muestra de claves de D-142
 * cuando NO corresponde reintroduce el modo de falla central del repo: faltan palabras, sin
 * excepción y sin log. Cada caso de acá es una forma de que la huella deje de cambiar cuando
 * debería.
 */
class PackVerificationTest {

    private fun huella(
        bytes: Long = 75_161_600L,
        modifiedAt: Long = 1_700_000_000_000L,
        normVersion: Int = 3,
    ) = PackVerification.fingerprint(bytes, modifiedAt, normVersion)

    @Test
    fun aDifferentFileIsADifferentFingerprint() {
        // Reinstalar un pack con otro contenido del mismo nombre es el caso normal: devpack.py
        // sobrescribe el .db en su lugar.
        assertTrue(huella() != huella(bytes = 75_161_601L))
        assertTrue(huella() != huella(modifiedAt = 1_700_000_000_001L))
    }

    @Test
    fun bumpingNormVersionInvalidatesEveryPack() {
        // ⚠️ El caso que no se puede fallar. Si `norm()` cambia, TODO pack ya verificado tiene
        // que volver a probarse: sus claves se calcularon con otras reglas. Un caché que
        // sobreviviera a esto haría exactamente el daño que D-142 existe para evitar.
        assertTrue(huella(normVersion = 3) != huella(normVersion = 4))
    }

    @Test
    fun anUnknownPackIsNotVerified() {
        assertFalse(PackVerification.isVerified(stored = "", "es-def.db", huella()))
        assertFalse(PackVerification.isVerified(stored = null, "es-def.db", huella()))
    }

    @Test
    fun aPackIsVerifiedOnlyWithItsOwnFingerprint() {
        val memo = PackVerification.remember("", "es-def.db", huella())
        assertTrue(PackVerification.isVerified(memo, "es-def.db", huella()))
        // El mismo archivo con otra huella: no vale.
        assertFalse(PackVerification.isVerified(memo, "es-def.db", huella(bytes = 1L)))
        // Otro archivo con la huella de éste: tampoco.
        assertFalse(PackVerification.isVerified(memo, "en-def.db", huella()))
    }

    @Test
    fun rememberingTheSamePackTwiceDoesNotGrowTheMemo() {
        var memo = PackVerification.remember("", "es-def.db", huella())
        memo = PackVerification.remember(memo, "es-def.db", huella())
        assertEquals(1, memo.lines().count { it.isNotBlank() })
    }

    @Test
    fun reinstallingReplacesTheEntryInsteadOfStackingIt() {
        // Sin esto el memo crece sin techo: un .db que se reinstala cada semana deja una línea
        // muerta por vez, y las muertas no caducan solas.
        var memo = PackVerification.remember("", "es-def.db", huella())
        memo = PackVerification.remember(memo, "es-def.db", huella(bytes = 999L))
        assertEquals(1, memo.lines().count { it.isNotBlank() })
        assertTrue(PackVerification.isVerified(memo, "es-def.db", huella(bytes = 999L)))
        assertFalse(PackVerification.isVerified(memo, "es-def.db", huella()))
    }

    @Test
    fun aMemoWithGarbageInItDoesNotVerifyAnything() {
        // El memo vive en SharedPreferences y lo puede haber escrito otra versión de la app.
        // Ante cualquier duda tiene que decir "no verificado": el costo es volver a probar, que
        // es exactamente lo que hacíamos antes.
        assertFalse(PackVerification.isVerified("basura sin tabs", "es-def.db", huella()))
        assertFalse(PackVerification.isVerified("es-def.db", "es-def.db", huella()))
    }

    @Test
    fun packsThatAreGoneLeaveTheMemo() {
        var memo = PackVerification.remember("", "es-def.db", huella())
        memo = PackVerification.remember(memo, "en-def.db", huella(bytes = 300L))
        val podado = PackVerification.prune(memo, setOf("es-def.db"))
        assertTrue(PackVerification.isVerified(podado, "es-def.db", huella()))
        assertFalse(PackVerification.isVerified(podado, "en-def.db", huella(bytes = 300L)))
    }
}
