package cl.fadiaz.dictionary.presentation

import android.app.LocaleManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.wear.tiles.TileService
import cl.fadiaz.dictionary.tile.EXTRA_ENTRY_ID
import cl.fadiaz.dictionary.tile.EXTRA_HEADWORD
import cl.fadiaz.dictionary.tile.EXTRA_OPEN_INPUT
import cl.fadiaz.dictionary.tile.EXTRA_PACK_ID
import cl.fadiaz.dictionary.tile.HistoryTileService
import cl.fadiaz.dictionary.tile.WordOfTheDayTileService
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cl.fadiaz.dictionary.core.TextNormalizer
import cl.fadiaz.dictionary.core.Entry
import cl.fadiaz.dictionary.BuildConfig
import cl.fadiaz.dictionary.R
import cl.fadiaz.dictionary.data.PackHandle
import androidx.work.WorkManager
import cl.fadiaz.dictionary.data.DebugIntents
import cl.fadiaz.dictionary.data.DictLog
import cl.fadiaz.dictionary.data.DownloadPackWorker
import kotlinx.coroutines.flow.map
import cl.fadiaz.dictionary.data.CatalogClient
import cl.fadiaz.dictionary.data.PackStore
import cl.fadiaz.dictionary.data.Visit
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import cl.fadiaz.dictionary.presentation.theme.DictionaryTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DictionaryApp(requestedEntry(intent), pideInput(intent)) }
    }

    /**
     * The entry a tile asked for, if it came from there and if the request makes sense.
     *
     * **This is untrusted input.** `MainActivity` is exported --it has LAUNCHER-- so any app on
     * the watch can launch it with whatever extras it likes. That is why they are validated here
     * and the route is built locally: a `packId` that does not exist never gets to open another
     * word, it is ignored and the app starts on the search, which is its normal state.
     */
    private fun requestedEntry(intent: Intent?): Visit? {
        val packId = intent?.getStringExtra(EXTRA_PACK_ID)?.takeIf { it.isNotBlank() } ?: return null
        val entryId = intent.getLongExtra(EXTRA_ENTRY_ID, 0L)
        if (entryId <= 0L) return null
        // The headword travels so the id can be FIXED, not to be displayed: the tile publishes
        // an `entryId` from `SharedPreferences` that a pack rebuild leaves pointing at another
        // word (D-055). If it does not arrive --an intent from another app, or an old tile-- we
        // fall back to trusting the id, which is what used to happen.
        val headword = intent.getStringExtra(EXTRA_HEADWORD).orEmpty()
        return Visit(packId = packId, entryId = entryId, headword = headword, partOfSpeech = null)
    }

    /**
     * Did the tile ask to open the system input?
     *
     * It is read **once, from the launching intent**. A tile accepts no text (D-026) but it does
     * fire an intent, and the `bottomSlot` is where the Wear OS guidance puts the action:
     * searching from the carousel goes from three taps to one.
     */
    private fun pideInput(intent: Intent?): Boolean =
        intent?.getStringExtra(EXTRA_OPEN_INPUT) != null
}

private const val ROUTE_SEARCH = "busqueda"
private const val ROUTE_ENTRY = "entrada"
private const val ROUTE_ATTRIBUTION = "atribucion"
private const val ROUTE_SETTINGS = "ajustes"
private const val ROUTE_FAVORITES = "favoritos"
private const val ROUTE_HISTORY = "historial"
private const val ROUTE_PACKS = "packs"

/**
 * Asks both tiles to redraw themselves.
 *
 * It is the only mechanism they have: the history one is published with
 * `freshnessIntervalMillis = 0`, which per the javadoc means the system will **not** refresh it
 * on its own.
 */
private fun notifyTiles(context: Context) {
    val updater = TileService.getUpdater(context)
    updater.requestUpdate(HistoryTileService::class.java)
    updater.requestUpdate(WordOfTheDayTileService::class.java)
}


/**
 * The catalogue url this build is pointing at right now.
 *
 * One function because it is read from three places --the index, a download, and the dump-- and
 * three copies of the same `if` is how one of them keeps pointing at the old server.
 */
private fun catalogoEnUso(context: android.content.Context): String =
    if (BuildConfig.DEBUG_INTENTS) DebugIntents.catalogUrl(context) else BuildConfig.CATALOG_URL

@Composable
fun DictionaryApp(entradaInicial: Visit? = null, abrirInput: Boolean = false) {
    DictionaryTheme {
        AppScaffold {
            val navController = rememberSwipeDismissableNavController()
            // The ViewModel is handed how to open the pack instead of building it: that was the
            // only thing it needed from Android, and taking it out leaves it testable on the JVM
            // (SearchViewModelTest). The applicationContext and not the Activity's: the pack
            // survives a rotation.
            val context = LocalContext.current.applicationContext
            val viewModel: SearchViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        SearchViewModel(
                            openPacks = { onExtracting ->
                                PackStore.open(context, PackStore.preferredPack(context), onExtracting)
                            },
                            // With no stored preference the watch locale decides, not the alphabet.
                            preferred = {
                                PackStore.preferredPack(context) ?: Locale.getDefault().language
                            },
                            saveActiveLanguage = { lang -> PackStore.rememberLanguage(context, lang) },
                            savedHistory = { PackStore.history(context) },
                            saveHistory = { PackStore.rememberHistory(context, it) },
                            saveTileHistory = { PackStore.rememberTileHistory(context, it) },
                            // The date comes in through here instead of a clock inside the
                            // ViewModel: that is what lets the word of the day be tested on the
                            // JVM (D-072).
                            todayDate = { LocalDate.now().toString() },
                            savedSettings = { PackStore.settings(context) },
                            saveSettings = { PackStore.rememberSettings(context, it) },
                            savedFavorites = { PackStore.favorites(context) },
                            saveFavorites = { PackStore.rememberFavorites(context, it) },
                            deleteFromDisk = { fileName -> PackStore.deletePack(context, fileName) },
                            savedWeekWords = {
                                PackStore.weekWords(context)
                            },
                            saveWeekWords = { since, words ->
                                PackStore.rememberWeekWords(context, since, words)
                            },
                            // The tiles have no scheduled refresh: if the app does not push
                            // them, they keep whatever they had.
                            notifyTiles = { notifyTiles(context) },
                            // The url comes from BuildConfig, set with -PcatalogUrl=..., and a
                            // debug build can point somewhere else over `adb` without being
                            // rebuilt (DEBUG_CATALOG). Only `debug` may speak over http://
                            // (src/debug/AndroidManifest.xml).
                            //
                            // ⚠️ **Read at each use and not once**, so an override takes effect
                            // on the next press instead of on the next launch -- the whole point
                            // is not restarting anything.
                            //
                            // ⚠️ **The `if` is what keeps release clean**: `DEBUG_INTENTS` is a
                            // constant `false` there, so R8 folds this to the BuildConfig value
                            // and `DebugIntents` leaves the dex entirely.
                            fetchCatalog = { etag ->
                                CatalogClient.fetchIndex(catalogoEnUso(context), etag)
                            },
                            startDownload = { pack ->
                                DownloadPackWorker.enqueue(context, catalogoEnUso(context), pack)
                            },
                            // The `.gz.part` lives next to the packs: cancelling has to be able to
                            // delete it, or something the user already stopped keeps taking disk.
                            cancelDownload = { pack ->
                                DownloadPackWorker.cancel(
                                    context,
                                    pack.packId,
                                    PackStore.packsDir(context),
                                    pack.url,
                                )
                            },
                            // What WorkManager reports, translated. A Flow and not a one-off read
                            // because the state changes BY ITSELF --on plugging the charger in,
                            // for instance-- and the screen has to find out without anybody asking.
                            downloadStates = WorkManager.getInstance(context)
                                .getWorkInfosByTagFlow(DownloadPackWorker.TAG)
                                .map { infos ->
                                    infos.mapNotNull {
                                        DownloadPackWorker.toPackDownload(it.tags, it.state, it.progress)
                                    }
                                },
                        )
                    }
                },
            )
            val state by viewModel.state.collectAsStateWithLifecycle()

            // The debug intents. `install` returns null in release --the constant is `false` and
            // R8 takes the class away-- so there is nothing to ask here. See [DebugIntents].
            DisposableEffect(Unit) {
                val baja = DebugIntents.install(
                    context,
                    // It comes in through the SAME door as the keyboard: through another one, what
                    // gets verified from adb would not be what the user does.
                    onSearch = { texto ->
                        navController.popBackStack(ROUTE_SEARCH, inclusive = false)
                        viewModel.onQueryChange(texto)
                    },
                    onDump = {
                        val estado = viewModel.state.value
                        DebugIntents.dump(
                            // `loaded` and NOT `available`: the latter is what the selector
                            // offers, and it hides a bundled core whose language a full pack
                            // already covers. Reading it here made the dump report `abiertos=2`
                            // on a launch whose startup line said 3.
                            opened = estado.loaded
                                .map { "${it.packId}@${it.metadata.dataVersion}" },
                            active = estado.active?.packId,
                            rejected = estado.rejected.map { "${it.fileName}:${it.rejection.id}" },
                            memo = PackStore.verificationMemo(context),
                            appVersion = runCatching {
                                context.packageManager
                                    .getPackageInfo(context.packageName, 0)
                                    .longVersionCode.toInt()
                            }.getOrDefault(0),
                            buildId = "${BuildConfig.BUILD_COMMIT} ${BuildConfig.BUILD_TIME}",
                            catalog = catalogoEnUso(context),
                            overrides = if (BuildConfig.DEBUG_INTENTS) {
                                DebugIntents.activeOverrides(context)
                            } else {
                                emptyMap()
                            },
                        ).forEach { linea -> DictLog.i { linea } }
                    },
                )
                onDispose { baja?.invoke() }
            }

            // ⚠️ **Leaving the app returns to a clean home** (see `onLeftApp`). A watch is not
            // closed, the wrist is lowered: coming back three hours later to some earlier card is
            // not resuming anything.
            //
            // ⚠️ **It hooks `ON_STOP` and NOT `ON_PAUSE`**, and the difference matters: the system
            // input --`ACTION_REMOTE_INPUT`, a full-screen SysUI Activity-- pauses ours, and
            // resetting there would erase the word right while it is being dictated. `ON_STOP`
            // arrives in that case too, so the last time we launched the input is also recorded
            // and the first stop after it is ignored.
            val owner = LocalLifecycleOwner.current
            DisposableEffect(owner, navController) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event != Lifecycle.Event.ON_STOP) return@LifecycleEventObserver
                    if (viewModel.consumeSystemInputPause()) return@LifecycleEventObserver
                    viewModel.onLeftApp()
                    navController.popBackStack(ROUTE_SEARCH, inclusive = false)
                }
                owner.lifecycle.addObserver(observer)
                onDispose { owner.lifecycle.removeObserver(observer) }
            }

            // The text setting MULTIPLIES on top of the system fontScale, it never replaces it:
            // WO-V1 of the Wear OS quality list asks to respect the size the user configured on
            // the watch, and someone who already raised it has to keep seeing it raised.
            val scope = rememberCoroutineScope()
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = base.density,
                    fontScale = base.fontScale * state.settings.textScale.factor,
                ),
            ) {
            // If the app was opened from a tile, navigate to that entry ONCE.
            //
            // It waits for the packs to be open: before that `entry()` would return null and the
            // screen would show an empty entry instead of the word. And it is validated against
            // the dictionaries actually open --not against the active one-- because falling back
            // to the active one is exactly the bug D-080 fixed: showing ANOTHER word, with no
            // error.
            LaunchedEffect(entradaInicial, state.status) {
                val requested = entradaInicial ?: return@LaunchedEffect
                if (state.status != SearchState.Status.Ready) return@LaunchedEffect
                val target = viewModel.targetOf(requested) ?: return@LaunchedEffect
                navController.navigate(
                    "$ROUTE_ENTRY/${Uri.encode(requested.packId)}/$target",
                )
            }

            SwipeDismissableNavHost(
                navController = navController,
                startDestination = ROUTE_SEARCH,
            ) {
                composable(ROUTE_SEARCH) {
                    // ⚠️ **With text typed, back returns to the home instead of LEAVING the app**
                    // (D-143). The home is the first screen, so the gesture fell through to the
                    // Activity and closed it: on a watch that is an abrupt exit for what the user
                    // meant as "undo what I typed". With no text it is not enabled, and then
                    // leaving is still leaving -- always trapping the gesture would leave the app
                    // with no way to be closed by the gesture the whole system uses.
                    //
                    // It is wiring and not logic: what `clearQuery` does is covered in the gate by
                    // `SearchViewModelTest`; this condition is not, because `createComposeRule()`
                    // brings no Activity and with no Activity there is no back dispatcher.
                    BackHandler(enabled = state.query.isNotEmpty()) { viewModel.clearQuery() }
                    SearchScreen(
                        state = state,
                        onQueryChange = viewModel::onQueryChange,
                        onTypingChanged = viewModel::onTypingChanged,
                        onLanguageChange = viewModel::onLanguageChange,
                        onSystemInputOpening = viewModel::onSystemInputOpening,
                        // Only the FIRST composition: the tile asks for it on opening, and
                        // reopening it on the way back from the entry would be a loop.
                        abrirInputAlEntrar = abrirInput,
                        onSearchDefinitions = viewModel::onSearchDefinitions,
                        // The packId travels with the entry: without it, with two packs open it
                        // would be resolved against the active one and would show another word.
                        onOpenEntry = {
                            viewModel.recordVisit(it)
                            navController.navigate("$ROUTE_ENTRY/${Uri.encode(it.packId)}/${it.entryId}")
                        },
                        // We do not navigate with the stored `entryId` as is: if the pack was
                        // rebuilt, that id is now ANOTHER word (D-055). `targetOf` validates it
                        // against the headword and fixes it, or returns null if the word is gone
                        // --and then we navigate nowhere, which is better than opening some
                        // other word--.
                        onOpenVisita = { visit ->
                            scope.launch {
                                val target = viewModel.targetOf(visit)
                                if (target != null) {
                                    // It records from the history too: opening it again takes it
                                    // to the top, which is what a history has to do.
                                    viewModel.recordVisit(visit.copy(entryId = target))
                                    navController.navigate(
                                        "$ROUTE_ENTRY/${Uri.encode(visit.packId)}/$target",
                                    )
                                }
                            }
                        },
                        onOpenAttribution = { navController.navigate(ROUTE_ATTRIBUTION) },
                        onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                        onOpenFavoritos = { navController.navigate(ROUTE_FAVORITES) },
                        onOpenHistory = { navController.navigate(ROUTE_HISTORY) },
                        // Each word of the day opens in ITS dictionary, which with two languages
                        // loaded is not necessarily the active one.
                        // Through `targetOf` like the history, and here it matters MORE: the word
                        // of the day is precomputed a week ahead and cached (D-097), so a rebuild
                        // mid-week leaves those `entryId` values pointing at another word for up
                        // to seven days. It is the most likely case of the D-055 defect.
                        onOpenWordOfTheDay = { packOfTheWord, word ->
                            scope.launch {
                                val visit = Visit(
                                    packId = packOfTheWord,
                                    entryId = word.entryId,
                                    headword = word.headword,
                                    partOfSpeech = word.partOfSpeech,
                                )
                                val target = viewModel.targetOf(visit)
                                if (target != null) {
                                    viewModel.recordVisit(visit.copy(entryId = target))
                                    navController.navigate(
                                        "$ROUTE_ENTRY/${Uri.encode(packOfTheWord)}/$target",
                                    )
                                }
                            }
                        },
                    )
                }
                composable(
                    route = "$ROUTE_ENTRY/{packId}/{entryId}",
                    arguments = listOf(
                        navArgument("packId") { type = NavType.StringType },
                        navArgument("entryId") { type = NavType.LongType },
                    ),
                ) { backStackEntry ->
                    val packId = backStackEntry.arguments?.getString("packId").orEmpty()
                    EntryScreen(
                        entryId = backStackEntry.arguments?.getLong("entryId") ?: 0L,
                        // The word is resolved and opened in THE SAME pack as the entry that
                        // contains it. Sending it to the active pack would be the D-080 bug all
                        // over again: it would open another word, with no error.
                        onOpenWord = { destino ->
                            // Jumping from one word to another is also opening it. Only the id is
                            // held, so the ViewModel reads the header to record it.
                            //
                            // ⚠️ **It navigates to the pack the link says, not this screen's.**
                            // For a gloss word they are the same; for a translation they are not,
                            // and using the screen's would open another word with no error (D-080).
                            viewModel.recordVisit(destino.packId, destino.entryId)
                            navController.navigate(
                                "$ROUTE_ENTRY/${Uri.encode(destino.packId)}/${destino.entryId}",
                            )
                        },
                        // Tapping words stacks entries on top of entries. Going back one at a
                        // time is the usual swipe; this is the shortcut to the start, and it is
                        // the repo's first popBackStack.
                        onBackToSearch = {
                            // With the word ERASED (D-143): it used to come back with whatever was
                            // typed and you had to clear it by hand to search for something else.
                            viewModel.clearQuery()
                            navController.popBackStack(ROUTE_SEARCH, inclusive = false)
                        },
                        resolveIn = { norms, idiomaEntrada, origen ->
                            val meta = state.available.filterIsInstance<PackHandle.Open>()
                                .firstOrNull { it.packId == packId }?.metadata
                            // ⚠️ **Which language to look the term up in, which is what fixes the
                            // `pie` bug.** A gloss word is in the entry's language; a translation
                            // term is in the OTHER one -- which in a bidirectional pack lives in
                            // this same file, and failing that, in the language the pack declares
                            // as its target.
                            val destino = when (origen) {
                                TermSource.GLOSS -> idiomaEntrada
                                TermSource.TRANSLATION ->
                                    meta?.langs?.firstOrNull { it != idiomaEntrada }
                                        ?: meta?.translationsTo
                            }
                            // This pack first; whatever it does not resolve here is looked up in
                            // another of the same target language. The order matters: a word from
                            // the dictionary's own file always wins.
                            val propias = viewModel.resolveIn(packId, norms, destino)
                                .mapValues { (_, id) -> WordLink(packId, id) }
                            val ajenas = if (destino == null) emptyMap() else {
                                viewModel.resolveInLanguage(destino, norms - propias.keys)
                                    .mapValues { (_, par) -> WordLink(par.first, par.second) }
                            }
                            ajenas + propias
                        },
                        // The same setting as Settings, where it is read from (see EntryScreen).
                        textScale = state.settings.textScale,
                        onTextScaleChange = viewModel::onTextScaleChange,
                        actions = { entry ->
                            wordActions(
                                // From the collected STATE and not from `viewModel.isFavorite`:
                                // that one reads a plain `var`, which Compose cannot observe, so
                                // the label did not change on saving (D-129).
                                isFavorite = state.favorites.any {
                                    it.packId == packId && it.entryId == entry.entryId
                                },
                                onToggleFavorite = {
                                    viewModel.toggleFavorite(
                                        Visit(packId, entry.entryId, entry.headword, entry.partOfSpeech),
                                    )
                                },
                                onCopy = {
                                    // ClipboardManager is android.*, so it comes in through here
                                    // and not through the ViewModel, which has to keep running on
                                    // the JVM.
                                    val clipboard = context
                                        .getSystemService(ClipboardManager::class.java)
                                    clipboard?.setPrimaryClip(
                                        ClipData.newPlainText(entry.headword, entry.headword),
                                    )
                                },
                            )
                        },
                        cargar = { id -> viewModel.entry(packId, id) },
                    )
                }
                composable(ROUTE_ATTRIBUTION) {
                    AttributionScreen(packs = state.available)
                }
                composable(ROUTE_FAVORITES) {
                    WordListScreen(
                        words = state.favorites,
                        title = R.string.saved_title,
                        empty = R.string.saved_empty,
                        // Removing from the list (D-155): `toggleFavorite` on one that is ALREADY
                        // a favourite takes it out, so no new path in the model is needed.
                        onDelete = viewModel::toggleFavorite,
                        // The same tags as the results (D-152): a saved word and a result are the
                        // same word.
                        tags = historyTags(state.available),
                        // Same reason as the history: the stored id may belong to an earlier
                        // pack. See `SearchViewModel.targetOf`.
                        onOpen = { visit ->
                            scope.launch {
                                val target = viewModel.targetOf(visit)
                                if (target != null) {
                                    // Opening a saved word is still opening it: it goes to the
                                    // history.
                                    viewModel.recordVisit(visit.copy(entryId = target))
                                    navController.navigate(
                                        "$ROUTE_ENTRY/${Uri.encode(visit.packId)}/$target",
                                    )
                                }
                            }
                        },
                    )
                }
                // The full history (D-148). The home shows three; the rest lives here. It is the
                // SAME screen as the saved words, with another title and another list: two
                // identical composables with different names diverge the moment somebody fixes
                // only one.
                composable(ROUTE_HISTORY) {
                    WordListScreen(
                        words = state.history,
                        title = R.string.home_recent,
                        empty = R.string.history_empty,
                        tags = historyTags(state.available),
                        // Same reason as on the home: the stored id may belong to an earlier pack.
                        // See `SearchViewModel.targetOf`.
                        onOpen = { visit ->
                            scope.launch {
                                val target = viewModel.targetOf(visit)
                                if (target != null) {
                                    viewModel.recordVisit(visit.copy(entryId = target))
                                    navController.navigate(
                                        "$ROUTE_ENTRY/${Uri.encode(visit.packId)}/$target",
                                    )
                                }
                            }
                        },
                    )
                }
                composable(ROUTE_PACKS) {
                    PacksScreen(
                        packs = state.available,
                        rejected = state.rejected,
                        onDelete = viewModel::deletePack,
                        catalog = state.catalog,
                        onCheckCatalog = viewModel::onCheckCatalog,
                        downloads = state.downloads,
                        onDownload = viewModel::onDownload,
                        onCancel = viewModel::onCancelDownload,
                    )
                }
                composable(ROUTE_SETTINGS) {
                    // The UI language is NOT one of our preferences: since API 33 the platform
                    // stores it per app and applies it before a single Composable runs, so
                    // keeping a copy in Settings would give two sources of truth --and the
                    // platform's would win, in silence.
                    val locales = context.getSystemService(LocaleManager::class.java)
                    var uiLanguage by remember {
                        mutableStateOf(locales.applicationLocales[0]?.toLanguageTag())
                    }
                    SettingsScreen(
                        packs = state.available,
                        scale = state.settings.textScale,
                        uiLanguage = uiLanguage,
                        appVersion = BuildConfig.VERSION_NAME,
                        buildCommit = BuildConfig.BUILD_COMMIT,
                        buildTime = BuildConfig.BUILD_TIME,
                        onManagePacks = { navController.navigate(ROUTE_PACKS) },
                        onScaleChange = viewModel::onTextScaleChange,
                        onUiLanguageChange = { tag ->
                            // The local state is updated too and not only the service: setting
                            // the locales recreates the Activity, but not before this frame, and
                            // without it the radio button stayed on the old option for the blink
                            // in between.
                            uiLanguage = tag
                            locales.applicationLocales = if (tag == null) {
                                // Empty is the automatic case. There is no "auto" tag.
                                LocaleList.getEmptyLocaleList()
                            } else {
                                LocaleList.forLanguageTags(tag)
                            }
                        },
                        onClearHistory = viewModel::clearHistory,
                        hasHistory = state.history.isNotEmpty(),
                    )
                }
            }
            }
        }
    }
}
