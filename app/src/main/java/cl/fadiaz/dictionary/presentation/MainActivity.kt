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
     * La entrada que pidio un tile, si es que vino de ahi y si el pedido tiene sentido.
     *
     * **Esto es entrada no confiable.** `MainActivity` esta exportada --tiene LAUNCHER-- asi que
     * cualquier app del reloj puede lanzarla con los extras que quiera. Por eso se validan aca y
     * la ruta se arma sola: un `packId` que no existe no llega a abrir otra palabra, se ignora y
     * la app arranca en la busqueda, que es su estado normal.
     */
    private fun requestedEntry(intent: Intent?): Visit? {
        val packId = intent?.getStringExtra(EXTRA_PACK_ID)?.takeIf { it.isNotBlank() } ?: return null
        val entryId = intent.getLongExtra(EXTRA_ENTRY_ID, 0L)
        if (entryId <= 0L) return null
        // El lema viaja para poder CORREGIR el id, no para mostrarlo: el tile publica un
        // `entryId` de `SharedPreferences` que un rebuild del pack deja apuntando a otra palabra
        // (D-055). Si no viene --un intent de otra app, o un tile viejo-- se cae a confiar en el
        // id, que es lo que se hacia antes.
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
 * Le pide a los dos tiles que se vuelvan a dibujar.
 *
 * Es el unico mecanismo que tienen: el de historial se publica con
 * `freshnessIntervalMillis = 0`, que segun el javadoc significa que el sistema **no** lo va a
 * refrescar solo.
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
            // El ViewModel recibe como abrir el pack en vez de construirlo: es lo unico que
            // necesitaba de Android, y sacarlo lo deja testeable en la JVM (SearchViewModelTest).
            // El applicationContext y no el de la Activity: el pack sobrevive a una rotacion.
            val context = LocalContext.current.applicationContext
            val viewModel: SearchViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        SearchViewModel(
                            openPacks = { onExtracting ->
                                PackStore.open(context, PackStore.preferredPack(context), onExtracting)
                            },
                            // Sin preferencia guardada manda el idioma del reloj, no el alfabeto.
                            preferred = {
                                PackStore.preferredPack(context) ?: Locale.getDefault().language
                            },
                            saveActivePack = { id -> PackStore.rememberPack(context, id) },
                            savedHistory = { PackStore.history(context) },
                            saveHistory = { PackStore.rememberHistory(context, it) },
                            // La fecha entra por aca y no sale de un reloj dentro del ViewModel:
                            // es lo que deja testear la palabra del dia en la JVM (D-072).
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
                            // Los tiles no tienen refresco programado: si la app no los empuja,
                            // se quedan con lo que tenian.
                            notifyTiles = { notifyTiles(context) },
                        )
                    }
                },
            )
            val state by viewModel.state.collectAsStateWithLifecycle()

            // El ajuste de texto MULTIPLICA sobre el fontScale del sistema, nunca lo reemplaza:
            // WO-V1 de la lista de calidad de Wear OS pide respetar el tamano que el usuario
            // configuro en el reloj, y quien ya lo subio tiene que seguir viendolo subido.
            val scope = rememberCoroutineScope()
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = base.density,
                    fontScale = base.fontScale * state.settings.textScale.factor,
                ),
            ) {
            // Si la app se abrio desde un tile, se navega a esa entrada UNA vez.
            //
            // Se espera a que los packs esten abiertos: antes de eso `entry()` devolveria null y
            // la pantalla mostraria una entrada vacia en vez de la palabra. Y se valida contra los
            // diccionarios realmente abiertos --no contra el activo-- porque caer al activo es
            // justo el bug que D-080 arreglo: mostrar OTRA palabra, sin error.
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
                    SearchScreen(
                        state = state,
                        onQueryChange = viewModel::onQueryChange,
                        onPackChange = viewModel::onPackChange,
                        onSearchDefinitions = viewModel::onSearchDefinitions,
                        // El packId viaja con la entrada: sin el, con dos packs abiertos se
                        // resolveria contra el activo y mostraria otra palabra.
                        onOpenEntry = {
                            viewModel.recordVisit(it)
                            navController.navigate("$ROUTE_ENTRY/${Uri.encode(it.packId)}/${it.entryId}")
                        },
                        // No se navega con el `entryId` guardado tal cual: si el pack se
                        // reconstruyo, ese id es ahora OTRA palabra (D-055). `destinoDe` lo
                        // valida contra el lema y lo corrige, o devuelve null si la palabra ya
                        // no esta --y entonces no se navega a ningun lado, que es mejor que
                        // abrir cualquier otra--.
                        onOpenVisita = { visit ->
                            scope.launch {
                                val target = viewModel.targetOf(visit)
                                if (target != null) {
                                    navController.navigate(
                                        "$ROUTE_ENTRY/${Uri.encode(visit.packId)}/$target",
                                    )
                                }
                            }
                        },
                        onOpenAttribution = { navController.navigate(ROUTE_ATTRIBUTION) },
                        onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                        onOpenFavoritos = { navController.navigate(ROUTE_FAVORITES) },
                        // Cada palabra del dia se abre en SU diccionario, que con dos idiomas
                        // cargados no es necesariamente el activo.
                        // Por `destinoDe` igual que el historial, y aca importa MAS: la palabra
                        // del dia se adelanta una semana y se cachea (D-097), asi que un rebuild
                        // a mitad de semana deja esos `entryId` apuntando a otra palabra durante
                        // hasta siete dias. Es el caso mas probable del defecto de D-055.
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
                        // La palabra se resuelve y se abre en EL MISMO pack que la entrada que
                        // la contiene. Mandarla al pack activo seria el bug de D-080 otra vez:
                        // abriria otra palabra y sin error.
                        onOpenWord = { id ->
                            navController.navigate("$ROUTE_ENTRY/${Uri.encode(packId)}/$id")
                        },
                        // Tocar palabras apila entradas sobre entradas. Volver de a una es el
                        // swipe de siempre; esto es el atajo al principio, y es el primer
                        // popBackStack del repo.
                        onBackToSearch = {
                            navController.popBackStack(ROUTE_SEARCH, inclusive = false)
                        },
                        resolveIn = { norms -> viewModel.resolveIn(packId, norms) },
                        actions = { entry ->
                            wordActions(
                                isFavorite = viewModel.isFavorite(packId, entry.entryId),
                                onToggleFavorite = {
                                    viewModel.toggleFavorite(
                                        Visit(packId, entry.entryId, entry.headword, entry.partOfSpeech),
                                    )
                                },
                                translationPack = translationPack(state.available, packId),
                                onViewTranslation = { other ->
                                    // La misma palabra en el otro diccionario: se resuelve por
                                    // `norm`, que es la clave con la que se indexo, y se abre EN
                                    // SU pack -- si se abriera en el activo seria D-080 otra vez.
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
                                    // ClipboardManager es android.*, asi que entra por aca y no
                                    // por el ViewModel, que tiene que seguir corriendo en la JVM.
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
                        // Mismo motivo que el historial: el id guardado puede ser de un
                        // pack anterior. Ver `SearchViewModel.destinoDe`.
                        onOpen = { visit ->
                            scope.launch {
                                val target = viewModel.targetOf(visit)
                                if (target != null) {
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
