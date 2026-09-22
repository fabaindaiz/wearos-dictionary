package cl.fadiaz.dictionary.data

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * La traduccion de lo que reporta WorkManager.
 *
 * ⚠️ El aserto que paga el archivo es el del **tag**: `WorkInfo` **no expone `inputData`**, asi que
 * el `packId` solo puede venir de ahi. Si alguien quita el tag al encolar, el progreso llega sin
 * dueño y la pantalla se queda inmovil sin un solo error.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadPackWorkerTest {

    private val tags = setOf(DownloadPackWorker.TAG, DownloadPackWorker.packTag("es-def"))

    private fun mapa(state: WorkInfo.State, progress: Data = Data.EMPTY) =
        DownloadPackWorker.toPackDownload(tags, state, progress)

    @Test
    fun `sin el tag del pack no hay a quien atribuir el progreso`() {
        assertNull(
            DownloadPackWorker.toPackDownload(setOf(DownloadPackWorker.TAG), WorkInfo.State.RUNNING, Data.EMPTY),
            "sin tag no se puede saber de que pack es",
        )
    }

    @Test
    fun `encolado y bloqueado son lo mismo para el usuario, TODAVIA NO EMPEZO`() {
        // En esta app casi siempre es porque el reloj no esta cargando (D-029), y esa espera
        // tiene que poder explicarse en la pantalla.
        assertEquals(DownloadPhase.WAITING, mapa(WorkInfo.State.ENQUEUED)?.phase)
        assertEquals(DownloadPhase.WAITING, mapa(WorkInfo.State.BLOCKED)?.phase)
    }

    @Test
    fun `los demas estados se traducen uno a uno`() {
        assertEquals(DownloadPhase.RUNNING, mapa(WorkInfo.State.RUNNING)?.phase)
        assertEquals(DownloadPhase.DONE, mapa(WorkInfo.State.SUCCEEDED)?.phase)
        assertEquals(DownloadPhase.FAILED, mapa(WorkInfo.State.FAILED)?.phase)
        assertEquals(DownloadPhase.FAILED, mapa(WorkInfo.State.CANCELLED)?.phase)
    }

    @Test
    fun `el progreso viaja con su pack`() {
        val d = mapa(
            WorkInfo.State.RUNNING,
            workDataOf(DownloadPackWorker.KEY_DONE to 512L, DownloadPackWorker.KEY_TOTAL to 2048L),
        )
        assertEquals("es-def", d?.packId)
        assertEquals(512L, d?.done)
        assertEquals(2048L, d?.total)
    }

    @Test
    fun `las restricciones son las de D-029 y no otras`() {
        // ⚠️ Escrito como aserto porque relajarlas es exactamente el tipo de cambio que se cuela
        // "para probar mas rapido" y no se revierte. La guia oficial de Wear OS pone el acceso a
        // red por encima de encender la pantalla.
        val c = DownloadPackWorker.constraints()
        assertEquals(true, c.requiresCharging())
        assertEquals(androidx.work.NetworkType.UNMETERED, c.requiredNetworkType)
    }

    @Test
    fun `los datos de entrada llevan lo que la descarga necesita, y nada mas`() {
        val pack = CatalogPack(
            packId = "es-def", name = "Español", description = "larga", langs = listOf("es"),
            entryCount = 9, dataVersion = 202609220000L, schemaVersion = 4, normVersion = 2,
            license = "CC", url = "packs/x.db.gz", bytes = 10, sha256 = "aa",
            dbBytes = 20, dbSha256 = "bb",
        )
        val d = DownloadPackWorker.datos("http://h", pack)
        assertEquals("es-def", d.getString(DownloadPackWorker.KEY_PACK_ID))
        assertEquals("packs/x.db.gz", d.getString(DownloadPackWorker.KEY_URL))
        assertEquals("aa", d.getString(DownloadPackWorker.KEY_SHA))
        assertEquals("bb", d.getString(DownloadPackWorker.KEY_DB_SHA))
        assertEquals(10L, d.getLong(DownloadPackWorker.KEY_BYTES, -1))
        // Lo que NO viaja: la descripcion y la licencia caducarian en la cola y no deciden nada.
        assertNull(d.getString("description"))
    }
}
