package fr.hybridcoach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ProfileScreen(vm: CoachViewModel, back: () -> Unit) {
    val current by vm.profile.collectAsStateWithLifecycle()
    val currentTheme by vm.theme.collectAsStateWithLifecycle()
    var raw by
        rememberSaveable(current) {
            mutableStateOf(codec.encodeToString(AthleteProfile.serializer(), current))
        }
    val p = codec.decodeFromString<AthleteProfile>(raw)
    fun update(profile: AthleteProfile) {
        raw = codec.encodeToString(AthleteProfile.serializer(), profile)
    }
    var theme by rememberSaveable(currentTheme) { mutableStateOf(currentTheme) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageTitle(
            "Vos repères",
            "Les références sont figées lors de la planification d’une séance.",
        )
        Choice(
            "Apparence",
            theme,
            listOf("dark", "light", "system"),
            text = {
                when (it) {
                    "light" -> "Clair"
                    "system" -> "Système"
                    else -> "Sombre"
                }
            },
            change = { theme = it },
        )
        Field("VMA (km/h)", p.vma, { update(p.copy(vma = it)) }, true)
        Field("FC maximale (bpm)", p.maxHr, { update(p.copy(maxHr = it)) }, true)
        Text(
            "Allures en secondes par kilomètre : 3:30/km = 210 s/km.",
            style = MaterialTheme.typography.bodySmall,
        )
        Field("Allure 5 km (s/km)", p.pace5, { update(p.copy(pace5 = it)) }, true)
        Field("Allure 10 km (s/km)", p.pace10, { update(p.copy(pace10 = it)) }, true)
        Field("Allure seuil (s/km)", p.threshold, { update(p.copy(threshold = it)) }, true)
        Field("Allure semi-marathon (s/km)", p.half, { update(p.copy(half = it)) }, true)
        Field("Allure marathon (s/km)", p.marathon, { update(p.copy(marathon = it)) }, true)
        Field("Zones FC · ex. 1:120-140", p.hrZones, { update(p.copy(hrZones = it)) }, lines = 3)
        Field(
            "Zones d’allure (s/km) · ex. 1:300-360",
            p.paceZones,
            { update(p.copy(paceZones = it)) },
            lines = 3,
        )
        SectionTitle("Convertisseur")
        var speed by rememberSaveable { mutableStateOf("") }
        var distance by rememberSaveable { mutableStateOf("") }
        Field("Vitesse (km/h)", speed, { speed = it }, true)
        Field("Distance (mètres)", distance, { distance = it }, true)
        val pace = speed.decimal()?.takeIf { it > 0 }?.let { SportMath.paceFromSpeed(it) }
        Text(
            "Allure : ${SportMath.paceLabel(pace)} · Durée : ${pace?.let{p -> distance.decimal()?.takeIf{it>=0}?.let{SportMath.durationLabel(SportMath.targetSeconds(it,p).toLong())}} ?: "—"}"
        )
        Button(
            onClick = {
                vm.action {
                    vm.preferences.save(p, theme)
                    withContext(Dispatchers.Main) { back() }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Enregistrer le profil")
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
fun ExerciseLibraryScreen(vm: CoachViewModel, state: CoachState) {
    var search by rememberSaveable { mutableStateOf("") }
    var muscle by rememberSaveable { mutableStateOf("Tous") }
    var editing by remember { mutableStateOf<Exercise?>(null) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            PageTitle(
                "Le répertoire",
                "${state.exercises.size} exercices · vos mouvements, vos repères",
            )
            Field("Rechercher un exercice", search, { search = it })
            Choice(
                "Muscle",
                muscle,
                listOf("Tous") + state.exercises.map { it.muscle }.distinct().sorted(),
                change = { muscle = it },
            )
            Button(onClick = { editing = Exercise(name = "", muscle = "", custom = true) }) {
                Text("Créer un exercice")
            }
        }
        items(
            state.exercises.filter {
                it.name.contains(search, true) && (muscle == "Tous" || it.muscle == muscle)
            },
            key = { it.id },
        ) { e ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(e.name, style = MaterialTheme.typography.titleMedium)
                    Text("${e.muscle} · ${e.equipment}", color = MaterialTheme.colorScheme.primary)
                    if (e.secondary.isNotBlank())
                        Text(
                            "Secondaires : ${e.secondary}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    Text(e.movement, style = MaterialTheme.typography.bodySmall)
                    if (e.custom) TextButton(onClick = { editing = e }) { Text("Modifier") }
                }
            }
        }
    }
    editing?.let { original ->
        CustomExerciseDialog(original, { editing = null }) { e ->
            vm.action { vm.repo.dao.putExercise(e) }
            editing = null
        }
    }
}

@Composable
fun CustomExerciseDialog(
    original: Exercise = Exercise(name = "", muscle = "", custom = true),
    dismiss: () -> Unit,
    save: (Exercise) -> Unit,
) {
    var e by remember { mutableStateOf(original) }
    var error by remember { mutableStateOf<String?>(null) }
    FormDialog(
        "Exercice personnel",
        dismiss,
        {
            if (e.name.isBlank() || e.muscle.isBlank())
                error = "Indiquez un nom et un muscle principal."
            else save(e)
        },
    ) {
        Field("Nom", e.name, { e = e.copy(name = it) })
        Field("Muscle principal", e.muscle, { e = e.copy(muscle = it) })
        Field("Muscles secondaires", e.secondary, { e = e.copy(secondary = it) })
        Field("Matériel", e.equipment, { e = e.copy(equipment = it) })
        Field("Type de mouvement", e.movement, { e = e.copy(movement = it) })
        ErrorText(error)
    }
}
