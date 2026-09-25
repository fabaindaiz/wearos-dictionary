"""Checks the repository against the rules it writes about itself.

Every rule here is written in CLAUDE.md, in an <area>/CLAUDE.md or in docs/decisions.md. If a rule
changes there, change it here too; if a check here has no written rule, it should not be breaking
the build.

Two severities:
  - FALLA     stops the build.
  - AVISO     is always printed and stops nothing. It is used when the legitimate exceptions are
              real: an advisory that cries wolf ends up ignored.

It runs with no network and no dependencies: it is part of the gate.
"""

import hashlib
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUNDLE = ".agents"

#: Documents that name legitimately non-existent paths: the roadmap plans them, the changelog
#: remembers them. See check_doc_paths.
EXENTOS_DE_RUTAS = ("docs/roadmap.md", ".claude/logs/agent-changelog.md")

# Directorios de primer nivel cuyos paths se consideran referencias reales al repo.
REPO_DIRS = ("dict-core/", "tools/", "app/", "docs/", ".claude/", "gradle/", "dict-data/",
             ".agents/")

MARKDOWN = []
for base, dirs, files in os.walk(ROOT):
    dirs[:] = [d for d in dirs if d not in (".git", "build", ".gradle", ".idea", "__pycache__")]
    for name in files:
        if name.endswith(".md"):
            MARKDOWN.append(os.path.join(base, name))


#: The sources a mutation probe could dirty. The `.md` files are left out on purpose: a document
#: that EXPLAINS what a probe is, is not a probe, and this very file names them.
FUENTES = []
for base, dirs, files in os.walk(ROOT):
    dirs[:] = [d for d in dirs if d not in (".git", "build", ".gradle", ".idea", "__pycache__")]
    for name in files:
        if name.endswith((".kt", ".kts", ".py", ".java")):
            FUENTES.append(os.path.relpath(os.path.join(base, name), ROOT))


#: The markdown files by relative path. `MARKDOWN` keeps them absolute and the two uses differ.
MARKDOWN_REL = [os.path.relpath(p, ROOT) for p in MARKDOWN]


class Report:
    def __init__(self):
        self.failures = []
        self.advisories = []

    def failure(self, rule, detail):
        self.failures.append((rule, detail))

    def advisory(self, rule, detail):
        self.advisories.append((rule, detail))


# Whether this pass may REWRITE the counts it finds wrong. `--fix` switches it on.
#
# ⚠️ **Only the counts, and only the number.** It is the only failure in the file a human cannot
# deduce without running the suite: adding a test moves four or five figures spread across
# `README.md`, `app/CLAUDE.md`, `tools/CLAUDE.md` and `docs/roadmap.md`. Measured: it failed **five
# times in a single session**, always for the same reason, and **five more** in 2026-09-21's.
#
# ⚠️ **What `--fix` does NOT touch, on purpose**: a document having **stopped asserting** a count.
# There the sentence was rewritten or the datum deleted, and deciding that belongs to whoever
# writes -- an automatic fix would invent a sentence or delete a watch without anybody noticing.
ARREGLAR = False


def _escribir(path, texto):
    with open(os.path.join(ROOT, path), "w", encoding="utf-8") as handle:
        handle.write(texto)


def read(path):
    with open(os.path.join(ROOT, path), encoding="utf-8") as handle:
        return handle.read()


# --------------------------------------------------------------------------- checks


def check_mirror_declarations(report):
    """Rule: every mirrored file declares its mirror, and the declared path exists. (tools/CLAUDE.md)

    That the CONTENT matches is checked by the shared vectors, not by this.
    """
    # WARNING: both spellings are accepted while the translation is in flight. The marker moved
    # from "ESTE ARCHIVO TIENE UN ESPEJO" to "THIS FILE HAS A MIRROR" with the English pass, and
    # a check that only knew the new one would stop seeing any file nobody had translated yet --
    # it would pass by looking at nothing, which is exactly what the `found == 0` guard below is
    # for. That guard is what caught this the day the markers were translated.
    pattern = re.compile(
        r"(?:ESPEJO(?:\s+GENERADO)?|MIRROR(?:\s+\w+)?):\s*\n?[#/\* ]*([\w./\-]+\.(?:kt|py))"
    )
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
                        "declared mirror that does not exist",
                        "%s declares a mirror at %s" % (os.path.relpath(path, ROOT), target),
                    )
    if found == 0:
        report.failure(
            "ningun espejo declarado",
            "expected 'THIS FILE HAS A MIRROR' declarations;"
            " the check is seeing nothing",
        )


def check_version_constants(report):
    """Rule: the mirrored constants match between Kotlin and Python. (CLAUDE.md, D-005/D-006)"""
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
            # It is not an implementation mirror like the others: it is the schema version the
            # builder writes and the one the app accepts. If they drift apart, every freshly built
            # pack is rejected on opening -- loudly, but only on a device.
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
    """Rule: the repertoire's two copies come from the same origin. (D-003)"""
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
    """Rule: the profiles exist in both languages. (dict-core/CLAUDE.md)"""
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


def _patrones_ignorados():
    """The .gitignore patterns useful for deciding whether a path is generated.

    Deliberately partial: only directory prefixes (`build/`) and suffixes (`*.db`), which is all
    that is needed to tell a generated artifact from a repo file. It does not reimplement git's
    matcher, and it has no reason to.
    """
    patrones = []
    ruta = os.path.join(ROOT, ".gitignore")
    if not os.path.isfile(ruta):
        return patrones
    with open(ruta, encoding="utf-8") as handle:
        for linea in handle:
            linea = linea.strip()
            if not linea or linea.startswith(("#", "!")):
                continue
            patrones.append(linea.lstrip("/"))
    return patrones


def _esta_ignorado(candidate, patrones):
    partes = candidate.split("/")
    for patron in patrones:
        if patron.endswith("/"):
            # An ignored directory: any segment of the path naming it is enough.
            if patron.rstrip("/") in partes or candidate.startswith(patron):
                return True
        elif patron.startswith("*."):
            if candidate.endswith(patron[1:]):
                return True
        elif candidate == patron or candidate.startswith(patron.rstrip("*")):
            return True
    return False


def check_doc_paths(report):
    """Rule: a document does not point at a file that does not exist. (CLAUDE.md, document map)

    A dead pointer is worse than no pointer, and it is the commonest decay in an agent-assisted
    repo: they delete code faster than they re-read prose.

    docs/roadmap.md is exempt on purpose: its job is to name things that do not yet exist.

    ⚠️ **And the GENERATED is exempt too, because otherwise the check lies the other way**: it
    looks at `os.path.exists`, so a path like the toy pack --which `build_toy.py` writes and which
    D-020 decided not to commit-- **exists on the machine of anybody who has built once and does
    not exist in a clean clone**. The check passed in the tree of whoever wrote and failed in the
    tree of whoever reviewed. That is how it was found: verifying a commit with `git worktree`,
    which is where that difference shows.

    The exemption comes from `.gitignore` and not from a separate list: if git ignores it, it is
    not a repo file and a document may name it.

    ⚠️ **And the changelog is exempt for the same reason as the roadmap, but backwards in time**:
    it is a record and it names paths that existed when the entry was written. The docs/agents/
    folder was retired in D-221 and five old entries mention it correctly. The line-number
    grandfathering D-020 uses **is no use here**: the changelog is written from the top, so each
    session shifts every number. The cost is real and gets named: the repo's longest document has
    no path check.
    """
    ignorados = _patrones_ignorados()
    token = re.compile(r"`([^`\n]+)`")
    for path in MARKDOWN:
        relative = os.path.relpath(path, ROOT)
        if relative in EXENTOS_DE_RUTAS or relative.startswith(BUNDLE + "/"):
            continue
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        for match in token.finditer(text):
            candidate = match.group(1).strip()
            if not candidate.startswith(REPO_DIRS):
                continue
            if any(ch in candidate for ch in "<>*| "):
                continue
            if _esta_ignorado(candidate, ignorados):
                continue
            if not os.path.exists(os.path.join(ROOT, candidate)):
                report.failure(
                    "documento apunta a un archivo inexistente",
                    "%s -> %s" % (relative, candidate),
                )


def check_markdown_links(report):
    """Rule: the relative links between documents resolve. (CLAUDE.md, document map)"""
    link = re.compile(r"\[[^\]]+\]\(([^)]+)\)")
    for path in MARKDOWN:
        relative = os.path.relpath(path, ROOT)
        if relative.startswith(BUNDLE + "/"):
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
    """Rule: each index is defined once. (tools/CLAUDE.md, D-016)

    schema.sql and indexes.sql were separated because a comment with a semicolon broke the split
    (D-041). If an index reappears in schema.sql, the builder fails creating it twice.
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


# Dependencies that cannot get in, with the decision that says so.
FORBIDDEN_DEPENDENCIES = {
    "glance-wear-tiles": "D-025: deprecado y sera removido; NO es la libreria de Wear Widgets",
    "androidx.glance.wear": "D-024: Wear Widgets esta pospuesto; los packages estan en alpha",
    "androidx.compose.remote": "D-024: RemoteCompose esta en alpha y solo existe en Wear OS 7",
}


def check_forbidden_dependency(report):
    """Rule: certain dependencies do not get in. (D-024, D-025, app/CLAUDE.md)

    glance-wear-tiles is the dangerous case: it is the FIRST result when searching how to make a
    Tile with Glance, and it is the wrong one.
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


# JVM members that CANNOT be shadowed by a Kotlin extension: on the JVM the native member wins, so
# the extension would never run. It would work today and fail the day the module targets something
# else. See D-019.
SHADOWED_JVM_MEMBERS = ("appendCodePoint", "codePointAt", "codePointCount")


def check_shadowed_extensions(report):
    """Rule: a portable replacement for a JVM API carries a different name. (D-019)

    It already happened once: `StringBuilder.appendCodePoint` as an extension would never have
    run. It was renamed to `appendUtf16`.
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
    """Rule: ONLY the builder computes entry.uid; in Kotlin it does not exist. (D-057)

    `norm()` and `fuzzy()` live twice and therefore need shared vectors that detect they have
    drifted apart (D-005). `uid` escapes all of that as long as there is a single implementation:
    the app reads it from the column and never recomputes it.

    The day somebody writes `TextNormalizer.uid()` --out of convenience, to avoid reading the
    row-- the whole class of bug comes back, and this time with no vectors to catch it.
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


def check_module_direction(report):
    """Rule: :app -> :dict-data -> :dict-core, and never the other way. (docs/architecture.md)

    `docs/architecture.md` has asked for it in writing since it existed --"checking the direction
    is the first thing the audit has to add"-- and for three sessions the document described a
    check that did not exist. Now it does.

    The build file is looked at and not the imports, on purpose: `:app` and `:dict-data` **share
    the package name** `cl.fadiaz.dictionary.data`, so an import does not say which module it comes
    from. The dependency declaration does.

    What breaks if this gets inverted is not cosmetic: `:dict-core` is the one tested in
    milliseconds with no emulator and the one mirrored with the builder (D-005). A dependency
    upwards ties it to Android and those tests stop being able to run.
    """
    permitido = {
        "dict-core": set(),
        "dict-data": {":dict-core"},
        "app": {":dict-data", ":dict-core"},
    }
    for modulo, puede in permitido.items():
        relativo = os.path.join(modulo, "build.gradle.kts")
        if not os.path.isfile(os.path.join(ROOT, relativo)):
            report.failure("falta %s" % relativo, "si el modulo se renombro, mover este check")
            continue
        usados = set(re.findall(r'project\("(:[a-z-]+)"\)', read(relativo)))
        prohibidos = usados - puede
        if prohibidos:
            report.failure(
                ":%s depende hacia arriba" % modulo,
                "%s -- la direccion es :app -> :dict-data -> :dict-core y nunca al reves"
                % ", ".join(sorted(prohibidos)),
            )


def check_app_logic_is_jvm_testable(report):
    """Rule: `:app`'s logic does not import android.*; Android comes in as a parameter. (D-072)

    `:app` went without a single test until 2026-09-17, and the reason was not laziness: the
    ViewModel extended AndroidViewModel and built the pack from a Context, so **there was no way to
    run it on the JVM**. Retrofitting a test there cost a refactor, which is exactly the price this
    check exists not to pay again.

    The watched files are the ones with real logic --the ViewModel and the result of opening a
    pack-- and not the screens: a Composable is Android by definition and is tested on a device.
    The boundary is `PackLoad`, which knows no Android and therefore lets a fake through.
    """
    vigilados = (
        os.path.join("app", "src", "main", "java", "cl", "fadiaz", "dictionary",
                     "presentation", "SearchViewModel.kt"),
        os.path.join("app", "src", "main", "java", "cl", "fadiaz", "dictionary",
                     "data", "PackLoad.kt"),
        os.path.join("app", "src", "main", "java", "cl", "fadiaz", "dictionary",
                     "data", "PackSet.kt"),
        os.path.join("app", "src", "main", "java", "cl", "fadiaz", "dictionary",
                     "data", "Visit.kt"),
        os.path.join("app", "src", "main", "java", "cl", "fadiaz", "dictionary",
                     "data", "WordOfTheDay.kt"),
        os.path.join("app", "src", "main", "java", "cl", "fadiaz", "dictionary",
                     "data", "Settings.kt"),
        os.path.join("app", "src", "main", "java", "cl", "fadiaz", "dictionary",
                     "data", "PackVerification.kt"),
        os.path.join("app", "src", "main", "java", "cl", "fadiaz", "dictionary",
                     "data", "PackSelection.kt"),
        os.path.join("app", "src", "main", "java", "cl", "fadiaz", "dictionary",
                     "tile", "TileContent.kt"),
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


def check_tiles_dont_open_packs(report):
    """Rule: no Tile opens a pack. (D-042, and onTileRequest's contract)

    It is not a performance precaution --which without a measurement would be forbidden-- but the
    API's contract: `onTileRequest` is annotated @MainThread and "must complete after at most 10
    seconds". Opening a 69 or 295 MB pack there is ruled out in writing.

    The design avoids it by reading SharedPreferences, but nothing prevented it: a TileService has
    no `onCleared`, so a pack opened from there leaks --a native SQLite handle and a
    single-threaded dispatcher-- for the whole life of the process, and in silence.
    """
    prohibidos = ("PackStore.open", "PackFile.", "SqlitePackSource")
    carpeta = os.path.join(ROOT, "app", "src", "main", "java", "cl", "fadiaz", "dictionary", "tile")
    if not os.path.isdir(carpeta):
        report.failure(
            "el paquete de tiles no existe",
            "Si se movio, mover tambien este check: sin el, un tile puede abrir un pack de "
            "295 MB en el hilo principal y nadie lo nota hasta que el reloj se traba.",
        )
        return
    for nombre in sorted(os.listdir(carpeta)):
        if not nombre.endswith(".kt"):
            continue
        with open(os.path.join(carpeta, nombre), encoding="utf-8") as handle:
            for number, line in enumerate(handle, start=1):
                code = line.split("//")[0]
                for aguja in prohibidos:
                    if aguja in code:
                        report.failure(
                            "un tile abre un pack",
                            "tile/%s:%d menciona %s. onTileRequest corre en el hilo principal "
                            "con 10 s de tope: lo que el tile necesite tiene que dejarlo escrito "
                            "la app." % (nombre, number, aguja),
                        )


def check_attribution_screen(report):
    """Rule: the attribution is shown, and it comes from the pack. (D-031)

    The content is CC BY-SA and showing where it comes from is the **condition of using the data**,
    not a courtesy. If somebody deletes that screen to gain space, nothing else in the repo says so.

    This check is **cheap and limited on purpose**: it checks that the file exists and that what it
    shows comes from `meta`, not from a constant in the code. That the pixels appear is proven by
    `PantallasTest.laAtribucionMuestraLaLicenciaYLaFuente`, which needs a device and therefore does
    not run in the gate. The two together are the enforcer; neither alone is enough.
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
    # The attribution has to be read from each open pack's METADATA. With two packs this stopped
    # being a detail: showing a single licence is breaching the other's condition.
    if "metadata" not in texto:
        report.failure(
            "la atribucion dejo de leerse del pack",
            "%s no accede a la metadata de ningun pack. Con varios diccionarios instalados, "
            "cada uno trae SU licencia y todas tienen que mostrarse (D-031)." % pantalla,
        )


def _bloque(texto, apertura):
    """The body of the block starting at `apertura`, counting braces. Empty if it is not there.

    It is not a Kotlin parser and does not pretend to be: it is enough to narrow a check to the
    section it really wants to watch, rather than to the whole file.
    """
    inicio = texto.find(apertura)
    if inicio < 0:
        return ""
    profundidad = 0
    for i in range(inicio + len(apertura) - 1, len(texto)):
        if texto[i] == "{":
            profundidad += 1
        elif texto[i] == "}":
            profundidad -= 1
            if profundidad == 0:
                return texto[inicio:i + 1]
    return texto[inicio:]


def check_release_signing(report):
    """Rule: the release signing leaves no secrets in the repo and does not use the debug key.

    Two errors, both silent:

    - **A tracked keystore or password.** It breaks nothing and goes unnoticed, until the
      repository gets shared. A leaked key does not get "fixed": it gets replaced, and replacing it
      means no app installed with the old one can ever be updated again.
    - **Signing the release with `signingConfigs.getByName("debug")`.** It is the easy way out when
      the release comes out unsigned, and it **looks as though it works**: it installs and runs.
      What breaks appears months later, when you want to publish with the real key and the watch
      rejects the update because the signature does not match.
    """
    rastreados = subprocess.run(
        ["git", "ls-files"], cwd=ROOT, capture_output=True, text=True, check=False
    ).stdout.split()
    for nombre in rastreados:
        if nombre.endswith((".jks", ".keystore", ".p12")):
            report.failure(
                "hay una keystore en el repositorio",
                "%s esta trackeado por git. Una clave de firma vive FUERA del proyecto y "
                "local.properties guarda solo su ruta." % nombre,
            )

    gradle = os.path.join(ROOT, "app", "build.gradle.kts")
    if not os.path.isfile(gradle):
        return
    with open(gradle, encoding="utf-8") as handle:
        texto = handle.read()

    # ⚠️ **Only inside the `release` block**, and that is not a detail. The rule speaks about the
    # APK that goes out to people; a separate build type signing with the debug key so it can be
    # installed over adb is legitimate and necessary --it is how R8 gets tested before the keystore
    # exists, and it is what Macrobenchmark requires--. Looking at the whole file forbade that by
    # accident.
    if re.search(r'signingConfig\s*=\s*signingConfigs\.getByName\(\s*"debug"',
                 _bloque(texto, "release {")):
        report.failure(
            "el release se firma con la clave de debug",
            "app/build.gradle.kts firma el release con la config de debug. Instala y corre, y "
            "deja la app firmada con una clave que no es tuya: una actualizacion posterior con "
            "la clave buena va a ser rechazada por el dispositivo.",
        )

    for numero, linea in enumerate(texto.splitlines(), start=1):
        codigo = linea.split("//")[0]
        if re.search(r'(storePassword|keyPassword)\s*=\s*"[^"]+"', codigo):
            report.failure(
                "hay una contraseña literal en el build",
                "app/build.gradle.kts:%d pone una contraseña en el codigo. Tienen que salir de "
                "local.properties o del entorno." % numero,
            )


def check_debug_surface_stays_out_of_release(report):
    """Rule: the `adb` debug surface never reaches the APK that goes out to people.

    `DebugIntents` registers an **exported** receiver that seeds a query, clears it and dumps the
    loaded packs. That is what makes the watch answerable without a debugger, and it is also attack
    surface and battery the moment it ships. What keeps it out is one line: `release` sets
    `DEBUG_INTENTS` to `false`, R8 folds the `if`, and the class leaves the dex -- in release the
    code **does not exist**, rather than going unused.

    ⚠️ **The rule is "never in `release`", not "only in `debug`".** `benchmark` sets it to `true`
    on purpose: it inherits from `release`, it is the build startup and battery are measured on
    (D-166), and it is the only release-like one that installs. If it were the only build unable to
    seed a query, measuring a search would need a finger on a watch whose IME reorders keystrokes.
    A check forbidding it outside `debug` would break a workflow that was chosen with reasons.

    ⚠️ **Why it is a check and not a comment.** The property is protected today by a constant, and
    the constant is protected by nothing: flipping that one `false` to `true` compiles, installs,
    passes every test and ships an exported receiver. Deleting the line does not, because AGP needs
    the field in every variant -- so the silent edit is exactly the one this watches.
    """
    gradle = os.path.join(ROOT, "app", "build.gradle.kts")
    if not os.path.isfile(gradle):
        return
    with open(gradle, encoding="utf-8") as handle:
        texto = handle.read()

    bloque = _bloque(texto, "release {")
    if not bloque:
        report.failure(
            "no se encuentra el bloque release del build",
            "app/build.gradle.kts no tiene un bloque `release {`, asi que no se puede comprobar "
            "que la superficie de depuracion quede afuera del APK que sale a la gente.",
        )
        return

    declaradas = re.findall(
        r'buildConfigField\(\s*"boolean"\s*,\s*"DEBUG_INTENTS"\s*,\s*"(\w+)"\s*\)', bloque
    )
    if not declaradas:
        report.failure(
            "el release no declara DEBUG_INTENTS",
            "app/build.gradle.kts: el bloque `release` no fija DEBUG_INTENTS. Sin esa linea la "
            "constante depende de lo que herede, y lo que protege a un usuario deja de estar "
            "escrito donde se lee.",
        )
        return
    if any(valor != "false" for valor in declaradas):
        report.failure(
            "el release lleva la superficie de depuracion adentro",
            "app/build.gradle.kts: el bloque `release` fija DEBUG_INTENTS en %s. Eso mete un "
            "receiver EXPORTADO en el APK que sale a la gente: cualquier app instalada puede "
            "sembrarle consultas y leerle que packs tiene cargados. Tiene que ser false; "
            "`benchmark` es el build donde puede estar en true." % ", ".join(declaradas),
        )


def check_app_version(report):
    """Rule: the APK that goes to the watch is distinguishable from the previous one. (D-095)

    versionCode and versionName were born as template literals --1 and "1.0"-- and nothing
    incremented them: no task, no script, no CI, no git tag. That is not cosmetic: Android's
    installer **rejects** an APK with a versionCode lower than the installed one, and accepts
    reinstalling the same number only because the signature matches. It is the same trap devpack.py
    already avoids for the packs by comparing data_version.

    They live in gradle.properties and not in the .kts so bumping them is one line that touches no
    build logic. The .kts reads them with a default, so a clone without the property still compiles
    --the same rule as the signing (D-086)-- which is why this check looks at the property and also
    that the .kts does not override it with a literal.
    """
    props = read("gradle.properties")
    build = read("app/build.gradle.kts")

    code = re.search(r"^dictionary\.versionCode\s*=\s*(\S+)\s*$", props, re.M)
    if code is None:
        report.failure(
            "gradle.properties no define dictionary.versionCode",
            "sin el, el APK sale con el default y dos builds distintos se ven iguales",
        )
    elif not code.group(1).isdigit() or int(code.group(1)) < 2:
        report.failure(
            "dictionary.versionCode invalido",
            "%s: tiene que ser un entero >= 2 (1 era el del template)" % code.group(1),
        )

    if re.search(r"^dictionary\.versionName\s*=\s*\S+", props, re.M) is None:
        report.failure(
            "gradle.properties no define dictionary.versionName",
            "es el string que ve una persona; no lo lee ninguna maquina, pero tiene que existir",
        )

    if re.search(r"versionCode\s*=\s*\d", build):
        report.failure(
            "app/build.gradle.kts fija versionCode con un literal",
            "pisaria la property y volveriamos al numero que nadie sube. Leerlo de la property",
        )


def check_no_hardcoded_translations(report):
    """No text that already has a resource key may be WRITTEN into the code.

    ⚠️ **`check_locale_parity` does not catch this, which is why it is needed.** Both tables can be
    perfectly even --the keys exist in both languages-- and the screen still draw the literal. That
    is what happened: `home_saved` and `home_settings` existed in `values/` and in `values-es/`,
    and `SearchScreen` put "Guardadas" and "Ajustes" in by hand. On an English watch they came out
    in Spanish, beside English text.

    **Looking at the screen** found it, not a test, and this check exists so that next time looking
    is not necessary: if a `values-es/` value appears in quotes in a `.kt`, somebody wrote the text
    instead of asking for the resource.

    It is compared against Spanish and not against English on purpose: a short English value
    ("Search", "Saved") can legitimately appear in a test name or a comment, and the check would
    give false positives. The Spanish values do not have that problem.
    """
    ruta_es = os.path.join(ROOT, "app", "src", "main", "res", "values-es", "strings.xml")
    if not os.path.exists(ruta_es):
        report.failure("falta values-es/strings.xml", ruta_es)
        return
    with open(ruta_es, encoding="utf-8") as handle:
        texto = handle.read()
    # Only the long enough ones: "ES", "OK" or "%s" appear anywhere.
    # ⚠️ **Three and not four, and the change has a case**: the word "voz" was hardcoded in
    # `SearchBar` and this check missed it by one character. It was drawn in Spanish on an English
    # watch. At three, "ES" and "OK" stay out, which is what the floor protects.
    valores = {v for v in re.findall(r"<string name=\"[^\"]+\">([^<]*)</string>", texto)
               if len(v) >= 3 and "%" not in v}
    encontrados = []
    for ruta in _kotlin_sources(os.path.join(ROOT, "app", "src", "main")):
        with open(ruta, encoding="utf-8") as handle:
            # Without the comments: a KDoc that QUOTES the text --"it cannot look like `sin.`"-- is
            # explaining the rule, not breaking it. Looking at the whole file gave that false
            # positive, and a check with false positives ends up switched off.
            fuente = "\n".join(
                linea for linea in handle.read().split("\n")
                if not linea.lstrip().startswith(("//", "*", "/*"))
            )
        for valor in valores:
            if '"' + valor + '"' in fuente:
                encontrados.append((os.path.relpath(ruta, ROOT), valor))
    for ruta, valor in encontrados:
        report.failure(
            "texto traducido escrito a mano en el codigo",
            "%s escribe %r en vez de pedir el recurso" % (ruta, valor),
        )


def _kotlin_sources(base):
    for carpeta, _dirs, archivos in os.walk(base):
        for archivo in archivos:
            if archivo.endswith(".kt"):
                yield os.path.join(carpeta, archivo)


def check_locale_parity(report):
    """Rule: values/ and values-es/ declare the SAME keys, and the short ones stay short. (D-127)

    A key that exists in `values/` and is missing from `values-es/` breaks nothing: Android falls
    back to the default and the user sees **one line in English inside a screen in Spanish**. There
    is no exception, no log, and whoever added it does not see it because their watch is in the
    other language.

    The second thing it checks is why the pack's name was shortened (D-125): the type label is
    drawn beside the name in a watch row, and a long translation reintroduces the clipping D-125
    came to fix. It is measured in BOTH languages, which is something no screen test can do -- each
    runs in one locale.
    """
    import re as _re

    def claves(ruta):
        texto = read(ruta)
        tabla = {m.group(1): m.group(2) for m in
                 _re.finditer(r'<string name="([^"]+)"[^>]*>(.*?)</string>', texto, _re.S)}
        # The plurals count too. Without this, a `<plurals>` that exists in one language and is
        # missing from the other passes the check: the parity looked only at `<string>`, so the
        # repo's first plural --a pack's entry counter-- would have been born outside the rule.
        # They are stored with a prefix so they cannot collide with a string key.
        tabla.update({"plurals:" + m.group(1): m.group(2) for m in
                      _re.finditer(r'<plurals name="([^"]+)">(.*?)</plurals>', texto, _re.S)})
        return tabla

    base = claves(os.path.join("app", "src", "main", "res", "values", "strings.xml"))
    es = claves(os.path.join("app", "src", "main", "res", "values-es", "strings.xml"))
    if not base or not es:
        report.failure("faltan los strings.xml de un locale", "values/ o values-es/ no se leyeron")
        return

    faltan = sorted(set(base) - set(es))
    sobran = sorted(set(es) - set(base))
    if faltan:
        report.failure(
            "hay claves sin traducir en values-es",
            "%s. El usuario las ve EN INGLES dentro de la pantalla en español" % ", ".join(faltan),
        )
    if sobran:
        report.failure(
            "hay claves en values-es que no existen en la base",
            "%s. Son texto muerto: nadie las lee" % ", ".join(sobran),
        )

    # What gets drawn beside the pack's name, in a watch row.
    for clave in ("pack_kind_monolingual", "pack_kind_bilingual"):
        for idioma, tabla in (("values", base), ("values-es", es)):
            valor = tabla.get(clave, "")
            if len(valor) > 14:
                report.failure(
                    "la etiqueta de tipo de pack no entra en una fila",
                    "%s/%s = %r son %d caracteres; el limite es 14 (D-125)"
                    % (idioma, clave, valor, len(valor)),
                )


def check_ui_language_picker(report):
    """Rule: the language selector offers EXACTLY the languages the app has. (D-158)

    The two halves break differently and neither raises an error:

    - A new `values-xx/` folder with no row in the selector: the translation exists, it applies if
      the watch is in that language, and **there is no way to choose it by hand**. Nobody finds it.
    - A row in the selector with no folder: a language that does not exist can be chosen, and the
      app stays in English with the selector marking something else. It is worse than not offering
      it.

    The endonym is checked separately because it is the only UI string that is **not** translated,
    on purpose: a language selector written in the language you do not understand is no use at all.
    """
    fuente = os.path.join(ROOT, "app", "src", "main", "java", "cl", "fadiaz", "dictionary",
                          "data", "UiLanguage.kt")
    if not os.path.isfile(fuente):
        report.failure(
            "falta UiLanguage.kt",
            "es la lista que el selector de ajustes dibuja; sin ella no hay nada que comparar",
        )
        return
    with open(fuente, encoding="utf-8") as handle:
        texto = handle.read()
    entradas = dict(re.findall(r'^\s*[A-Z_]+\("([a-z]{2})",\s*"([^"]+)"\)', texto, re.M))
    if not entradas:
        report.failure(
            "UiLanguage.kt no declara ningun idioma",
            "o el formato del enum cambio y este chequeo dejo de ver nada. Revisar los dos",
        )
        return

    res = os.path.join(ROOT, "app", "src", "main", "res")
    # `values/` is the base and it is English (D-127): it carries no suffix, but it is an offerable
    # language.
    carpetas = {"en"}
    for nombre in os.listdir(res):
        if nombre.startswith("values-") and os.path.isfile(
                os.path.join(res, nombre, "strings.xml")):
            sufijo = nombre[len("values-"):]
            # Only the language qualifiers: `values-round` or `values-v33` are not translations.
            if re.fullmatch(r"[a-z]{2}", sufijo):
                carpetas.add(sufijo)

    sin_fila = sorted(carpetas - set(entradas))
    sin_carpeta = sorted(set(entradas) - carpetas)
    if sin_fila:
        report.failure(
            "hay traducciones que el selector no ofrece",
            "%s tienen strings.xml y no estan en UiLanguage: solo salen si el reloj ya viene "
            "en ese idioma" % ", ".join(sin_fila),
        )
    if sin_carpeta:
        report.failure(
            "el selector ofrece idiomas que la app no tiene",
            "%s estan en UiLanguage y no tienen strings.xml: elegirlos deja la app en ingles "
            "con el selector marcando otra cosa" % ", ".join(sin_carpeta),
        )

    for tag, endonimo in sorted(entradas.items()):
        if f'>{endonimo}<' in read(os.path.join("app", "src", "main", "res", "values-es",
                                                "strings.xml")):
            report.failure(
                "el endonimo del selector es un recurso traducible",
                "%r (%s) aparece como valor en values-es. El nombre de un idioma se escribe en "
                "ese idioma y no se traduce: es lo unico que hace usable el selector para quien "
                "tiene el reloj en un idioma que no lee" % (endonimo, tag),
            )


def _contar(carpeta, patron):
    """How many times `patron` appears at the start of a line, under `carpeta`."""
    total = 0
    base = os.path.join(ROOT, carpeta)
    if not os.path.isdir(base):
        return None
    for actual, _dirs, archivos in os.walk(base):
        for archivo in archivos:
            if not archivo.endswith((".kt", ".py")):
                continue
            with open(os.path.join(actual, archivo), encoding="utf-8") as handle:
                for linea in handle:
                    if linea.lstrip().startswith(patron):
                        total += 1
    return total


def check_test_counts(report):
    """Rule: a number a document asserts has to be the number that is there.

    ⚠️ **It is the decay this repo already paid for four times at once.** In a single review it was
    found that `README.md` said 560 tests, `dict-data/CLAUDE.md` said 31 instrumented,
    `tools/CLAUDE.md` said 129 Python ones and `app/CLAUDE.md` said 178 JVM ones. None was true,
    none broke anything, and each wastes the time of whoever reads it -- or worse, makes them
    believe a suite has shrunk.

    The count is **static** --lines starting with `@Test` or `def test_`-- and that is not an
    approximation: it was compared against Gradle's and unittest's runtime counts on 2026-09-20 and
    they give **exactly** the same (81, 251, 250). A static count keeps the check in the audit,
    which is pure stdlib and needs to compile nothing.

    ⚠️ **If a sentence is rewritten and the pattern stops matching, this FAILS.** That is
    deliberate and it is `check_app_logic_is_jvm_testable`'s same policy: a check that switches
    itself off when somebody touches the text it watches watches nothing. Rewriting the sentence
    forces coming here.
    """
    nucleo = _contar(os.path.join("dict-core", "src", "test"), "@Test")
    app_jvm = _contar(os.path.join("app", "src", "test"), "@Test")
    python = _contar(os.path.join("tools", "packbuilder", "tests"), "def test_")
    datos = _contar(os.path.join("dict-data", "src", "androidTest"), "@Test")
    app_disp = _contar(os.path.join("app", "src", "androidTest"), "@Test")
    # The knowledge notes CLAUDE.md sends a session to. Counted rather than written down, because
    # it is the one number here that a bundle release moves without this repository touching it --
    # so it is the one most likely to be quietly wrong. Only `active/`: `review/` is disputed and
    # `retired/` must not be read as live.
    notas = len([n for n in os.listdir(os.path.join(ROOT, BUNDLE, "knowledge", "notes", "active"))
                 if n.endswith(".md")])
    if None in (nucleo, app_jvm, python, datos, app_disp):
        report.failure(
            "no se pudo contar los tests",
            "alguna carpeta de tests cambio de lugar; mover tambien este chequeo",
        )
        return

    checks = len(CHECKS)
    # What the gate runs, and what it does not. The "in total" sum includes the checks on purpose:
    # it is how the roadmap has been counting it.
    gate = nucleo + app_jvm + python
    esperados = {
        ("README.md", r"el gate: compila, lint, (\d+) tests"): gate,
        ("README.md", r"connectedDebugAndroidTest\s+# los (\d+) tests"): datos,
        ("app/CLAUDE.md", r"testDebugUnitTest\s+# (\d+) JVM tests"): app_jvm,
        ("dict-data/CLAUDE.md", r"connectedDebugAndroidTest # the (\d+) tests"): datos,
        ("tools/CLAUDE.md", r"hatch run test\s+# the (\d+) tests"): python,
        ("docs/roadmap.md", r"`:dict-core`, \*\*(\d+) tests\*\*"): nucleo,
        ("docs/roadmap.md", r"`tools/`, \*\*(\d+) tests\*\*"): python,
        ("docs/roadmap.md", r"\*\*(\d+) JVM\n?de `:app`\*\*"): app_jvm,
        ("CLAUDE.md", r"phase guide over (\d+) notes"): notas,
        ("docs/roadmap.md", r"\*\*(\d+) checks\*\* de auditor"): checks,
        ("docs/roadmap.md", r"\*\*(\d+) tests en total\*\*"): gate + checks,
        ("docs/roadmap.md", r"Los \*\*(\d+)\n?instrumentados\*\*"): datos + app_disp,
    }
    for (documento, patron), esperado in esperados.items():
        texto = read(documento)
        hallado = re.search(patron, texto)
        if hallado is None:
            report.failure(
                "un documento dejo de afirmar un conteo que este chequeo vigila",
                "%s ya no matchea %r. Si la frase se reescribio, actualizar el patron aca; si "
                "el dato se borro, sacar la fila. Lo que no puede pasar es que el numero quede "
                "suelto otra vez" % (documento, patron),
            )
        elif int(hallado.group(1)) != esperado:
            if ARREGLAR:
                inicio, fin = hallado.span(1)
                _escribir(documento, texto[:inicio] + str(esperado) + texto[fin:])
                report.advisory(
                    "conteo corregido por --fix",
                    "%s: %s -> %d" % (documento, hallado.group(1), esperado),
                )
            else:
                report.failure(
                    "un documento afirma un conteo que no es",
                    "%s dice %s donde hay %d (%r). Corregible con `--fix`"
                    % (documento, hallado.group(1), esperado, patron),
                )


def check_r8_keep_rules(report):
    """Rule: the keep rules are wired in and do not protect classes that no longer exist. (D-163)

    R8 is on and **what it breaks, it breaks only in release and with no compilation error**. This
    check covers the two ways the rules stop being any use without anybody noticing:

    ⚠️ **(1) The file stops being wired in.** `keepRules { files.add(...) }` is one line of
    `app/build.gradle.kts`; if somebody reorganizes that block and loses it, the rules **stay there
    and are not applied**. The build stays green, the APK still comes out, and what fails is a tile
    on a watch.

    ⚠️ **(2) A rule names a class that no longer exists.** Renaming `HistoryTileService` without
    touching the `.pro` leaves a dead rule protecting nothing -- and `app/CLAUDE.md` already has it
    in writing that breaking a tile raises no compilation error and no test failure. It is the same
    decay `check_doc_paths` catches for the documents, applied to the rules.

    It does not check that every manifest component HAS a rule: AGP already keeps the declared
    components, so requiring it would over-restrict. What gets watched is that what we decided to
    protect stays genuinely protected.
    """
    # Without the comments: the `optimization` block EXPLAINS that the rules are in
    # proguard-rules.pro, so looking at the whole file gave a false negative -- the `files.add(...)`
    # could be disconnected and the check went on passing because of the prose. It was found by
    # checking that the check failed, which is what checking is for.
    build = "\n".join(
        linea for linea in read(os.path.join("app", "build.gradle.kts")).split("\n")
        if not linea.lstrip().startswith(("//", "*", "/*"))
    )
    if "enable = true" not in build:
        # R8 off: there is nothing to watch. It goes FIRST, even before looking at whether the
        # rules file exists: with R8 off it is not needed, and failing over its absence would make
        # this check prevent switching R8 off -- which is exactly what somebody would want to do if
        # R8 broke something on the watch.
        return

    reglas_rel = os.path.join("app", "proguard-rules.pro")
    reglas_path = os.path.join(ROOT, reglas_rel)
    if not os.path.isfile(reglas_path):
        report.failure(
            "faltan las reglas de keep de R8",
            "%s no existe y R8 esta encendido: el release encoge sin la red que decidimos "
            "poner" % reglas_rel,
        )
        return

    if "proguard-rules.pro" not in build:
        report.failure(
            "las reglas de keep no estan cableadas al build",
            "app/build.gradle.kts no nombra proguard-rules.pro. Las reglas quedan en el disco "
            "sin aplicarse: el build sigue verde y lo que falla es un tile en un reloj",
        )

    with open(reglas_path, encoding="utf-8") as handle:
        contenido = handle.read()

    # Only our own classes: androidx's change package with the library and are not ours to fix.
    nuestras = re.findall(r"^-keep class (cl\.fadiaz\.[\w.]+)", contenido, re.M)
    if not nuestras:
        report.failure(
            "las reglas de keep no protegen ninguna clase propia",
            "%s existe pero no nombra una sola clase de cl.fadiaz. O sobra el archivo, o "
            "alguien vacio las reglas" % reglas_rel,
        )
    for clase in nuestras:
        relativo = os.path.join("app", "src", "main", "java", *clase.split(".")) + ".kt"
        if not os.path.isfile(os.path.join(ROOT, relativo)):
            report.failure(
                "una regla de keep protege una clase que no existe",
                "%s nombra %s y no hay %s. Una regla muerta no protege nada, y romper un tile "
                "no da error de compilacion ni test" % (reglas_rel, clase, relativo),
            )


def check_manifest_hygiene(report):
    """Rule: the manifest declares no unused permissions, and loses nothing the app needs.

    ⚠️ **"100 % offline" is the first line of the README and of `CLAUDE.md`, and it had no
    enforcer.** If somebody adds `INTERNET` --and the pack installer will need it one day
    (D-029)-- the project's central claim stops being true and **nothing says so**. This check does
    not forbid adding it: it forces coming here and changing the rule by hand, which is the
    difference between a decision and an oversight.

    The second thing it looks at is the opposite: a declared and **never used** permission.
    `WAKE_LOCK` was like that from the template until 2026-09-20; it breaks nothing, but it is
    shown to the user on installing and has to be justifiable.

    And the third is `localeConfig`, generated from the real `values-*` folders: without it, the
    app **does not appear in the system's language list** (the app's own selector does work, which
    is why the gap went unnoticed for two sessions).
    """
    manifest = read(os.path.join("app", "src", "main", "AndroidManifest.xml"))
    build = read(os.path.join("app", "build.gradle.kts"))

    permisos = set(re.findall(r'uses-permission android:name="android\.permission\.(\w+)"',
                              manifest))
    # ⚠️ This rule CHANGED on 2026-09-22, when the download catalog arrived (D-213), and the change
    # is the point: it used to forbid INTERNET because nothing used it, and now it watches that the
    # promise that remains stays written. The property being defended was never "there is no
    # network" but **"searching uses no network"**, and that distinction is lost in one commit if
    # nobody checks it.
    inspeccion = permisos & {"ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE"}
    if inspeccion:
        report.failure(
            "la app inspecciona la red por su cuenta",
            "AndroidManifest.xml declara %s, y no hace falta: las restricciones de D-029 "
            "--cargando y Wi-Fi-- las impone WorkManager. Un permiso que existe para mirar el "
            "estado de la red es una invitacion a decidir cuando descargar desde la app, que es "
            "exactamente lo que D-029 saco de ahi" % ", ".join(sorted(inspeccion)),
        )

    # INTERNET is allowed, but only while both documents go on saying WHAT is the only thing that
    # uses it. If somebody deletes that sentence, the promise becomes indefensible in silence.
    if "INTERNET" in permisos:
        promesas = {
            "README.md": "descargar un diccionario es lo unico",
            "CLAUDE.md": "downloading one is the only thing that uses the internet",
        }
        for documento, frase in promesas.items():
            ruta = os.path.join(ROOT, documento)
            with open(ruta, encoding="utf-8") as handle:
                texto = handle.read()
            if frase.lower() not in texto.lower():
                report.failure(
                    "la promesa de que solo la descarga usa la red no esta escrita",
                    "%s declara INTERNET, asi que %s tiene que decir explicitamente que la "
                    "descarga es lo unico que la usa. No se encontro %r. La propiedad que este "
                    "proyecto vende no es 'no hay red', es 'buscar no usa red', y sin la frase "
                    "nadie puede distinguirlas dentro de un ano"
                    % ("AndroidManifest.xml", documento, frase),
                )

    # A declared permission has to appear in the code. The heuristic is coarse on purpose: it looks
    # for the permission's name or its most obvious API, and that is enough for this manifest's
    # size.
    usos = {
        "WAKE_LOCK": ("WakeLock", "WAKE_LOCK"),
        "VIBRATE": ("Vibrator", "vibrate"),
        "POST_NOTIFICATIONS": ("NotificationManager", "notify("),
        "BODY_SENSORS": ("SensorManager", "Sensor"),
    }
    fuentes = ""
    for ruta in _kotlin_sources(os.path.join(ROOT, "app", "src", "main")):
        with open(ruta, encoding="utf-8") as handle:
            fuentes += handle.read()
    for permiso in sorted(permisos):
        senales = usos.get(permiso)
        if senales and not any(senal in fuentes for senal in senales):
            report.failure(
                "un permiso declarado no se usa",
                "AndroidManifest.xml pide %s y no hay una sola linea de codigo que lo use. Se le "
                "muestra al usuario al instalar: o se usa, o se saca" % permiso,
            )

    if "generateLocaleConfig = true" not in build and "localeConfig" not in manifest:
        report.failure(
            "la app no declara su lista de idiomas",
            "sin `localeConfig` la app NO aparece en Ajustes -> Idiomas del sistema. El selector "
            "propio (D-158) sigue funcionando, que es por lo que el hueco no se nota mirando la "
            "app",
        )


# The meta keys a pack MUST carry. Growing this list breaks every already installed pack:
# `getValue` throws and the pack is rejected whole on opening.
# ⚠️ `langs` and `fuzzy_profiles` came in with `schema_version` 4, which is what this list requires
# in order to grow: the earlier packs are rejected on opening, and that is right because they do
# not have the `entry.lang` column either. `lang_src`/`lang_dst` went out -- a pack's languages are
# peers.
META_OBLIGATORIAS = {
    "attribution", "data_version", "entry_count", "fuzzy_profiles", "kind", "langs", "license",
    "name", "norm_version", "pack_id", "payload_dict", "schema_version",
}


def check_required_meta_keys(report):
    """Rule: the list of REQUIRED meta keys does not grow without somebody deciding it. (D-174)

    ⚠️ **Adding a key with `getValue` breaks every pack already on a watch**, and not while
    building them: on OPENING them. `getValue` throws, `PackFile.open` turns it into a rejected
    pack, and the user is left with no dictionary until it is rebuilt and re-uploaded -- today
    **372.6 MB**.

    The right way to add a datum to the pack is `meta[...]`, which returns null in an old pack and
    lets the code decide. It is what was already done with `description` (D-125), `sources` (D-138)
    and `subset_of`: three new keys, zero broken packs.

    This check does not forbid growing the list: **it forces coming here and changing it by hand**,
    which is the difference between a decision and an oversight. It is the same policy as D-072's
    watched files.
    """
    fuente = read(os.path.join("dict-data", "src", "main", "kotlin", "cl", "fadiaz",
                               "dictionary", "data", "PackFile.kt"))
    encontradas = set(re.findall(r'meta\.getValue\("([a-z_]+)"\)', fuente))
    nuevas = encontradas - META_OBLIGATORIAS
    if nuevas:
        report.failure(
            "un pack pasa a necesitar una clave de meta que los instalados no traen",
            "%s se lee con getValue. Todos los packs ya instalados se rechazarian AL ABRIR "
            "(372,6 MB para reconstruir). Si de verdad es obligatoria, sumarla a "
            "META_OBLIGATORIAS aca y subir schema_version en el mismo commit; si no, leerla con "
            "meta[...] como description, sources y subset_of" % ", ".join(sorted(nuevas)),
        )
    fueron = META_OBLIGATORIAS - encontradas
    if fueron:
        report.failure(
            "una clave de meta dejo de ser obligatoria y la lista no se entero",
            "%s ya no se lee con getValue. Sacarla de META_OBLIGATORIAS: una lista que sobra "
            "deja de describir el contrato y nadie vuelve a creerle" % ", ".join(sorted(fueron)),
        )


#: Marks of Spanish prose. Not meant to be a language detector: meant not to fire on the lines
#: this repository actually writes.
_MARCAS_ES = re.compile(
    # Only the accented letters and the inverted marks: plain vowels would match every line,
    # which is how the first version of this check reported 2,068 where the ruler said 1,778.
    r"[áéíóúñ¿¡]"
    r"|\b(que|porque|cuando|pero|donde|desde|hasta|cada|una|por|con|sin|esto|esta|"
    r"este|ese|los|las|del)\b",
    re.I,
)

#: The half that avoids the false positives, and without it this check would be useless. Half a
#: dozen documents are ENGLISH prose that QUOTES a Spanish UI string or log line -- *"the app says
#: «No hay ningun diccionario instalado»"* -- and a naive detector counts them. Measured
#: 2026-09-23: the rule without this counted 6,966 lines and with it 6,798, so about **170 false
#: positives**, nearly all in the CLAUDE.md files, which are already translated. A line carrying
#: function words from both languages is English quoting Spanish.
_MARCAS_EN = re.compile(
    r"\b(the|and|is|are|of|to|that|it|not|with|for|this|which|was|from|but|its|has|be)\b",
    re.I,
)


def _es_prosa(linea, ext):
    """Whether this line is prose rather than code. In a `.md` every line is."""
    t = linea.strip()
    if ext in (".kt", ".kts", ".java"):
        return t.startswith(("//", "*", "/*"))
    if ext == ".py":
        return t.startswith("#") or t.startswith(('"""', "'''"))
    return True


def lineas_en_espanol(ruta):
    """How many lines of Spanish prose a file holds, by the rule above."""
    ext = os.path.splitext(ruta)[1]
    total = 0
    for linea in read(ruta).splitlines():
        if (_es_prosa(linea, ext) and len(linea.strip()) > 25
                and _MARCAS_ES.search(linea) and not _MARCAS_EN.search(linea)):
            total += 1
    return total


#: The ceiling of Spanish prose **per area**, which drops when an area gets translated.
#:
#: A ceiling and not a zero, because a zero would fail today over 11,894 lines and a check that
#: cannot pass gets switched off. What this watches is not the debt: it is that the debt does not
#: GROW. The rule *"everything written from 2026-09-21 onward is English"* was written down and
#: was not holding -- measured with one ruler against two trees, **5,281 to 6,966 in two days,
#: +32 %**, written largely by sessions that had the rule loaded. By D-234 that is the signal to
#: raise its rung rather than restate it.
#:
#: **Per area and not one total**, because a total lets translating 200 lines in one module hide
#: 200 new ones in another. Each row drops on its own when its area is translated: that is the
#: ratchet.
#:
#: **The changelog is its own row and was never in the roadmap's table.** It is the largest area
#: of all, and translating it backwards is worth little -- it is the record of past sessions. What
#: the ceiling buys there is that **new entries get written in English**, which is the only part
#: that changes anything.
TECHO_ESPANOL = (
    # Translated whole on 2026-09-23: the builder, the sources, the 35 tests, the audit itself
    # and the two Unicode generators. WARNING: translating the "THIS FILE HAS A MIRROR" markers
    # broke `check_mirror_declarations`, which was looking for the Spanish spelling -- it failed
    # loudly through its own `found == 0` guard, which exists precisely so a check cannot pass
    # by seeing nothing. Both spellings are accepted now; see the comment there.
    ("tools/", 0),
    # Translated whole on 2026-09-23: 27 files, the whole Wear OS surface. The largest single
    # file was SearchViewModel.kt at 192 lines. WARNING: the row is left at 0 rather than
    # deleted -- a removed row is a ceiling nobody watches.
    ("app/src/main/", 0),
    # Translated whole on 2026-09-23, including the 34 lines that live in GENERATED files --
    # UnicodeRepertoire.kt and CaseFolding.kt -- whose prose comes from tools/unicode/gen_*.py.
    # WARNING: those two were reached by editing the generators and regenerating, never by
    # hand, and the regeneration was validated the only way that proves anything: running both
    # generators BEFORE any edit and checking the diff was empty. It was -- this machine's
    # Python 3.9.6 ships exactly the pinned Unicode 13.0.0 -- so every line the second run
    # changed is provably prose. Had that first diff been non-empty, the translation would have
    # silently re-pinned the tables and invalidated every sense_code already written.
    ("dict-core/src/main/", 0),
    # Translated whole on 2026-09-23: the first module of stage 3, smallest first. The row
    # stays at 0 rather than being deleted -- a removed row is a ceiling nobody watches.
    ("dict-data/src/main/", 0),
    ("docs/roadmap.md", 2305),
    ("docs/decisions.md", 260),
    (".claude/logs/", 4180),
)


def check_spanish_prose_budget(report):
    """Rule: Spanish prose does not grow. It drops, or it stays. (D-248)

    `CLAUDE.md` Working style says the repository is written in English, with one documented
    exception: the UI strings. The rule existed with no enforcer and was not holding.

    What makes this a check rather than a language detector: the ceiling is per area and is
    compared against a number this file declares. An area that gets translated lowers its row in
    the same commit; an area that grows fails. It has no opinion about the debt that already
    exists.
    """
    for prefijo, techo in TECHO_ESPANOL:
        total = 0
        for ruta in FUENTES + MARKDOWN_REL:
            if ruta.startswith(prefijo) or ruta == prefijo:
                total += lineas_en_espanol(ruta)
        if total > techo:
            report.failure(
                "la prosa en espanol crecio en %s: %d lineas contra un techo de %d"
                % (prefijo, total, techo),
                "lo que se escriba de ahora en adelante va en ingles (CLAUDE.md, Working style). "
                "Si tradujiste algo, baja el techo en TECHO_ESPANOL en el mismo commit",
            )
        elif total < techo:
            report.advisory(
                "el techo de %s quedo alto: %d lineas contra %d" % (prefijo, total, techo),
                "baja la fila de TECHO_ESPANOL a %d para que el ratchet no se afloje" % total,
            )


#: How long a `docs/decisions.md` row may be before it stops being an index entry.
#:
#: Artifact 6 of the method says it plainly: *"the prose and the measurements live in the document
#: that owns them; this file is the index that finds them"*. A row long enough to hold the
#: reasoning IS the prose, and then there are two copies of it -- the row and the document that
#: owns it -- with nothing comparing them.
#:
#: WARNING: **the number is a signal and not a limit, which is why this only ever advises.** There
#: is no length at which a row becomes wrong; what is measurable is the trend, and the trend has
#: been one way. Measured 2026-09-23 over 262 rows: median 1,049 characters, p90 1,941, longest
#: 3,996. A budget of 1,200 flags roughly the worst third.
PRESUPUESTO_FILA_DECISION = 1200


#: How many changelog entries may be missing one of artifact 5's required fields.
#:
#: A ceiling and not a zero, for the reason the prose budget gives: ten historical entries are
#: missing one --almost always *Por qué*-- and their sessions are gone, so nobody can honestly
#: reconstruct the answer. What this watches is that the number does not GROW, which fails the
#: author of an incomplete entry at the moment they write it, when fixing it is free.
TECHO_ENTRADAS_INCOMPLETAS = 10

#: The four fields artifact 5 requires, each in both spellings the file uses.
#:
#: WARNING: **both spellings are listed because translating them on the fly already went wrong.**
#: The first English entries invented `Unverified` where the reference says `Sin verificar`, and
#: `Deviation from the plan` went unwritten in seven entries -- a field with no name is not
#: omitted, it is forgotten. The reference at the end of the changelog now carries both sets.
CAMPOS_REQUERIDOS = (("Qué", "What"), ("Áreas", "Areas"),
                     ("Por qué", "Why"), ("Arquitectura", "Architecture"))


def check_changelog_entries_are_complete(report):
    """Rule: every changelog entry carries artifact 5's required fields. (method artifact 5)"""
    texto = read(".claude/logs/agent-changelog.md")
    partes = re.split(r"^## (20\d\d-\d\d-\d\d[^\n]*)$", texto, flags=re.M)
    entradas = list(zip(partes[1::2], partes[2::2]))
    if not entradas:
        # The guard that keeps this from passing by seeing nothing (D-253).
        report.failure(
            "no se encontro ninguna entrada en el changelog",
            "el patron '## AAAA-MM-DD' dejo de matchear y este check quedo mirando la nada",
        )
        return
    incompletas = [
        titulo for titulo, cuerpo in entradas
        if any("**%s.**" % es not in cuerpo and "**%s.**" % en not in cuerpo
               for es, en in CAMPOS_REQUERIDOS)
    ]
    n = len(incompletas)
    if n > TECHO_ENTRADAS_INCOMPLETAS:
        report.failure(
            "%d de %d entradas del changelog no traen algun campo requerido, contra un techo "
            "de %d" % (n, len(entradas), TECHO_ENTRADAS_INCOMPLETAS),
            "artefacto 5 pide Que / Areas / Por que / Arquitectura en cada entrada. La mas "
            "reciente sin completar: %s" % incompletas[0][:60],
        )
    elif n < TECHO_ENTRADAS_INCOMPLETAS:
        report.advisory(
            "el techo de entradas incompletas quedo alto: %d contra %d" % (n, TECHO_ENTRADAS_INCOMPLETAS),
            "baja TECHO_ENTRADAS_INCOMPLETAS a %d para que el ratchet no se afloje" % n,
        )


def check_decision_rows_stay_an_index(report):
    """Advisory: how far docs/decisions.md has drifted from being an index. (method artifact 6)

    WARNING: **it never fails.** Turning this into a ratchet is the next rung and it is a
    decision, not an implementation detail: a failure here would block writing a long row at the
    moment somebody is trying to record something they just learned, which is the worst possible
    time to argue about format.
    """
    filas = [l for l in read("docs/decisions.md").splitlines() if l.startswith("| D-")]
    if not filas:
        # The guard that keeps this from passing by seeing nothing: the file always has rows, so
        # zero means the shape changed and this check stopped looking at anything.
        report.failure(
            "no se encontro ninguna fila de decision en docs/decisions.md",
            "el patron '| D-' dejo de matchear: cambio el formato del archivo y este check quedo "
            "mirando la nada",
        )
        return
    largas = sorted((len(l), l[2:7]) for l in filas if len(l) > PRESUPUESTO_FILA_DECISION)
    if not largas:
        return
    peores = ", ".join("%s (%d)" % (d, n) for n, d in largas[-3:][::-1])
    report.advisory(
        "%d de %d filas de decisiones pasan de %d caracteres"
        % (len(largas), len(filas), PRESUPUESTO_FILA_DECISION),
        "artefacto 6: este archivo es el INDICE que encuentra la prosa, no la prosa. Las mas "
        "largas: %s. Mover la medicion al documento que la posee y dejar el puntero" % peores,
    )


#: The root file's line budget.
#:
#: WARNING: **220 and not the method's 200, and that is a deliberate deviation set by the owner
#: on 2026-09-23** (D-262). Artifact 1 says *"under 200 lines"*; artifact 19 says the host's
#: shapes win and the method's guarantees do, and the guarantee here is *the only file loaded on
#: every request stays small enough to read*, which a number 10 % larger still honours. It is
#: written here rather than remembered so the next method update re-proposes 200 against a
#: recorded answer instead of against silence.
#:
#: WARNING: **what it buys is room to evict deliberately, not room to grow.** The file had been
#: pinned at exactly 200 across many commits -- 198 once, refilled immediately -- which is a file
#: that stopped having a budget and got a queue, where every addition is a silent eviction and
#: nothing records what left.
PRESUPUESTO_RAIZ = 220


def _secciones(texto):
    """`CLAUDE.md`'s `##` sections and how many lines each one holds, in file order."""
    secciones, actual = [], ("(cabecera)", 0)
    for linea in texto.splitlines():
        if linea.startswith("## "):
            secciones.append(actual)
            actual = (linea[3:].strip(), 1)
        else:
            actual = (actual[0], actual[1] + 1)
    secciones.append(actual)
    return [s for s in secciones if s[1] > 1]


def _claude_md_previo():
    """`CLAUDE.md` as the last commit has it, or None when git cannot answer.

    None and not an exception: a tarball without `.git` has to keep passing the audit, which is
    the same reason `BUILD_COMMIT` degrades to `"unknown"` (D-238).
    """
    result = subprocess.run(["git", "show", "HEAD:CLAUDE.md"], cwd=ROOT,
                            capture_output=True, text=True, check=False)
    return result.stdout if result.returncode == 0 and result.stdout else None


def _por_que_crecio():
    """One line naming the sections to look at: what grew, or failing that what is biggest.

    ⚠️ **It answers the question the message already asked.** This check used to fail with
    *"which section grew?"* and leave it to a human counting lines by hand -- which happened twice
    in one session (204 -> 201 -> 199 lines) and is the friction `docs/roadmap.md` recorded.
    Growth beats size: a section that is merely long may be long on purpose, while one that grew
    is the one that just became a document.
    """
    ahora = _secciones(read("CLAUDE.md"))
    previo = _claude_md_previo()
    if previo is not None:
        antes = dict(_secciones(previo))
        crecidas = sorted(((n - antes.get(t, 0), t, n) for t, n in ahora if n > antes.get(t, 0)),
                          reverse=True)
        if crecidas:
            return "crecieron: " + ", ".join("%s +%d (%d lineas)" % (t, d, n)
                                             for d, t, n in crecidas[:3])
    mayores = sorted(ahora, key=lambda x: -x[1])[:3]
    return "las mas largas: " + ", ".join("%s (%d lineas)" % (t, n) for t, n in mayores)


def check_root_budget(report):
    """Rule: CLAUDE.md is paid for on every request and stays inside its budget. (CLAUDE.md)"""
    lines = len(read("CLAUDE.md").splitlines())
    if lines >= PRESUPUESTO_RAIZ:
        report.failure(
            "CLAUDE.md paso su presupuesto",
            "%d lineas contra %d. Una seccion que crece se volvio un documento; %s"
            % (lines, PRESUPUESTO_RAIZ, _por_que_crecio()),
        )
    elif lines > PRESUPUESTO_RAIZ - 25:
        report.advisory(
            "CLAUDE.md cerca del limite",
            "%d de %d lineas. La proxima regla que entre conviene que NOMBRE que desaloja; %s"
            % (lines, PRESUPUESTO_RAIZ, _por_que_crecio()),
        )


def check_skills_reachable(report):
    """Rule: a rule that leaves CLAUDE.md stays reachable. (D-222)

    CLAUDE.md is paid for on every request and lives inside a line budget, so the less specific rules
    move to a skill. The problem is that a skill **does not load on its own**: it loads when its
    `description` matches what the user said. So moving a rule and leaving the pointer is not
    enough -- the rule stops being read exactly in the situation it governs.

    Two halves:

    1. **Every skill CLAUDE.md names exists.** A broken pointer is a lost rule.
    2. **Every existing skill is named** in CLAUDE.md or in another skill. A skill nobody points at
       is content taken out of sight that nobody will read again.

    It does not check that the `description` fires --that is not decidable-- which is why the
    second half is what remains: if something moves, the map has to name it.
    """
    folder = os.path.join(ROOT, ".claude/skills")
    if not os.path.isdir(folder):
        return
    existentes = {
        n for n in os.listdir(folder)
        if os.path.isfile(os.path.join(folder, n, "SKILL.md"))
    }

    raiz = read("CLAUDE.md")
    nombradas_en_raiz = {n for n in existentes if "`%s`" % n in raiz}

    faltan = sorted(n for n in re.findall(r"`([a-z][a-z0-9-]+)` skill", raiz) if n not in existentes)
    if faltan:
        report.failure(
            "CLAUDE.md apunta a una skill que no existe",
            "%s. El puntero es lo unico que queda de la regla que se mudo" % ", ".join(faltan),
        )

    nombradas = set(nombradas_en_raiz)
    for n in existentes:
        otras = read(os.path.join(".claude/skills", n, "SKILL.md"))
        nombradas |= {o for o in existentes if o != n and "`%s`" % o in otras}

    huerfanas = sorted(existentes - nombradas)
    if huerfanas:
        report.failure(
            "una skill no esta nombrada en ningun lado",
            "%s. Nadie la va a encontrar: o se nombra en CLAUDE.md o su contenido vuelve"
            % ", ".join(huerfanas),
        )


def check_rules_without_enforcer(report):
    """Aviso: cuantas decisiones se pueden romper en silencio. (docs/decisions.md)

    Es aviso y no falla porque una decision sin enforcer esta permitida: lo que no esta
    permitido es que sea invisible. Que este numero suba es la senal.

    ⚠️ **Las descartadas NO cuentan, y por eso la seccion se excluye.** Esa tabla tiene tres
    columnas --numero, que se descarto, por que-- asi que la "ultima columna" que ve el regex es
    el POR QUE, que nunca empieza con raya: las 7 filas se contaban como reglas CON enforcer y
    engordaban el denominador (19 de 221 donde son 19 de 214). Una alternativa descartada no es
    una regla y no tiene nada que hacer cumplir.

    Since method v23 a new row's id is `d-<repo6>-<content6>`, minted by `bundle.py id d`, so two
    parallel sessions cannot take the same next number. The `D-###` rows keep their ids, so both
    shapes are counted: a pattern that knew only `D-\\d+` would stop counting every new row, and
    this number would drift down while the log grows.
    """
    texto = read("docs/decisions.md").split("## Decisiones descartadas")[0]
    rows = re.findall(r"^\| (D-\d+|d-[0-9a-f]{6}-[0-9a-f]{6}) \|.*\|([^|]*)\|\s*$", texto, re.M)
    without = [d for d, enforcer in rows if enforcer.strip().startswith("—")]
    report.advisory(
        "decisiones sin enforcer",
        "%d de %d se pueden romper en silencio: %s"
        % (len(without), len(rows), ", ".join(without)),
    )


#: The last decision written with the retired counter, and the roadmap items still without an id.
#:
#: **Both are ratchets and only move down.** The counter was retired by the method in v23 for a
#: reason this repository names in its own changelog header: two sessions on different branches
#: cannot see each other's next number, so a counted id makes one of them lie. Legacy ids stay as
#: written --renumbering would rewrite everyone else's citations-- so what a check can hold is the
#: boundary: no row past this number, and no new item without an id.
#:
#: Lowering either one is the work of converting a record, and the audit says so when it can.
ULTIMA_DECISION_CONTADA = 268
ITEMS_DE_ROADMAP_SIN_ID = 97


#: Entries of the changelog that sit out of order today. **A ratchet: it only moves down.**
#:
#: Two, both historical: 2026-09-21 followed by 2026-09-22, and 2026-09-18 followed by 2026-09-19.
#: They are left rather than reordered because moving blocks inside an append-only record is the
#: owner's call, not a repair an audit should make on its way past.
RUPTURAS_DE_ORDEN_DEL_CHANGELOG = 2


def check_changelog_is_newest_first(report):
    """Rule: anything chronological reads newest first. (bundle conventions)

    ⚠️ **It is not a tidiness preference: the order IS the insertion point.** The changelog's own
    header says an entry goes immediately under the `---`, which only means *newest* while the file
    is in order. A record whose order drifts stops telling the next session where to write, and the
    method has already lost entries that way -- swallowed into a fence that never closed, invisible
    in a diff.

    ⚠️ **A ratchet and not a demand for zero.** Reordering blocks inside an append-only record is a
    rewrite of somebody else's writing, and an audit should not do it while passing through. What a
    check can hold is that **no new break is added**, which is the half that costs nothing.
    """
    ruta = os.path.join(ROOT, ".claude", "logs", "agent-changelog.md")
    if not os.path.isfile(ruta):
        return
    texto = read(ruta)
    fechas = re.findall(r"^## (\d{4}-\d{2}-\d{2})", texto, re.M)
    if not fechas:
        report.failure(
            "el changelog no tiene entradas fechadas",
            ".claude/logs/agent-changelog.md no trae ningun `## AAAA-MM-DD`, asi que este chequeo "
            "no comprueba nada. Es el modo de falla que un check tiene que no tener",
        )
        return
    rupturas = [(fechas[i - 1], fechas[i]) for i in range(1, len(fechas))
                if fechas[i] > fechas[i - 1]]
    if len(rupturas) > RUPTURAS_DE_ORDEN_DEL_CHANGELOG:
        report.failure(
            "una entrada del changelog quedo fuera de orden",
            "hay %d rupturas contra un techo de %d: %s. Una entrada va inmediatamente debajo del "
            "`---`, y eso solo significa *la mas nueva* mientras el archivo este en orden"
            % (len(rupturas), RUPTURAS_DE_ORDEN_DEL_CHANGELOG,
               "; ".join("%s antes de %s" % r for r in rupturas[:4])),
        )
    elif len(rupturas) < RUPTURAS_DE_ORDEN_DEL_CHANGELOG:
        report.advisory(
            "el techo de rupturas del changelog quedo alto",
            "hay %d contra un techo de %d: baja RUPTURAS_DE_ORDEN_DEL_CHANGELOG a %d"
            % (len(rupturas), RUPTURAS_DE_ORDEN_DEL_CHANGELOG, len(rupturas)),
        )


def check_record_ids_are_minted(report):
    """Rule: a new decision or roadmap item carries a minted id, not the next number. (D-269)

    `bundle.py id d|i TEXT` gives `<kind>-<repo6>-<content6>`; the carrier id is this repository's
    and the content six hex are frozen at creation. The method retired the counter in v23.

    ⚠️ **Why a boundary and not "every row must match"**: 268 decisions and 97 roadmap items were
    written under the counter and they stay as written, because other documents and other copies
    cite them and a renumber is a silent rewrite of every one of those references. So the check
    holds the edge — nothing past the last counted number, nothing added without an id — which is
    the same ratchet shape the Spanish-prose budget uses, and for the same reason: a rule that
    cannot pass today gets switched off.

    ⚠️ **It does NOT check that an id's hash matches its text.** `bundle.py ids` does the format,
    the carrier prefix and uniqueness; an id is frozen at creation, so a later edit to the record
    must not move it, and a check against the content hash would demand exactly that.
    """
    decisiones = os.path.join(ROOT, "docs", "decisions.md")
    if os.path.isfile(decisiones):
        pasadas = sorted({int(m) for m in re.findall(r"^\| D-(\d{3}) ", read(decisiones), re.M)
                          if int(m) > ULTIMA_DECISION_CONTADA})
        if pasadas:
            report.failure(
                "una decision nueva uso el contador retirado",
                "docs/decisions.md trae %s, por encima de D-%d. El contador lo retiro el metodo "
                "en v23: dos sesiones en ramas distintas no ven el proximo numero de la otra, asi "
                "que una de las dos miente. Se acuna con `python3 .agents/tools/bundle.py id d "
                "\"<el texto de la fila>\"`"
                % (", ".join("D-%d" % n for n in pasadas), ULTIMA_DECISION_CONTADA),
            )

    roadmap = os.path.join(ROOT, "docs", "roadmap.md")
    if os.path.isfile(roadmap):
        titulos = re.findall(r"^### .*$", read(roadmap), re.M)
        sin_id = [t for t in titulos
                  if not re.search(r"\bi-[0-9a-f]{6}-[0-9a-f]{6}\b", t)]
        if len(sin_id) > ITEMS_DE_ROADMAP_SIN_ID:
            nuevos = len(sin_id) - ITEMS_DE_ROADMAP_SIN_ID
            report.failure(
                "un item de roadmap nuevo no lleva id",
                "docs/roadmap.md tiene %d titulos sin id contra un techo de %d: %d sin acunar. "
                "Se acuna con `python3 .agents/tools/bundle.py id i \"<el titulo>\"` y va en el "
                "titulo despues de un ` · `. Los %d viejos se quedan como estan: renumerar "
                "reescribe las citas de todos"
                % (len(sin_id), ITEMS_DE_ROADMAP_SIN_ID, nuevos, ITEMS_DE_ROADMAP_SIN_ID),
            )
        elif len(sin_id) < ITEMS_DE_ROADMAP_SIN_ID:
            report.advisory(
                "el techo de items de roadmap sin id quedo alto",
                "hay %d contra un techo de %d: baja ITEMS_DE_ROADMAP_SIN_ID a %d para que el "
                "ratchet no se afloje" % (len(sin_id), ITEMS_DE_ROADMAP_SIN_ID, len(sin_id)),
            )


def check_bundle_privacy_and_ids(report):
    """Rule: nothing private travels in the bundle, and record ids are well formed. (method principle 20)

    The bundle this repository carries is copied into other repositories, so the audit runs the
    bundle's own recipe rather than a copy of it: `bundle.py privacy` over `.agents/` (generic rules,
    plus this machine's private-terms list when it has one), and `bundle.py ids` over the two
    records sessions write ids into. A second recipe here would drift from the tool the way the
    digest recipe did when `method/changelog.md` appeared.
    """
    tool = os.path.join(ROOT, BUNDLE, "tools", "bundle.py")
    runs = [
        ("privacidad del bundle", [tool, "privacy", os.path.join(ROOT, BUNDLE)]),
        ("ids de registros", [tool, "ids", "--carrier", ROOT,
                              os.path.join(ROOT, ".claude/logs/agent-changelog.md"),
                              os.path.join(ROOT, "docs/decisions.md")]),
    ]
    for rule, argv in runs:
        result = subprocess.run([sys.executable] + argv, capture_output=True, text=True)
        if result.returncode != 0:
            lines = [l.strip() for l in (result.stdout + result.stderr).splitlines() if l.strip().startswith("x ")]
            report.failure(rule, "; ".join(lines[:5]) or result.stdout.strip()[-400:])


def check_bundle_digests(report):
    """Regla: el bundle de agentes no se edita en el lugar; cambiarlo es forkear. (D-059, D-221)

    Three headers declare a digest over their own content --the method, the knowledge base and the
    bundle as a whole-- and if the content does not give that number, somebody edited the bundle
    without forking or recomputing, and the next comparison between two copies concludes
    *identical* while discarding one side in silence.

    ⚠️ **It runs the bundle's own `digest --check` rather than a copy of the recipe** (d-a2f271-969225).
    This function used to reimplement it: sha256 over the bodies in name order, frontmatter
    stripped, cut to 12 hex. **It had already drifted once** -- the method's recipe narrowed to
    `prompt-*.md` and a session had to patch the copy -- which is the failure
    `check_bundle_privacy_and_ids` names in its own docstring, one screen above.

    ⚠️ **And one recipe buys five more checks.** `--check` adds provenance (every travelling file
    carries its header), links, **note reachability**, session reads and privacy. The reachability
    half had been written here by hand as a separate check the day before; it was a second recipe
    for something the tool already did, and it is retired in the same change.

    ⚠️ **What this costs, named.** The gate now depends on `.agents/tools/bundle.py` running, so
    an absent or broken tool loses six checks at once instead of one. That is why the tool missing
    is a **failure and not a skip**: the old version returned silently when the folder was gone,
    and that is exactly the class of bug this repo cannot see.
    """
    tool = os.path.join(ROOT, BUNDLE, "tools", "bundle.py")
    if not os.path.isdir(os.path.join(ROOT, BUNDLE)):
        report.failure(
            "el bundle de agentes no esta",
            "%s/ es donde vive el metodo (D-221). Sin el, este chequeo no comprueba nada" % BUNDLE,
        )
        return
    if not os.path.isfile(tool):
        report.failure(
            "falta la herramienta que comprueba el bundle",
            "%s no esta, y es la receta unica de los tres digests mas provenance, enlaces, "
            "alcanzabilidad de notas, lecturas de sesion y privacidad. Sin ella se pierden seis "
            "chequeos de golpe, asi que esto falla en vez de saltarse" % os.path.relpath(tool, ROOT),
        )
        return

    result = subprocess.run(
        [sys.executable, tool, "digest", os.path.join(ROOT, BUNDLE), "--check"],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        lines = [l.strip() for l in (result.stdout + result.stderr).splitlines()
                 if l.strip().startswith("x ")]
        report.failure(
            "el bundle no corresponde a lo que declara",
            "%s. Editar el bundle es forkear (D-059): nueva id opaca en ancestry, forked_at, y "
            "recalcular el digest" % ("; ".join(lines[:5]) or result.stdout.strip()[-400:]),
        )


def check_rejection_mirror(report):
    """Rule: the app's rejection reasons and verify_pack.py's match. (D-217)

    ⚠️ **It is the repo's FOURTH cross-language contract, and the only one born with an enforcer.**
    The other three --`norm()`, `sense_code` and the catalog index-- earned one after drifting
    apart. `verify_pack.py`'s `--como-la-app` mode exists to answer *"if I put this pack on the
    watch, does it appear?"*, and that answer is worth exactly what its fidelity is worth: a reason
    added in Kotlin and not here makes the verifier say yes to a pack the app will reject.

    ⚠️ **It compares the ORDER too.** Every check rejects (D-217), so the order does not decide
    whether a pack gets in: it decides **which reason gets reported**, which is the only line the
    user reads. The data directory's seven `schema_version` 3 packs came out as "incomplete
    metadata" instead of "another version of the format" because the order was the wrong way round.
    """
    kotlin = read("dict-core/src/main/kotlin/cl/fadiaz/dictionary/core/Model.kt")
    bloque = re.search(r"enum class PackRejection\(val id: String\) \{(.*?)\n\}", kotlin, re.S)
    if not bloque:
        report.failure("espejo de rechazos", "no se encontro el enum PackRejection en Model.kt")
        return
    ids_kt = re.findall(r'^\s*[A-Z_]+\("([a-z]+)"\),', bloque.group(1), re.M)

    python = read("tools/packbuilder/verify_pack.py")
    tabla = re.search(r"MOTIVOS_DE_LA_APP = \((.*?)\n\)", python, re.S)
    if not tabla:
        report.failure("espejo de rechazos", "no se encontro MOTIVOS_DE_LA_APP en verify_pack.py")
        return
    ids_py = re.findall(r'^\s*\("([a-z]+)", ', tabla.group(1), re.M)

    if not ids_kt or not ids_py:
        report.failure("espejo de rechazos", "alguno de los dos lados quedo vacio")
    elif ids_kt != ids_py:
        faltan = [x for x in ids_kt if x not in ids_py]
        sobran = [x for x in ids_py if x not in ids_kt]
        detalle = "Kotlin=%s Python=%s" % (ids_kt, ids_py)
        if faltan:
            detalle += "; le faltan a verify_pack: %s" % ", ".join(faltan)
        if sobran:
            detalle += "; sobran en verify_pack: %s" % ", ".join(sobran)
        if not faltan and not sobran:
            detalle += "; mismos motivos, OTRO ORDEN -- y el orden decide que motivo se reporta"
        report.failure("motivos de rechazo desincronizados", detalle)

    # The EVALUATION order is declared too, and it has to cover the same reasons minus `damaged`,
    # which is not checked: it is what is left when opening the file throws.
    orden = re.search(r"ORDEN_DE_EVALUACION = \((.*?)\n\)", python, re.S)
    if not orden:
        report.failure("espejo de rechazos", "no se encontro ORDEN_DE_EVALUACION")
        return
    ids_orden = re.findall(r'"([a-z]+)"', orden.group(1))
    esperados = [x for x in ids_kt if x != "damaged"]
    if sorted(ids_orden) != sorted(esperados):
        report.failure(
            "orden de evaluacion incompleto",
            "ORDEN_DE_EVALUACION no cubre los mismos motivos: %s contra %s"
            % (sorted(ids_orden), sorted(esperados)),
        )


def check_xml_comments(report):
    """Rule: an XML comment does not contain `--`, which is its own closing marker.

    ⚠️ **Third strike, which is why it exists** (roadmap §Proceso). This repo's comment style
    writes `--` all the time --"the name lied --and a measurement decided it--"-- because it is the
    parenthetical dash you type without an em dash. In `.kt` and in `.py` it is correct; in XML it
    breaks `mergeDebugResources` with *"The string `--` is not permitted within comments"*,
    followed by thirty lines of Xerces stack that do not name the file until the first one.

    The symptom reads as a resources problem and not a punctuation one, and it costs a Gradle run
    to work out. Here it costs seconds. The alternative --remembering to write `—`-- has already
    failed three times.
    """
    objetivos = []
    base = os.path.join(ROOT, "app", "src", "main")
    for carpeta, _, archivos in os.walk(base):
        for nombre in archivos:
            if nombre.endswith(".xml"):
                objetivos.append(os.path.join(carpeta, nombre))
    for ruta in sorted(objetivos):
        try:
            texto = open(ruta, encoding="utf-8").read()
        except OSError:
            continue
        for comentario in re.findall(r"<!--(.*?)-->", texto, re.S):
            if "--" in comentario:
                relativa = os.path.relpath(ruta, ROOT)
                linea = texto[:texto.index(comentario)].count("\n") + 1
                report.failure(
                    "`--` dentro de un comentario XML",
                    "%s:%d rompe mergeDebugResources; en XML el guion de inciso es `\u2014`"
                    % (relativa, linea),
                )


def check_core_index_name(report):
    """Rule: the version index's name matches in the build and in the app. (D-229)

    `bundlePacks` writes `assets/<CORE_INDEX>` with each bundled pack's `data_version`, and
    `PackStore` reads it by the same name. They are the two ends of one file and **there is no way
    to share a constant between a Gradle script and the app's code**.

    The failure mode is the one this repo cannot see: if the literals drift apart, the index **is
    not found and nothing fails**. `coreIndex` returns an empty map --by design, so an APK with no
    index degrades-- so the cores are left with no declared version and **never update**. No
    exception, no error log, and the app working.
    """
    build = read("app/build.gradle.kts")
    store = read("app/src/main/java/cl/fadiaz/dictionary/data/PackStore.kt")

    en_build = re.search(r'val\s+CORE_INDEX\s*=\s*"([^"]+)"', build)
    en_app = re.search(r'const\s+val\s+CORE_INDEX\s*=\s*"([^"]+)"', store)

    if en_build is None or en_app is None:
        report.failure(
            "no se encuentra CORE_INDEX en los dos lados",
            "build.gradle.kts=%s, PackStore.kt=%s; sin los dos no se puede comprobar que "
            "coincidan, y si no coinciden el indice no se lee y nada falla"
            % (en_build is not None, en_app is not None),
        )
        return

    if en_build.group(1) != en_app.group(1):
        report.failure(
            "CORE_INDEX no coincide entre el build y la app",
            "build.gradle.kts escribe %r y PackStore.kt lee %r: el indice no se encuentra, los "
            "nucleos quedan sin version declarada y no se actualizan nunca, en silencio"
            % (en_build.group(1), en_app.group(1)),
        )


def check_no_probes_left_behind(report):
    """Rule: a mutation probe does not outlive the session that ran it. (D-236)

    Step 3 of the method's session loop: *the audit finds the probes and whoever ran them cleans
    them up*. Before this it depended on remembering, and remembering is not a mechanism.

    ⚠️ **The failure mode is the worst this repo has: a forgotten mutation breaks NOTHING.** It is
    written so a test fails, it is checked that it fails, and if the restore does not come back
    --or comes back half way-- what is left is deliberately wrong code with every test green,
    because the test that detected it is precisely the one being tested. It already bit once in
    another shape: macOS caches bytecode outside the repo and a mutation of the same width survived
    a restore.

    It looks for the markers this repo uses when probing. It does not cover an unmarked mutation
    --nothing can-- which is why the marker is the convention: **a probe is written with its
    mark**, and this rule turns forgetting it into the only way for one to go unnoticed.
    """
    marcadores = ("MUTACION", "MUTACIÓN", "MUTATION PROBE", "SONDA:")
    # This file names them itself; excluding it is the only exemption, and it goes in writing.
    exento = os.path.join("tools", "audit_dictionary.py")
    for ruta in FUENTES:
        if ruta == exento:
            continue
        contenido = read(ruta)
        for n, linea in enumerate(contenido.splitlines(), 1):
            if any(m in linea for m in marcadores):
                report.failure(
                    "quedo una sonda de mutacion sin limpiar: %s:%d" % (ruta, n),
                    "una mutacion olvidada no rompe NADA --el test que la detectaba es el que se "
                    "estaba probando-- asi que queda codigo deliberadamente equivocado con el "
                    "gate en verde. La linea: %s" % linea.strip()[:100],
                )


CHECKS = [
    check_no_probes_left_behind,
    check_decision_rows_stay_an_index,
    check_changelog_entries_are_complete,
    check_spanish_prose_budget,
    check_core_index_name,
    check_mirror_declarations,
    check_version_constants,
    check_rejection_mirror,
    check_unicode_table,
    check_fuzzy_profiles,
    check_doc_paths,
    check_markdown_links,
    check_index_definitions,
    check_forbidden_dependency,
    check_shadowed_extensions,
    check_forbidden_mirror,
    check_module_direction,
    check_app_logic_is_jvm_testable,
    check_tiles_dont_open_packs,
    check_attribution_screen,
    check_release_signing,
    check_debug_surface_stays_out_of_release,
    check_app_version,
    check_locale_parity,
    check_ui_language_picker,
    check_test_counts,
    check_r8_keep_rules,
    check_manifest_hygiene,
    check_xml_comments,
    check_required_meta_keys,
    check_no_hardcoded_translations,
    check_root_budget,
    check_bundle_digests,
    check_changelog_is_newest_first,
    check_record_ids_are_minted,
    check_bundle_privacy_and_ids,
    check_rules_without_enforcer,
    check_skills_reachable,
]


def main(argv=()):
    global ARREGLAR
    ARREGLAR = "--fix" in argv
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
    sys.exit(main(sys.argv[1:]))
