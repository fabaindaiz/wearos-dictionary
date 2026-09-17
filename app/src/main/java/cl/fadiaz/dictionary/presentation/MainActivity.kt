package cl.fadiaz.dictionary.presentation

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
                        SearchViewModel { onExtracting -> PackStore.open(context, onExtracting) }
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
                        onOpenEntry = { navController.navigate("$RUTA_ENTRADA/${it.entryId}") },
                        onOpenAttribution = { navController.navigate(RUTA_ATRIBUCION) },
                    )
                }
                composable(
                    route = "$RUTA_ENTRADA/{entryId}",
                    arguments = listOf(navArgument("entryId") { type = NavType.LongType }),
                ) { backStackEntry ->
                    EntryScreen(
                        entryId = backStackEntry.arguments?.getLong("entryId") ?: 0L,
                        cargar = viewModel::entry,
                    )
                }
                composable(RUTA_ATRIBUCION) {
                    AttributionScreen(
                        packName = state.packName,
                        attribution = state.attribution,
                        license = state.license,
                    )
                }
            }
        }
    }
}
