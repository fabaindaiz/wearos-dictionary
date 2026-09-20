package cl.fadiaz.dictionary.presentation

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.wear.tiles.TileService
import cl.fadiaz.dictionary.tile.EXTRA_ENTRY_ID
import cl.fadiaz.dictionary.tile.EXTRA_HEADWORD
import cl.fadiaz.dictionary.tile.EXTRA_PACK_ID
import cl.fadiaz.dictionary.tile.HistoryTileService
import cl.fadiaz.dictionary.tile.WordOfTheDayTileService
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cl.fadiaz.dictionary.core.TextNormalizer
import cl.fadiaz.dictionary.core.Entry
import cl.fadiaz.dictionary.data.PackHandle
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
        setContent { DictionaryApp(requestedEntry(intent)) }
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
}

private const val ROUTE_SEARCH = "busqueda"
private const val ROUTE_ENTRY = "entrada"
private const val ROUTE_ATTRIBUTION = "atribucion"
private const val ROUTE_SETTINGS = "ajustes"
private const val ROUTE_FAVORITES = "favoritos"
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

@Composable
fun DictionaryApp(entradaInicial: Visit? = null) {
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
                            saveActivePack = { id -> PackStore.rememberPack(context, id) },
                            savedHistory = { PackStore.history(context) },
                            saveHistory = { PackStore.rememberHistory(context, it) },
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
                        )
                    }
                },
            )
            val state by viewModel.state.collectAsStateWithLifecycle()

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
                    // ⚠️ **Con texto escrito, atras vuelve al inicio en vez de SALIR de la app**
                    // (D-143). El inicio es la primera pantalla, asi que el gesto caia en la
                    // Activity y la cerraba: en un reloj eso es una salida brusca para lo que el
                    // usuario quiso decir con "deshace lo que escribi". Sin texto no se habilita,
                    // y entonces salir sigue siendo salir -- atrapar el gesto siempre dejaria la
                    // app sin forma de cerrarse con el gesto que todo el sistema usa.
                    //
                    // Es cableado y no logica: lo que hace `clearQuery` esta cubierto en el gate
                    // por `SearchViewModelTest`; esta condicion no, porque `createComposeRule()`
                    // no trae Activity y sin Activity no hay despachador de atras.
                    BackHandler(enabled = state.query.isNotEmpty()) { viewModel.clearQuery() }
                    SearchScreen(
                        state = state,
                        onQueryChange = viewModel::onQueryChange,
                        onTypingChanged = viewModel::onTypingChanged,
                        onPackChange = viewModel::onPackChange,
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
                                    // Anota tambien desde el historial: abrirla otra vez la sube
                                    // al tope, que es lo que un historial tiene que hacer.
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
                        onOpenWord = { id ->
                            // Saltar de una palabra a otra tambien es abrirla. Solo se tiene el
                            // id, asi que el ViewModel lee la cabecera para anotarla.
                            viewModel.recordVisit(packId, id)
                            navController.navigate("$ROUTE_ENTRY/${Uri.encode(packId)}/$id")
                        },
                        // Tapping words stacks entries on top of entries. Going back one at a
                        // time is the usual swipe; this is the shortcut to the start, and it is
                        // the repo's first popBackStack.
                        onBackToSearch = {
                            // Con la palabra BORRADA (D-143): antes volvia con lo que habia
                            // escrito y habia que borrarlo a mano para buscar otra cosa.
                            viewModel.clearQuery()
                            navController.popBackStack(ROUTE_SEARCH, inclusive = false)
                        },
                        resolveIn = { norms -> viewModel.resolveIn(packId, norms) },
                        actions = { entry ->
                            wordActions(
                                // Del STATE recolectado y no de `viewModel.isFavorite`: ese
                                // lee un `var` comun, que Compose no puede observar, asi que la
                                // etiqueta no cambiaba al guardar (D-129).
                                isFavorite = state.favorites.any {
                                    it.packId == packId && it.entryId == entry.entryId
                                },
                                onToggleFavorite = {
                                    viewModel.toggleFavorite(
                                        Visit(packId, entry.entryId, entry.headword, entry.partOfSpeech),
                                    )
                                },
                                translationPack = translationPack(state.available, packId),
                                onViewTranslation = { other ->
                                    // The same word in the other dictionary: resolved through
                                    // `norm`, which is the key it was indexed by, and opened IN
                                    // ITS pack -- opening it in the active one would be D-080
                                    // all over again.
                                    scope.launch {
                                        val key = TextNormalizer.norm(entry.headword)
                                        val target = viewModel
                                            .resolveIn(other.packId, setOf(key))[key]
                                        if (target != null) {
                                            navController.navigate(
                                                "$ROUTE_ENTRY/${Uri.encode(other.packId)}/$target",
                                            )
                                        }
                                    }
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
                    AttributionScreen(
                        packs = state.available,
                        problems = state.problems,
                    )
                }
                composable(ROUTE_FAVORITES) {
                    FavoritesScreen(
                        favorites = state.favorites,
                        // Same reason as the history: the stored id may belong to an earlier
                        // pack. See `SearchViewModel.targetOf`.
                        onOpen = { visit ->
                            scope.launch {
                                val target = viewModel.targetOf(visit)
                                if (target != null) {
                                    // Abrir una guardada tambien es abrirla: va al historial.
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
                        active = state.active?.packId,
                        onActivate = viewModel::onPackChange,
                        onDelete = viewModel::deletePack,
                    )
                }
                composable(ROUTE_SETTINGS) {
                    SettingsScreen(
                        packs = state.available,
                        scale = state.settings.textScale,
                        onManagePacks = { navController.navigate(ROUTE_PACKS) },
                        onScaleChange = viewModel::onTextScaleChange,
                        onClearHistory = viewModel::clearHistory,
                        hasHistory = state.history.isNotEmpty(),
                    )
                }
            }
            }
        }
    }
}
