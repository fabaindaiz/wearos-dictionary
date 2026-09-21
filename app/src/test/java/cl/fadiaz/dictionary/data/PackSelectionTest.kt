package cl.fadiaz.dictionary.data

import cl.fadiaz.dictionary.core.FuzzyProfile
import cl.fadiaz.dictionary.core.PackKind
import cl.fadiaz.dictionary.core.PackMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Qué packs se consultan, de todos los que están instalados.
 *
 * ⚠️ **Instalado y consultado NO son la misma lista, y ésa es la idea entera.** Ajustes tiene que
 * seguir mostrando todo lo que ocupa disco para que se pueda borrar; la búsqueda tiene que
 * preguntarle sólo a lo que aporta algo. Hoy son la misma lista y por eso un pack viejo y uno
 * nuevo del mismo diccionario se consultan los dos.
 */
class PackSelectionTest {

    private fun pack(
        packId: String,
        dataVersion: Long = 202609210000,
        lang: String = "es",
        subsetOf: String? = null,
        entries: Int = 1000,
    ): cl.fadiaz.dictionary.core.DictionarySource = SoloMetadata(
            PackMetadata(
                packId = packId, schemaVersion = 3, normVersion = 2,
                kind = PackKind.MONOLINGUAL, name = packId, description = null,
                langSource = lang, langTarget = null, fuzzyProfile = FuzzyProfile.SPANISH,
                entryCount = entries, dataVersion = dataVersion,
                license = "CC0-1.0", attribution = packId, subsetOf = subsetOf,
            ),
    )

    private fun ids(packs: List<cl.fadiaz.dictionary.core.DictionarySource>) =
        packs.map { it.metadata.packId }

    @Test
    fun deCadaDiccionarioSeConsultaSoloElBuildMasNuevo() {
        // El defecto que esto cierra: `PackStore` abre TODOS los `.db` del directorio, así que
        // reinstalar un pack sin borrar el anterior deja dos builds del mismo diccionario
        // contestando. La salida no sale mal --se deduplica por (lema, tipo)-- pero se paga el
        // doble de consultas y el doble de disco, en silencio.
        val viejo = pack("es-def-wikc", dataVersion = 202601010000)
        val nuevo = pack("es-def-wikc", dataVersion = 202609210000)
        assertEquals(listOf("es-def-wikc"), ids(packsToQuery(listOf(viejo, nuevo))))
        assertEquals(
            202609210000L,
            packsToQuery(listOf(viejo, nuevo)).single().metadata.dataVersion,
            )
    }

    @Test
    fun elOrdenEnQueLleganNoDecideCualGana() {
        // Llegan como los lista el directorio, que no promete orden.
        val viejo = pack("es-def-wikc", dataVersion = 202601010000)
        val nuevo = pack("es-def-wikc", dataVersion = 202609210000)
        for (entrada in listOf(listOf(viejo, nuevo), listOf(nuevo, viejo))) {
            assertEquals(202609210000L, packsToQuery(entrada).single().metadata.dataVersion)
        }
    }

    @Test
    fun dosDiccionariosDISTINTOSSeConsultanLosDos() {
        // El control. Sin esto, una regla que deduplica por `pack_id` podría estar tirando packs
        // de fuentes distintas, que es justo lo que D-136 existe para juntar.
        val packs = listOf(pack("es-def-wikc"), pack("es-def-wd"))
        assertEquals(listOf("es-def-wd", "es-def-wikc"), ids(packsToQuery(packs)).sorted())
    }

    @Test
    fun unSubconjuntoNoSeConsultaSiEstaElPackQueLoContiene() {
        // El núcleo se hace a un lado cuando está el completo: cada respuesta suya o ya vino del
        // completo y se descarta al deduplicar, o es un lema que el completo no tiene, **lo
        // cual no puede pasar si de verdad es un subconjunto**.
        val nucleo = pack("es-core-wikc", subsetOf = "es-def-wikc")
        val completo = pack("es-def-wikc")
        assertEquals(listOf("es-def-wikc"), ids(packsToQuery(listOf(nucleo, completo))))
    }

    @Test
    fun unSubconjuntoSOLOSiSeConsulta() {
        // Sin el completo instalado, el núcleo **es** el diccionario. Que declare de quién es
        // subconjunto no lo invalida: lo describe.
        val nucleo = pack("es-core-wikc", subsetOf = "es-def-wikc")
        assertEquals(listOf("es-core-wikc"), ids(packsToQuery(listOf(nucleo))))
    }

    @Test
    fun unSubsetOfQueApuntaAUnPackViejoTampocoSalva() {
        // ⚠️ El orden de las dos reglas importa. Si el completo instalado es un build viejo que
        // otro build más nuevo ya reemplazó, el núcleo sigue sin hacer falta: lo que decide es
        // que el diccionario que lo contiene esté, no qué build.
        val nucleo = pack("es-core-wikc", subsetOf = "es-def-wikc")
        val viejo = pack("es-def-wikc", dataVersion = 202601010000)
        val nuevo = pack("es-def-wikc", dataVersion = 202609210000)
        assertEquals(listOf("es-def-wikc"), ids(packsToQuery(listOf(nucleo, viejo, nuevo))))
    }

    @Test
    fun dosPacksQueSeDeclaranSubconjuntoUnoDelOtroNoSeBorranLosDos() {
        // ⚠️ Un pack de la comunidad puede declarar cualquier cosa. Si dos se declaran
        // subconjunto mutuamente y la regla se aplicara en cadena, **la búsqueda se quedaría sin
        // diccionario**: el peor resultado posible para una declaración mal hecha. Se aplica en
        // UNA pasada, así que en el peor caso sobra trabajo, nunca falta un diccionario.
        val a = pack("es-a", subsetOf = "es-b")
        val b = pack("es-b", subsetOf = "es-a")
        assertEquals(emptyList<String>(), ids(packsToQuery(listOf(a, b))).minus(setOf("es-a", "es-b")))
        assert(packsToQuery(listOf(a, b)).isNotEmpty()) {
            "una declaración circular no puede dejar la búsqueda sin packs"
        }
    }
}

/** Un pack del que sólo importa su metadata: lo que se prueba es la selección, no la consulta. */
private class SoloMetadata(override val metadata: PackMetadata) :
    cl.fadiaz.dictionary.core.DictionarySource {
    override suspend fun suggest(query: String, limit: Int) =
        emptyList<cl.fadiaz.dictionary.core.Suggestion>()
    override suspend fun searchDefinitions(query: String, limit: Int) =
        emptyList<cl.fadiaz.dictionary.core.Suggestion>()
    override suspend fun entry(entryId: Long): cl.fadiaz.dictionary.core.Entry? = null
    override suspend fun resolveHeadwords(norms: Set<String>) = emptyMap<String, Long>()
    override suspend fun summary(entryId: Long): cl.fadiaz.dictionary.core.EntrySummary? = null
    override fun close() = Unit
}
