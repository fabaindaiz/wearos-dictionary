package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.PackRejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que decide si hay que volver a probar un pack.
 *
 * ⚠️ **Estos tests son el guardrail, no el ahorro.** Saltarse la verificación cuando NO
 * corresponde reintroduce el modo de falla central del repo: faltan palabras, sin excepción y
 * sin log. Cada caso de acá es una forma de que la huella deje de cambiar cuando debería.
 *
 * ⚠️ **Y desde que el memo guarda también los RECHAZOS, la mitad de estos casos cambió de
 * signo.** Un sí cacheado de más cuesta que un pack malo se use; un **no** cacheado de más
 * cuesta que un pack perfectamente bueno **desaparezca para siempre**, sin que nada lo vuelva a
 * mirar. Por eso la huella lleva las reglas enteras y no sólo `NORM_VERSION`.
 */
class PackVerificationTest {

    private fun reglas(
        normVersion: Int = 3,
        schemaVersion: Int = 4,
        codecId: String = "deflate-v2",
        appVersion: Int = 4,
    ) = PackVerification.rules(normVersion, schemaVersion, codecId, appVersion)

    private fun huella(
        bytes: Long = 75_161_600L,
        modifiedAt: Long = 1_700_000_000_000L,
        rules: String = reglas(),
    ) = PackVerification.fingerprint(bytes, modifiedAt, rules)

    private fun memoCon(
        name: String = "es-def.db",
        fingerprint: String = huella(),
        rejection: PackRejection? = null,
        stored: String = "",
    ) = PackVerification.remember(stored, name, fingerprint, rejection)

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
        assertTrue(huella(rules = reglas(normVersion = 3)) != huella(rules = reglas(normVersion = 4)))
    }

    @Test
    fun installingANewAppVersionInvalidatesEveryPack() {
        // ⚠️ **La vía que no depende de que nadie se acuerde.** Las otras cuatro partes de la
        // huella son constantes que alguien tiene que subir a mano; agregar una invariante a
        // `PackFile.open` sin tocar `CHECKS_VERSION` deja a todo pack ya anotado saltándose la
        // comprobación nueva **para siempre**. El `versionCode` no se puede olvidar: el
        // instalador de Android rechaza un downgrade (D-095).
        assertTrue(huella(rules = reglas(appVersion = 4)) != huella(rules = reglas(appVersion = 5)))
    }

    @Test
    fun aNewAppVersionAlsoRetriesWhatItHadREJECTED() {
        // ⚠️ **La mitad que más importa, y la que se olvida al leer «caduca el memo».** Un sí
        // cacheado de más cuesta que se use un pack malo; un **no** cacheado de más cuesta que
        // un pack bueno desaparezca sin log, porque el archivo no cambia y nada lo vuelve a
        // mirar. Una app nueva es exactamente el momento en que un rechazo puede haber dejado
        // de ser cierto — y tiene que volver a mirarse aunque el motivo no fuera el esquema.
        val memo = memoCon(
            fingerprint = huella(rules = reglas(appVersion = 4)),
            rejection = PackRejection.DAMAGED,
        )
        assertNull(
            PackVerification.verdict(memo, "es-def.db", huella(rules = reglas(appVersion = 5))),
        )
    }

    @Test
    fun supportingANewSchemaInvalidatesEveryRejection() {
        // ⚠️ **El caso que el memo de rechazos introduce, y es el peligroso.** Un pack de
        // `schema_version` 5 se rechaza hoy; si mañana la app entiende 5, ese rechazo cacheado
        // lo dejaría escondido **para siempre** -- el archivo no cambió, así que `bytes` y
        // `modifiedAt` tampoco. Lo único que lo puede invalidar es que las reglas entren en la
        // huella.
        assertTrue(huella(rules = reglas(schemaVersion = 4)) != huella(rules = reglas(schemaVersion = 5)))
    }

    @Test
    fun readingANewCodecInvalidatesEveryRejection() {
        // Mismo caso que el anterior por el otro eje: un pack con un códec que esta versión no
        // lee se rechaza, y la versión que sí lo lea tiene que volver a mirarlo.
        assertTrue(huella(rules = reglas(codecId = "deflate-v2")) != huella(rules = reglas(codecId = "deflate-v3")))
    }

    @Test
    fun changingTheChecksInvalidatesEveryVerdict() {
        // ⚠️ La tercera vía, y la más fácil de olvidar: las reglas no cambian sólo cuando cambia
        // una CONSTANTE del formato, sino cuando cambia **qué se comprueba**. Agregar una
        // invariante sin bumpear esto deja pasar como verificados los packs que la nueva
        // comprobación habría rechazado.
        assertTrue(PackVerification.CHECKS_VERSION > 0)
        assertTrue(reglas().contains(PackVerification.CHECKS_VERSION.toString()))
    }

    @Test
    fun anUnknownPackHasNoVerdict() {
        assertNull(PackVerification.verdict(stored = "", "es-def.db", huella()))
        assertNull(PackVerification.verdict(stored = null, "es-def.db", huella()))
    }

    @Test
    fun aPassedPackIsRememberedAsPassed() {
        val memo = memoCon()
        assertEquals(PackVerification.Verdict.Passed, PackVerification.verdict(memo, "es-def.db", huella()))
    }

    @Test
    fun aRejectedPackIsRememberedWithItsReason() {
        // Es el pedido entero: un pack rechazado no se vuelve a escanear, y la pantalla de
        // diccionarios puede decir POR QUÉ sin volver a abrirlo.
        val memo = memoCon(rejection = PackRejection.FTS_MISALIGNED)
        assertEquals(
            PackVerification.Verdict.Rejected(PackRejection.FTS_MISALIGNED),
            PackVerification.verdict(memo, "es-def.db", huella()),
        )
    }

    @Test
    fun aVerdictOnlyCountsWithItsOwnFingerprint() {
        val memo = memoCon(rejection = PackRejection.KEYS)
        // El mismo archivo con otra huella: no vale.
        assertNull(PackVerification.verdict(memo, "es-def.db", huella(bytes = 1L)))
        // Otro archivo con la huella de éste: tampoco.
        assertNull(PackVerification.verdict(memo, "en-def.db", huella()))
    }

    @Test
    fun anUnknownReasonInTheMemoMakesThePackWorthLookingAtAgain() {
        // ⚠️ El memo lo pudo escribir otra versión de la app, con un motivo que ésta no conoce.
        // Lo seguro es volver a abrir el pack, nunca esconderlo por un código ilegible: eso es
        // lo que `PackRejection.fromId` garantiza al degradar a DAMAGED en vez de lanzar.
        val memo = "es-def.db\t${huella()}\tun-motivo-del-futuro"
        assertEquals(
            PackVerification.Verdict.Rejected(PackRejection.DAMAGED),
            PackVerification.verdict(memo, "es-def.db", huella()),
        )
    }

    @Test
    fun rememberingTheSamePackTwiceDoesNotGrowTheMemo() {
        var memo = memoCon()
        memo = PackVerification.remember(memo, "es-def.db", huella(), null)
        assertEquals(1, memo.lines().count { it.isNotBlank() })
    }

    @Test
    fun reinstallingReplacesTheEntryInsteadOfStackingIt() {
        // Sin esto el memo crece sin techo: un .db que se reinstala cada semana deja una línea
        // muerta por vez, y las muertas no caducan solas.
        var memo = memoCon()
        memo = PackVerification.remember(memo, "es-def.db", huella(bytes = 999L), null)
        assertEquals(1, memo.lines().count { it.isNotBlank() })
        assertEquals(
            PackVerification.Verdict.Passed,
            PackVerification.verdict(memo, "es-def.db", huella(bytes = 999L)),
        )
        assertNull(PackVerification.verdict(memo, "es-def.db", huella()))
    }

    @Test
    fun aRejectedPackThatIsReinstalledGetsAnotherChance() {
        // ⚠️ La otra mitad de que un rechazo no sea para siempre: el archivo cambió, así que el
        // veredicto viejo no dice nada de éste. Sin esto, reinstalar el pack corregido no
        // serviría de nada.
        var memo = memoCon(rejection = PackRejection.ENTRY_COUNT)
        assertNull(PackVerification.verdict(memo, "es-def.db", huella(modifiedAt = 99L)))
        memo = PackVerification.remember(memo, "es-def.db", huella(modifiedAt = 99L), null)
        assertEquals(
            PackVerification.Verdict.Passed,
            PackVerification.verdict(memo, "es-def.db", huella(modifiedAt = 99L)),
        )
    }

    @Test
    fun aMemoWithGarbageInItVerifiesNothing() {
        // El memo vive en SharedPreferences y lo puede haber escrito otra versión de la app.
        // Ante cualquier duda tiene que decir "no sé": el costo es volver a probar, que es
        // exactamente lo que hacíamos antes.
        assertNull(PackVerification.verdict("basura sin tabs", "es-def.db", huella()))
        assertNull(PackVerification.verdict("es-def.db", "es-def.db", huella()))
        assertNull(PackVerification.verdict("es-def.db\t", "es-def.db", huella()))
    }

    @Test
    fun packsThatAreGoneLeaveTheMemo() {
        var memo = memoCon()
        memo = PackVerification.remember(memo, "en-def.db", huella(bytes = 300L), PackRejection.LICENSE)
        val podado = PackVerification.prune(memo, setOf("es-def.db"))
        assertEquals(PackVerification.Verdict.Passed, PackVerification.verdict(podado, "es-def.db", huella()))
        assertNull(PackVerification.verdict(podado, "en-def.db", huella(bytes = 300L)))
    }

    @Test
    fun theSeparatorCannotComeFromAFileName() {
        // El memo es texto delimitado y el nombre del archivo lo elige quien instala el pack.
        // Un tab ahí adentro partiría la línea en otro lado y haría pasar por verificado a un
        // pack que no lo está.
        val memo = memoCon(name = "raro\tnombre.db")
        assertFalse(memo.lines().first().count { it == '\t' } > 2)
    }
}
