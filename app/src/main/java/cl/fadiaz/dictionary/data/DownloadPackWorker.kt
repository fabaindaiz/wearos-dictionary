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
 * Downloads a pack **when the watch is charging and on unmetered Wi-Fi** (D-029).
 *
 * ⚠️ **The constraints are not a precaution, they are the official Wear OS guidance**, which
 * classifies network access as *very high impact* -- above turning the screen on. And with English
 * at 192 MB compressed it is not optional: a download like that with the wrist raised is the most
 * expensive thing this app can do.
 *
 * ⚠️ **The consequence has to be said on screen, not hidden**: if the watch is not charging,
 * pressing download **downloads nothing yet**. Progress that does not move with no explanation
 * reads as a broken app.
 *
 * ⚠️ **WorkManager watches the network and the app never does.** That is why the manifest does
 * **not** ask for `ACCESS_NETWORK_STATE`, and the audit fails if somebody adds it: a permission to
 * look at network state is an invitation to decide when to download from here, which is exactly
 * what D-029 took out of the app.
 */
/**
 * Who asked for a download, which is what decides whether it waits for a charger (D-263).
 *
 * ⚠️ **It is not a priority and not a network policy.** Both kinds wait for Wi-Fi: a data plan is
 * billed the same whoever started the transfer. What it separates is **consent to a battery cost**
 * nobody has measured -- present for it, or not.
 */
enum class DownloadOrigin {
    /** Somebody pressed download and is looking at the progress bar. */
    MANUAL,

    /**
     * The app decided: an update found by the catalogue, a retry, anything nobody is watching.
     *
     * ⚠️ **Nothing produces this yet**, and the entry exists so that whoever writes the first
     * automatic download has to choose rather than inherit.
     */
    QUEUED,
}

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
                // ⚠️ It is recorded that this file came from the catalog, and without that there
                // is a silent downgrade: if the pack also ships in the APK --the cores-- the next
                // version of the app would re-extract it and overwrite the freshly downloaded one
                // with the old one. See `PackStore.assetsToExtract`.
                PackStore.rememberDownloaded(applicationContext, resultado.file.name)
                DictLog.i { "worker: $packId instalado" }
                Result.success(workDataOf(KEY_PACK_ID to packId))
            }
            is DownloadResult.Failed -> {
                // ⚠️ `retry` and not `failure`: what fails here is almost always the network, and
                // the `.part` is kept, so the retry **resumes** instead of starting from zero. The
                // only case where that is wrong --a `.gz` that does not add up-- is already
                // handled by deleting the partial inside [PackDownloader], and then the retry
                // downloads the whole thing.
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

        /** A pack's unique work name. Pressing twice does not download twice. */
        fun workName(packId: String) = "descarga:$packId"

        /**
         * The data the worker needs. They are passed loose because `Data` only carries primitives,
         * and **that is an advantage**: it forces naming exactly what the download uses, instead
         * of serializing a whole [CatalogPack] whose remainder would go stale in the queue.
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

        /**
         * D-029 and D-263, written once: **who asked for the download decides who waits.**
         *
         * ⚠️ **The two constraints defend different things and only one of them was ever argued.**
         * `UNMETERED` protects a **data plan** -- a 314 MB pack over a metered connection is a
         * real bill, and a watch paired to a phone can be on one without saying so -- and it holds
         * for every download regardless of who started it. `setRequiresCharging` protects the
         * **battery**, and there is no number behind it in this repo: `docs/bateria.md` has the
         * screen at 33.2 mAh and all of our CPU at 6.05, so the budget exists and the download was
         * simply never put against it.
         *
         * ⚠️ **So what changes here is WHO decides, not what it costs.** A download somebody is
         * watching is their choice to pay for; one that fires on its own is not, and nobody can
         * consent to a cost they are not present for. It is the reasoning D-212 used for logging.
         *
         * ⚠️ **Today every download is [DownloadOrigin.MANUAL]**, so the observable effect is that
         * the charger stops being required, full stop. The parameter is not speculative
         * generality: it is where the rule waits for the queued download to arrive, instead of
         * being deleted and rediscovered by whoever writes it.
         */
        fun constraints(origin: DownloadOrigin): Constraints = Constraints.Builder()
            .setRequiresCharging(origin == DownloadOrigin.QUEUED)
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

        /**
         * ⚠️ **[origin] has no default on purpose.** A default would let the next caller inherit
         * the looser rule without naming it, which is the whole thing being decided here.
         */
        fun enqueue(context: Context, base: String, pack: CatalogPack, origin: DownloadOrigin) {
            val req = OneTimeWorkRequestBuilder<DownloadPackWorker>()
                .setInputData(datos(base, pack))
                .setConstraints(constraints(origin))
                .addTag(TAG)
                // ⚠️ The packId goes as a TAG and not only in the input data, because `WorkInfo`
                // **does not expose `inputData`**: without this there is no way to know which pack
                // a progress update arriving from WorkManager belongs to.
                .addTag(packTag(pack.packId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(pack.packId),
                // KEEP and not REPLACE: pressing again while it downloads has to NOT restart the
                // download, which is what a REPLACE would do, losing what was already fetched.
                ExistingWorkPolicy.KEEP,
                req,
            )
            DictLog.i {
                val espera = if (origin == DownloadOrigin.QUEUED) "cargando + " else ""
                "worker: encolado ${pack.packId} ($espera Wi-Fi sin medir, origen=$origin)"
            }
        }

        /**
         * Cancels [packId]'s download and **frees what had already been fetched**.
         *
         * ⚠️ **Deleting the `.part` is a decision, not a cleanup.** That file is the only thing
         * that makes resuming real (D-214): keeping it makes asking for the pack again carry on
         * from where it was, and with English at 192 MB that is worth something. But *cancel*
         * means, to whoever presses it, **get the space back and stop** -- and leaving 150 MB
         * invisible in `filesDir` for something that was cancelled is the opposite of what was
         * asked. **The cost, named**: asking again downloads from zero.
         *
         * ⚠️ **It cancels first and deletes afterwards.** The other way round, the worker would
         * keep writing over the just-deleted file and recreate it -- it would read "cancelled"
         * with the download running, which is the same shape of error D-104 closed for deleting a
         * pack.
         *
         * It returns whether there was anything partial to free, so a test can assert it.
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
         * The `.gz.part` belonging to that url, or `null` if the url does not name a `.db.gz`.
         *
         * ⚠️ **It derives the name the same way `PackDownloader` does, and that is a known
         * duplication.** The right thing would be a single function; it lives here because
         * cancelling cannot depend on starting the download. A test checks that the two
         * derivations agree: if they drift apart, cancelling deletes another file or none,
         * **with no error**.
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
         * Translates what WorkManager reports into [PackDownload], or `null` if it is not ours.
         *
         * It is kept apart and without a `Context` **so it can be tested**: building a `WorkInfo`
         * in a test is cheap, starting WorkManager is not.
         */
        fun toPackDownload(
            tags: Set<String>,
            state: WorkInfo.State,
            progress: Data,
        ): PackDownload? {
            val packId = tags.firstOrNull { it.startsWith(PACK_TAG) }
                ?.removePrefix(PACK_TAG) ?: return null
            val fase = when (state) {
                // BLOCKED and ENQUEUED are the same thing to the user: **it has not started yet**,
                // and in this app that is almost always because the watch is not charging (D-029).
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadPhase.WAITING
                WorkInfo.State.RUNNING -> DownloadPhase.RUNNING
                WorkInfo.State.SUCCEEDED -> DownloadPhase.DONE
                WorkInfo.State.FAILED -> DownloadPhase.FAILED
                // ⚠️ **CANCELLED is NOT FAILED**, and until here it was. `FAILED` tells the user
                // *"it failed, it will be retried"*, which about something they stopped themselves
                // is false and also alarming. With a phase of its own, the screen can drop the row
                // and leave the offer as it was -- which is what "cancel" means.
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
