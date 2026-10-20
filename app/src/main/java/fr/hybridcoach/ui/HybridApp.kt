package fr.hybridcoach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import fr.hybridcoach.domain.*
import java.time.LocalDate

private val tabs =
    listOf(
        Triple("today", "Aujourd’hui", Icons.Default.WbSunny),
        Triple("calendar", "Calendrier", Icons.Default.CalendarMonth),
        Triple("preparations", "Prépas", Icons.Default.Flag),
        Triple("history", "Historique", Icons.Default.History),
        Triple("progress", "Progrès", Icons.Default.Insights),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HybridApp(vm: CoachViewModel) {
    val theme by vm.theme.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.notices.collect { snackbar.showSnackbar(it) } }
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "today"
    val main = tabs.any { it.first == route }
    HybridTheme(theme) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (main) "HYBRID COACH"
                            else
                                when {
                                    route.startsWith("planning") -> "Planification"
                                    route.startsWith("editor") -> "Créer & planifier"
                                    route.startsWith("session") -> "Votre séance"
                                    route.startsWith("preparation/") -> "Préparation"
                                    route == "garmin" -> "Garmin Connect"
                                    route == "profile" -> "Profil sportif"
                                    route == "exercises" -> "Exercices"
                                    else -> "Modèles de séances"
                                },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    navigationIcon = {
                        if (!main)
                            IconButton(onClick = { nav.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                            }
                    },
                    actions = {
                        if (main) {
                            IconButton(
                                onClick = { nav.navigate("templates") },
                                enabled = state.loaded,
                            ) {
                                Icon(Icons.Default.CollectionsBookmark, "Modèles")
                            }
                            IconButton(
                                onClick = { nav.navigate("exercises") },
                                enabled = state.loaded,
                            ) {
                                Icon(Icons.Default.FitnessCenter, "Exercices")
                            }
                            IconButton(
                                onClick = { nav.navigate("profile") },
                                enabled = state.loaded,
                            ) {
                                Icon(Icons.Default.Tune, "Profil sportif")
                            }
                        }
                    },
                )
            },
            bottomBar = {
                if (main)
                    NavigationBar {
                        tabs.forEach { (path, label, icon) ->
                            NavigationBarItem(
                                enabled = state.loaded,
                                selected = route == path,
                                onClick = {
                                    nav.navigate(path) {
                                        popUpTo("today") { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(icon, label) },
                                label = {
                                    Text(
                                        label,
                                        maxLines = 1,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                            )
                        }
                    }
            },
            floatingActionButton = {
                if (main && state.loaded)
                    FloatingActionButton(
                        onClick = {
                            nav.navigate("editor/new?date=${LocalDate.now()}&template=&prep=")
                        }
                    ) {
                        Icon(Icons.Default.Add, "Créer une séance")
                    }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (error != null)
                    Surface(color = MaterialTheme.colorScheme.errorContainer) {
                        Row(Modifier.padding(12.dp)) {
                            Text(error!!, Modifier.weight(1f))
                            TextButton(onClick = { vm.error.value = null }) { Text("Fermer") }
                        }
                    }
                if (pending > 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!state.loaded) {
                    Box(Modifier.padding(32.dp)) { Text("Ouverture du carnet d’entraînement…") }
                } else
                    NavHost(nav, startDestination = "today", modifier = Modifier.weight(1f)) {
                        composable("today") {
                            TodayScreen(
                                state,
                                { nav.navigate("session/$it") },
                                { nav.navigate("editor/new?date=$it&template=&prep=") },
                            )
                        }
                        composable("calendar") {
                            CalendarScreen(
                                vm,
                                state,
                                { nav.navigate("session/$it") },
                                { nav.navigate("editor/new?date=$it&template=&prep=") },
                                { nav.navigate("planning?prep=") },
                            )
                        }
                        composable("planning?prep={prep}") { e ->
                            PlanningScreen(
                                vm,
                                state,
                                e.arguments?.getString("prep") ?: "",
                                { nav.navigate("session/$it") },
                                {
                                    nav.navigate(
                                        "editor/$it?date=${LocalDate.now()}&template=&prep="
                                    )
                                },
                                { date, prep, template ->
                                    nav.navigate(
                                        "editor/new?date=$date&template=$template&prep=$prep"
                                    )
                                },
                            )
                        }
                        composable("preparations") {
                            PreparationsScreen(vm, state) { nav.navigate("preparation/$it") }
                        }
                        composable("preparation/{id}") { e ->
                            PreparationScreen(
                                vm,
                                state,
                                e.arguments!!.getString("id")!!,
                                { nav.navigate("session/$it") },
                                { date, id ->
                                    nav.navigate("editor/new?date=$date&template=&prep=$id")
                                },
                                { nav.navigate("planning?prep=$it") },
                            )
                        }
                        composable("history") {
                            HistoryScreen(state) { nav.navigate("session/$it") }
                        }
                        composable("progress") { ProgressScreen(state) }
                        composable("profile") {
                            ProfileScreen(vm, { nav.popBackStack() }, { nav.navigate("garmin") })
                        }
                        composable("garmin") {
                            GarminScreen(vm, state) { nav.navigate("session/$it") }
                        }
                        composable("exercises") { ExerciseLibraryScreen(vm, state) }
                        composable("templates") {
                            TemplatesScreen(
                                vm,
                                state,
                                { id ->
                                    nav.navigate(
                                        "editor/new?date=${LocalDate.now()}&template=$id&prep="
                                    )
                                },
                                { id ->
                                    nav.navigate(
                                        "editor/$id?date=${LocalDate.now()}&template=edit&prep="
                                    )
                                },
                            )
                        }
                        composable("editor/{id}?date={date}&template={template}&prep={prep}") { e ->
                            WorkoutEditor(
                                vm,
                                state,
                                e.arguments!!.getString("id")!!,
                                e.arguments!!.getString("date") ?: LocalDate.now().toString(),
                                e.arguments!!.getString("template"),
                                e.arguments!!.getString("prep"),
                            ) {
                                nav.popBackStack()
                            }
                        }
                        composable("session/{id}") { e ->
                            SessionScreen(
                                vm,
                                state,
                                e.arguments!!.getString("id")!!,
                                {
                                    nav.navigate(
                                        "editor/$it?date=${LocalDate.now()}&template=&prep="
                                    )
                                },
                                { nav.popBackStack() },
                            )
                        }
                    }
            }
        }
    }
}
