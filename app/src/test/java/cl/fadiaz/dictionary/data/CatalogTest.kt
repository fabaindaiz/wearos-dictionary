package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.FuzzyProfile
import cl.fadiaz.dictionary.core.PackKind
import cl.fadiaz.dictionary.core.PackMetadata
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El catalogo de descarga: repartir lo publicado contra lo instalado.
 *
 * ⚠️ El aserto que paga el archivo es el de **INCOMPATIBLE por igualdad**. `PackFile.open` rechaza
 * un `schema_version` distinto --mayor o menor-- y si aca se comparara con `>=` la pantalla
 * ofreceria packs que la app no abre, despues de que el usuario pague 192 MB de Wi-Fi.
 *
 * Corre con Robolectric porque [Catalog.parse] usa `org.json`, que en un test JVM plano es un stub.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogTest {

    private fun ofrecido(
        id: String,
        version: Long = 200L,
        schema: Int = 4,
        norm: Int = 2,
    ) = CatalogPack(
        packId = id, name = id, description = null, langs = listOf("es"), entryCount = 100,
        dataVersion = version, schemaVersion = schema, normVersion = norm, license = null,
        url = "packs/$id.db.gz", bytes = 10, sha256 = "a", dbBytes = 20, dbSha256 = "b",
    )

    private fun instalado(id: String, version: Long) = PackMetadata(
        packId = id, schemaVersion = 4, normVersion = 2, kind = PackKind.MONOLINGUAL,
        name = id, description = null, langs = listOf("es"),
        fuzzyProfiles = listOf(FuzzyProfile.SPANISH), entryCount = 100, dataVersion = version,
        license = "CC-BY-SA-4.0", attribution = id,
    )

    @Test
    fun `lo que no esta instalado se ofrece para DESCARGAR`() {
        val r = Catalog.classify(listOf(ofrecido("es-def")), installed = emptyList())
        assertEquals(1, r.size)
        assertEquals(CatalogStatus.DOWNLOAD, r.single().status)
        assertNull(r.single().installedVersion, "no hay version local que reportar")
    }

    @Test
    fun `una data_version mayor es ACTUALIZAR, y dice cual hay`() {
        val r = Catalog.classify(
            listOf(ofrecido("es-def", version = 300L)),
            installed = listOf(instalado("es-def", 200L)),
        )
        assertEquals(CatalogStatus.UPDATE, r.single().status)
        assertEquals(200L, r.single().installedVersion)
    }

    @Test
    fun `la misma version es INSTALADO y no se ofrece`() {
        val r = Catalog.classify(
            listOf(ofrecido("es-def", version = 200L)),
            installed = listOf(instalado("es-def", 200L)),
        )
        assertEquals(CatalogStatus.INSTALLED, r.single().status)
    }

    @Test
    fun `un pack local MAS NUEVO que el catalogo no es una actualizacion`() {
        // Pasa en desarrollo todo el tiempo: se construye un pack y el servidor sirve el viejo.
        // Ofrecer "actualizar" a una version anterior seria un downgrade disfrazado.
        val r = Catalog.classify(
            listOf(ofrecido("es-def", version = 100L)),
            installed = listOf(instalado("es-def", 999L)),
        )
        assertEquals(CatalogStatus.INSTALLED, r.single().status)
    }

    @Test
    fun `un schema distinto es INCOMPATIBLE, por IGUALDAD y no por mayor-o-igual`() {
        val viejo = Catalog.classify(listOf(ofrecido("v", schema = 3)), emptyList())
        val nuevo = Catalog.classify(listOf(ofrecido("n", schema = 5)), emptyList())
        assertEquals(CatalogStatus.INCOMPATIBLE, viejo.single().status)
        assertEquals(
            CatalogStatus.INCOMPATIBLE,
            nuevo.single().status,
            "un schema MAYOR tampoco se abre: PackFile.open compara por igualdad",
        )
    }

    @Test
    fun `un norm_version distinto tambien es INCOMPATIBLE`() {
        val r = Catalog.classify(listOf(ofrecido("x", norm = 1)), emptyList())
        assertEquals(CatalogStatus.INCOMPATIBLE, r.single().status)
    }

    @Test
    fun `INCOMPATIBLE gana sobre ACTUALIZAR`() {
        // Un pack instalado cuya version nueva cambio de schema: no se puede ofrecer.
        val r = Catalog.classify(
            listOf(ofrecido("es-def", version = 300L, schema = 5)),
            installed = listOf(instalado("es-def", 200L)),
        )
        assertEquals(CatalogStatus.INCOMPATIBLE, r.single().status)
    }

    @Test
    fun `parsea el indice que produce packserver`() {
        val json = """
            {"catalog_version":1,"packs":[
              {"pack_id":"es-def-wikc","name":"Espanol","description":"d","langs":["es"],
               "entry_count":209484,"data_version":202609211937,"schema_version":4,
               "norm_version":2,"license":"CC-BY-SA-4.0","url":"packs/es-def-wikc.db.gz",
               "bytes":37000000,"sha256":"aa","db_bytes":73600000,"db_sha256":"bb"}]}
        """.trimIndent()
        val packs = Catalog.parse(json)
        assertEquals(1, packs.size)
        val p = packs.single()
        assertEquals("es-def-wikc", p.packId)
        assertEquals(202609211937L, p.dataVersion)
        assertEquals(listOf("es"), p.langs)
        assertEquals(73600000L, p.dbBytes)
    }

    @Test
    fun `una entrada rota se SALTA y las buenas sobreviven`() {
        // Un catalogo de seis packs con uno mal escrito tiene que ofrecer los cinco buenos.
        val json = """
            {"packs":[
              {"pack_id":"sin-hash","url":"packs/a.db.gz","sha256":"aa"},
              {"pack_id":"bueno","url":"packs/b.db.gz","sha256":"aa","db_sha256":"bb",
               "schema_version":4,"norm_version":2}]}
        """.trimIndent()
        val packs = Catalog.parse(json)
        assertEquals(listOf("bueno"), packs.map { it.packId })
    }

    @Test
    fun `un pack de schema 3 sin langs se parsea sin caerse`() {
        // packserver publica los packs de esquema viejo a proposito, y esos no tienen `langs`.
        val json = """
            {"packs":[{"pack_id":"es-def-wd","url":"packs/x.db.gz","sha256":"aa",
             "db_sha256":"bb","schema_version":3,"norm_version":2}]}
        """.trimIndent()
        val p = Catalog.parse(json).single()
        assertTrue(p.langs.isEmpty())
        assertEquals(CatalogStatus.INCOMPATIBLE, Catalog.classify(listOf(p), emptyList()).single().status)
    }
}
