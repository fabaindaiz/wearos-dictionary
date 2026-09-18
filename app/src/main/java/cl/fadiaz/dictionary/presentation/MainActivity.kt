package cl.fadiaz.dictionary.presentation

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cl.fadiaz.dictionary.data.PackStore
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
        setContent { DictionaryApp() }
    }
}

private const val RUTA_BUSQUEDA = "busqueda"
private const val RUTA_ENTRADA = "entrada"
private const val RUTA_ATRIBUCION = "atribucion"

@Composable
fun DictionaryApp() {
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
                            abrirPacks = { onExtracting ->
                                PackStore.open(context, PackStore.packPreferido(context), onExtracting)
                            },
                            // Sin preferencia guardada manda el idioma del reloj, no el alfabeto.
                            preferido = {
                                PackStore.packPreferido(context) ?: Locale.getDefault().language
                            },
                            recordar = { id -> PackStore.recordarPack(context, id) },
                            historialGuardado = { PackStore.historial(context) },
                            guardarHistorial = { PackStore.recordarHistorial(context, it) },
                        )
                    }
                },
            )
            val state by viewModel.state.collectAsStateWithLifecycle()

            SwipeDismissableNavHost(
                navController = navController,
                startDestination = RUTA_BUSQUEDA,
            ) {
                composable(RUTA_BUSQUEDA) {
                    SearchScreen(
                        state = state,
                        onQueryChange = viewModel::onQueryChange,
                        onPackChange = viewModel::onPackChange,
                        onSearchDefinitions = viewModel::onSearchDefinitions,
                        // El packId viaja con la entrada: sin el, con dos packs abiertos se
                        // resolveria contra el activo y mostraria otra palabra.
                        onOpenEntry = {
                            viewModel.registrarVisita(it)
                            navController.navigate("$RUTA_ENTRADA/${Uri.encode(it.packId)}/${it.entryId}")
                        },
                        onOpenVisita = {
                            navController.navigate("$RUTA_ENTRADA/${Uri.encode(it.packId)}/${it.entryId}")
                        },
                        onOpenAttribution = { navController.navigate(RUTA_ATRIBUCION) },
                    )
                }
                composable(
                    route = "$RUTA_ENTRADA/{packId}/{entryId}",
                    arguments = listOf(
                        navArgument("packId") { type = NavType.StringType },
                        navArgument("entryId") { type = NavType.LongType },
                    ),
                ) { backStackEntry ->
                    val packId = backStackEntry.arguments?.getString("packId").orEmpty()
                    EntryScreen(
                        entryId = backStackEntry.arguments?.getLong("entryId") ?: 0L,
                        cargar = { id -> viewModel.entry(packId, id) },
                    )
                }
                composable(RUTA_ATRIBUCION) {
                    AttributionScreen(
                        packs = state.disponibles,
                        problemas = state.problemas,
                    )
                }
            }
        }
    }
}
