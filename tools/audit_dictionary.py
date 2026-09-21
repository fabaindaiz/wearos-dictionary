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
    """
    ignorados = _patrones_ignorados()
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

    if re.search(r'signingConfig\s*=\s*signingConfigs\.getByName\(\s*"debug"', texto):
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
        ("tools/CLAUDE.md", r"hatch run test\s+# los (\d+) tests"): python,
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
            report.failure(
                "un documento afirma un conteo que no es",
                "%s dice %s donde hay %d (%r)"
                % (documento, hallado.group(1), esperado, patron),
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
    check_module_direction,
    check_app_logic_is_jvm_testable,
    check_tiles_dont_open_packs,
    check_attribution_screen,
    check_release_signing,
    check_app_version,
    check_locale_parity,
    check_ui_language_picker,
    check_test_counts,
    check_no_hardcoded_translations,
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
