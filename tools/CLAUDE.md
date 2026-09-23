# tools

Two areas of Python, **with no third-party dependencies** (stdlib only). That property is
deliberate and is kept: `dependencies = []` in `pyproject.toml`.

- `packbuilder/` — builds the `.db` packs from lexicographic sources.
- `unicode/` — generates the pinned Unicode repertoire shared by the builder and the app.

Plus five loose scripts at the root: `audit_dictionary.py` (the structural audit, which *does*
run in the gate), `devpack.py` (sideloading over adb), `avd_como_el_reloj.py` (the emulator with
the watch's geometry, D-150) and `measure_query_cost.py`.

`measure_query_cost.py` **does not measure battery — it measures the work the battery pays for**:
how many rungs of the cascade run, how many rows they touch, and how much the SQL costs, over a
real pack. It is a **replica** of `SqlitePackSource`'s SQL with its constants copied, so if those
change and this does not, it lies quietly — it says so in its own docstring. See `docs/bateria.md`.

## The environment

Hatch, configured in the root `pyproject.toml`. The gate does not need it — `./gradlew check` runs
plain `python3`, so a clean clone works without installing anything. Hatch is the development
layer.

```sh
hatch run test              # the 494 tests
hatch run audit             # the structural audit
python3 tools/audit_dictionary.py --fix   # rewrites the test counts it finds wrong
hatch run all               # both
hatch run matrix:test       # THE TESTS UNDER EVERY PYTHON VERSION
hatch run lint:check        # ruff, defects only
hatch run lint:fix          # fixes what it can on its own
hatch run format-check      # the formatter, kept separate on purpose (see below)
hatch run build-toy         # regenerates the toy pack
hatch run verify <pack.db>  # invariants of a real pack
hatch run gen-repertoire    # regenerates the repertoire (only runs under Python 3.9)
hatch run push <pack.db>    # installs a pack on the watch over adb
hatch run packs             # which packs are installed
```

⚠️ **`check` is the linter and not the formatter, and that split is measured.** They used to run
together and the result was that neither ran: `ruff format --check` reports **2,359 lines across 28
of 36 files**, because this repo's Python was written by hand in another style and the formatter
never ran. With that much noise the 26 real findings the linter did have — unused variables,
ambiguous names — were invisible. So `check` looks for **defects** and has to be zero; adopting the
formatter is a separate act with its own commit (D-160).

## `devpack.py` is not the installer

`tools/devpack.py` is the development layer of sideloading, the same way Hatch is the development
layer of the gate: it puts a `.db` into `filesDir/packs/` over adb and nothing else. It does not
download, it knows no catalogue, and it knows nothing about D-029. The real installer is blocked on
a product decision — where the catalogue is hosted — and when it exists it will write into that same
directory.

What it solves, and why it is not an `adb push`: the copy is **atomic** (`.part` + `mv`, the same
convention `PackStore.installAtomically` uses) and it is checked with **sha256 on both sides**
before renaming. A half-copied `.db` opens without error and returns fewer words than it holds. See
D-082.

It lives in `tools/` and not in `packbuilder/` because it neither builds nor validates packs: it
talks to the device. Its pure logic — assembling the command plan, picking a device, comparing
hashes — enters the gate through `:tools:pythonTest`; running adb needs a watch and **does not**.

## `packserver.py`: the download path, without deciding where the catalogue lives

The installer was blocked on a product question — *where is the catalogue hosted* — and that also
blocked writing the mechanism. This is the development answer: a static file server over a
directory of packs, stdlib only.

```sh
adb reverse tcp:8765 tcp:8765                                       # the tunnel, first
python3 tools/packserver.py ../wearos-dictionary-data/dist --port 8765
python3 tools/packserver.py ../wearos-dictionary-data --index-only   # just print the index

# ⚠️ **El indice TAMBIEN lo consume el build del APK**, y por eso conviene dejarlo escrito en
# `dist/`. `:app:bundlePacks` lo lee para saber que `data_version` tiene cada nucleo que empaqueta
# y lo copia a `assets/core-index.tsv` (D-229). Sin el, la app no puede decidir si el nucleo del
# APK es mas nuevo que el que el usuario bajo, y **deja el del usuario** -- que es seguro, pero
# significa que un nucleo actualizado desde el catalogo no vuelve a actualizarse nunca.
#
# Se regenera despues de construir packs. Si queda desactualizado NO miente: `bundlePacks` compara
# el `db_bytes` declarado contra el archivo real y, si no coinciden, no declara version.
python3 tools/packserver.py ../wearos-dictionary-data/dist --index-only \
    > ../wearos-dictionary-data/dist/index.json
```

⚠️ **Start with `adb reverse`, and the reason is measured.** The app's default catalogue URL used
to be `10.0.2.2` — the host alias an emulator normally has — and **it does not work**: verified on
this project's emulator on 2026-09-22, `SocketTimeoutException: failed to connect to /10.0.2.2
(port 8765) from /10.0.2.15 after 8000ms`, while the same server answered 200 to curl from the host
on both loopback and the LAN address.

⚠️ **The cause is not proven, and saying so is part of the record**: macOS's firewall is enabled
(`socketfilterfw --getglobalstate` returns 1) and Python is not in its allow list, which is
*consistent* with the timeout — but the block itself was not measured. What is a fact is that
`10.0.2.2` did not arrive and `adb reverse` did.

`adb reverse tcp:8765 tcp:8765` tunnels the device's own `localhost:8765` to this machine over adb,
which sidesteps the firewall, **needs no IP discovery** (that changes network to network), and
**works identically on a real watch** over wireless debugging, where `10.0.2.2` means nothing. The
server still prints the LAN address for when serving over Wi-Fi is what you want.

**The index is generated by reading the packs, and that is correctness, not convenience.** Every
field comes from the `.db`'s own `meta` or from measuring the file, so it cannot disagree with
what is served. A hand-kept catalogue that claims `data_version` 202609211937 for a file that is
now a different file is precisely the failure this repo cannot observe: the app believes the
version and never downloads again.

### Two fields are in the index for efficiency, not for display

| | |
|---|---|
| `schema_version`, `norm_version` | Published **before the url**. They are the two that reject a pack whole (D-001, D-006), so the watch can discard an incompatible pack **without spending 192 MB to throw it away** |
| `sha256` **and** `db_sha256` | Two hashes: of the `.gz` that travels and of the `.db` that lands. Verification happens at both moments, and `installAtomically` compares the second (D-165) |

⚠️ **`attribution` is deliberately NOT published**: ~600 characters per pack — the Spanish core
cites five sources — and **the pack already carries it**, so the app reads it after installing.
`license` is published because it is short and the screen shows it before downloading.

### Why `.db.gz` as an opaque file, and why the raw `.db` too

Measured: Spanish 73.6 → ~37 MB, English 306.8 → ~192 MB. But with `Content-Encoding: gzip` the
decompression is transparent, so `Range` would apply to the **decompressed** stream: no resume, and
`Content-Length` lies. On a watch that downloads 192 MB only while charging, losing resume costs
more than the bytes saved. So the `.gz` travels as just another file and the app inflates it.

**And the raw `.db` is served alongside, on purpose.** A block-level delta update (zsync) needs
`Range` over bytes that resemble the previous version, and a gzip stream resembles nothing after
the first changed byte. Keeping the raw file costs nothing on a development machine and is what
holds that door open. See `docs/roadmap.md` §Actualización versionada de packs.

### `Range` is implemented here because the stdlib does not have it

`SimpleHTTPRequestHandler` ignores `Range`. Without it, a Wi-Fi drop at 90 % of 192 MB means
starting over — and **D-040 picked `HttpURLConnection` precisely because it does `Range`**, so a
server without it makes that decision unverifiable. The three forms of RFC 9110 §14.1.1 are
handled, and an unsatisfiable range is a **416 and not the whole file**: swallowing it would make a
miscalculated resume look like success.

⚠️ **Three things reading the real output found that the tests did not.** `.OLD` backups sit next
to the good packs in the data directory and publishing them would serve a stale pack as the
catalogue; `attribution` is dead weight; and `es-def-wd` is `schema_version` 3 and therefore has
**no `langs` field at all** — published as-is, because rejecting it cheaply is the whole point.

## Why the version matrix matters most

The builder writes keys that the watch recomputes. That it behaves identically across every Python
is not a convenience: it is the central invariant.

**Measured 2026-09-17:** the tests pass identically under Python 3.9 (Unicode 13.0) and 3.14
(Unicode 16.0), including `abࡰcd` → `ab cd`, which is the exact case that diverged before the
repertoire was pinned. The builder is independent of the Python version.

The exceptions are `gen_repertoire.py` **and `gen_casefold.py`**, which **require Python 3.9.x** because they need exactly
Unicode 13.0.0. The guard is verified: under 3.14 it refuses with exit 1.

## The file to copy

One module: `payload.py` — a docstring that explains the decision and the alternative that was
rejected, short functions, zero global state.

One test: `tests/test_build.py` — it builds real packs in a temporary directory and inspects them
with SQL. It does not mock SQLite.

## The builder is two-pass, and not for fun

The real sources are gigabytes (the Wiktionary JSONL is 1.1 GB) and do not fit in memory. So
`PackBuilder` writes to a staging table inside the file itself, assembles the compression dictionary
from a reservoir sample, and only on the second pass compresses and fills `entry` and `fts_def`.

Indexes are created **at the end**, over already-populated tables. Maintaining them during ingestion
is far slower.

If something fails halfway, the pack is deleted. A half-built pack is worse than none: it opens
without error and returns fewer results than it should.

## Deriving a core pack

`build_core.py` takes a **complete pack that is already built** and keeps the entries whose headword
is among the most-used words of the language.

⚠️ **It derives rather than rebuilding from the dumps, and that is correctness rather than
convenience.** The core declares `subset_of`, and deriving it makes that claim true **by
construction** — built separately it would hold only while both runs used the same sources and the
same filters, a promise nothing checks. It also takes seven seconds instead of an hour.

⚠️ **The vocabulary comes from usage frequency (Tatoeba), never from `rank`, and the gap is
measured**: by `rank` a core takes **91 %** of the Spanish inflection table — the richest pages are
verbs, and a Spanish verb has 33 forms — while by frequency it takes **5.5 %**. See D-175.

## `build_packs.py`: el pipeline entero, y donde vive cada cosa

**Los scripts van en el repo; los packs, no.** Son 4,4 GB de dumps y cientos de MB de artefactos,
y `.gitignore` ya cubre `/*.db`. Lo que faltaba es que **el orden del rebuild vivía sólo en prosa**
—en esta misma página—, y ahí no se puede correr ni comprobar.

```sh
python3 tools/build_packs.py ../wearos-dictionary-data --dry-run   # el plan, sin tocar nada
python3 tools/build_packs.py ../wearos-dictionary-data             # y corriéndolo
python3 tools/build_packs.py ../wearos-dictionary-data --solo es
```

### Tres directorios, y la separación es el punto

```
<raíz>/dumps/   las entradas: los .jsonl de kaikki, los corpus, WordNet, dbnary
<raíz>/build/   los INTERMEDIOS: packs que son entrada de un merge y no se distribuyen
<raíz>/dist/    lo que se publica, y lo único que `packserver.py` debe servir
```

⚠️ **Con todo en un directorio plano, `es-def-wd` apareció en el catálogo del emulador como un
pack descargable.** No lo es: es una fuente que el pack español lleva **fundida dentro**.
Publicarlo ofrece un diccionario de una sola fuente, que es justo el modelo que D-215 descartó.

⚠️ **Y hoy `build/` está vacío, porque ese intermedio resultó no existir.** El plan lo construía
creyendo que `--sumar` leía un pack; `--sumar <pack> <dump>` lee el **dump** —el nombre sólo
selecciona el lector y la atribución (D-146)—, así que `build/es-def-wd.db` no lo consumía nadie:
30 s y 4,5 MB por nada. Lo agarró el **rebuild**, no el gate, y ahora lo fija
`test_nada_se_construye_para_que_NADIE_lo_consuma`. El directorio se queda: separar lo intermedio
de lo publicable sigue siendo la regla, y lo que hoy no tiene sujeto mañana lo tiene.

⚠️ **El inglés completo vive en `dist/` aunque TAMBIÉN sea una entrada** —del bilingüe, por
`--flexiones`—. Es las dos cosas, y lo que decide dónde vive es **si se distribuye**.

### El orden, y por qué un test lo fija

`--flexiones` lee un pack **ya construido** del idioma destino, así que el bilingüe tiene que ir
después del inglés. ⚠️ **Saltárselo no da error**: el pack sale bien formado, pasa `verify_pack.py`
y es **peor en silencio** — 8,6 puntos de cobertura inversa, medidos.

Por eso `plan()` se devuelve en vez de correrse: **la forma del plan entra al gate** y correrlo
necesita los dumps, que no. Es el mismo reparto que `devpack.py`.

Y cada pack publicable **se verifica antes de seguir**: encadenar sobre un pack a medias propaga
el defecto, y un pack a medias se abre sin error.

### Qué se genera, y qué no

Los niveles salen de `build_core.py --rango-mb <min> <max>`, derivando del `full` y nunca de otro
nivel —derivar un `core` de un `main` haría que `subset_of` apunte al intermedio—. ⚠️ **Un idioma
cuyo `full` ya cabe entero en el rango de `main` no genera `main`**: el español completo son 73,6
MB, por debajo del máximo de 150, así que sería un segundo pack con el mismo contenido. Lo decide
el tamaño medido, no una lista escrita a mano.

⚠️ **Es un rango y no un presupuesto, y eso es lo que hace que se cumpla.** `--budget-mb` estima
el tamaño escalando los payloads por la proporción del pack de **origen**, y el derivado tiene
otra —se lleva las formas de sus lemas y no las de los demás—: **pedir 25 MB dio 17,7**, por
debajo del mínimo del nivel, sin error y con el pack pasando todas sus invariantes. `--rango-mb`
deriva, **mide el archivo**, corrige el factor y vuelve, hasta cuatro veces, y **sale con código 1
si no lo logra** — porque un exit 0 con un artefacto fuera de rango le dice al pipeline *esto
cumple*. `--budget-mb` sigue existiendo para explorar la curva; publicar con él no garantiza nada
(D-220).

## ⚠️ Rebuilding a pack: the flags that are not optional

**A pack built without its flags comes out well-formed, passes `verify_pack.py`, and is quietly
worse.** There is no error to notice, so they are listed here rather than only in
`build_pack.py --help`:

| pack | sin qué sale peor | qué se pierde |
|---|---|---|
| `es` | `--frases` · `--tesauro` · `--sumar es-wd` | ejemplos, sinónimos de WordNet, 5.283 lemas |
| **`es-en`** | **`--flexiones en-def-wikt.db`** | **la dirección inversa**: sin él `ran`, `went` y `eaten` no llegan. ⚠️ **Desde D-196 las flexiones van al `form` de la ENTRADA inglesa** —`went` es flexión de `go`, y `go` ya es un lema— en vez de expandirse dentro de `trans`, que en un pack bidireccional está vacía. Saltarse el flag costó **8,6 puntos** de cobertura inversa en el top 1.000, medidos sobre el pack construido: 97,0 % con él, 89,8 % sin él |
| `en` | `--tesauro` | +30.423 entradas con sinónimos |

⚠️ **Y el bilingüe se construye DESPUÉS del inglés, no en cualquier orden**: `--flexiones` lee
un pack ya construido, así que `en-def-wikt.db` tiene que existir antes. El orden completo de un
rebuild es **inglés → español → bilingüe → los dos núcleos**.

`--flexiones` toma un **pack ya construido** del idioma destino, no un dump: las flexiones ya
están extraídas y podadas ahí, y volver al dump de 3,2 GB sería otra hora de build más una segunda
poda que puede divergir de la primera — el mismo razonamiento de D-175.

## `--como-la-app`: would the watch accept this pack?

`verify_pack.py` answers *"is this pack well built?"*. That is not the question you have before
sideloading, which is *"if I install this, does it show up?"* — and the two differ, because the
app checks **less** than the builder and rejects on a different set.

```sh
python3 tools/packbuilder/verify_pack.py --como-la-app ../wearos-dictionary-data/dist/*.db
```

It runs **exactly** what `PackFile.open` rejects on, in the same order, and prints the
`PackRejection` the user would read. Several packs at once, because the question is always "do
they *all* pass?". Exit 1 if any would be rejected.

⚠️ **This is the repo's fourth cross-language contract, and the only one born with an enforcer.**
The other three — `norm()`, `sense_code`, the catalogue index — earned theirs after drifting.
`audit_dictionary.py` → `check_rejection_mirror` compares the ids of `MOTIVOS_DE_LA_APP` against
the `PackRejection` enum **including the order**: a reason added in Kotlin and not here makes the
verifier say yes to a pack the app will reject.

⚠️ **The order is part of the contract.** Every check rejects (D-217), so the order does not
decide whether a pack gets in — it decides **which reason is reported**, which is the only line
the user reads. The seven schema-3 packs in the data directory came out as "incomplete metadata"
instead of "another format version" purely from having it backwards.

## Las listas de cobertura: lo que un pack TIENE que poder encontrar

`vectors/cobertura-es.txt` y `cobertura-en.txt`. `verify_pack.py` las corre **solo**, sin flag,
cuando el pack declara ese idioma, y falla nombrando lo que falta. Una palabra cuenta como
encontrada si es lema **o** una de sus formas flexionadas: `fui` llega a `ir`, que es lo que el
usuario experimenta.

⚠️ **Es el primer chequeo de CONTENIDO del repo, y por eso existe.** El gate compila, las
invariantes pasan y `--como-la-app` dice que sí sobre un `en-core` que **no tiene ningún mes del
año** — no hay nada estructuralmente roto que mirar. Lo encontró la lista el día que se escribió:
`dist/en-core.db` da **0 entradas** para `january`…`sunday`.

⚠️ **Agregar una palabra es documentar una decisión de producto**, no engordar un test: afirma que
el diccionario, sin ella, está roto. Por eso van agrupadas y cada grupo dice qué defiende.

⚠️ **Y lo que NO resuelve hay que decirlo**: una lista escrita a mano tiene el mismo sesgo que
mirar a mano — no sabe lo que nadie pensó en poner. Sirve contra **regresiones**, no contra huecos
desconocidos. Un pack de fixture (menos entradas que palabras en la lista) se salta el chequeo.

## Adding a source

It goes in `sources/`, and it hands back `Record` — the builder knows nothing about formats. What
the source has to solve:

- **Pruning.** This is where the pack's size is decided. Keep `word`, `pos`, glosses, forms,
  translations and **the citation of the example** (D-216); drop etymologies, pronunciations and
  categories.

  ⚠️ The citation used to be on the drop list, and measuring is what moved it: **86.5 %** of the
  English dump's examples are quotations lifted from a published text, so an example with no
  attribution reads as a definition that does not add up. It is trimmed to year and author in
  the source reader — **119 bytes become 31** — and the trimming separator is per-language, in
  `Perfil`: the Spanish dump writes its `ref` with periods and the author first, so the English
  rule would produce garbage there.
- **Streaming.** Never load the whole file.
- **Which edition and section it comes from.** The Spanish Wiktionary and the English Wiktionary are
  different datasets: the first gives Spanish glosses, the second English glosses about Spanish
  words.

## Regenerating the Unicode repertoire is a deliberate act

**Two generators, same rule.** `gen_casefold.py` emits the pinned case-folding table that
`payload.fold_gloss` and `PayloadCodec.foldGloss` both read.

⚠️ **It is a table and not a function call for a measured reason**: `toCaseFold()` — the operation
the standard defines for *caseless matching*, rule R4 of §3.13 — exists in Python as
`str.casefold()` and **does not exist in Java or Kotlin**. The only thing that offers it is ICU,
and D-003 forbids the platform's Unicode data because every Android ships its own version. So the
table is pinned, the way `UnicodeRepertoire` already is, and the sha256 ties the two copies.

It also **verifies its own assumption**: the Kotlin side indexes by `Char` to avoid JVM APIs that
D-017 bans in `:dict-core`, which only holds while nothing folds outside the BMP. The generator
aborts if that ever stops being true.

`gen_repertoire.py` aborts if the Python running it does not ship exactly Unicode 13.0.0. That is
not a bug: it pins the repertoire to the **floor** shared by the builder and the oldest Android we
support.

Raising the version requires checking that every platform supports the new floor, reviewing the diff
of `repertoire.txt`, and raising `NORM_VERSION`. Read the generator's header first.

## The mirror obligation

**There are now THREE mirrors, not one**, and the second and third are easy to forget because
neither looks like normalisation:

| Python | Kotlin | qué se rompe si divergen |
|---|---|---|
| `normalize.py` | `TextNormalizer.kt` | **falta una palabra** en los resultados |
| `payload.py` → `sense_code` / `fold_gloss` | `PayloadCodec.kt` → `senseCode` / `foldGloss` | **un enlace a una acepción lleva a otra**, o a ninguna |
| `packserver.py` → `META_FIELDS` y `catalog_entry` | `Catalog.kt` → `Catalog.parse` | **la pantalla de descarga dice «nada nuevo» para siempre**: un campo renombrado deja la lista vacía, sin excepción y sin log |

El tercero lo fija un **fixture del índice real** —`app/src/test/resources/catalog-index-fixture.json`—
que `CatalogTest` parsea y verifica campo por campo. Se regenera a mano, y eso es deliberado: que
sea un acto explícito es lo que hace que un renombrado se note. **Verificado por mutación**: leer
`db_sha` en vez de `db_sha256` tira cuatro tests.

```sh
python3 tools/packserver.py <dir> --index-only > app/src/test/resources/catalog-index-fixture.json
```

⚠️ **El fixture incluye a propósito un pack de esquema VIEJO** (`es-def-wd`, schema 3). Así el
test comprueba también que se clasifique como incompatible, que es lo que evita descargar 192 MB
para tirarlos.

⚠️ **Ya NO describe el directorio real, y eso es deliberado.** Decía *«porque el directorio de
datos real lo tiene»* y dejó de ser cierto con el rebuild del 2026-09-22: `dist/` tiene seis packs
y **ninguno de esquema 3** (comprobado el 2026-09-23). Regenerar el fixture desde el directorio de
hoy **debilitaría el test** —se quedaría sin el caso incompatible, que es la mitad de lo que
vigila— así que el fixture se queda como está y pasa a ser un caso sintético. Quien lo regenere
tiene que volver a meter a mano un pack de esquema viejo.

Los dos fallan igual: sin excepción, sin log, y con el pack pasando todas sus invariantes. El
segundo lo fija un vector idéntico en ambos lados — `sense_code(1, "casa")` = `8ec316909e48`.

⚠️ **Y trajo una lección general para el próximo espejo**: una clase de caracteres de una
expresión regular **no es portable**. `\s` en Python sobre `str` es **Unicode** y en Java es
**ASCII**, así que un espacio duro se plegaría de un lado y del otro no. En `fold_gloss` el
espacio se enumera a mano por eso. Ver `docs/contratos-cruzados.md` §6.

`normalize.py` is the hand-written mirror of `TextNormalizer.kt`. Any file with a mirror declares it
in its header:

```
ESTE ARCHIVO TIENE UN ESPEJO: <path>
```

`tools/audit_dictionary.py` checks that the declared path exists. That the **contents** match is
checked by the shared vectors, not by the audit.
