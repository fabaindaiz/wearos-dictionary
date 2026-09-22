package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.PackMetadata
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

    /**
     * El pack declara un `schema_version` o `norm_version` que esta app no abre.
     *
     * ⚠️ **Se muestra en vez de esconderse**, y es a proposito: un pack que existe y no se puede
     * usar es una pregunta que el usuario se va a hacer, y "no aparece" es la peor respuesta.
     */
    INCOMPATIBLE,
}

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
    data class Ready(val offers: List<CatalogOffer>) : CatalogState

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
    ): List<CatalogOffer> {
        // Por `packId` y nunca por nombre de archivo: el pack del nucleo espanol vive en
        // `es-core.db` y se llama `es-def-wikc-tat-freq-wn-wd-core`. La identidad es la que el
        // artefacto DECLARA (D-138); confundirla con la ubicacion haria que renombrar un archivo
        // se vea como un pack nuevo.
        val localPorId = installed.associate { it.packId to it.dataVersion }
        return catalog.map { pack ->
            val local = localPorId[pack.packId]
            val status = when {
                // Primero, y gana sobre todo lo demas: si no se puede abrir, da igual si hay algo
                // instalado. Ofrecer una actualizacion que la app va a rechazar al abrirla cobra
                // la descarga y no entrega nada.
                pack.schemaVersion != schemaVersion || pack.normVersion != normVersion ->
                    CatalogStatus.INCOMPATIBLE
                local == null -> CatalogStatus.DOWNLOAD
                // Estrictamente mayor. En desarrollo pasa a diario tener un pack local mas nuevo
                // que el que sirve el servidor, y ofrecer "actualizar" ahi seria un downgrade.
                pack.dataVersion > local -> CatalogStatus.UPDATE
                else -> CatalogStatus.INSTALLED
            }
            CatalogOffer(pack, status, local)
        }
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
