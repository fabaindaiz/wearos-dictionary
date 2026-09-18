package cl.fadiaz.dictionary.presentation

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
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
import cl.fadiaz.dictionary.data.Visita
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
        setContent { DictionaryApp() }
    }
}

private const val RUTA_BUSQUEDA = "busqueda"
private const val RUTA_ENTRADA = "entrada"
private const val RUTA_ATRIBUCION = "atribucion"
private const val RUTA_AJUSTES = "ajustes"
private const val RUTA_FAVORITOS = "favoritos"
private const val RUTA_PACKS = "packs"

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
                            favoritosGuardados = { PackStore.favoritos(context) },
                            guardarFavoritos = { PackStore.recordarFavoritos(context, it) },
                            borrarDelDisco = { archivo -> PackStore.borrarPack(context, archivo) },
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
                        onOpenFavoritos = { navController.navigate(RUTA_FAVORITOS) },
                        // Cada palabra del dia se abre en SU diccionario, que con dos idiomas
                        // cargados no es necesariamente el activo.
                        onOpenPalabraDelDia = { packDeLaPalabra, palabra ->
                            navController.navigate(
                                "$RUTA_ENTRADA/${Uri.encode(packDeLaPalabra)}/${palabra.entryId}",
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
                        acciones = { entrada ->
                            accionesDeLaPalabra(
                                entrada = entrada,
                                esFavorita = viewModel.esFavorita(packId, entrada.entryId),
                                onAlternarFavorita = {
                                    viewModel.alternarFavorita(
                                        Visita(packId, entrada.entryId, entrada.headword, entrada.partOfSpeech),
                                    )
                                },
                                otroPack = state.disponibles
                                    .filterIsInstance<PackHandle.Abierto>()
                                    .firstOrNull { it.packId != packId },
                                onVerEnOtroIdioma = { otro ->
                                    // La misma palabra en el otro diccionario: se resuelve por
                                    // `norm`, que es la clave con la que se indexo, y se abre EN
                                    // SU pack -- si se abriera en el activo seria D-080 otra vez.
                                    scope.launch {
                                        val clave = TextNormalizer.norm(entrada.headword)
                                        val destino = viewModel
                                            .resolver(otro.packId, setOf(clave))[clave]
                                        if (destino != null) {
                                            navController.navigate(
                                                "$RUTA_ENTRADA/${Uri.encode(otro.packId)}/$destino",
                                            )
                                        }
                                    }
                                },
                                onCopiar = {
                                    // ClipboardManager es android.*, asi que entra por aca y no
                                    // por el ViewModel, que tiene que seguir corriendo en la JVM.
                                    val portapapeles = context
                                        .getSystemService(ClipboardManager::class.java)
                                    portapapeles?.setPrimaryClip(
                                        ClipData.newPlainText(entrada.headword, entrada.headword),
                                    )
                                },
                            )
                        },
                        cargar = { id -> viewModel.entry(packId, id) },
                    )
                }
                composable(RUTA_ATRIBUCION) {
                    AttributionScreen(
                        packs = state.disponibles,
                        problemas = state.problemas,
                    )
                }
                composable(RUTA_FAVORITOS) {
                    FavoritesScreen(
                        favoritos = state.favoritos,
                        onOpen = {
                            navController.navigate(
                                "$RUTA_ENTRADA/${Uri.encode(it.packId)}/${it.entryId}",
                            )
                        },
                    )
                }
                composable(RUTA_PACKS) {
                    PacksScreen(
                        packs = state.disponibles,
                        activo = state.activo?.packId,
                        onActivar = viewModel::onPackChange,
                        onBorrar = viewModel::borrarPack,
                    )
                }
                composable(RUTA_AJUSTES) {
                    SettingsScreen(
                        packs = state.disponibles,
                        activo = state.activo?.packId,
                        escala = state.ajustes.escalaDeTexto,
                        onGestionarPacks = { navController.navigate(RUTA_PACKS) },
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

/**
 * Las tres acciones de una palabra.
 *
 * Vive aca y no en la pantalla porque dos de las tres necesitan Android --el portapapeles y el
 * navController-- y la pantalla tiene que poder probarse pasandole una lista armada a mano.
 *
 * "Ver en el otro idioma" **solo aparece si hay otro pack instalado**: ofrecer una accion que no
 * puede hacer nada ensena a desconfiar del resto del menu.
 */
private fun accionesDeLaPalabra(
    entrada: Entry,
    esFavorita: Boolean,
    onAlternarFavorita: () -> Unit,
    otroPack: PackHandle.Abierto?,
    onVerEnOtroIdioma: (PackHandle.Abierto) -> Unit,
    onCopiar: () -> Unit,
): List<AccionDeEntrada> = buildList {
    add(
        AccionDeEntrada(
            etiqueta = if (esFavorita) "Quitar de guardadas" else "Guardar",
            onClick = onAlternarFavorita,
        ),
    )
    if (otroPack != null) {
        add(
            AccionDeEntrada(
                etiqueta = "Ver en ${otroPack.metadata.name}",
                onClick = { onVerEnOtroIdioma(otroPack) },
            ),
        )
    }
    add(AccionDeEntrada(etiqueta = "Copiar", onClick = onCopiar))
}
