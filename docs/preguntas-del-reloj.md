# Questions only the watch can answer

**What this document is.** The standing list of open questions whose answer **is not on this
machine**, each with the readout that answers it and what it unblocks. It is not a task list: it
is what to take along the next time there is a watch on a wrist, so that session comes back with
**decisions** rather than impressions.

**Why it exists.** It is the second half of the method's principle 7. The first half —naming the
class of bug this repo cannot see and giving it a pre-ship check— is already here: the central
invariant, the shared vectors, `verify_pack.py`. The second is **observing after shipping**, and
the place this software runs is a wrist, watched by somebody who is not at a terminal.

> Without this document, a pass with the watch answers whatever occurs to whoever is wearing it.
> With it, it answers what was blocking something — and the answer goes into `docs/decisions.md`.

**How it is used.**

1. Before a pass: read it whole and pick the questions the time allows.
2. During: capture the readout **verbatim**, not the interpretation.
3. After: each answered question is struck through here with its date and its number, and if it
   settled something, its row goes to `docs/decisions.md`. A question answered and not struck
   through gets asked again.

**How a question gets in.** A claim a session **could not verify** says so in its changelog entry
**and adds its question here in the same change** (`CLAUDE.md` §Logging obligation). That is the
only route: arriving any other way, nobody knows which session left it open.

---

## What can already be asked without a finger

Since D-232 the app can be driven over `adb` on any build that is not `release`. **Almost every
question below is answered that way**, and that is new: until 2026-09-23 they depended on typing
into a field that does not take focus from a synthetic tap.

```sh
adb shell am broadcast -p cl.fadiaz.dictionary -a cl.fadiaz.dictionary.DEBUG_SEARCH -e q "hous"
adb shell am broadcast -p cl.fadiaz.dictionary -a cl.fadiaz.dictionary.DEBUG_CLEAR
adb shell am broadcast -p cl.fadiaz.dictionary -a cl.fadiaz.dictionary.DEBUG_DUMP
adb logcat -s Dict:V
adb shell setprop log.tag.Dict DEBUG      # the per-query detail
```

⚠️ **`-p` goes before the extra.** An empty extra does not survive `adb shell`: measured
2026-09-23, `-e q "" -p cl.fadiaz.dictionary` left the app searching for the literal `-p`. That is
why clearing has its own action.

---

## The open questions

| # | The question | The readout that answers it | What it unblocks |
|---|---|---|---|
| P-3 | Do both tiles **draw**, and with R8? | Add them to the carousel by hand —a user gesture, no `adb` for it— and `tile historial: pantalla=NNNdp filas=N` in `logcat` | **D-149** and **D-163**. The package manager resolves both `TileService`s, so R8 did not strip them; whether they **render** is what is missing |
| P-4 | How long does a cold start really take, and a query at p99? | `am start -W` over the **`benchmark`** build, and `buscar … en N ms` with `log.tag.Dict DEBUG` | **O-1**, and the budget that replaced D-207. ⚠️ **It also gates the pack-size levers**: `detail=none` was measured at **−38 MB on the English pack** and is being held until there is a real start-up number to protect. An emulator figure does **not** count (D-043) |
| P-5 | How much battery does a real burst of use cost? | `dumpsys batterystats` before and after, over `benchmark` | **O-4** and `docs/bateria.md`, which today holds no measured figure at all |
| P-11 | Does tapping a word in a gloss now land on the word you meant? | Open a card with several linked words, tap deliberately between two of them, and compare the entry that opens against the one aimed at | **D-243**, built against the recommendation. The bound that matters -- a tap inside a line cannot jump lines -- is pinned by tests, but *whether the snap feels right* only shows on a wrist |
| P-12 | Do the principal parts read well beside the headword at 234 dp? | Open `correr` in a rebuilt pack: `corriendo (gerundio) · corrido (participio)` should sit above the translations in a sense's body weight | **D-242**. The channel and the toy fixture are verified; the layout at watch width is not, and it competes for the rows the definition needs |
| P-13 | Settings grew by **three rows** (D-266) and it was already the app's longest list. Is the search switch reachable without a scroll that outlasts patience, and does anybody find it? | Open Settings and scroll to the bottom without hunting; note where *Buscar también en el otro idioma* falls and whether the About block is still reachable | **D-266**. The switch is off by default and nothing points at it, so if it cannot be found it is a setting that exists only in the code. The row was placed above the text size for exactly this reason and that placement is a guess until somebody scrolls |

---

## Answered

*Struck through with their date and their number; never deleted, because a deleted question comes
back next quarter with no memory of what closed it.*

| # | The question | Answered | With what |
|---|---|---|---|
| P-9 | How long does installing a 111 MB APK over wireless adb actually take? | **2026-09-23**, SM-L715F over wireless debugging | **2 min 17 s**, `Success`. And the first launch after it cost **3,904 ms to be ready to search** — the one-off price of extracting the two cores (94 MB) out of the APK, with the packs opening in 927 ms and 339 ms. ⚠️ **Both are watch numbers and neither is a steady-state one**: the install is paid once per build and the 3.9 s once per `versionCode`. A launch with the cores already on disk was not measured, because the session ended before it |
| P-1 | Does the language fallback return anything when the active one has nothing? | **2026-09-23**, by the owner, watch in hand | *«El pack español si no encuentra nada en el core busca en el otro pack. Ahora hay dos botones, uno de buscar en definiciones y otro de buscar en el otro idioma.»* ⚠️ **The answer reframes the question**: the automatic fallback is **off** by default since D-189 (`LanguageScope.STRICT`), so what the owner is describing are the two **manual** escape hatches. D-168 is therefore not what needs verifying — improving those two buttons is, and that is now a roadmap item rather than a watch question |
| P-2 | Are a gloss's synonyms tappable, and do they open the right entry? | **2026-09-23**, emulator `wear_sm_l715f` | Tapping `lengua` on the `idioma` card opened the `lengua` entry. ⚠️ **An earlier claim in this same session that "nothing is linked" was wrong** and came from reading a screenshot instead of tapping. The tap beats the screenshot |
| P-6 | Do the 46 instrumented tests pass on **hardware**? | **2026-09-23**, withdrawn by the owner | *«Todo hasta ahora ha funcionado bien en el reloj, así que no necesito las pruebas porque el debug del reloj es inestable para sesiones largas.»* ⚠️ **This is a withdrawal, not an answer**, and the distinction is kept: the assumptions about the device's ICU and SQLite remain unverified on hardware. What replaces it is P-4's short, targeted run, which is what a fifteen-minute session can actually finish |
| P-7 | Does `extractIfNewer` replace the core when the APK carries a newer one? | **2026-09-23**, emulator | Built a synthetic core at `202701010000`, served it over `adb reverse`, downloaded it, then installed an APK carrying `202801010000`. Log: `es-core.db: el APK trae uno mas nuevo (202801010000 > 202701010000), reemplazado`, and the catalog mark was forgotten as D-229 requires |
| P-8 | Does installing a new version expire the memo over more than two packs? | **2026-09-23**, emulator, three packs | All three fingerprints moved `.a6` → `.a7`. ⚠️ **`es-full`'s mtime did not change** (`…941484` both runs), so the re-verification came from the fingerprint rule and not from the file looking different — which the two cores alone could not have shown, since re-extraction changes their mtime anyway. The 450 MB first-launch **cost** is still unmeasured; that is P-4's business |
| P-10 | Does a word of the day from a **core pack** read like something worth learning? | **2026-09-23**, emulator | `polvo` (Español core) and `anywhere` (English core), against the simulation's `acción`, `anillo`, `Christmas`, `afternoon`. Zero function words. The row now reads `verbo · ES` (D-253) |
| — | Does a core pack from the APK return results through the user's own path? | **2026-09-23**, emulator `wear_sm_l715f` | `DEBUG_SEARCH hous` → `house` first on screen. Closed the doubt that had forced an answer by reading the `.db` with `sqlite3` |
| — | Is the screen 192 dp or 234? | **2026-09-19**, SM-L715F | `sw234dp w234dp h234dp 340dpi`. Moved five layout decisions |
