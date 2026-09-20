"""La TERCERA fuente: frases de Tatoeba, para las entradas que se ven vacias.

Tatoeba es un corpus de oraciones, no un diccionario: no define nada y no pretende hacerlo. Lo
que aporta es **uso real**, que es justo lo que le falta al 70,4 % del pack español.

⚠️ **Por que vale mas que la segunda fuente.** Medido contra el mismo pack: enwiktionary rescata
**307** entradas, Tatoeba **7.019** -- 23 veces mas, con una regla igual de estricta. La razon es
que un ejemplo no necesita que las dos fuentes estén de acuerdo sobre las acepciones: solo
necesita **contener la palabra sin ambigüedad**.

⚠️ **Y el precio es distinto.** Las oraciones son CC BY 2.0 FR, no CC0: el export `sentences_CC0`
existe pero trae **37 frases en español** de 562.186. Asi que hay atribucion obligatoria, igual
que en D-135.
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
        # Tiene que ser el mismo `norm()` que indexa el pack, o la frase no se encuentra nunca.
        got = self.mapa(("spa", "Tomé un café cargado."))
        self.assertIn("cafe", got, "la tilde tiene que haberse plegado como en el indice")

    def test_LO_QUE_CUESTA_el_filtro_de_mayusculas(self):
        """El precio del filtro de arriba, escrito para que no se descubra como un bug.

        "Alemania" y "México" **sí** están en el pack: D-116 los deja pasar porque tienen vida
        lexica. Pero en una frase van en mayuscula y a mitad de oracion, asi que este modulo los
        descarta junto con "Nadal" y se quedan sin ejemplo.

        Es la direccion segura del error: son ~731 nombres propios en el pack español contra la
        alternativa, que es colgarle a una palabra comun una frase sobre otra cosa. Si algun dia
        se quiere recuperarlos, el camino es un segundo mapa para claves capitalizadas que el
        builder use **solo** con lemas capitalizados -- no relajar esto.
        """
        got = self.mapa(("spa", "Nos vamos a Alemania mañana temprano."))
        self.assertNotIn("alemania", got)
        self.assertIn("temprano", got)

    def test_otro_idioma_no_entra(self):
        got = self.mapa(("eng", "This is a sentence."), ("spa", "Esto es una frase."))
        self.assertNotIn("sentence", got)
        self.assertIn("frase", got)

    def test_gana_la_frase_mas_CORTA(self):
        """En un reloj el renglon es el recurso escaso, y las dos frases son igual de validas."""
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
        """El error que encontro leer el pack, y que ningun test veia.

        La palabra dialectal "nadal" (Navidad) recibio *"Alonso, Nadal y Pau Gasol, entre los
        mejor pagados del mundo."*. `norm()` baja a minusculas, asi que el apellido del tenista
        colisiona con el sustantivo comun -- y el filtro de ambiguedad del builder **no puede
        verlo**, porque D-116 poda los nombres propios y no queda una segunda entrada con la que
        empatar. La clave parece inequivoca porque el competidor fue podado.

        ⚠️ **El primer intento fue una heuristica de POSICION --descartar las mayusculas a mitad
        de frase-- y no alcanzo**: "Nadal, mejor deportista español de la historia" empieza con
        el apellido, asi que pasaba por la excepcion de la primera palabra. Lo que si separa es
        un hecho del corpus y no una posicion: **una palabra que aparece en minuscula alguna vez
        es comun; una que aparece siempre en mayuscula es un nombre propio.**
        """
        got = self.mapa(("spa", "Nadal gano el torneo de tenis otra vez."),
                        ("spa", "Alonso, Nadal y Gasol ganaron mucho dinero."))
        self.assertNotIn("nadal", got)
        self.assertIn("ganaron", got, "las minusculas de la misma frase siguen valiendo")

    def test_la_primera_palabra_indexa_SI_el_corpus_la_escribe_en_minuscula_alguna_vez(self):
        """Toda frase empieza en mayuscula: sin esto se perderia una palabra de cada oracion.

        Alcanza con que **otra** frase la traiga en minuscula, que es lo que pasa con cualquier
        palabra comun en un corpus de 442.135 oraciones.
        """
        got = self.mapa(("spa", "Corrimos hasta la esquina."),
                        ("spa", "Ayer corrimos por el parque."))
        self.assertIn("corrimos", got)

    def test_LO_QUE_CUESTA_el_filtro(self):
        """El precio, escrito para que no se descubra despues como si fuera un bug.

        "Alemania" y "México" **sí** están en el pack: D-116 los deja pasar porque tienen vida
        lexica. Pero el corpus casi nunca los escribe en minuscula, asi que se quedan sin frase.

        Es la direccion segura del error: son ~731 nombres propios en el pack español, contra la
        alternativa de colgarle a una palabra comun una frase sobre otra cosa. Si algun dia se
        quieren recuperar, el camino es un segundo mapa de claves capitalizadas que el builder
        use **solo** con lemas capitalizados -- no relajar esto.
        """
        got = self.mapa(("spa", "Nos vamos a Alemania mañana temprano."))
        self.assertNotIn("alemania", got)
        self.assertIn("temprano", got)

    def test_los_numeros_no_son_palabras(self):
        got = self.mapa(("spa", "Compró 25 naranjas frescas."))
        self.assertNotIn("25", got)
        self.assertIn("naranjas", got)
