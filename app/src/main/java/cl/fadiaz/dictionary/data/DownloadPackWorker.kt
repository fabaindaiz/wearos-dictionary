package cl.fadiaz.dictionary.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File

/**
 * Baja un pack **cuando el reloj este cargando y con Wi-Fi sin medir** (D-029).
 *
 * ⚠️ **Las restricciones no son una precaucion, son la guia oficial de Wear OS**, que clasifica el
 * acceso a red como *very high impact* -- por encima de encender la pantalla. Y con el ingles en
 * 192 MB comprimidos no es opcional: una descarga asi con la muñeca levantada es el gasto mas caro
 * que esta app puede hacer.
 *
 * ⚠️ **La consecuencia hay que decirla en la pantalla, no esconderla**: si el reloj no esta
 * cargando, apretar descargar **no descarga nada todavia**. Un progreso que no se mueve sin
 * explicacion se lee como una app rota.
 *
 * ⚠️ **Quien mira la red es WorkManager y nunca la app.** Por eso el manifest **no** pide
 * `ACCESS_NETWORK_STATE`, y el audit falla si alguien lo agrega: un permiso para mirar el estado
 * de la red es una invitacion a decidir cuando descargar desde aqui, que es justo lo que D-029
 * saco de la app.
 */
class DownloadPackWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val packId = inputData.getString(KEY_PACK_ID) ?: return Result.failure()
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val base = inputData.getString(KEY_BASE) ?: return Result.failure()
        val pack = CatalogPack(
            packId = packId,
            name = inputData.getString(KEY_NAME).orEmpty(),
            description = null,
            langs = emptyList(),
            entryCount = 0,
            dataVersion = 0,
            schemaVersion = 0,
            normVersion = 0,
            license = null,
            url = url,
            bytes = inputData.getLong(KEY_BYTES, 0),
            sha256 = inputData.getString(KEY_SHA).orEmpty(),
            dbBytes = 0,
            dbSha256 = inputData.getString(KEY_DB_SHA).orEmpty(),
        )
        DictLog.i { "worker: empieza $packId" }
        val destino = PackStore.packsDir(applicationContext)
        val resultado = PackDownloader.download(base, pack, destino) { hecho, total ->
            setProgressAsync(workDataOf(KEY_DONE to hecho, KEY_TOTAL to total))
        }
        return when (resultado) {
            is DownloadResult.Installed -> {
                // ⚠️ Se anota que este archivo vino del catalogo, y sin esto hay un downgrade
                // silencioso: si el pack tambien viene en el APK --los nucleos-- la proxima
                // version de la app lo re-extraeria y pisaria el recien bajado con el viejo.
                // Ver `PackStore.assetsToExtract`.
                PackStore.rememberDownloaded(applicationContext, resultado.file.name)
                DictLog.i { "worker: $packId instalado" }
                Result.success(workDataOf(KEY_PACK_ID to packId))
            }
            is DownloadResult.Failed -> {
                // ⚠️ `retry` y no `failure`: lo que falla aqui es casi siempre la red, y el `.part`
                // se conserva, asi que el reintento **reanuda** en vez de empezar de cero. El unico
                // caso en que no conviene --un `.gz` que no cuadra-- ya se resuelve borrando el
                // parcial dentro de [PackDownloader], y entonces el reintento baja entero.
                DictLog.w { "worker: $packId fallo (${resultado.reason}), se reintentara" }
                Result.retry()
            }
        }
    }

    companion object {
        const val KEY_PACK_ID = "pack_id"
        const val KEY_NAME = "name"
        const val KEY_URL = "url"
        const val KEY_BASE = "base"
        const val KEY_BYTES = "bytes"
        const val KEY_SHA = "sha256"
        const val KEY_DB_SHA = "db_sha256"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"

        /** El nombre unico del trabajo de un pack. Apretar dos veces no baja dos veces. */
        fun workName(packId: String) = "descarga:$packId"

        /**
         * Los datos que el worker necesita. Se pasan sueltos porque `Data` solo lleva primitivos,
         * y **eso es una ventaja**: obliga a nombrar exactamente lo que la descarga usa, en vez de
         * serializar un [CatalogPack] entero cuyo resto caducaria en la cola.
         */
        fun datos(base: String, pack: CatalogPack): Data = workDataOf(
            KEY_PACK_ID to pack.packId,
            KEY_NAME to pack.name,
            KEY_URL to pack.url,
            KEY_BASE to base,
            KEY_BYTES to pack.bytes,
            KEY_SHA to pack.sha256,
            KEY_DB_SHA to pack.dbSha256,
        )

        /** D-029, escrito una sola vez. */
        fun constraints(): Constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

        fun enqueue(context: Context, base: String, pack: CatalogPack) {
            val req = OneTimeWorkRequestBuilder<DownloadPackWorker>()
                .setInputData(datos(base, pack))
                .setConstraints(constraints())
                .addTag(TAG)
                // ⚠️ El packId va como TAG y no solo en los datos de entrada, porque `WorkInfo`
                // **no expone `inputData`**: sin esto no hay forma de saber a que pack pertenece
                // un progreso que llega desde WorkManager.
                .addTag(packTag(pack.packId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(pack.packId),
                // KEEP y no REPLACE: apretar otra vez mientras baja tiene que NO reiniciar la
                // descarga, que es lo que un REPLACE haria perdiendo lo ya bajado.
                ExistingWorkPolicy.KEEP,
                req,
            )
            DictLog.i { "worker: encolado ${pack.packId} (cargando + Wi-Fi sin medir)" }
        }

        /**
         * Cancela la descarga de [packId] y **libera lo que ya habia bajado**.
         *
         * ⚠️ **Borrar el `.part` es una decision, no una limpieza.** Ese archivo es lo unico que
         * hace real la reanudacion (D-214): conservarlo hace que volver a pedir el pack siga
         * desde donde iba, y con el ingles en 192 MB eso vale. Pero *cancelar* significa, para
         * quien lo aprieta, **recuperar el espacio y parar** -- y dejar 150 MB invisibles en
         * `filesDir` de algo que se cancelo es lo contrario de lo pedido. **El costo, nombrado**:
         * pedirlo de nuevo baja desde cero.
         *
         * ⚠️ **Se cancela primero y se borra despues.** Al reves, el worker seguiria escribiendo
         * sobre el archivo recien borrado y lo volveria a crear -- se veria "cancelado" con la
         * descarga corriendo, que es la misma forma del error que D-104 cerro para el borrado de
         * un pack.
         *
         * Devuelve si habia algo parcial que liberar, para poder afirmarlo en un test.
         */
        fun cancel(context: Context, packId: String, dir: File, url: String): Boolean {
            WorkManager.getInstance(context).cancelUniqueWork(workName(packId))
            val parcial = partialFor(dir, url) ?: return false
            val habia = parcial.isFile
            parcial.delete()
            DictLog.i { "worker: cancelado $packId${if (habia) " (se libero el parcial)" else ""}" }
            return habia
        }

        /**
         * El `.gz.part` que le corresponde a esa url, o `null` si la url no nombra un `.db.gz`.
         *
         * ⚠️ **Deriva el nombre igual que `PackDownloader`, y eso es una duplicacion conocida.**
         * Lo correcto seria una sola funcion; vive aca porque cancelar no puede depender de
         * arrancar la descarga. Un test comprueba que las dos derivaciones coincidan: si se
         * separan, cancelar borra otro archivo o ninguno, **sin error**.
         */
        internal fun partialFor(dir: File, url: String): File? {
            val nombre = url.substringAfterLast('/').removeSuffix(".gz")
            if (nombre.isEmpty() || !nombre.endsWith(".db")) return null
            return File(dir, "$nombre.gz.part")
        }

        const val TAG = "descarga-pack"

        private const val PACK_TAG = "pack:"

        fun packTag(packId: String) = PACK_TAG + packId

        /**
         * Traduce lo que WorkManager reporta a [PackDownload], o `null` si no es nuestro.
         *
         * Se deja aparte y sin `Context` **para poder probarlo**: construir un `WorkInfo` en un
         * test es barato, levantar WorkManager no.
         */
        fun toPackDownload(
            tags: Set<String>,
            state: WorkInfo.State,
            progress: Data,
        ): PackDownload? {
            val packId = tags.firstOrNull { it.startsWith(PACK_TAG) }
                ?.removePrefix(PACK_TAG) ?: return null
            val fase = when (state) {
                // BLOCKED y ENQUEUED son lo mismo para el usuario: **todavia no empezo**, y en
                // esta app casi siempre es porque el reloj no esta cargando (D-029).
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadPhase.WAITING
                WorkInfo.State.RUNNING -> DownloadPhase.RUNNING
                WorkInfo.State.SUCCEEDED -> DownloadPhase.DONE
                WorkInfo.State.FAILED -> DownloadPhase.FAILED
                // ⚠️ **CANCELLED NO es FAILED**, y hasta aqui lo era. `FAILED` le dice al usuario
                // *"fallo, se reintentara"*, que sobre algo que el mismo paro es falso y ademas
                // alarmante. Con fase propia, la pantalla puede sacar la fila y dejar la oferta
                // como estaba -- que es lo que "cancelar" significa.
                WorkInfo.State.CANCELLED -> DownloadPhase.CANCELLED
            }
            return PackDownload(
                packId = packId,
                phase = fase,
                done = progress.getLong(KEY_DONE, 0),
                total = progress.getLong(KEY_TOTAL, 0),
            )
        }
    }
}
