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
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUNDLE = ".agents"

#: Documentos que nombran rutas legítimamente inexistentes: el roadmap las planifica, el
#: changelog las recuerda. Ver check_doc_paths.
EXENTOS_DE_RUTAS = ("docs/roadmap.md", ".claude/logs/agent-changelog.md")

# Directorios de primer nivel cuyos paths se consideran referencias reales al repo.
REPO_DIRS = ("dict-core/", "tools/", "app/", "docs/", ".claude/", "gradle/", "dict-data/")

MARKDOWN = []
for base, dirs, files in os.walk(ROOT):
    dirs[:] = [d for d in dirs if d not in (".git", "build", ".gradle", ".idea", "__pycache__")]
    for name in files:
        if name.endswith(".md"):
            MARKDOWN.append(os.path.join(base, name))


#: Las fuentes que una sonda de mutacion podria ensuciar. Los `.md` quedan fuera a proposito: un
#: documento que EXPLICA que es una sonda no es una sonda, y este mismo archivo las nombra.
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


# Si este pase puede REESCRIBIR los conteos que encuentre mal. Lo enciende `--fix`.
#
# ⚠️ **Sólo los conteos, y sólo el número.** Es la única falla del archivo que un humano no
# puede deducir sin correr la suite: agregar un test mueve cuatro o cinco cifras repartidas en
# `README.md`, `app/CLAUDE.md`, `tools/CLAUDE.md` y `docs/roadmap.md`. Medido: falló **cinco
# veces en una sola sesión**, siempre por lo mismo, y **cinco más** en la del 2026-09-21.
#
# ⚠️ **Lo que `--fix` NO toca, a propósito**: que un documento haya **dejado de afirmar** un
# conteo. Ahí la frase se reescribió o el dato se borró, y decidirlo es de quien escribe — un
# arreglo automático inventaría una frase o borraría una vigilancia sin que nadie se entere.
ARREGLAR = False


def _escribir(path, texto):
    with open(os.path.join(ROOT, path), "w", encoding="utf-8") as handle:
        handle.write(texto)


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


def _patrones_ignorados():
    """Los patrones de .gitignore que sirven para decidir si una ruta es generada.

    Deliberadamente parcial: solo prefijos de directorio (`build/`) y sufijos (`*.db`), que es
    todo lo que hace falta para distinguir un artefacto generado de un archivo del repo. No
    reimplementa el matcher de git, y no tiene por que.
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
            # Un directorio ignorado: cualquier segmento de la ruta que lo nombre alcanza.
            if patron.rstrip("/") in partes or candidate.startswith(patron):
                return True
        elif patron.startswith("*."):
            if candidate.endswith(patron[1:]):
                return True
        elif candidate == patron or candidate.startswith(patron.rstrip("*")):
            return True
    return False


def check_doc_paths(report):
    """Regla: un documento no apunta a un archivo que no existe. (CLAUDE.md, mapa de documentos)

    Un puntero muerto es peor que no tener puntero, y es la decadencia mas comun en un repo
    asistido por agentes: borran codigo mas rapido de lo que releen prosa.

    docs/roadmap.md queda exento a proposito: su trabajo es nombrar cosas que todavia no
    existen.

    ⚠️ **Y lo GENERADO tambien queda exento, porque si no el chequeo miente al reves**: mira
    `os.path.exists`, asi que una ruta como el pack de juguete --que `build_toy.py` escribe y
    que D-020 decidio no commitear-- **existe en la maquina de cualquiera que haya construido
    una vez y no existe en un clone limpio**. El chequeo pasaba en el arbol del que escribia y
    fallaba en el del que revisaba. Se encontro asi: verificando un commit con `git worktree`,
    que es donde esa diferencia se ve.

    La exencion sale de `.gitignore` y no de una lista aparte: si git lo ignora, no es un
    archivo del repo y un documento puede nombrarlo.

    ⚠️ **Y el changelog queda exento por la misma razon que el roadmap, pero al reves en el
    tiempo**: es un registro y nombra rutas que existian cuando se escribio la entrada. La carpeta
    docs/agents/ se retiro en D-221 y cinco entradas viejas la mencionan correctamente. El
    grandfathering por numero de linea que usa D-020 **no sirve aca**: el changelog se escribe de
    arriba, asi que cada sesion desplaza todos los numeros. El costo es real y se nombra: el
    documento mas largo del repo no tiene chequeo de rutas.
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
    """Regla: los enlaces relativos entre documentos resuelven. (CLAUDE.md, mapa de documentos)"""
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


def check_module_direction(report):
    """Regla: :app -> :dict-data -> :dict-core, y nunca al reves. (docs/architecture.md)

    `docs/architecture.md` lo pide por escrito desde que existe --"comprobar la direccion es lo
    primero que la auditoria tiene que agregar"-- y durante tres sesiones el documento describio
    un check que no existia. Ahora existe.

    Se mira el build file y no los imports a proposito: `:app` y `:dict-data` **comparten el
    nombre de paquete** `cl.fadiaz.dictionary.data`, asi que un import no dice de que modulo
    viene. La declaracion de dependencia si.

    Lo que rompe si esto se invierte no es estetico: `:dict-core` es el que se testea en
    milisegundos sin emulador y el que se espeja con el builder (D-005). Una dependencia hacia
    arriba lo ata a Android y esos tests dejan de poder correr.
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
    """Regla: ningun Tile abre un pack. (D-042, y el contrato de onTileRequest)

    No es una precaucion de rendimiento --que sin medir estaria prohibida-- sino el contrato de
    la API: `onTileRequest` esta anotado @MainThread y "must complete after at most 10 seconds".
    Abrir un pack de 69 o 295 MB ahi esta descartado por escrito.

    El diseno lo evita leyendo SharedPreferences, pero nada lo impedia: un TileService no tiene
    `onCleared`, asi que un pack abierto desde ahi se filtra --un handle nativo de SQLite y un
    dispatcher de un hilo-- por toda la vida del proceso, y en silencio.
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


def _bloque(texto, apertura):
    """El cuerpo del bloque que empieza en `apertura`, contando llaves. Vacio si no esta.

    No es un parser de Kotlin y no pretende serlo: alcanza para acotar un chequeo a la seccion
    que de verdad quiere vigilar, en vez de a todo el archivo.
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
    """Regla: la firma del release no deja secretos en el repo ni usa la clave de debug.

    Dos errores, los dos silenciosos:

    - **Una keystore o una contraseña trackeadas.** No rompe nada y no se nota, hasta que el
      repositorio se comparte. Una clave filtrada no se "arregla": se reemplaza, y reemplazarla
      significa que ninguna app instalada con la vieja se puede volver a actualizar.
    - **Firmar el release con `signingConfigs.getByName("debug")`.** Es la salida facil cuando el
      release sale sin firmar, y **parece funcionar**: instala y corre. Lo que rompe aparece
      meses despues, cuando se quiere publicar con la clave de verdad y el reloj rechaza la
      actualizacion porque la firma no coincide.
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

    # ⚠️ **Sólo dentro del bloque `release`**, y eso no es un detalle. La regla habla del APK que
    # sale a la gente; un build type aparte que firma con la clave de debug para poder instalarse
    # por adb es legítimo y necesario --es como se prueba R8 antes de que exista la keystore, y es
    # lo que Macrobenchmark exige--. Mirar el archivo entero prohibía eso por accidente.
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


def check_app_version(report):
    """Regla: el APK que sale al reloj se distingue del anterior. (D-095)

    versionCode y versionName nacieron como literales del template --1 y "1.0"-- y nada los
    incrementaba: ni tarea, ni script, ni CI, ni un git tag. Eso no es cosmetico: el instalador
    de Android **rechaza** un APK con versionCode menor al instalado, y acepta reinstalar el
    mismo numero solo porque la firma coincide. Es la misma trampa que devpack.py ya evita para
    los packs comparando data_version.

    Viven en gradle.properties y no en el .kts para que subirlos sea una linea que no toca
    logica de build. El .kts los lee con un default, asi que un clone sin la property sigue
    compilando --misma regla que la firma (D-086)-- y por eso este check mira la property y
    ademas que el .kts no la pise con un literal.
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
    """Ningun texto que ya tiene clave de recurso puede estar ESCRITO en el codigo.

    ⚠️ **Esto no lo agarra `check_locale_parity`, y por eso hace falta.** Las dos tablas pueden
    estar perfectamente parejas --las claves existen en los dos idiomas-- y la pantalla seguir
    dibujando el literal. Fue lo que paso: `home_saved` y `home_settings` existian en `values/` y
    en `values-es/`, y `SearchScreen` ponia "Guardadas" y "Ajustes" a mano. En un reloj en ingles
    salian en español, al lado de texto en ingles.

    Lo encontro **mirar la pantalla**, no un test, y este chequeo existe para que la proxima vez
    no haga falta mirar: si un valor de `values-es/` aparece entre comillas en un `.kt`, es que
    alguien escribio el texto en vez de pedir el recurso.

    Se compara contra el español y no contra el ingles a proposito: un valor ingles corto
    ("Search", "Saved") puede aparecer legitimamente en un nombre de test o un comentario, y el
    chequeo daria falsos positivos. Los valores en español no tienen ese problema.
    """
    ruta_es = os.path.join(ROOT, "app", "src", "main", "res", "values-es", "strings.xml")
    if not os.path.exists(ruta_es):
        report.failure("falta values-es/strings.xml", ruta_es)
        return
    with open(ruta_es, encoding="utf-8") as handle:
        texto = handle.read()
    # Solo los suficientemente largos: "ES", "OK" o "%s" aparecen en cualquier lado.
    # ⚠️ **Tres y no cuatro, y el cambio tiene un caso**: la palabra "voz" estaba escrita a mano
    # en `SearchBar` y este chequeo no la vio por un caracter. Se dibujaba en español en un reloj
    # en ingles. Con tres, "ES" y "OK" siguen afuera, que es lo que el piso protege.
    valores = {v for v in re.findall(r"<string name=\"[^\"]+\">([^<]*)</string>", texto)
               if len(v) >= 3 and "%" not in v}
    encontrados = []
    for ruta in _kotlin_sources(os.path.join(ROOT, "app", "src", "main")):
        with open(ruta, encoding="utf-8") as handle:
            # Sin los comentarios: un KDoc que CITA el texto --"no puede parecerse a `sin.`"--
            # esta explicando la regla, no rompiendola. Mirar el archivo entero daba ese falso
            # positivo, y un chequeo con falsos positivos se termina apagando.
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
    """Regla: values/ y values-es/ declaran las MISMAS claves, y las cortas siguen cortas. (D-127)

    Una clave que existe en `values/` y falta en `values-es/` no rompe nada: Android cae al
    default y el usuario ve **una linea en ingles dentro de una pantalla en español**. No hay
    excepcion, no hay log, y quien la agrego no la ve porque su reloj esta en el otro idioma.

    Lo segundo que comprueba es por que el nombre del pack se acorto (D-125): la etiqueta de tipo
    se dibuja al lado del nombre en una fila de reloj, y una traduccion larga reintroduce el
    recorte que D-125 vino a arreglar. Se mide en LOS DOS idiomas, que es algo que ningun test de
    pantalla puede hacer -- cada uno corre en un locale.
    """
    import re as _re

    def claves(ruta):
        texto = read(ruta)
        tabla = {m.group(1): m.group(2) for m in
                 _re.finditer(r'<string name="([^"]+)"[^>]*>(.*?)</string>', texto, _re.S)}
        # Los plurales cuentan igual. Sin esto, un `<plurals>` que existe en un idioma y falta
        # en el otro pasa el chequeo: la paridad miraba solo `<string>`, asi que el primer
        # plural del repo --el contador de entradas de un pack-- habria nacido fuera de la
        # regla. Se guardan con un prefijo para que no puedan chocar con una clave de string.
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

    # Lo que se dibuja al lado del nombre del pack, en una fila de reloj.
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
    """Regla: el selector de idioma ofrece EXACTAMENTE los idiomas que la app tiene. (D-158)

    Las dos mitades se rompen distinto y ninguna da error:

    - Una carpeta `values-xx/` nueva sin fila en el selector: la traduccion existe, se aplica si
      el reloj esta en ese idioma, y **no hay forma de elegirla a mano**. Nadie la encuentra.
    - Una fila en el selector sin carpeta: se puede elegir un idioma que no existe, y la app
      queda en ingles con el selector marcando otra cosa. Es peor que no ofrecerlo.

    El endonimo se comprueba aparte porque es la unica cadena de la UI que **no** se traduce a
    proposito: un selector de idioma escrito en el idioma que no entiendes no sirve para nada.
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
    # `values/` es la base y es el ingles (D-127): no lleva sufijo, pero es un idioma ofrecible.
    carpetas = {"en"}
    for nombre in os.listdir(res):
        if nombre.startswith("values-") and os.path.isfile(
                os.path.join(res, nombre, "strings.xml")):
            sufijo = nombre[len("values-"):]
            # Solo los calificadores de idioma: `values-round` o `values-v33` no son traducciones.
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
    """Cuantas veces aparece `patron` al principio de una linea, bajo `carpeta`."""
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
    """Regla: un numero que un documento afirma tiene que ser el numero que hay.

    ⚠️ **Es la decadencia que este repo ya pago cuatro veces a la vez.** En una sola revision se
    encontro que `README.md` decia 560 tests, `dict-data/CLAUDE.md` decia 31 instrumentados,
    `tools/CLAUDE.md` decia 129 de Python y `app/CLAUDE.md` decia 178 de JVM. Ninguno era cierto,
    ninguno rompia nada, y cada uno le hace perder el tiempo a quien lo lea -- o peor, le hace
    creer que una suite encogio.

    El conteo es **estatico** --lineas que empiezan con `@Test` o `def test_`-- y eso no es una
    aproximacion: se comparo contra los conteos de runtime de Gradle y unittest el 2026-09-20 y
    dan **exactamente** lo mismo (81, 251, 250). Un conteo estatico deja el chequeo en la
    auditoria, que es stdlib pura y no necesita compilar nada.

    ⚠️ **Si una frase se reescribe y el patron deja de matchear, esto FALLA.** Es deliberado y es
    la misma politica que `check_app_logic_is_jvm_testable`: un chequeo que se apaga solo cuando
    alguien toca el texto que vigila no vigila nada. Reescribir la frase obliga a venir aca.
    """
    nucleo = _contar(os.path.join("dict-core", "src", "test"), "@Test")
    app_jvm = _contar(os.path.join("app", "src", "test"), "@Test")
    python = _contar(os.path.join("tools", "packbuilder", "tests"), "def test_")
    datos = _contar(os.path.join("dict-data", "src", "androidTest"), "@Test")
    app_disp = _contar(os.path.join("app", "src", "androidTest"), "@Test")
    if None in (nucleo, app_jvm, python, datos, app_disp):
        report.failure(
            "no se pudo contar los tests",
            "alguna carpeta de tests cambio de lugar; mover tambien este chequeo",
        )
        return

    checks = len(CHECKS)
    # Lo que corre el gate, y lo que no. La suma "en total" incluye los checks a proposito:
    # es como el roadmap la viene contando.
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
    """Regla: las reglas de keep estan cableadas y no protegen clases que ya no existen. (D-163)

    R8 esta encendido y **lo que rompe, lo rompe solo en release y sin error de compilacion**.
    Este chequeo cubre las dos formas en que las reglas dejan de servir sin que nadie lo note:

    ⚠️ **(1) El archivo deja de estar cableado.** `keepRules { files.add(...) }` es una linea de
    `app/build.gradle.kts`; si alguien reorganiza ese bloque y la pierde, las reglas **siguen ahi
    y no se aplican**. El build sigue verde, el APK sigue saliendo, y lo que falla es un tile en
    un reloj.

    ⚠️ **(2) Una regla nombra una clase que ya no existe.** Renombrar `HistoryTileService` sin
    tocar el `.pro` deja una regla muerta que no protege nada -- y `app/CLAUDE.md` ya tiene
    escrito que romper un tile no da error de compilacion ni test. Es la misma decadencia que
    `check_doc_paths` atrapa para los documentos, aplicada a las reglas.

    No comprueba que cada componente del manifest TENGA regla: AGP ya conserva los componentes
    declarados, asi que exigirlo seria sobre-restringir. Lo que se vigila es que lo que decidimos
    proteger siga protegido de verdad.
    """
    # Sin los comentarios: el bloque `optimization` EXPLICA que las reglas estan en
    # proguard-rules.pro, asi que mirar el archivo entero daba un falso negativo -- se podia
    # desconectar el `files.add(...)` y el chequeo seguia pasando por culpa de la prosa. Se
    # encontro comprobando que el chequeo fallara, que es para lo que se comprueba.
    build = "\n".join(
        linea for linea in read(os.path.join("app", "build.gradle.kts")).split("\n")
        if not linea.lstrip().startswith(("//", "*", "/*"))
    )
    if "enable = true" not in build:
        # R8 apagado: no hay nada que vigilar. Va PRIMERO, antes incluso de mirar si el archivo
        # de reglas existe: con R8 apagado no hace falta, y fallar por su ausencia haria que este
        # chequeo impidiera apagar R8 -- que es justo lo que alguien querria hacer si R8 rompiera
        # algo en el reloj.
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

    # Solo las clases nuestras: las de androidx cambian de paquete con la libreria y no son
    # nuestras para arreglar.
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
    """Regla: el manifest no declara permisos que no se usan, ni pierde lo que la app necesita.

    ⚠️ **«100 % offline» es la primera linea del README y de `CLAUDE.md`, y no tenia enforcer.**
    Si alguien agrega `INTERNET` --y el instalador de packs lo va a necesitar algun dia (D-029)--
    la afirmacion central del proyecto deja de ser cierta y **nada lo dice**. Este chequeo no
    prohibe agregarlo: obliga a venir aca y cambiar la regla a mano, que es la diferencia entre
    una decision y un descuido.

    Lo segundo que mira es lo contrario: un permiso declarado y **nunca usado**. `WAKE_LOCK`
    estuvo asi desde el template hasta el 2026-09-20; no rompe nada, pero se lo muestra al
    usuario al instalar y hay que poder justificarlo.

    Y lo tercero es `localeConfig`, que se genera desde las carpetas `values-*` reales: sin el,
    la app **no aparece en la lista de idiomas del sistema** (el selector propio si funciona, y
    por eso el hueco paso desapercibido dos sesiones).
    """
    manifest = read(os.path.join("app", "src", "main", "AndroidManifest.xml"))
    build = read(os.path.join("app", "build.gradle.kts"))

    permisos = set(re.findall(r'uses-permission android:name="android\.permission\.(\w+)"',
                              manifest))
    # ⚠️ Esta regla CAMBIO el 2026-09-22, cuando llego el catalogo de descarga (D-213), y el
    # cambio es el punto: antes prohibia INTERNET porque nada lo usaba, y ahora vigila que la
    # promesa que queda siga escrita. La propiedad que se defiende nunca fue "no hay red" sino
    # **"buscar no usa red"**, y esa distincion se pierde en un commit si nadie la comprueba.
    inspeccion = permisos & {"ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE"}
    if inspeccion:
        report.failure(
            "la app inspecciona la red por su cuenta",
            "AndroidManifest.xml declara %s, y no hace falta: las restricciones de D-029 "
            "--cargando y Wi-Fi-- las impone WorkManager. Un permiso que existe para mirar el "
            "estado de la red es una invitacion a decidir cuando descargar desde la app, que es "
            "exactamente lo que D-029 saco de ahi" % ", ".join(sorted(inspeccion)),
        )

    # INTERNET si esta permitido, pero solo mientras los dos documentos sigan diciendo QUE es lo
    # unico que lo usa. Si alguien borra esa frase, la promesa se vuelve indefendible en silencio.
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

    # Un permiso declarado tiene que aparecer en el codigo. La heuristica es grosera a proposito:
    # busca el nombre del permiso o su API mas obvia, y con eso alcanza para el tamano de este
    # manifest.
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


# Las claves de meta que un pack DEBE traer. Crecer esta lista rompe todos los packs ya
# instalados: `getValue` lanza y el pack se rechaza entero al abrir.
# ⚠️ `langs` y `fuzzy_profiles` entraron con `schema_version` 4, que es lo que esta lista exige
# para crecer: los packs anteriores se rechazan al abrir, y eso es correcto porque tampoco tienen
# la columna `entry.lang`. `lang_src`/`lang_dst` salieron -- los idiomas de un pack son pares.
META_OBLIGATORIAS = {
    "attribution", "data_version", "entry_count", "fuzzy_profiles", "kind", "langs", "license",
    "name", "norm_version", "pack_id", "payload_dict", "schema_version",
}


def check_required_meta_keys(report):
    """Regla: la lista de claves de meta OBLIGATORIAS no crece sin que alguien lo decida. (D-174)

    ⚠️ **Agregar una clave con `getValue` rompe todos los packs que ya estan en un reloj**, y no
    al construirlos: al ABRIRLOS. `getValue` lanza, `PackFile.open` lo convierte en un pack
    rechazado, y el usuario se queda sin diccionario hasta reconstruir y volver a subir -- hoy
    **372,6 MB**.

    La forma correcta de sumar un dato al pack es `meta[...]`, que devuelve null en un pack viejo
    y deja que el codigo decida. Es lo que ya se hizo con `description` (D-125), `sources`
    (D-138) y `subset_of`: tres claves nuevas, cero packs rotos.

    Este chequeo no prohibe subir la lista: **obliga a venir aca y cambiarla a mano**, que es la
    diferencia entre una decision y un descuido. Es la misma politica que los archivos vigilados
    de D-072.
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
    ("tools/", 1778),
    ("app/src/main/", 1293),
    ("dict-core/src/main/", 587),
    # Translated whole on 2026-09-23: the first module of stage 3, smallest first. The row
    # stays at 0 rather than being deleted -- a removed row is a ceiling nobody watches.
    ("dict-data/src/main/", 0),
    ("docs/roadmap.md", 2314),
    ("docs/decisions.md", 262),
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


def check_skills_reachable(report):
    """Regla: una regla que sale de CLAUDE.md sigue siendo alcanzable. (D-222)

    CLAUDE.md se paga en cada request y vive bajo 200 lineas, asi que las reglas menos
    especificas se mudan a una skill. El problema es que una skill **no se carga sola**: se
    carga cuando su `description` matchea lo que el usuario dijo. Entonces mudar una regla y
    dejar el puntero no alcanza --la regla deja de leerse justo en la situacion que gobierna.

    Dos mitades:

    1. **Toda skill que CLAUDE.md nombra existe.** Un puntero roto es una regla perdida.
    2. **Toda skill existente esta nombrada** en CLAUDE.md o en otra skill. Una skill a la que
       nadie apunta es contenido que se saco de la vista y nadie va a volver a leer.

    No comprueba que la `description` dispare --eso no es decidible-- y por eso la segunda
    mitad es lo que queda: si algo se muda, el mapa tiene que nombrarlo.
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
    """
    texto = read("docs/decisions.md").split("## Decisiones descartadas")[0]
    rows = re.findall(r"^\| (D-\d+) \|.*\|([^|]*)\|\s*$", texto, re.M)
    without = [d for d, enforcer in rows if enforcer.strip().startswith("—")]
    report.advisory(
        "decisiones sin enforcer",
        "%d de %d se pueden romper en silencio: %s"
        % (len(without), len(rows), ", ".join(without)),
    )


def _bundle_body(relative):
    """El cuerpo de un documento del bundle: todo menos su frontmatter."""
    lines = read(relative).splitlines(True)
    if not lines or lines[0].rstrip("\n") != "---":
        return None, lines
    for i in range(1, len(lines)):
        if lines[i].rstrip("\n") == "---":
            return "".join(lines[1:i]), lines[i + 1:]
    return None, lines


def _declared(front, field="digest"):
    match = re.search(r'^\s*%s:\s*"([0-9a-f]+)"' % field, front or "", re.M)
    return match.group(1) if match else None


def _digest(relatives):
    body = []
    for relative in relatives:
        body.extend(_bundle_body(relative)[1])
    return hashlib.sha256("".join(body).encode("utf-8")).hexdigest()[:12]


def _bundle_md(subfolder):
    """Los .md bajo .agents/<subfolder>, en orden de byte de la ruta relativa al bundle."""
    found = []
    base = os.path.join(ROOT, BUNDLE, subfolder)
    for folder, _, names in os.walk(base):
        for name in names:
            if name.endswith(".md"):
                full = os.path.join(folder, name)
                found.append(os.path.relpath(full, os.path.join(ROOT, BUNDLE)))
    return sorted(found)


def check_bundle_digests(report):
    """Regla: el bundle de agentes no se edita en el lugar; cambiarlo es forkear. (D-059, D-221)

    Tres headers declaran un digest sobre su propio contenido, y los tres se comprueban:
    el del metodo (`.agents/method/prompt-*.md`), el de la base de conocimiento
    (`.agents/knowledge/notes/*.md`) y el del bundle entero (`method/` + `knowledge/` +
    `layout.md`). Cada uno es el sha256 de los cuerpos concatenados en orden de nombre, con
    el frontmatter sacado --por eso escribir el digest en el header no cambia el digest--,
    cortado a 12 hex.

    Si el contenido no da ese numero, alguien edito el bundle sin forkear ni recalcular, y la
    proxima comparacion entre dos copias va a concluir "identicas" descartando un lado en
    silencio.

    ⚠️ **Antes vivia en `docs/agents/` y cubria cuatro archivos; ahora son 65.** La version
    vieja tambien hacia `return` si la carpeta no estaba, y eso ES el modo de falla que este
    repo no puede ver: por eso ahora la ausencia del bundle FALLA en vez de callarse.
    """
    if not os.path.isdir(os.path.join(ROOT, BUNDLE)):
        report.failure(
            "el bundle de agentes no esta",
            "%s/ es donde vive el metodo (D-221). Sin el, este chequeo no comprueba nada" % BUNDLE,
        )
        return

    method = _bundle_md("method")
    sets = [
        ("metodo", method, method),
        ("conocimiento", [os.path.join(BUNDLE, "knowledge/README.md")], _bundle_md("knowledge/notes")),
        ("bundle", [os.path.join(BUNDLE, "README.md")],
         _bundle_md("method") + _bundle_md("knowledge") + ["layout.md"]),
    ]

    for label, headers, content in sets:
        declared = set()
        for relative in headers:
            where = relative if relative.startswith(BUNDLE) else os.path.join(BUNDLE, relative)
            front = _bundle_body(where)[0]
            value = _declared(front)
            if value:
                declared.add(value)
        if not declared:
            report.failure("el set %s no declara digest" % label, ", ".join(headers))
            continue
        if len(declared) > 1:
            report.failure(
                "los headers del set %s no coinciden" % label,
                "digests distintos entre archivos: %s" % ", ".join(sorted(declared)),
            )
            continue

        paths = [c if c.startswith(BUNDLE) else os.path.join(BUNDLE, c) for c in sorted(content)]
        actual = _digest(paths)
        expected = declared.pop()
        if actual != expected:
            report.failure(
                "el set %s no corresponde a su digest" % label,
                "declara %s y el contenido da %s. Editar el bundle es forkear (D-059): "
                "nueva id opaca en ancestry, forked_at, y recalcular el digest" % (expected, actual),
            )


def check_rejection_mirror(report):
    """Regla: los motivos de rechazo de la app y los de verify_pack.py coinciden. (D-217)

    ⚠️ **Es el CUARTO contrato cruzado del repo, y el unico que nacio con enforcer.** Los otros
    tres --`norm()`, `sense_code` y el indice del catalogo-- lo ganaron despues de separarse. El
    modo `--como-la-app` de `verify_pack.py` existe para contestar *"si subo este pack al reloj,
    ¿aparece?"*, y esa respuesta vale exactamente lo que valga su fidelidad: un motivo agregado
    en Kotlin y no aca hace que el verificador diga que si a un pack que la app va a rechazar.

    ⚠️ **Compara tambien el ORDEN.** Todas las comprobaciones rechazan (D-217), asi que el orden
    no decide si un pack entra: decide **que motivo se reporta**, que es la unica linea que el
    usuario lee. Los siete packs de `schema_version` 3 del directorio de datos salian como
    "metadatos incompletos" en vez de "otra version del formato" por tener el orden al reves.
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

    # El orden de EVALUACION tambien se declara, y tiene que cubrir los mismos motivos menos
    # `damaged`, que no se comprueba: es lo que queda cuando abrir el archivo lanza.
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
    """Regla: un comentario XML no contiene `--`, que es su propio cierre.

    ⚠️ **Tercer golpe, y por eso existe** (roadmap §Proceso). El estilo de comentario de este
    repo escribe `--` todo el tiempo --«el nombre mentia --y lo decidio una medicion--»-- porque
    es el guion de inciso que se teclea sin raya. En `.kt` y en `.py` es correcto; en XML rompe
    `mergeDebugResources` con *"The string `--` is not permitted within comments"*, seguido de
    treinta lineas de stack de Xerces que no nombran el archivo hasta la primera.

    El sintoma se lee como un problema de recursos y no de puntuacion, y cuesta una corrida de
    Gradle averiguarlo. Aca cuesta segundos. La alternativa --acordarse de escribir `—`-- ya
    fallo tres veces.
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
    """Regla: el nombre del indice de versiones coincide en el build y en la app. (D-229)

    `bundlePacks` escribe `assets/<CORE_INDEX>` con el `data_version` de cada pack incluido, y
    `PackStore` lo lee por el mismo nombre. Son los dos extremos de un archivo y **no hay forma de
    compartir una constante entre un script de Gradle y el codigo de la app**.

    El modo de falla es el que este repo no puede ver: si los literales se separan, el indice
    **no se encuentra y nada falla**. `coreIndex` devuelve un mapa vacio --por diseno, para que un
    APK sin indice degrade-- asi que los nucleos quedan sin version declarada y **no se actualizan
    nunca**. Sin excepcion, sin log de error, y con la app funcionando.
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
    """Regla: una sonda de mutacion no sobrevive a la sesion que la corrio. (D-236)

    El paso 3 del session loop del metodo: *las sondas las encuentra el audit y las limpia quien
    las corrio*. Antes de esto dependia de acordarse, y acordarse no es un mecanismo.

    ⚠️ **El modo de falla es el peor que tiene este repo: una mutacion olvidada NO rompe nada.**
    Se escribe para que un test falle, se comprueba que falla, y si el restore no vuelve --o
    vuelve a medias-- lo que queda es codigo deliberadamente equivocado con todos los tests en
    verde, porque el test que la detectaba es justo el que se estaba probando. Ya mordio una vez
    de otra forma: macOS cachea bytecode fuera del repo y una mutacion del mismo ancho sobrevivio
    a un restore.

    Busca los marcadores que este repo usa al sondear. No cubre una mutacion sin marcar --nada
    puede-- y por eso el marcador es la convencion: **una sonda se escribe con su marca**, y esta
    regla convierte olvidarla en la unica forma de que pase desapercibida.
    """
    marcadores = ("MUTACION", "MUTACIÓN", "MUTATION PROBE", "SONDA:")
    # Este archivo se nombra a si mismo; excluirlo es la unica exencion, y va escrita.
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
