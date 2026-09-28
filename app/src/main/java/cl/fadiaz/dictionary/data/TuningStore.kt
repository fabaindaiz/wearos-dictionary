package cl.fadiaz.dictionary.data

import android.content.Context
import cl.fadiaz.dictionary.core.DisplayTuning
import cl.fadiaz.dictionary.core.SearchTuning
import cl.fadiaz.dictionary.core.Tuning
import org.json.JSONObject

/**
 * Reads `assets/tuning.json` into a [Tuning].
 *
 * ⚠️ **Every failure degrades to the default and SAYS SO.** A missing file, malformed JSON, a key
 * with the wrong type, a `forms` list that is empty — none of them may stop the app, because a
 * dictionary that does not open is worse than one running last month's numbers. But a silent
 * fallback would make *"my change did nothing"* and *"the file did not parse"* the same report,
 * which is the failure class this repo keeps finding (D-212). So each fallback is one line in
 * `DictLog`, and the whole read is one INFO line naming what actually took effect.
 *
 * ⚠️ **Read ONCE, at startup, and passed down as a value.** Re-reading per query would put an
 * asset open in the cascade, and re-reading per screen would let two screens disagree about the
 * same build. It is a build-time constant that happens to be stored as a file.
 *
 * Keys beginning with `_` are comments: JSON has none, and the file is meant to be edited by
 * hand. They are ignored here rather than rejected, so a note can be added next to any value.
 */
object TuningStore {

    const val ASSET = "tuning.json"

    fun load(context: Context): Tuning = try {
        parse(context.assets.open(ASSET).bufferedReader().use { it.readText() })
    } catch (e: Exception) {
        // ⚠️ **Broad on purpose, and it is the one place that should be.** `IOException` covers a
        // missing asset and `JSONException` a malformed one, but a `tuning.json` somebody edited
        // can fail in ways neither names, and none of them is worth a watch that will not search.
        @Suppress("TooGenericExceptionCaught")
        DictLog.w { "tuning: $ASSET no se pudo leer (${e.message}); corre con los valores por defecto" }
        Tuning()
    }

    /**
     * The same read, from text.
     *
     * Split out so the gate can reach it: every interesting case here is a **badly written file**,
     * and staging those through `context.assets` would mean shipping broken assets to test them.
     */
    fun parse(texto: String): Tuning {
        val raiz = JSONObject(texto)
        val leido = Tuning(
            search = searchFrom(raiz.optJSONObject("search")),
            display = displayFrom(raiz.optJSONObject("display")),
        )
        DictLog.i {
            "tuning: formas=${leido.display.forms.joinToString(",")} " +
                "acepciones=${leido.display.visibleSenses} sonda=${leido.display.emptyProbeCap} " +
                "interlineado=${leido.display.glossLineHeightInFonts} " +
                "limite=${leido.search.limit} difusos=${leido.search.fuzzyCandidates}"
        }
        return leido
    }

    private fun searchFrom(json: JSONObject?): SearchTuning {
        val d = SearchTuning()
        if (json == null) return d
        return SearchTuning(
            limit = positivo(json, "limit", d.limit),
            prefixOverfetch = positivo(json, "prefixOverfetch", d.prefixOverfetch),
            fuzzyTrigger = positivo(json, "fuzzyTrigger", d.fuzzyTrigger),
            fuzzyCandidates = positivo(json, "fuzzyCandidates", d.fuzzyCandidates),
        )
    }

    private fun displayFrom(json: JSONObject?): DisplayTuning {
        val d = DisplayTuning()
        if (json == null) return d
        return DisplayTuning(
            forms = formas(json, d.forms),
            visibleSenses = positivo(json, "visibleSenses", d.visibleSenses),
            emptyProbeCap = positivo(json, "emptyProbeCap", d.emptyProbeCap),
            glossLineHeightInFonts = fraccion(json, "glossLineHeightInFonts", d.glossLineHeightInFonts),
        )
    }

    /**
     * An ordered list of form keys, or the default.
     *
     * ⚠️ **An EMPTY list falls back instead of being obeyed**, and that asymmetry is deliberate:
     * `"forms": []` reads like a typo far more often than like *show no forms at all*, and
     * obeying it produces a card with a section that silently never draws. Somebody who really
     * wants none can delete the whole section, which is a code change and gets reviewed.
     */
    private fun formas(json: JSONObject, porDefecto: List<String>): List<String> {
        val array = json.optJSONArray("forms") ?: return porDefecto
        val claves = (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
        if (claves.isEmpty()) {
            DictLog.w { "tuning: 'forms' vacia o ilegible; se usan las ${porDefecto.size} por defecto" }
            return porDefecto
        }
        return claves
    }

    /**
     * A whole number above zero, or the default.
     *
     * Zero and negatives fall back rather than being obeyed: every one of these is a count or a
     * multiplier, and a `limit` of 0 is a search that returns nothing with no error anywhere.
     */
    private fun positivo(json: JSONObject, clave: String, porDefecto: Int): Int {
        if (!json.has(clave)) return porDefecto
        val valor = json.optInt(clave, porDefecto)
        if (valor <= 0) {
            DictLog.w { "tuning: '$clave'=$valor no es un entero positivo; se usa $porDefecto" }
            return porDefecto
        }
        return valor
    }

    /** A positive fraction, or the default. Same rule as [positivo]. */
    private fun fraccion(json: JSONObject, clave: String, porDefecto: Float): Float {
        if (!json.has(clave)) return porDefecto
        val valor = json.optDouble(clave, porDefecto.toDouble()).toFloat()
        if (!valor.isFinite() || valor <= 0f) {
            DictLog.w { "tuning: '$clave'=$valor no es un numero positivo; se usa $porDefecto" }
            return porDefecto
        }
        return valor
    }
}
