package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.PackMetadata
import java.time.LocalDate
import cl.fadiaz.dictionary.core.TextNormalizer
import org.json.JSONObject

/**
 * Un pack tal como lo publica el catalogo, **antes** de descargarlo.
 *
 * Es deliberadamente distinto de [PackMetadata]: eso es lo que dice un pack que ya esta abierto,
 * y esto es una promesa sobre un archivo que todavia no existe en el reloj. Mezclarlos haria que
 * un pack ofrecido y uno instalado se vean iguales en el codigo, que es como se acaba mostrando
 * "instalado" algo que nunca se bajo.
 *
 * ⚠️ **`schemaVersion` y `normVersion` estan aca por una razon de eficiencia y no de completitud**:
 * son las dos que hacen que `PackFile.open` rechace un pack entero, asi que tenerlas ANTES de la
 * url permite descartar un pack incompatible **sin gastar 192 MB para tirarlos**.
 *
 * ⚠️ **Dos hashes, y hacen falta los dos.** [sha256] es del `.gz` que viaja e [dbSha256] del `.db`
 * que queda en disco tras descomprimir. Se verifica en los dos momentos, y lo que compara
 * `PackStore.installAtomically` es el segundo (D-165).
 */
data class CatalogPack(
    val packId: String,
    val name: String,
    val description: String?,
    val langs: List<String>,
    val entryCount: Int,
    /** El entero monotono que escribe el builder. Es con esto que se decide si hay novedad. */
    val dataVersion: Long,
    val schemaVersion: Int,
    val normVersion: Int,
    val license: String?,
    val url: String,
    val bytes: Long,
    val sha256: String,
    val dbBytes: Long,
    val dbSha256: String,
)

/** En que cajon cae un pack del catalogo cuando se lo compara con lo que hay instalado. */
enum class CatalogStatus {
    /** No esta instalado y se puede usar. */
    DOWNLOAD,

    /** Esta instalado y el catalogo tiene una `data_version` mayor. */
    UPDATE,

    /** Esta instalado y al dia. Se muestra, pero no se ofrece. */
    INSTALLED,
}

/**
 * El catalogo ya comparado con lo que hay instalado.
 *
 * ⚠️ **Los packs que esta app no abre NO estan en [offers]**, y eso revierte una decision anterior.
 * Antes se mostraban en una seccion propia, con el razonamiento de que *«un pack que existe y no
 * se puede usar es una pregunta que el usuario se va a hacer»*. Pedido explicito: *«no quiero
 * listar packs no disponibles; en su lugar solo deberia aclarar que se debe actualizar la
 * aplicacion»*. Y es mejor: la lista solo deberia tener cosas que se pueden tener, y la respuesta
 * util no es *"este pack no sirve"* sino **"actualiza la app"**, que es accionable.
 */
data class CatalogListing(
    val offers: List<CatalogOffer>,
    /** Hubo packs descartados por version. La pantalla lo dice una vez, no pack por pack. */
    val needsAppUpdate: Boolean,
)

/** En que punto esta la descarga de UN pack. */
enum class DownloadPhase {
    /**
     * Encolada, esperando a que se cumpla D-029: cargando y con Wi-Fi sin medir.
     *
     * ⚠️ **Este estado tiene que verse en la pantalla.** Si el reloj no esta cargando, tocar
     * descargar no descarga nada todavia, y un progreso que no se mueve sin explicacion se lee
     * como una app rota.
     */
    WAITING,

    RUNNING,
    DONE,

    /** Fallo; WorkManager reintentara. El `.part` se conserva, asi que reanudara. */
    FAILED,

    /**
     * La paro el usuario. **No es [FAILED]**, y la diferencia es lo que se le dice.
     *
     * `FAILED` promete un reintento; esto no lo tiene. La pantalla la trata como *"no hay
     * descarga"*: la fila vuelve a ser una oferta, que es lo que cancelar significa. Y el `.part`
     * ya no esta --ver `DownloadPackWorker.cancel`-- asi que volver a pedirla baja desde cero.
     */
    CANCELLED,
}

/** El progreso de la descarga de un pack, tal como lo reporta WorkManager. */
data class PackDownload(
    val packId: String,
    val phase: DownloadPhase,
    val done: Long = 0,
    val total: Long = 0,
)

/**
 * En que punto esta la consulta al catalogo, para la pantalla de gestion.
 *
 * ⚠️ **Arranca en [Idle] y se queda ahi hasta que el usuario aprieta el boton.** Entrar a la
 * pantalla no consulta nada: fue el pedido explicito y coincide con D-029, porque la guia oficial
 * de Wear OS pone el acceso a red por encima de encender la pantalla.
 */
sealed interface CatalogState {
    /** Nadie pregunto todavia. */
    data object Idle : CatalogState

    /** Se esta preguntando. La pantalla muestra que algo pasa. */
    data object Checking : CatalogState

    /** Llego una respuesta. [offers] puede estar vacia: un catalogo sin nada que ofrecer. */
    data class Ready(
        val offers: List<CatalogOffer>,
        /** Hubo packs que esta version no abre. Se dice una vez, no pack por pack. */
        val needsAppUpdate: Boolean = false,
    ) : CatalogState

    /** No se pudo. [reason] se muestra tal cual: en desarrollo es lo unico que orienta. */
    data class Failed(val reason: String) : CatalogState
}

/** Un pack del catalogo con su veredicto y, si estaba, la version que ya hay en disco. */
data class CatalogOffer(
    val pack: CatalogPack,
    val status: CatalogStatus,
    val installedVersion: Long?,
)

/**
 * El catalogo: leerlo y compararlo con lo instalado.
 *
 * No descarga nada ni toca la red: esto es la parte pura. Ver [CatalogClient] para el HTTP.
 */
object Catalog {

    /**
     * Reparte los packs del catalogo en los cajones de [CatalogStatus].
     *
     * ⚠️ **La compatibilidad se compara por IGUALDAD y no por `>=`**, porque es exactamente lo que
     * hace `PackFile.open`: un `schema_version` distinto se rechaza, mayor o menor. Poner `>=` aca
     * ofreceria packs que la app despues no abre, y el usuario habria pagado la descarga.
     *
     * Las versiones se reciben como parametro --con el valor real por defecto-- para que un test
     * pueda mover el umbral sin tocar las constantes, que tienen su propio espejo en el audit.
     */
    fun classify(
        catalog: List<CatalogPack>,
        installed: List<PackMetadata>,
        schemaVersion: Int = PackFile.SUPPORTED_SCHEMA_VERSION,
        normVersion: Int = TextNormalizer.NORM_VERSION,
    ): CatalogListing {
        // Por `packId` y nunca por nombre de archivo: el pack del nucleo espanol vive en
        // `es-core.db` y se llama `es-def-wikc-tat-freq-wn-wd-core`. La identidad es la que el
        // artefacto DECLARA (D-138); confundirla con la ubicacion haria que renombrar un archivo
        // se vea como un pack nuevo.
        val localPorId = installed.associate { it.packId to it.dataVersion }
        // ⚠️ **Se comparan por IGUALDAD y no por `>=`**, porque es lo que hace `PackFile.open`: un
        // `schema_version` distinto se rechaza, mayor o menor. Con `>=` se ofreceria un pack que
        // la app despues no abre, y el usuario ya habria pagado la descarga.
        val (abribles, rechazados) = catalog.partition {
            it.schemaVersion == schemaVersion && it.normVersion == normVersion
        }
        val offers = abribles.map { pack ->
            val local = localPorId[pack.packId]
            val status = when {
                local == null -> CatalogStatus.DOWNLOAD
                // Estrictamente mayor. En desarrollo pasa a diario tener un pack local mas nuevo
                // que el que sirve el servidor, y ofrecer "actualizar" ahi seria un downgrade.
                pack.dataVersion > local -> CatalogStatus.UPDATE
                else -> CatalogStatus.INSTALLED
            }
            CatalogOffer(pack, status, local)
        }
        return CatalogListing(offers, needsAppUpdate = rechazados.isNotEmpty())
    }

    /**
     * La fecha que lleva dentro un `data_version`, o `null` si ese numero no es una fecha.
     *
     * ⚠️ **Esto salio de verlo en el emulador**, que es donde se ven las decisiones de UI en este
     * repo. La fila de una actualizacion decia literalmente
     * `3,0 MB · v202609211912, you have v202609211911`: dos numeros de doce digitos que difieren
     * en el ultimo, 390 px de los ~459 utiles a esa altura de una pantalla redonda, y con eso no
     * se decide nada. La fecha si informa.
     *
     * El builder escribe `data_version` como `YYYYMMDDHHMM`, pero **un pack ajeno puede poner lo
     * que quiera ahi** --es un entero monotono y nada mas-- asi que esto es defensivo: si no
     * parece una fecha valida devuelve `null` y la fila se queda con el tamano.
     */
    fun dataVersionDate(dataVersion: Long): LocalDate? {
        // ⚠️ **Hay DOS anchos en circulacion**, visto en el indice real: los packs nuevos traen
        // `YYYYMMDDHHMM` (202609211912) y `es-def-wd` trae `YYYYMMDD` (20260920). Aceptar solo
        // uno dejaba la mitad de las filas sin fecha, y sin ningun error que lo delatara.
        val ymd = when (dataVersion) {
            in 10_000_101L..99_991_231L -> dataVersion
            in 100_001_010_000L..999_912_312_359L -> dataVersion / 10_000L
            else -> return null
        }.toInt()
        // `runCatching` y no mas comprobaciones a mano: `LocalDate.of` ya rechaza el mes 13 y el
        // 30 de febrero, y duplicar ese calendario aca seria una segunda fuente de verdad.
        return runCatching {
            LocalDate.of(ymd / 10_000, (ymd / 100) % 100, ymd % 100)
        }.getOrNull()
    }

    /**
     * Parsea el `index.json` del servidor.
     *
     * ⚠️ **Un pack al que le falte un campo obligatorio se SALTA en vez de tumbar el catalogo.**
     * Un catalogo con seis packs y uno mal escrito tiene que ofrecer los cinco buenos; si una
     * entrada rota deja al usuario sin ninguna opcion, el fallo es del cliente y no del servidor.
     */
    fun parse(json: String): List<CatalogPack> {
        val root = JSONObject(json)
        val array = root.optJSONArray("packs") ?: return emptyList()
        val out = mutableListOf<CatalogPack>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val packId = o.optString("pack_id").takeIf { it.isNotEmpty() } ?: continue
            val url = o.optString("url").takeIf { it.isNotEmpty() } ?: continue
            val sha = o.optString("sha256").takeIf { it.isNotEmpty() } ?: continue
            val dbSha = o.optString("db_sha256").takeIf { it.isNotEmpty() } ?: continue
            val langs = o.optJSONArray("langs")
            out += CatalogPack(
                packId = packId,
                name = o.optString("name").takeIf { it.isNotEmpty() } ?: packId,
                description = o.optString("description").takeIf { it.isNotEmpty() },
                langs = (0 until (langs?.length() ?: 0)).mapNotNull {
                    langs?.optString(it)?.takeIf { s -> s.isNotEmpty() }
                },
                entryCount = o.optInt("entry_count"),
                dataVersion = o.optLong("data_version"),
                schemaVersion = o.optInt("schema_version"),
                normVersion = o.optInt("norm_version"),
                license = o.optString("license").takeIf { it.isNotEmpty() },
                url = url,
                bytes = o.optLong("bytes"),
                sha256 = sha,
                dbBytes = o.optLong("db_bytes"),
                dbSha256 = dbSha,
            )
        }
        return out
    }
}
