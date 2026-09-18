package cl.fadiaz.dictionary.presentation

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cl.fadiaz.dictionary.data.PackStore
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
        setContent { DictionaryApp() }
    }
}

private const val RUTA_BUSQUEDA = "busqueda"
private const val RUTA_ENTRADA = "entrada"
private const val RUTA_ATRIBUCION = "atribucion"
private const val RUTA_AJUSTES = "ajustes"

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
                            // La fecha entra por aca y no sale de un reloj dentro del ViewModel:
                            // es lo que deja testear la palabra del dia en la JVM (D-072).
                            fechaDeHoy = { LocalDate.now().toString() },
                            ajustesGuardados = { PackStore.ajustes(context) },
                            guardarAjustes = { PackStore.recordarAjustes(context, it) },
                        )
                    }
                },
            )
            val state by viewModel.state.collectAsStateWithLifecycle()

            // El ajuste de texto MULTIPLICA sobre el fontScale del sistema, nunca lo reemplaza:
            // WO-V1 de la lista de calidad de Wear OS pide respetar el tamano que el usuario
            // configuro en el reloj, y quien ya lo subio tiene que seguir viendolo subido.
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = base.density,
                    fontScale = base.fontScale * state.ajustes.escalaDeTexto.factor,
                ),
            ) {
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
                        onOpenAjustes = { navController.navigate(RUTA_AJUSTES) },
                        // La palabra del dia es del pack ACTIVO, asi que se abre en el suyo.
                        onOpenPalabraDelDia = { palabra ->
                            val packId = state.activo?.packId ?: return@SearchScreen
                            navController.navigate(
                                "$RUTA_ENTRADA/${Uri.encode(packId)}/${palabra.entryId}",
                            )
                        },
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
                        // La palabra se resuelve y se abre en EL MISMO pack que la entrada que
                        // la contiene. Mandarla al pack activo seria el bug de D-080 otra vez:
                        // abriria otra palabra y sin error.
                        onOpenPalabra = { id ->
                            navController.navigate("$RUTA_ENTRADA/${Uri.encode(packId)}/$id")
                        },
                        // Tocar palabras apila entradas sobre entradas. Volver de a una es el
                        // swipe de siempre; esto es el atajo al principio, y es el primer
                        // popBackStack del repo.
                        onVolverABuscar = {
                            navController.popBackStack(RUTA_BUSQUEDA, inclusive = false)
                        },
                        resolver = { norms -> viewModel.resolver(packId, norms) },
                        cargar = { id -> viewModel.entry(packId, id) },
                    )
                }
                composable(RUTA_ATRIBUCION) {
                    AttributionScreen(
                        packs = state.disponibles,
                        problemas = state.problemas,
                    )
                }
                composable(RUTA_AJUSTES) {
                    SettingsScreen(
                        packs = state.disponibles,
                        activo = state.activo?.packId,
                        escala = state.ajustes.escalaDeTexto,
                        onPackChange = viewModel::onPackChange,
                        onEscalaChange = viewModel::onEscalaDeTextoChange,
                        onLimpiarHistorial = viewModel::limpiarHistorial,
                        hayHistorial = state.historial.isNotEmpty(),
                    )
                }
            }
            }
        }
    }
}
