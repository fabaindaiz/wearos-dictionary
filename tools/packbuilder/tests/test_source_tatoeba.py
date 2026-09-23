"""The THIRD source: Tatoeba sentences, for the entries that look empty.

Tatoeba is a corpus of sentences, not a dictionary: it defines nothing and does not pretend to.
What it contributes is **real usage**, which is exactly what 70.4 % of the Spanish pack lacks.

⚠️ **Why it is worth more than the second source.** Measured against the same pack: enwiktionary
rescues **307** entries, Tatoeba **7,019** -- 23 times more, under an equally strict rule. The
reason is that an example does not need the two sources to agree about the senses: it only needs
to **contain the word unambiguously**.

⚠️ **And the price is different.** The sentences are CC BY 2.0 FR, not CC0: the `sentences_CC0`
export exists but brings **37 Spanish sentences** out of 562,186. So attribution is compulsory,
just as in D-135.
"""

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from sources import tatoeba  # noqa: E402


def _tsv(*filas):
    handle = tempfile.NamedTemporaryFile(
        mode="w", suffix=".tsv", encoding="utf-8", delete=False
    )
    with handle:
        for i, (lang, texto) in enumerate(filas):
            handle.write("%d\t%s\t%s\n" % (i + 1, lang, texto))
    return handle.name


class TatoebaTest(unittest.TestCase):

    def setUp(self):
        self.paths = []

    def tearDown(self):
        for path in self.paths:
            os.unlink(path)

    def mapa(self, *filas):
        path = _tsv(*filas)
        self.paths.append(path)
        return tatoeba.shortest_by_norm(path)

    def test_cada_palabra_de_la_frase_es_una_clave(self):
        got = self.mapa(("spa", "El hiragana es un silabario."))
        self.assertEqual("El hiragana es un silabario.", got["hiragana"])
        self.assertEqual("El hiragana es un silabario.", got["silabario"])

    def test_la_clave_es_la_forma_NORMALIZADA(self):
        # It has to be the same `norm()` that indexes the pack, or the sentence is never found.
        got = self.mapa(("spa", "Tomé un café cargado."))
        self.assertIn("cafe", got, "la tilde tiene que haberse plegado como en el indice")

    def test_LO_QUE_CUESTA_el_filtro_de_mayusculas(self):
        """The price of the filter above, written down so it is not discovered as a bug.

        "Alemania" and "México" **are** in the pack: D-116 lets them through because they have
        lexical life. But in a sentence they go capitalized and mid-sentence, so this module
        discards them along with "Nadal" and they end up with no example.

        It is the error's safe direction: it is ~731 proper nouns in the Spanish pack against the
        alternative, which is hanging a sentence about something else off a common word. If they
        are ever to be recovered, the way is a second map for capitalized keys that the builder
        uses **only** with capitalized lemmas -- not relaxing this.
        """
        got = self.mapa(("spa", "Nos vamos a Alemania mañana temprano."))
        self.assertNotIn("alemania", got)
        self.assertIn("temprano", got)

    def test_otro_idioma_no_entra(self):
        got = self.mapa(("eng", "This is a sentence."), ("spa", "Esto es una frase."))
        self.assertNotIn("sentence", got)
        self.assertIn("frase", got)

    def test_gana_la_frase_mas_CORTA(self):
        """On a watch the line is the scarce resource, and both sentences are equally valid."""
        got = self.mapa(("spa", "Tom dio con el bug mientras revisaba todo el codigo ayer."),
                        ("spa", "Tom dio con el bug."))
        self.assertEqual("Tom dio con el bug.", got["bug"])

    def test_una_frase_demasiado_corta_o_demasiado_larga_no_sirve(self):
        corta = "Sí."
        larga = "Este es un ejemplo deliberadamente larguisimo que no entra de ninguna " \
                "manera en la pantalla de un reloj sin comerse la definicion entera."
        self.assertLess(len(corta), tatoeba.MIN_LARGO)
        self.assertGreater(len(larga), tatoeba.MAX_LARGO)
        got = self.mapa(("spa", corta), ("spa", larga))
        self.assertEqual({}, got)

    def test_una_palabra_que_el_corpus_SIEMPRE_escribe_en_mayuscula_no_indexa(self):
        """The error reading the pack found, and that no test could see.

        The dialectal word "nadal" (Christmas) received *"Alonso, Nadal y Pau Gasol, entre los
        mejor pagados del mundo."*. `norm()` lowercases, so the tennis player's surname collides
        with the common noun -- and the builder's ambiguity filter **cannot see it**, because
        D-116 prunes the proper nouns and no second entry is left to tie with. The key looks
        unambiguous because the competitor was pruned.

        ⚠️ **The first attempt was a POSITION heuristic --discarding capitals mid-sentence-- and it
        was not enough**: "Nadal, mejor deportista español de la historia" starts with the surname,
        so it passed through the first-word exception. What does separate them is a fact about the
        corpus and not a position: **a word that appears in lowercase at least once is common; one
        that always appears capitalized is a proper noun.**
        """
        got = self.mapa(("spa", "Nadal gano el torneo de tenis otra vez."),
                        ("spa", "Alonso, Nadal y Gasol ganaron mucho dinero."))
        self.assertNotIn("nadal", got)
        self.assertIn("ganaron", got, "las minusculas de la misma frase siguen valiendo")

    def test_la_primera_palabra_indexa_SI_el_corpus_la_escribe_en_minuscula_alguna_vez(self):
        """Every sentence starts capitalized: without this one word per sentence would be lost.

        It is enough for **another** sentence to carry it in lowercase, which is what happens with
        any common word in a corpus of 442,135 sentences.
        """
        got = self.mapa(("spa", "Corrimos hasta la esquina."),
                        ("spa", "Ayer corrimos por el parque."))
        self.assertIn("corrimos", got)

    def test_LO_QUE_CUESTA_el_filtro(self):
        """The price, written down so it is not discovered later as if it were a bug.

        "Alemania" and "México" **are** in the pack: D-116 lets them through because they have
        lexical life. But the corpus almost never writes them in lowercase, so they end up with no
        sentence.

        It is the error's safe direction: it is ~731 proper nouns in the Spanish pack, against the
        alternative of hanging a sentence about something else off a common word. If they are ever
        to be recovered, the way is a second map of capitalized keys that the builder uses **only**
        with capitalized lemmas -- not relaxing this.
        """
        got = self.mapa(("spa", "Nos vamos a Alemania mañana temprano."))
        self.assertNotIn("alemania", got)
        self.assertIn("temprano", got)

    def test_los_numeros_no_son_palabras(self):
        got = self.mapa(("spa", "Compró 25 naranjas frescas."))
        self.assertNotIn("25", got)
        self.assertIn("naranjas", got)


class FrecuenciasTest(unittest.TestCase):
    """The **usage** signal that chooses a core pack's vocabulary.

    ⚠️ **It exists because `rank` is no use for this and that is measured.** `rank` is richness of
    the dictionary's page, not frequency of speech: a core chosen by `rank` takes **91 % of the
    Spanish pack's inflections table**, because the richest pages are the verbs and a Spanish verb
    has 33 forms. See docs/roadmap.md §Dividir los packs grandes.
    """

    def _corpus(self, *frases):
        h = tempfile.NamedTemporaryFile(mode="w", suffix=".tsv", encoding="utf-8", delete=False)
        with h:
            for i, texto in enumerate(frases):
                h.write("%d\tspa\t%s\n" % (i + 1, texto))
        return h.name

    def test_cuenta_por_clave_normalizada(self):
        # The key is `norm()`, the same one that indexes the pack: anything else would be a second
        # definition of word equality.
        path = self._corpus("el árbol es alto", "un arbol pequeño", "otro ÁRBOL")
        frec = tatoeba.frequencies(path)
        self.assertEqual(3, frec["arbol"])

    def test_cuenta_sobre_TODAS_las_frases_no_solo_las_que_sirven_de_ejemplo(self):
        # ⚠️ `_frases` filters by length (15-80) because it serves for EXAMPLES, where the watch's
        # line is the limit. Counting frequency with that filter would bias the count towards
        # medium-length sentences, which have no reason to use the most common words.
        corta = "pan"                       # 3 caracteres: debajo del piso de los ejemplos
        larga = "x" * 200 + " zumbido"       # 208: por encima del techo
        path = self._corpus(corta, larga, "hola")
        frec = tatoeba.frequencies(path)
        self.assertEqual(1, frec.get("zumbido"), "una frase larga cuenta igual")
        self.assertEqual(1, frec.get("pan"), "una frase corta tambien")

    def test_un_nombre_propio_que_CASI_nunca_va_en_minuscula_tampoco_cuenta(self):
        # ⚠️ **"Seen in lowercase AT LEAST ONCE" is not enough here, and why was measured.** In
        # 442,135 sentences almost any word appears in lowercase once --a typo, a hyphen-- and
        # "tom" passed the filter with **1 of 36,749 occurrences** (0.0 %), landing in position 11
        # for Spanish. For choosing an example that noise is cheap; for choosing a pack's
        # vocabulary, it is not.
        #
        # The measured separation is enormous and clean: `tom` 0.0 %, `maria` 0.3 %, `john` 0.0 %
        # against `agua` 99.4 %, `water` 98.1 %, `enero` 89.2 %. The threshold goes in the middle.
        frases = ["Tom come pan"] * 9 + ["a tom le gusta"]   # 10 % en minuscula
        path = self._corpus(*frases)
        self.assertNotIn("tom", tatoeba.frequencies(path))

    def test_un_nombre_propio_que_NUNCA_va_en_minuscula_no_cuenta(self):
        # ⚠️ **Measured over the real corpus: "Tom" is in 36,694 of 442,135 sentences, 8.3 %.**
        # Without this filter it would be one of Spanish's most frequent words and would enter the
        # core ahead of "agua". It is the same fact about the corpus that saved "nadal".
        path = self._corpus("Tom come pan", "Tom bebe agua", "el pan es agua")
        frec = tatoeba.frequencies(path)
        self.assertNotIn("tom", frec)
        self.assertEqual(2, frec["pan"])

    def test_pero_una_palabra_que_SI_aparece_en_minuscula_cuenta_aunque_empiece_frases(self):
        # The control: every sentence starts capitalized, so the filter cannot be positional.
        path = self._corpus("Agua por favor", "quiero agua")
        self.assertEqual(2, tatoeba.frequencies(path)["agua"])

    def test_los_digitos_no_son_palabras(self):
        path = self._corpus("tengo 25 años", "covid19 existe")
        frec = tatoeba.frequencies(path)
        self.assertNotIn("25", frec)
        self.assertNotIn("covid19", frec)

    def test_el_resultado_no_depende_del_orden_del_archivo(self):
        # Two builds of the same dump have to give the same pack.
        a = tatoeba.frequencies(self._corpus("agua fria", "pan duro", "agua"))
        b = tatoeba.frequencies(self._corpus("pan duro", "agua", "agua fria"))
        self.assertEqual(a, b)

    def test_el_idioma_se_puede_pedir(self):
        # El corpus CC0 es multilingue: la columna 2 decide.
        h = tempfile.NamedTemporaryFile(mode="w", suffix=".tsv", encoding="utf-8", delete=False)
        with h:
            h.write("1\tspa\tagua clara\n2\teng\twater clear\n")
        self.assertIn("water", tatoeba.frequencies(h.name, lang="eng"))
        self.assertNotIn("agua", tatoeba.frequencies(h.name, lang="eng"))
