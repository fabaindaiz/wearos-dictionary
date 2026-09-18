"""Comprueba el repositorio contra las reglas que él mismo escribe sobre sí mismo.

Cada regla de acá está escrita en CLAUDE.md, en un <area>/CLAUDE.md o en docs/decisions.md.
Si una regla cambia allá, cambiala acá también; si un check de acá no tiene regla escrita, no
debería estar rompiendo el build.

Dos severidades:
  - FALLA     detiene el build.
  - AVISO     se imprime siempre y no detiene nada. Se usa cuando las excepciones legítimas
              son reales: un aviso que grita en falso se termina ignorando.

Corre sin red y sin dependencias: es parte del gate.
"""

import hashlib
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Directorios de primer nivel cuyos paths se consideran referencias reales al repo.
REPO_DIRS = ("dict-core/", "tools/", "app/", "docs/", ".claude/", "gradle/", "dict-data/")

MARKDOWN = []
for base, dirs, files in os.walk(ROOT):
    dirs[:] = [d for d in dirs if d not in (".git", "build", ".gradle", ".idea", "__pycache__")]
    for name in files:
        if name.endswith(".md"):
            MARKDOWN.append(os.path.join(base, name))


class Report:
    def __init__(self):
        self.failures = []
        self.advisories = []

    def failure(self, rule, detail):
        self.failures.append((rule, detail))

    def advisory(self, rule, detail):
        self.advisories.append((rule, detail))


def read(path):
    with open(os.path.join(ROOT, path), encoding="utf-8") as handle:
        return handle.read()


# --------------------------------------------------------------------------- checks


def check_mirror_declarations(report):
    """Regla: todo archivo con espejo lo declara, y la ruta declarada existe. (tools/CLAUDE.md)

    Que el CONTENIDO coincida lo comprueban los vectores compartidos, no esto.
    """
    pattern = re.compile(r"ESPEJO(?:\s+GENERADO)?:\s*\n?[#/\* ]*([\w./\-]+\.(?:kt|py))")
    found = 0
    for base, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in (".git", "build", ".gradle", "__pycache__")]
        for name in files:
            if not name.endswith((".kt", ".py")):
                continue
            path = os.path.join(base, name)
            with open(path, encoding="utf-8") as handle:
                text = handle.read()
            for match in pattern.finditer(text):
                found += 1
                target = match.group(1)
                candidates = [
                    os.path.join(ROOT, target),
                    os.path.join(ROOT, "tools", "packbuilder", target),
                    os.path.join(os.path.dirname(path), target),
                ]
                if not any(os.path.exists(c) for c in candidates):
                    report.failure(
                        "espejo declarado que no existe",
                        "%s declara espejo en %s" % (os.path.relpath(path, ROOT), target),
                    )
    if found == 0:
        report.failure(
            "ningun espejo declarado",
            "se esperaban declaraciones 'ESTE ARCHIVO TIENE UN ESPEJO';"
            " el check no esta viendo nada",
        )


def check_version_constants(report):
    """Regla: las constantes espejadas coinciden entre Kotlin y Python. (CLAUDE.md, D-005/D-006)"""
    pairs = [
        (
            "NORM_VERSION",
            r"const val NORM_VERSION: Int = (\d+)",
            "dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/TextNormalizer.kt",
            r"^NORM_VERSION = (\d+)",
            "tools/packbuilder/normalize.py",
        ),
        (
            "PAYLOAD_VERSION",
            r"const val PAYLOAD_VERSION: Int = (\d+)",
            "dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/PayloadCodec.kt",
            r"^PAYLOAD_VERSION = (\d+)",
            "tools/packbuilder/payload.py",
        ),
        (
            # No es un espejo de implementacion como los otros: es la version del esquema que
            # el builder escribe y la que la app acepta. Si se desincronizan, todo pack recien
            # construido se rechaza al abrirse -- ruidoso, pero solo en dispositivo.
            "SCHEMA_VERSION",
            r"const val SUPPORTED_SCHEMA_VERSION: Int = (\d+)",
            "dict-data/src/main/kotlin/cl/fadiaz/dictionary/data/PackFile.kt",
            r"^SCHEMA_VERSION = (\d+)",
            "tools/packbuilder/build.py",
        ),
        (
            "CODEC_ID",
            r'const val CODEC_ID: String = "([^"]+)"',
            "dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/PayloadCodec.kt",
            r'^CODEC_ID = "([^"]+)"',
            "tools/packbuilder/payload.py",
        ),
    ]
    for name, kt_re, kt_path, py_re, py_path in pairs:
        kt = re.search(kt_re, read(kt_path), re.M)
        py = re.search(py_re, read(py_path), re.M)
        if not kt or not py:
            report.failure("constante no encontrada", "%s no se pudo leer de ambos lados" % name)
        elif kt.group(1) != py.group(1):
            report.failure(
                "constante espejada desincronizada",
                "%s: Kotlin=%s Python=%s. Un pack construido ahora seria rechazado o, peor, "
                "aceptado con claves distintas" % (name, kt.group(1), py.group(1)),
            )


def check_unicode_table(report):
    """Regla: las dos copias del repertorio salen del mismo origen. (D-003)"""
    data = {}
    for line in read("tools/unicode/repertoire.txt").splitlines():
        if line and not line.startswith("#"):
            key, _, value = line.partition(" ")
            data[key] = value

    digest = hashlib.sha256(data["data"].encode("ascii")).hexdigest()
    if digest != data["sha256"]:
        report.failure("repertoire.txt corrupto", "el sha256 declarado no corresponde a los datos")

    kotlin = read("dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/UnicodeRepertoire.kt")
    match = re.search(r'const val DIGEST: String = "([0-9a-f]+)"', kotlin)
    if not match:
        report.failure("UnicodeRepertoire sin DIGEST", "no se encontro la constante")
    elif match.group(1) != data["sha256"]:
        report.failure(
            "repertorio desincronizado",
            "UnicodeRepertoire.kt no corresponde a repertoire.txt. Regenerar con "
            "python3 tools/unicode/gen_repertoire.py",
        )


def check_fuzzy_profiles(report):
    """Regla: los perfiles existen en los dos lenguajes. (dict-core/CLAUDE.md)"""
    kotlin = set(
        re.findall(r'^\s{4}[A-Z_]+\(\s*\n?\s*"([a-z]+)"', read(
            "dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/FuzzyProfile.kt"), re.M)
    )
    kotlin |= set(re.findall(
        r'[A-Z_]+\("([a-z]+)", emptyList\(\)\)',
        read("dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/FuzzyProfile.kt")))
    python = set(re.findall(r'^\s{4}"([a-z]+)":', read("tools/packbuilder/normalize.py"), re.M))

    if not kotlin or not python:
        report.failure("perfiles no detectados", "el check no esta viendo perfiles en algun lado")
        return
    if kotlin != python:
        report.failure(
            "perfiles fuzzy desincronizados",
            "solo en Kotlin: %s | solo en Python: %s"
            % (sorted(kotlin - python) or "-", sorted(python - kotlin) or "-"),
        )


def check_doc_paths(report):
    """Regla: un documento no apunta a un archivo que no existe. (CLAUDE.md, mapa de documentos)

    Un puntero muerto es peor que no tener puntero, y es la decadencia mas comun en un repo
    asistido por agentes: borran codigo mas rapido de lo que releen prosa.

    docs/roadmap.md queda exento a proposito: su trabajo es nombrar cosas que todavia no
    existen.
    """
    token = re.compile(r"`([^`\n]+)`")
    for path in MARKDOWN:
        relative = os.path.relpath(path, ROOT)
        if relative == "docs/roadmap.md" or relative.startswith("docs/agents/"):
            continue
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        for match in token.finditer(text):
            candidate = match.group(1).strip()
            if not candidate.startswith(REPO_DIRS):
                continue
            if any(ch in candidate for ch in "<>*| "):
                continue
            if not os.path.exists(os.path.join(ROOT, candidate)):
                report.failure(
                    "documento apunta a un archivo inexistente",
                    "%s -> %s" % (relative, candidate),
                )


def check_markdown_links(report):
    """Regla: los enlaces relativos entre documentos resuelven. (CLAUDE.md, mapa de documentos)"""
    link = re.compile(r"\[[^\]]+\]\(([^)]+)\)")
    for path in MARKDOWN:
        relative = os.path.relpath(path, ROOT)
        if relative.startswith("docs/agents/"):
            continue
        base = os.path.dirname(path)
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        for match in link.finditer(text):
            target = match.group(1).split("#")[0]
            if not target or target.startswith(("http://", "https://", "mailto:")):
                continue
            if not os.path.exists(os.path.join(base, target)):
                report.failure("enlace roto", "%s -> %s" % (relative, target))


def check_index_definitions(report):
    """Regla: cada indice se define una vez. (tools/CLAUDE.md, D-016)

    schema.sql e indexes.sql se separaron porque un comentario con un punto y coma rompia el
    split (D-041). Si un indice reaparece en schema.sql, el builder falla al crearlo dos veces.
    """
    schema = read("tools/packbuilder/schema.sql")
    indexes = read("tools/packbuilder/indexes.sql")
    if "CREATE INDEX" in schema:
        report.failure(
            "indice definido en schema.sql",
            "los CREATE INDEX van en indexes.sql, que corre al final sobre las tablas pobladas",
        )
    names = re.findall(r"CREATE INDEX (\w+)", indexes)
    duplicates = {n for n in names if names.count(n) > 1}
    if duplicates:
        report.failure("indice duplicado", ", ".join(sorted(duplicates)))


# Dependencias que no pueden entrar, con la decision que lo dice.
FORBIDDEN_DEPENDENCIES = {
    "glance-wear-tiles": "D-025: deprecado y sera removido; NO es la libreria de Wear Widgets",
    "androidx.glance.wear": "D-024: Wear Widgets esta pospuesto; los packages estan en alpha",
    "androidx.compose.remote": "D-024: RemoteCompose esta en alpha y solo existe en Wear OS 7",
}


def check_forbidden_dependency(report):
    """Regla: ciertas dependencias no entran. (D-024, D-025, app/CLAUDE.md)

    glance-wear-tiles es el caso peligroso: es el PRIMER resultado al buscar como hacer un Tile
    con Glance, y es el equivocado.
    """
    for base, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in (".git", "build", ".gradle", "__pycache__", "docs")]
        for name in files:
            if not name.endswith((".kts", ".toml", ".gradle")):
                continue
            path = os.path.join(base, name)
            with open(path, encoding="utf-8") as handle:
                text = handle.read()
            for needle, reason in FORBIDDEN_DEPENDENCIES.items():
                if needle in text:
                    report.failure(
                        "dependencia prohibida",
                        "%s usa %r -- %s" % (os.path.relpath(path, ROOT), needle, reason),
                    )


# Miembros de la JVM que NO se pueden sombrear con una extension de Kotlin: en la JVM gana el
# miembro nativo, asi que la extension nunca correria. Funcionaria hoy y fallaria el dia que el
# modulo apunte a otro target. Ver D-019.
SHADOWED_JVM_MEMBERS = ("appendCodePoint", "codePointAt", "codePointCount")


def check_shadowed_extensions(report):
    """Regla: un reemplazo portable de una API JVM lleva nombre distinto. (D-019)

    Ya paso una vez: `StringBuilder.appendCodePoint` como extension nunca se habria ejecutado.
    Se renombro a `appendUtf16`.
    """
    core = os.path.join(ROOT, "dict-core", "src", "main")
    for base, _dirs, files in os.walk(core):
        for name in files:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(base, name)
            with open(path, encoding="utf-8") as handle:
                for number, line in enumerate(handle, start=1):
                    code = line.split("//")[0]
                    for member in SHADOWED_JVM_MEMBERS:
                        if re.search(r"fun\s+\w+\.%s\s*\(" % member, code):
                            report.failure(
                                "extension que sombrea un miembro de la JVM",
                                "%s:%d define .%s() como extension: en la JVM gana el miembro "
                                "nativo y este codigo nunca correria (D-019)"
                                % (os.path.relpath(path, ROOT), number, member),
                            )


def check_forbidden_mirror(report):
    """Regla: entry.uid lo calcula SOLO el builder; en Kotlin no existe. (D-057)

    `norm()` y `fuzzy()` viven dos veces y por eso necesitan vectores compartidos que detecten
    que se separaron (D-005). `uid` se salva de todo eso mientras siga habiendo una sola
    implementacion: la app lo lee de la columna y nunca lo recalcula.

    El dia que alguien escriba `TextNormalizer.uid()` --por conveniencia, para no tener que leer
    la fila-- vuelve la clase de bug entera, y esta vez sin vectores que la atrapen.
    """
    sospechas = (
        (r"\bUID_RECIPE\b", "declara la receta del uid"),
        (r"fun\s+\w*[Uu]id\s*\(", "define una funcion que calcula un uid"),
        (r"fun\s+stableUid\b", "define stableUid()"),
    )
    for modulo in ("dict-core", "dict-data"):
        base_dir = os.path.join(ROOT, modulo, "src", "main")
        for base, _dirs, files in os.walk(base_dir):
            for name in files:
                if not name.endswith(".kt"):
                    continue
                path = os.path.join(base, name)
                with open(path, encoding="utf-8") as handle:
                    for number, line in enumerate(handle, start=1):
                        code = line.split("//")[0]
                        for patron, motivo in sospechas:
                            if re.search(patron, code):
                                report.failure(
                                    "el uid se calcula en Kotlin",
                                    "%s:%d %s. entry.uid lo escribe el builder y la app solo lo "
                                    "lee: una segunda implementacion puede divergir y no hay "
                                    "vectores que lo detecten (D-057)"
                                    % (os.path.relpath(path, ROOT), number, motivo),
                                )


def check_app_logic_is_jvm_testable(report):
    """Regla: la logica de :app no importa android.*; lo de Android entra por parametro. (D-072)

    `:app` estuvo sin un solo test hasta el 2026-09-17, y la razon no fue pereza: el ViewModel
    extendia AndroidViewModel y construia el pack desde un Context, asi que **no habia forma de
    correrlo en la JVM**. Retrofitear un test ahi costo un refactor, que es exactamente el precio
    que este check existe para no volver a pagar.

    Los archivos vigilados son los que tienen logica de verdad --el ViewModel y el resultado de
    abrir un pack--, no las pantallas: un Composable es Android por definicion y se prueba en un
    dispositivo. La frontera es `PackLoad`, que no conoce Android y por eso deja pasar un fake.
    """
    vigilados = (
        os.path.join("app", "src", "main", "java", "cl", "fadiaz", "dictionary",
                     "presentation", "SearchViewModel.kt"),
        os.path.join("app", "src", "main", "java", "cl", "fadiaz", "dictionary",
                     "data", "PackLoad.kt"),
        os.path.join("app", "src", "main", "java", "cl", "fadiaz", "dictionary",
                     "data", "PackSet.kt"),
        os.path.join("app", "src", "main", "java", "cl", "fadiaz", "dictionary",
                     "data", "Visita.kt"),
    )
    for relativo in vigilados:
        path = os.path.join(ROOT, relativo)
        if not os.path.isfile(path):
            report.failure(
                "la logica de :app dejo de ser testeable en la JVM",
                "%s no existe. Si se renombro, mover tambien este check: sin el, el proximo "
                "ViewModel vuelve a nacer atado a un Context y sin tests." % relativo,
            )
            continue
        with open(path, encoding="utf-8") as handle:
            for number, line in enumerate(handle, start=1):
                code = line.split("//")[0]
                if re.match(r"\s*import\s+android\.", code):
                    report.failure(
                        "la logica de :app dejo de ser testeable en la JVM",
                        "%s:%d importa android.*. Eso saca sus tests del gate y los manda al "
                        "dispositivo: lo que necesite Android tiene que entrar por parametro, "
                        "como `abrirPack` (D-072)." % (relativo, number),
                    )


def check_attribution_screen(report):
    """Regla: la atribucion se muestra, y sale del pack. (D-031)

    El contenido es CC BY-SA y mostrar de donde sale es la **condicion de uso de los datos**, no
    una cortesia. Si alguien borra esa pantalla para ganar espacio, nada mas en el repo lo dice.

    Este check es **barato y limitado a proposito**: comprueba que el archivo exista y que lo que
    muestra venga de `meta`, no de una constante en el codigo. Que los pixeles aparezcan lo
    prueba `PantallasTest.laAtribucionMuestraLaLicenciaYLaFuente`, que necesita un dispositivo y
    por lo tanto no corre en el gate. Los dos juntos son el enforcer; ninguno solo alcanza.
    """
    pantalla = os.path.join("app", "src", "main", "java", "cl", "fadiaz", "dictionary",
                            "presentation", "AttributionScreen.kt")
    path = os.path.join(ROOT, pantalla)
    if not os.path.isfile(path):
        report.failure(
            "la pantalla de atribucion desaparecio",
            "%s no existe. El contenido es CC BY-SA: mostrar la fuente y la licencia es la "
            "condicion de uso de los datos (D-031), no una pantalla opcional." % pantalla,
        )
        return
    with open(path, encoding="utf-8") as handle:
        texto = handle.read()
    for parametro in ("attribution", "license"):
        if parametro not in texto:
            report.failure(
                "la pantalla de atribucion dejo de mostrar %s" % parametro,
                "%s no menciona `%s`. Tiene que salir de meta del pack abierto: un pack de otra "
                "fuente trae otra licencia, y una constante en el codigo mostraria la "
                "equivocada (D-031)." % (pantalla, parametro),
            )
    # La atribucion tiene que leerse de la METADATA de cada pack abierto. Con dos packs esto
    # dejo de ser un detalle: mostrar una sola licencia es incumplir la condicion de la otra.
    if "metadata" not in texto:
        report.failure(
            "la atribucion dejo de leerse del pack",
            "%s no accede a la metadata de ningun pack. Con varios diccionarios instalados, "
            "cada uno trae SU licencia y todas tienen que mostrarse (D-031)." % pantalla,
        )


def check_root_budget(report):
    """Regla: CLAUDE.md se paga en cada request y vive bajo 200 lineas. (CLAUDE.md)"""
    lines = len(read("CLAUDE.md").splitlines())
    if lines > 200:
        report.failure(
            "CLAUDE.md paso su presupuesto",
            "%d lineas. Que seccion crecio? Una seccion que crece se volvio un documento" % lines,
        )
    elif lines > 175:
        report.advisory("CLAUDE.md cerca del limite", "%d de 200 lineas" % lines)


def check_rules_without_enforcer(report):
    """Aviso: cuantas decisiones se pueden romper en silencio. (docs/decisions.md)

    Es aviso y no falla porque una decision sin enforcer esta permitida: lo que no esta
    permitido es que sea invisible. Que este numero suba es la senal.
    """
    rows = re.findall(r"^\| (D-\d+) \|.*\|([^|]*)\|\s*$", read("docs/decisions.md"), re.M)
    without = [d for d, enforcer in rows if enforcer.strip().startswith("—")]
    report.advisory(
        "decisiones sin enforcer",
        "%d de %d se pueden romper en silencio: %s"
        % (len(without), len(rows), ", ".join(without)),
    )


def check_method_digest(report):
    """Regla: el set del metodo no se edita en el lugar; cambiarlo es forkear. (D-059)

    El header de docs/agents/prompt-*.md declara un digest: el sha256 del set concatenado en
    orden de nombre, con los bloques yaml del propio header sacados, cortado a 12 hex. Si el
    contenido no da ese numero, alguien edito el metodo sin forkear ni recalcular, y la proxima
    comparacion entre dos copias va a concluir "identicas" descartando un lado en silencio.

    Degrada seguro: si la carpeta no esta, no hay nada que comprobar.
    """
    folder = os.path.join(ROOT, "docs/agents")
    if not os.path.isdir(folder):
        return
    names = sorted(n for n in os.listdir(folder) if n.startswith("prompt-") and n.endswith(".md"))
    if not names:
        return

    declared = set()
    body = []
    for name in names:
        text = read(os.path.join("docs/agents", name))
        inside = False
        for line in text.splitlines(True):
            stripped = line.rstrip("\n")
            if not inside and stripped == "```yaml":
                inside = True
                continue
            if inside:
                if stripped == "```":
                    inside = False
                    continue
                match = re.match(r'\s*digest:\s*"([0-9a-f]+)"', stripped)
                if match:
                    declared.add(match.group(1))
                continue
            body.append(line)

    if not declared:
        report.failure("el set del metodo no declara digest", ", ".join(names))
        return
    if len(declared) > 1:
        report.failure(
            "los headers del set no coinciden",
            "digests distintos entre archivos: %s" % ", ".join(sorted(declared)),
        )
        return

    actual = hashlib.sha256("".join(body).encode("utf-8")).hexdigest()[:12]
    expected = declared.pop()
    if actual != expected:
        report.failure(
            "el set del metodo no corresponde a su digest",
            "declara %s y el contenido da %s. Editar el metodo es forkear (D-059): "
            "nueva id opaca en ancestry, forked_at, y recalcular el digest" % (expected, actual),
        )


CHECKS = [
    check_mirror_declarations,
    check_version_constants,
    check_unicode_table,
    check_fuzzy_profiles,
    check_doc_paths,
    check_markdown_links,
    check_index_definitions,
    check_forbidden_dependency,
    check_shadowed_extensions,
    check_forbidden_mirror,
    check_app_logic_is_jvm_testable,
    check_attribution_screen,
    check_root_budget,
    check_method_digest,
    check_rules_without_enforcer,
]


def main():
    report = Report()
    for check in CHECKS:
        try:
            check(report)
        except Exception as error:  # noqa: BLE001 - un check roto es una falla, no un crash
            report.failure("check roto", "%s: %r" % (check.__name__, error))

    for rule, detail in report.advisories:
        print("AVISO  %s: %s" % (rule, detail))
    for rule, detail in report.failures:
        print("FALLA  %s: %s" % (rule, detail))

    print("")
    print(
        "%d checks, %d fallas, %d avisos"
        % (len(CHECKS), len(report.failures), len(report.advisories))
    )
    return 1 if report.failures else 0


if __name__ == "__main__":
    sys.exit(main())
