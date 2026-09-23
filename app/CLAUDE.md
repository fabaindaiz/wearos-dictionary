# app

The Wear OS app, **running on a physical watch** against the real pack of 114,619 entries.

The screens live in `presentation/`: search (which **is also the home**), entry, attribution,
settings, dictionary management and saved words. `data/PackStore.kt` is the only thing that knows
where a pack comes from.

The glanceable surface is **two tiles** —recent words and word of the day— and **neither opens a
pack**: both read `SharedPreferences` (D-106). The template's complication was switched off.

The rules here are preventive: they are expensive to discover late.

## The test comes first, and here that has a technical precondition

`:app` was born with no tests and retrofitting them cost a refactor. **The cause was not
laziness**: `SearchViewModel` extended `AndroidViewModel` and built the pack from a `Context`, so
there was no way to run it on the JVM. A test that needs a device does not enter the gate, and one
that does not enter the gate does not get run.

Hence the rule, which is D-072 and is enforced by the audit: **`:app`'s logic does not import
`android.*`**. Whatever needs Android arrives as a parameter —`SearchViewModel` receives
`openPacks`, not a `Context`— and the boundary is `PackLoad`, a type without Android that a fake
passes through.

The **screens are outside the rule** as code —a Composable is Android by definition— but **no
longer as tests**: they run under Robolectric on the JVM and **do enter the gate** (D-110). Of the
**89**, **88 run that way**; the only one that does not is tapping a word inside a gloss, which
depends on the real text layout.

⚠️ **Robolectric runs on SDK 36, not 37**, which is the watch's level: that is as far as it goes
(`app/src/test/resources/robolectric.properties`). Anything that depends on API 37 still needs a
device — and this project already had one case, the input injection that forced pinning espresso
3.7.0 (D-093).

Those tests do not build a `DictionarySource`: the screens are functions of the state, so the
state is built by hand. If a screen ever needs a fake, that is a sign logic crept into it that
belongs in the ViewModel.

## The budget was 192 dp — and the watch is 234

The 192 dp figure comes from a 384×384 px screen at 320 dpi, and the Wear OS guidance asks for a
48 dp minimum touch area. That gave **three rows and nothing more**, measured, and it is where
D-073 (one-line list, 48 dp) and D-075 (the text input collapses when there are results) come
from.

✅ **The project's watch is 234 dp, confirmed on device (2026-09-19).** `wm size` gives 498×498 px
and `wm density` gives 340, and —this is the part that was missing— **the system reports the
configuration the app actually receives as `sw234dp w234dp h234dp 340dpi`**, with the Activity's
bounds at the full 498×498, so no reduced window is in play. That is the number
`LocalConfiguration.screenWidthDp` returns.

**It is 22 % more screen than the currency five decisions were priced in** (D-073, D-075, D-078,
D-084, D-085). At 48 dp of touch area that is room for a **fourth row**, which is 33 % more
results. None of those five has been revisited yet: the measurement is in, the redesign is not.
`ScreensTest.threeResultsFitWithoutScrolling` still measures against whatever device runs it.

If somebody drops below 48 dp to squeeze in a fourth row, the density test **still passes** and
what breaks is the touch area. That is why the minimum lives in a named constant.

⚠️ **The density test used to pass for the wrong reason.** `threeResultsFitWithoutScrolling` ran
under Robolectric's **default device, which is not a watch**, so all four suggestions composed and
the assertion was vacuous. It is now two tests pinned to real sizes with `@Config(qualifiers)`:
**192 dp composes 2 rows and 234 dp composes 3**. The absolute numbers are lower than the 384×384
emulator's because `h192dp` is *available* height and discounts decor — so do not compare them to
on-device counts. **What the pair fixes is the relationship**: 22 % more screen buys exactly one
more row, and a change that helps the project's watch while hurting a generic one now fails.

⚠️ **Functional probes go on the EMULATOR, not on the watch.** Searching words, result order,
opening entries, navigation: all of it on `tools/avd_como_el_reloj.py`, which has the watch's
geometry (D-150). **The watch is for debug data and short experiments only** — performance,
battery, Perfetto, `dumpsys`. That is not a preference: it is measured cost. The watch dropped
mid-run on 2026-09-21, `connectedAndroidTest` had already uninstalled the app, and **the five
packs went with it** — ~450 MB to push again. The watch's IME also reorders the keystrokes of
`adb shell input text` and discards them on BACK, so every on-screen probe costs several tries.

⚠️ **And a `connectedAndroidTest` whose device disappears mid-run reports `BUILD SUCCESSFUL` with
ZERO tests.** An empty green. Read the **count**, never the colour.

```sh
./gradlew :app:testDebugUnitTest         # 420 JVM tests, screens included
./gradlew :app:connectedDebugAndroidTest # 7 tests that really do need a device -- UNINSTALLS the app
./gradlew :app:releasePrecheck           # is there a keystore to sign with? says what is missing
./gradlew :app:assembleRelease           # 35 MB; with no keystore it comes out UNSIGNED, it does not break
```

What they cover is what **gives no error**: results from an old query overwriting the current one,
one query per keystroke draining the battery, the search left dead while the pack loads, and a
half-copied pack —which opens without complaining and returns fewer words than it holds.

## The home is the search, and that is deliberate

There is no menu screen. The Wear OS guidance asks for hierarchies of **at most two levels** and
for the primary action to be elevated; a home that routes to the search sinks it by one tap. So
`SearchScreen`'s empty state **is** the home: search bar, voice, word of the day, history, saved
words and settings (D-096, reordered by D-111). Settings is the only second level, and dictionary
management hangs off it.

Three rules that came from looking at it on screen, not from reasoning about it:

- **An item that arrives asynchronously is not inserted at the very top.** The word of the day
  takes 32 reads; by the time it arrives the list has settled, and since the items have `key` it
  keeps its position — inserted at index 0 it appeared **off screen**. It goes under its heading.
- **Every `item` carries a `key`.** Without a stable identity, an item that changes position is
  destroyed and recomposed, and that took the text field's focus with it, and the keyboard behind
  it (D-089).
- **Two buttons go in a `Row`, not stacked**: side by side they cost 48 dp, stacked 96 (D-100).

## Deleting a dictionary: the order is the contract

**All** connections are closed before the disk is touched, and only then is the set reloaded
(D-104). It is not a theoretical precaution: on Unix a deleted file with an open descriptor keeps
occupying the disk, so the user would see *"deleted"* and zero space freed. **Measured**: free
9,802,568 kB → with the 72.2 MB pack, 9,732,048 kB → after deleting, 9,802,568 kB again. (Measured
with the pack from before D-116; today it is 68.2 MB and the conclusion does not change.)

The demo pack **cannot be deleted**: it ships in the APK and `PackStore.open` re-extracts it on
reopening, so the button would do nothing.

## A tile does not open a pack, and that is not a performance opinion

`onTileRequest` is annotated **`@MainThread`** and *"must complete after at most 10 seconds"*. It
is in the javadoc of `tiles 1.6.2`, so opening a 69 or 295 MB `.db` there is ruled out **in
writing**, with nothing to measure. `check_tiles_dont_open_packs` enforces it.

What a tile needs **the app leaves written** in `SharedPreferences`: the history already stores
`headword` and `pos` denormalised, and the word of the day is computed a week ahead because it is
deterministic by (date, pack) (D-097). Both tiles are adapters over `TileContent.kt`, which is
pure and tested in the gate.

**The freshness interval is not wall clock.** Verbatim: *"elapsed time (not wall clock time)"*,
and *"inexact"*. To make the word change at midnight a `Timeline` with `TimeInterval` windows is
used, which are epoch-based (D-107). And `freshnessIntervalMillis = 0` means the system does
**not** call the tile again: the history one depends on the app pushing it with `requestUpdate`.

**Do not declare `androidx.wear.tiles.GROUP`** on either of them: *"tile providers in the same
group represent the same tile on the device"*, and you would fuse them into one.

⚠️ **The tile classes are named in `AndroidManifest.xml`.** Renaming one without touching the
manifest breaks both tiles on the watch with **no compile error and no test** — it already
happened once.

## Tiles and widgets accept no text input

The search lives **necessarily inside the app**. The glanceable surface is for word of the day,
recent searches or a shortcut into the search — not for searching.

## `androidx.glance:glance-wear-tiles` is forbidden

Deprecated and will be removed. The naming confuses: it is **not** the Wear Widgets library. If
you search for how to build a Tile with Glance, that is the result you will find and it is the
wrong one.

What is used today: `androidx.wear.tiles` + `androidx.wear.protolayout`.

## Wear Widgets is postponed, not discarded

Wear OS 7 brings Wear Widgets (Glance + RemoteCompose) as the evolution of full-screen Tiles. They
are not used yet because `androidx.glance.wear:*` and `androidx.compose.remote:*` are in alpha with
packages moving between releases, and only exist on Wear OS 7.

The future migration is adapting the tile's `mainSlot` into a 2x2 widget, designed to be direct.

## Input

Voice through `RecognizerIntent` as the primary path, keyboard as the fallback. That is not a
product preference: it is the reason the search has an error-tolerant level — voice dictation
produces input that matches no headword exactly.

Implemented that way in `SearchScreen`. The keyboard is compose foundation's `BasicTextField` and
not a Wear Compose component **because Wear Compose ships no text field**: the library assumes
input arrives by voice or through the system activity. It is also the keyboard that exercises the
incremental search — `SearchViewModel`'s 120 ms `debounce` and `mapLatest` do nothing for voice,
which delivers the whole phrase at once.

The `RecognizerIntent` asks for `EXTRA_LANGUAGE = "es"` explicitly. Without it the recogniser uses
the system language, and a watch in English dictating "perro" returns anything.

## The UI text is Spanish, and which Spanish is decided

Everything written into the repo is English (root `CLAUDE.md`), **except the strings the user
reads**. Those are Spanish, and specifically **neutral Latin American Spanish**:

- **No voseo.** `Busca`, not `Buscá`. `Abre`, not `Abrí`. `usa`, not `usá`.
- **No River Plate regionalisms.** `Aquí`, not `Acá`.
- **No peninsular forms either.** `computadora`, not `ordenador`; `celular`, not `móvil`;
  `descargar`, not `bajar`; and never `vosotros`, `pulsar` or `fichero`.

It is written down because the first pass was written in River Plate Spanish and nothing catches
it: it compiles, it reads fine to whoever wrote it, and it is the user who notices. The whole
surface is small —every string lives in `presentation/` or in `res/values/strings.xml`— so a
sweep for those forms takes a minute.

**The localization landed** (D-127, D-140, D-153): the 117 keys live in `values/` (English base)
and `values-es/`, and the rule above governs the Spanish side. Since D-158 the language is also
**pickable in Settings** — Automatic / English / Español.

⚠️ **The picker does NOT store a preference.** Since API 33 `LocaleManager` keeps the app locale
per application and applies it before a single Composable runs, so a copy of ours would be a second
source of truth that the platform's would silently outrank. `SettingsScreen` receives the tag as a
parameter and `MainActivity` makes the call — same boundary as everything else Android here.

Two things that bite, and both are enforced:

- **The tag comes back resolved**, with a region: `es-CL`, `es-419`, `en-US`. Compare only the
  primary subtag or the list shows nothing selected, which reads as the choice being forgotten.
- **Adding a third language is two things**: a `values-xx/` folder with all 117 keys and a row in
  `UiLanguage`. Miss either and `check_ui_language_picker` says which. A folder without a row is a
  translation **nobody can pick**; a row without a folder leaves the app in English with the
  picker marking something else.

And the endonyms —`Español`, `English`— are **the one UI string that is not a resource**, on
purpose: a language picker written in the language you cannot read fails exactly the person it is
for.

## Accepted MVP costs (D-087)

They are no longer inherited from the template: they were decided, with the cost on the table.

- **R8 is disabled.** The official Wear OS guide names it as one of the two main levers, but
  enabling it reintroduces the class of bug that only shows up in release and is tied to an
  on-device check that has not been done yet. Roadmap O-2.
- ~~The template's Tile and Complication~~ **Closed on 2026-09-19** (D-106 to D-109): there are two
  dictionary tiles and the complication was switched off, and with it the 24 daily wakeups.
- ~~`ic_launcher_round` present but without `android:roundIcon`~~ **Closed by D-115**: the icon is
  an open book in vector form, `roundIcon` is declared and the 10 `.webp` files are gone.
- ~~`app_name` = "Dictionary" in English with the UI in Spanish~~ **Closed**: it is a resource in
  both locales. Minor leftover untouched: the `WAKE_LOCK` permission declared and never used.

## Signing the release

The keystore **lives outside the repo** and `local.properties` stores only its path (D-086). With
no keystore configured the release comes out **unsigned instead of breaking**, because a clean
clone has to keep compiling.

```sh
./gradlew :app:releasePrecheck   # says what is missing and the keytool to generate it
```

## Reading the app over adb

**The app writes to one tag, `Dict`, and the detail is off until you ask for it.**

```sh
adb logcat -s Dict:V                      # what it says by default: INFO and up
adb shell setprop log.tag.Dict DEBUG      # the per-query detail, no rebuild, no reinstall
adb shell setprop log.tag.Dict INFO       # and off again
```

⚠️ **It is `Log.isLoggable` and deliberately NOT `BuildConfig.DEBUG`.** R8 strips
BuildConfig-guarded calls in `release`, and `benchmark` inherits from `release` — so the build used
to measure startup and battery would be the one build with no logs, which is where they are needed
most. `setprop` works identically in all three.

**Messages are lambdas, and that is the contract**: `DictLog.d { "x=$x" }` builds nothing when
nobody listens. `DictLog.d("x=" + x)` would build the string on every keystroke in production.
`DictLogTest` asserts the lambda is not evaluated, and it is verified by mutation — removing the
guard fails it.

### Changing the app's internal knobs from `adb`, without rebuilding

```sh
adb shell am broadcast -p cl.fadiaz.dictionary -a cl.fadiaz.dictionary.DEBUG_SET \
    -e catalog http://localhost:8799 -e scale LARGE
adb shell am broadcast -p cl.fadiaz.dictionary -a cl.fadiaz.dictionary.DEBUG_SET   # clears all
```

The registry is `DebugKnobs`; the door is `DebugIntents`. **Extras are read by name**, so several
travel in one broadcast and a typo names itself (`NO existe el ajuste 'catalogue'. Hay: catalog,
scale`) instead of doing nothing. A bad value and an unknown key both change **nothing**: a
half-applied broadcast leaves the next probe running against a state nobody described.

⚠️ **An override does not survive a `versionCode` change, in either direction.** It is
scaffolding tied to one build: a catalogue url left pointing at a laptop that stopped serving
turns the next session's *"downloads are broken"* into an hour spent on the wrong thing. The
store carries the `versionCode` that wrote it and the expiry happens **on read**, so there is no
window where a stale override is live. Equality and not `>=`, because sideloading moves the
number both ways. Same rule as the pack memo (D-225) and the asset re-extraction (D-176).

⚠️ **The USER's settings are NOT overrides and must not expire.** `Settings.textScale` is a
choice somebody made about their own watch.

⚠️ **`sensitive` is about the Settings SCREEN, not about `adb`.** The catalogue url is marked
sensitive because the index comes from that server and every pack's sha256 comes from that index:
whoever answers the url decides what the app installs, and verifying the hash proves the download
matches what the server said, never that the server is the right one. A test asserts the flag.

### What is worth reading, and why it is that and not something else

Each line exists because of a failure this repo actually had.

| Level | Line | The failure it makes visible |
|---|---|---|
| INFO | `pack <id> schema=4 norm=2 langs=es+en tier=full entries=209484 dataVersion=… N MB en M ms` | *A word is missing.* `schema_version` and `norm_version` are the two that reject a pack whole (D-001, D-006), and there was no way to ask over adb |
| WARN | `pack RECHAZADO <file>: <reason>` | A rejected pack only ever reached a UI string. On the watch, "no pack was rejected" had to be inferred from the absence of a crash |
| WARN | `pack <id> FALLO al consultarlo` | `SearchRepository.recolectar` swallows it on purpose so one corrupt pack cannot take the search down — and its comment claimed such a pack "is visible". It is, on screen. In logcat it looked like an empty one |
| ERROR | `no se pudo extraer <asset> del APK` | `runCatching` swallowed it, so a core that never extracts looked like a language the user did not install |
| INFO | `arranque: listo para buscar en N ms` | `am start -W` stops at the first frame; the packs open afterwards on IO. This is the part the user waits for |
| INFO | `tile historial: pantalla=NNNdp filas=N contenido=… en M ms` | `onTileRequest` is `@MainThread` with ten seconds (D-106) and *nobody opens a tile on purpose*. The screen width is also the number the 225 dp breakpoint item needs |
| INFO | `tile palabra del dia: since=… palabras=N ventanas=N` | Zero windows means the empty state, which is how the word of the day has broken **twice** — translation packs, then the cores |
| DEBUG | `buscar 'cas' -> 7 (prefix=2 translation=4 fuzzy=1) RESPALDO` | `measure_query_cost.py` replicates the cascade on the desktop and **nothing observed it on the device**. This is what would have caught the ordering bugs |

**Not logged, on purpose**: anything per row, anything in a hot loop, and no counters of things
nobody has asked a question about. The rungs are reported in the order of the `MatchKind` enum —
the cascade's own order — so two lines can be compared; a test pins that.

### `:dict-core` cannot log, and that shapes the design

`ArchitectureTest` forbids `import java.*` and `System.` in that module (D-017), so neither
`android.util.Log` nor a timestamp can go there. The cascade therefore **reports** through
`SearchTrace` — pure Kotlin, counts only, no timing, `SearchTrace.None` by default — and `:app`
translates it in `LogSearchTrace`. **Timing is always the caller's job.**

`SearchTrace.enabled` exists so the report is not even assembled when nobody listens: grouping the
results costs a pass over the list. The contract is *whoever reports asks first*, and a test fails
if the guard is removed.

⚠️ **One consequence to know about**: instrumenting the ViewModel broke ten `SearchViewModelTest`
cases, because the stub `android.jar` of a plain JVM test throws on `Log.isLoggable`. The fix is
`isReturnDefaultValues = true` in `testOptions`, and its price is written where it is set — every
unmocked Android method now fails quietly instead of saying so.

## Performance

Many watches have considerably more limited CPU and GPU than a phone. Minimise animations, and if
there is a loop leave a pause at least as long as the animation.

Measure on a **physical watch**, never on the emulator: the emulator is for correctness, not for
performance. See the `benchmark` skill.

## Downloads

They are deferred to **charging and on Wi-Fi**, with WorkManager. It is the official Wear OS
guidance, and with packs of tens of MB it is not optional.

Exclude the packs from backup with `android:dataExtractionRules`. With `minSdk 33`,
`fullBackupContent` **does not apply**: it is the mechanism for Android 11 and below. Done:
`res/xml/data_extraction_rules.xml` excludes `packs/` from cloud-backup and from device-transfer.

## The packs do not travel in the APK

The APK carries only a 53 KB **demo pack** (D-081). The real dictionaries live in
`filesDir/packs/` and arrive through `tools/devpack.py`; once the installer exists it will write
into that same directory and the app will not notice the difference.

```sh
python3 tools/packbuilder/build_pack.py es <kaikki-es.jsonl> es-def-wikc.db
# El bilingue NECESITA --flexiones, o la direccion inversa se pierde en silencio (D-184):
python3 tools/packbuilder/build_pack.py es-en <es-en-wikt.jsonl> es-tr-enwikt.db \
        --flexiones en-def-wikt.db
python3 tools/devpack.py install es-def-wikc.db   # or: hatch run push es-def-wikc.db
python3 tools/devpack.py list                     # what ended up on the watch
python3 tools/devpack.py rm es-def-wikc           # to test the degradation
```

**Do not do it with `adb push` by hand.** Copying straight onto the `.db` is not atomic: if the
push is cut short a truncated pack is left, and a truncated pack **opens without error and returns
fewer words than it holds**. `devpack.py` writes to `.part`, compares the sha256 on both sides and
only then renames — the same thing `installAtomically` does for the APK's packs (D-082).

It also does a `force-stop` first and relaunches afterwards, because **the app has no rescan**:
the scan is one-shot in the ViewModel's `init` and a copied pack does not appear until the process
restarts.

With no packs the app starts and says *"No hay ningún diccionario instalado."*, which is the right
degradation.

**This closed D-071**, which was a conscious deviation: the pack travelled as an asset and was
extracted on first launch, duplicating it on disk. The decision said it would be reverted once the
installer existed; what brought it forward was measuring English — **295.1 MiB on disk, 184.7 MiB
compressed**, which with both languages left the APK at ~270 MB. Without packs it weighs 50 MB.

## Two packs, one active language

One is chosen and searched; **results are not merged** — that is composition and needs
`SearchRepository`, which does not exist (D-078).

Three things that are not preference but defences against bugs that already existed:

- **The active pack is decided by the watch locale, never by alphabetical order** (D-079). Before,
  the first alphabetical `.db` was opened, so installing English would have hidden Spanish in
  silence: `en-` sorts before `es-`.
- **Navigation carries `packId`** as well as `entryId` (D-080). Without it, tapping an English
  result resolved it against the active pack and showed another word.
- ~~**Voice comes from the active pack's `metadata.langSource`.**~~ **Superseded twice.** First
  the input moved to `ACTION_REMOTE_INPUT`, which uses the **watch's** language and accepts no
  `EXTRA_LANGUAGE`; then `langSource` itself disappeared — a pack declares `meta.langs` as peers
  (D-195), so "the pack's language" stopped being a single value. What tells the user which
  language they are searching is the chip, which is now the thing they pick (D-197).

The selector lives **inside the Options section of the home** and costs a row of its own (D-111).
That changed what D-078 said —that it cost zero rows, because it replaced the title— in exchange
for it no longer competing with the search bar for the top spot. With a query and no results it
also appears as *Buscar en \<idioma\>*, which is exactly when it helps.
