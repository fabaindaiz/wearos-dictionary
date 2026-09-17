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


def check_forbidden_dependency(report):
    """Regla: androidx.glance:glance-wear-tiles esta prohibido. (D-025, app/CLAUDE.md)

    Deprecado y sera removido. El naming confunde y es el primer resultado al buscar como
    hacer un Tile con Glance.
    """
    for base, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in (".git", "build", ".gradle", "__pycache__", "docs")]
        for name in files:
            if not name.endswith((".kts", ".toml", ".gradle")):
                continue
            path = os.path.join(base, name)
            with open(path, encoding="utf-8") as handle:
                if "glance-wear-tiles" in handle.read():
                    report.failure(
                        "dependencia prohibida",
                        "%s usa androidx.glance:glance-wear-tiles (D-025)"
                        % os.path.relpath(path, ROOT),
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


CHECKS = [
    check_mirror_declarations,
    check_version_constants,
    check_unicode_table,
    check_fuzzy_profiles,
    check_doc_paths,
    check_markdown_links,
    check_index_definitions,
    check_forbidden_dependency,
    check_root_budget,
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
