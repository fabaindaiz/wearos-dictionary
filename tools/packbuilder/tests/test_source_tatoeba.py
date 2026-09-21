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


class FrecuenciasTest(unittest.TestCase):
    """La señal de **uso** que elige el vocabulario de un pack núcleo.

    ⚠️ **Existe porque `rank` no sirve para esto y eso está medido.** `rank` es riqueza de página
    del diccionario, no frecuencia de habla: un núcleo elegido por `rank` se lleva **el 91 % de la
    tabla de flexiones** del pack español, porque las páginas más ricas son los verbos y un verbo
    español tiene 33 formas. Ver docs/roadmap.md §Dividir los packs grandes.
    """

    def _corpus(self, *frases):
        h = tempfile.NamedTemporaryFile(mode="w", suffix=".tsv", encoding="utf-8", delete=False)
        with h:
            for i, texto in enumerate(frases):
                h.write("%d\tspa\t%s\n" % (i + 1, texto))
        return h.name

    def test_cuenta_por_clave_normalizada(self):
        # La clave es `norm()`, la misma que indexa el pack: cualquier otra cosa seria una segunda
        # definicion de la igualdad de palabras.
        path = self._corpus("el árbol es alto", "un arbol pequeño", "otro ÁRBOL")
        frec = tatoeba.frequencies(path)
        self.assertEqual(3, frec["arbol"])

    def test_cuenta_sobre_TODAS_las_frases_no_solo_las_que_sirven_de_ejemplo(self):
        # ⚠️ `_frases` filtra por largo (15-80) porque sirve para EJEMPLOS, donde el renglon del
        # reloj es el limite. Contar frecuencia con ese filtro sesgaria la cuenta hacia las frases
        # de largo medio, que no tienen por que usar las palabras mas comunes.
        corta = "pan"                       # 3 caracteres: debajo del piso de los ejemplos
        larga = "x" * 200 + " zumbido"       # 208: por encima del techo
        path = self._corpus(corta, larga, "hola")
        frec = tatoeba.frequencies(path)
        self.assertEqual(1, frec.get("zumbido"), "una frase larga cuenta igual")
        self.assertEqual(1, frec.get("pan"), "una frase corta tambien")

    def test_un_nombre_propio_que_CASI_nunca_va_en_minuscula_tampoco_cuenta(self):
        # ⚠️ **"Vista en minuscula ALGUNA VEZ" no alcanza acá, y se midió por qué.** En 442.135
        # frases casi cualquier palabra aparece en minuscula una vez --un tipeo, un guion-- y
        # "tom" pasaba el filtro con **1 de 36.749 apariciones** (0,0 %), quedando en el puesto 11
        # del español. Para elegir un ejemplo ese ruido es barato; para elegir el vocabulario de
        # un pack, no.
        #
        # La separacion medida es enorme y limpia: `tom` 0,0 %, `maria` 0,3 %, `john` 0,0 %
        # contra `agua` 99,4 %, `water` 98,1 %, `enero` 89,2 %. El umbral va en el medio.
        frases = ["Tom come pan"] * 9 + ["a tom le gusta"]   # 10 % en minuscula
        path = self._corpus(*frases)
        self.assertNotIn("tom", tatoeba.frequencies(path))

    def test_un_nombre_propio_que_NUNCA_va_en_minuscula_no_cuenta(self):
        # ⚠️ **Medido sobre el corpus real: "Tom" esta en 36.694 de 442.135 frases, el 8,3 %.**
        # Sin este filtro seria una de las palabras mas frecuentes del español y entraria al
        # nucleo antes que "agua". Es el mismo hecho del corpus que salvo a "nadal".
        path = self._corpus("Tom come pan", "Tom bebe agua", "el pan es agua")
        frec = tatoeba.frequencies(path)
        self.assertNotIn("tom", frec)
        self.assertEqual(2, frec["pan"])

    def test_pero_una_palabra_que_SI_aparece_en_minuscula_cuenta_aunque_empiece_frases(self):
        # El control: toda frase empieza en mayuscula, asi que el filtro no puede ser posicional.
        path = self._corpus("Agua por favor", "quiero agua")
        self.assertEqual(2, tatoeba.frequencies(path)["agua"])

    def test_los_digitos_no_son_palabras(self):
        path = self._corpus("tengo 25 años", "covid19 existe")
        frec = tatoeba.frequencies(path)
        self.assertNotIn("25", frec)
        self.assertNotIn("covid19", frec)

    def test_el_resultado_no_depende_del_orden_del_archivo(self):
        # Dos builds del mismo dump tienen que dar el mismo pack.
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
