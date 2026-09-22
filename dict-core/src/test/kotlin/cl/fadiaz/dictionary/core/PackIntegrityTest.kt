package cl.fadiaz.dictionary.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * El orden en que se juzga la `meta` de un pack, que decide **qué lee el usuario**.
 *
 * ⚠️ **Estos casos existen porque correr las reglas nuevas contra los packs viejos de verdad
 * encontró un defecto que ningún test veía.** Los siete `.db` de `schema_version` 3 que quedaron
 * en el directorio de datos se rechazaban por `METADATA` —"metadatos incompletos"— y no por
 * `SCHEMA_VERSION`. Los dos rechazan, así que nada fallaba; lo que cambiaba era la única línea
 * que el usuario lee, y "metadatos incompletos" suena a archivo corrupto mientras que "hecho
 * para otra versión de la app" es la verdad y además dice qué hacer.
 *
 * La causa es de dependencia, no de prolijidad: **`schema_version` es la clave que dice qué otras
 * claves tienen que existir**. Exigir el juego de claves de la versión 4 antes de mirar la
 * versión juzga a un pack de la 3 con reglas que no eran las suyas.
 */
class PackIntegrityTest {

    private fun meta(vararg cambios: Pair<String, String>): Map<String, String> = buildMap {
        put("pack_id", "es-def-wikc")
        put("schema_version", "4")
        put("norm_version", "2")
        put("kind", "monolingual")
        put("name", "Español")
        put("langs", "es")
        put("fuzzy_profiles", "es")
        put("entry_count", "152281")
        put("data_version", "202609211903")
        put("license", "CC BY-SA 4.0")
        put("attribution", "Wikcionario")
        put("payload_dict", "00ff")
        put("payload_dict_sha256", "da39a3ee")
        put("payload_codec", "deflate-v2")
        put("sources", "definitions\tWikcionario\thttps://x\tCC BY-SA 4.0\thttps://y")
        for ((k, v) in cambios) if (v.isEmpty()) remove(k) else put(k, v)
    }

    private fun juicio(meta: Map<String, String>) =
        PackIntegrity.checkMeta(meta, supportedSchema = 4, normVersion = 2, codecId = "deflate-v2")

    @Test
    fun `un pack correcto no tiene nada que objetar`() {
        assertNull(juicio(meta()))
    }

    @Test
    fun `un pack de otro esquema se rechaza POR EL ESQUEMA, aunque le falten claves`() {
        // ⚠️ El caso que encontraron los packs reales. Un pack de `schema_version` 3 no trae
        // `langs` ni `fuzzy_profiles` --nacieron con la 4-- así que la comprobación de claves
        // obligatorias lo agarraba primero y lo llamaba "metadatos incompletos".
        val viejo = meta("schema_version" to "3", "langs" to "", "fuzzy_profiles" to "")
        assertEquals(PackRejection.SCHEMA_VERSION, juicio(viejo)?.rejection)
    }

    @Test
    fun `sin schema_version no se puede juzgar nada y eso es METADATA`() {
        // Es la única clave que se mira antes que el resto, así que su ausencia no puede
        // reportarse como "otro esquema": no hay esquema que nombrar.
        assertEquals(PackRejection.METADATA, juicio(meta("schema_version" to ""))?.rejection)
        assertEquals(PackRejection.METADATA, juicio(meta("schema_version" to "cuatro"))?.rejection)
    }

    @Test
    fun `una clave obligatoria que falta es METADATA y la nombra`() {
        val problema = juicio(meta("entry_count" to ""))
        assertEquals(PackRejection.METADATA, problema?.rejection)
        // El detalle es para el log: sin el nombre de la clave, encontrarlo cuesta abrir el pack
        // a mano, que es justo lo que pasó cuando `data_version` se escribió como fecha (D-070).
        assertEquals(true, problema?.detail?.contains("entry_count"))
    }

    @Test
    fun `un numero que no es numero es METADATA y no un pack danado`() {
        // `data_version` se escribió una vez como "2026-09-15" y reventaba al ABRIR, en el
        // reloj, con un NumberFormatException que no nombraba la clave (D-070).
        assertEquals(PackRejection.METADATA, juicio(meta("data_version" to "2026-09-15"))?.rejection)
        assertEquals(PackRejection.METADATA, juicio(meta("entry_count" to "muchas"))?.rejection)
    }

    @Test
    fun `langs vacio o perfiles descuadrados es METADATA`() {
        // `PackMetadata` lo exige con `require`, que lanza IllegalArgumentException; sin esto
        // salía como "dañado", que le echa la culpa al archivo en vez de a su manifiesto.
        assertEquals(PackRejection.METADATA, juicio(meta("langs" to " , "))?.rejection)
        assertEquals(PackRejection.METADATA, juicio(meta("fuzzy_profiles" to "es,en"))?.rejection)
    }

    @Test
    fun `otra norm_version se rechaza por la normalizacion`() {
        assertEquals(PackRejection.NORM_VERSION, juicio(meta("norm_version" to "3"))?.rejection)
    }

    @Test
    fun `otro codec se rechaza por el codec`() {
        assertEquals(PackRejection.PAYLOAD_CODEC, juicio(meta("payload_codec" to "zstd-v1"))?.rejection)
    }

    @Test
    fun `un pack que no declara fuentes no se puede acreditar`() {
        // D-031: la pantalla de atribución no es opcional, y un pack que no dice de dónde sale
        // su contenido la vuelve imposible de cumplir.
        assertEquals(PackRejection.LICENSE, juicio(meta("sources" to ""))?.rejection)
    }

    @Test
    fun `una fuente sin licencia tampoco`() {
        val sinLicencia = meta("sources" to "definitions\tWikcionario\thttps://x\t\thttps://y")
        assertEquals(PackRejection.LICENSE, juicio(sinLicencia)?.rejection)
    }

    @Test
    fun `el esquema se mira antes que la normalizacion y que el codec`() {
        // Los tres rechazan, así que el orden sólo cambia el mensaje -- y el más específico es
        // el que le sirve a quien lo lee. Un pack de otro esquema probablemente también tenga
        // otra `norm_version`; decirle "indexado con otras reglas" sería cierto y menos útil.
        val todo = meta("schema_version" to "9", "norm_version" to "7", "payload_codec" to "x")
        assertEquals(PackRejection.SCHEMA_VERSION, juicio(todo)?.rejection)
    }
}
