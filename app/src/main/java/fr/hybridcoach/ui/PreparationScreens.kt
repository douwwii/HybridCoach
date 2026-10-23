package fr.hybridcoach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import fr.hybridcoach.export.*
import java.time.*
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.*

@Composable
fun PreparationsScreen(vm: CoachViewModel, state: CoachState, open: (String) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var archived by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PageTitle(
                "Un objectif. Un chemin.",
                "Rassemblez course et musculation autour de votre prochain défi.",
            )
            Button(onClick = { creating = true }) {
                Icon(Icons.Default.Add, null)
                Text("Créer une préparation")
            }
            CheckLine("Afficher les archives", archived) { archived = it }
        }
        val preps = state.preparations.filter { archived || !it.archived }
        if (preps.isEmpty())
            item {
                EmptyState(
                    "Votre prochain défi commence ici",
                    "Définissez une compétition, une date et un chrono. Construisez ensuite votre préparation semaine par semaine.",
                )
            }
        items(preps, key = { it.id }) { p ->
            val goal = state.goals.find { it.id == p.goalId }
            val workouts = state.workouts.filter { it.preparationId == p.id }
            Card(onClick = { open(p.id) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (p.archived) "ARCHIVÉE" else "PRÉPARATION",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(p.name, style = MaterialTheme.typography.titleLarge)
                    Text("${goal?.name?:""} · ${goal?.raceDate?.let{dateLabel(it)}?:""}")
                    Text(
                        "${workouts.count{it.status.isResult}} / ${workouts.size} séances réalisées",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(
                        progress = {
                            if (workouts.isEmpty()) 0f
                            else workouts.count { it.status.isResult }.toFloat() / workouts.size
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
    if (creating) PreparationDialog(vm, null, null, { creating = false })
}

@Composable
fun PreparationDialog(
    vm: CoachViewModel,
    original: TrainingPreparation?,
    originalGoal: TrainingGoal?,
    dismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(original?.name ?: "") }
    var goalName by rememberSaveable { mutableStateOf(originalGoal?.name ?: "") }
    var start by rememberSaveable { mutableStateOf(original?.start ?: LocalDate.now().toString()) }
    var end by rememberSaveable {
        mutableStateOf(original?.end ?: LocalDate.now().plusWeeks(10).toString())
    }
    var race by rememberSaveable {
        mutableStateOf(originalGoal?.raceDate ?: LocalDate.now().plusWeeks(10).toString())
    }
    var distance by rememberSaveable { mutableStateOf(originalGoal?.distanceKm?.toString() ?: "") }
    var target by rememberSaveable { mutableStateOf(originalGoal?.targetSeconds?.toString() ?: "") }
    var priority by rememberSaveable { mutableStateOf(originalGoal?.priority ?: "") }
    var notes by rememberSaveable { mutableStateOf(originalGoal?.notes ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    FormDialog(
        if (original == null) "Nouvelle préparation" else "Modifier la préparation",
        dismiss,
        {
            if (!busy)
                try {
                    require(name.isNotBlank() && goalName.isNotBlank()) {
                        "Nommez la préparation et l’objectif."
                    }
                    require(start.asDate() <= end.asDate()) { "Vérifiez les dates de préparation." }
                    require(race.asDate() >= start.asDate()) { "La compétition précède le début." }
                    val km =
                        if (distance.isBlank()) null
                        else
                            requireNotNull(distance.decimal()?.takeIf { it > 0 }) {
                                "Distance invalide."
                            }
                    val seconds =
                        if (target.isBlank()) null
                        else
                            requireNotNull(target.toIntOrNull()?.takeIf { it > 0 }) {
                                "Chrono invalide (secondes entières)."
                            }
                    val goal =
                        TrainingGoal(
                            id = originalGoal?.id ?: newId(),
                            name = goalName.trim(),
                            raceDate = race,
                            distanceKm = km,
                            targetSeconds = seconds,
                            priority = priority,
                            notes = notes,
                        )
                    val p =
                        TrainingPreparation(
                            id = original?.id ?: newId(),
                            goalId = goal.id,
                            name = name.trim(),
                            start = start,
                            end = end,
                            archived = original?.archived ?: false,
                        )
                    busy = true
                    vm.action {
                        try {
                            vm.repo.savePreparation(p, goal)
                            withContext(Dispatchers.Main) { dismiss() }
                        } finally {
                            withContext(Dispatchers.Main) { busy = false }
                        }
                    }
                } catch (e: Exception) {
                    error = e.localizedMessage
                }
        },
        if (busy) "Enregistrement…" else "Enregistrer",
    ) {
        Field("Nom de la préparation *", name, { name = it })
        Field("Compétition / objectif *", goalName, { goalName = it })
        DateField("Début", start) { start = it }
        DateField("Fin", end) { end = it }
        DateField("Compétition", race) { race = it }
        Field("Distance (km)", distance, { distance = it }, true)
        Field("Chrono cible (secondes)", target, { target = it }, true)
        Text(
            "Exemple : 34 min 48 s = 2088 secondes. Allure cible : ${SportMath.paceLabel(target.decimal()?.let{s->distance.decimal()?.takeIf{it>0}?.let{s/it}})}",
            style = MaterialTheme.typography.bodySmall,
        )
        Choice(
            "Priorité",
            priority,
            listOf("", "A", "B", "C"),
            text = { it.ifBlank { "Non définie" } },
            change = { priority = it },
        )
        Field("Notes personnelles", notes, { notes = it }, lines = 2)
        ErrorText(error)
    }
}

@Composable
fun PreparationScreen(
    vm: CoachViewModel,
    state: CoachState,
    id: String,
    open: (String) -> Unit,
    create: (String, String) -> Unit,
    planning: (String) -> Unit,
) {
    val p = state.preparations.find { it.id == id } ?: return
    val goal = state.goals.find { it.id == p.goalId } ?: return
    val sessions = state.workouts.filter { it.preparationId == id }
    val start = p.start.asDate().monday()
    val count =
        (ChronoUnit.WEEKS.between(start, p.end.asDate().monday()).toInt() + 1).coerceAtLeast(1)
    val today = LocalDate.now()
    val current = ChronoUnit.WEEKS.between(start, today.monday()).toInt().coerceIn(0, count - 1)
    var week by rememberSaveable(id) { mutableStateOf(current) }
    week = week.coerceIn(0, count - 1)
    var edit by remember { mutableStateOf(false) }
    var share by remember { mutableStateOf(false) }
    var associate by remember { mutableStateOf(false) }
    var all by rememberSaveable { mutableStateOf(false) }
    val volumes = Statistics.weeks(state, start, count, id)
    val done = sessions.count { it.status.isResult }
    val remaining =
        sessions.count { it.status in listOf(Status.PLANNED, Status.POSTPONED, Status.ACTIVE) }
    val duration = ChronoUnit.DAYS.between(p.start.asDate(), p.end.asDate()).coerceAtLeast(1)
    val progress =
        (if (today >= p.end.asDate()) 1f
            else ChronoUnit.DAYS.between(p.start.asDate(), today).toFloat() / duration)
            .coerceIn(0f, 1f)
    LazyColumn(
        Modifier.fillMaxSize().testTag("preparation-screen"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            FilledTonalButton(onClick = { planning(id) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.EditCalendar, null)
                Text("Ouvrir la planification avancée")
            }
        }
        item {
            PageTitle(p.name)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${ChronoUnit.DAYS.between(today,goal.raceDate.asDate()).let{if(it>=0)"J − $it" else "Objectif passé · ${-it} jours"}}",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text("Compétition · ${dateLabel(goal.raceDate)}")
                    Disclosure("Objectif et progression", "Semaine ${current + 1} / $count") {
                        Text(
                            "${goal.distanceKm?.let{"${it.pretty()} km"}?:"Distance libre"} · ${goal.targetSeconds?.let{SportMath.durationLabel(it.toLong())}?:"Chrono libre"} · ${SportMath.paceLabel(goal.targetSeconds?.let{s->goal.distanceKm?.let{s/it}})}"
                        )
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${(progress*100).toInt()} % du temps écoulé · ${current+1} / $count semaines",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "$done réalisées / partielles · $remaining restantes",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item {
            ActionsMenu(
                listOf(
                    MenuAction("Modifier") { edit = true },
                    MenuAction("Partager la préparation") { share = true },
                    MenuAction("Associer une séance existante") { associate = true },
                    MenuAction(if (p.archived) "Désarchiver" else "Archiver") {
                        vm.action { vm.repo.dao.putPreparation(p.copy(archived = !p.archived)) }
                    },
                ),
                "Gérer la préparation",
            )
        }
        item {
            Disclosure(
                "Volumes hebdomadaires",
                "${volumes.sumOf { it.actualKm }.pretty()} km réalisés",
            ) {
                Text(
                    "${volumes.sumOf { it.sessions }} séances réalisées · ${SportMath.durationLabel(volumes.sumOf { it.trainingSeconds }.toLong())} d’entraînement"
                )
                VolumeChart(volumes.map { it.plannedKm }, volumes.map { it.actualKm })
                Text(
                    "Prévu connu (gris) · Réalisé saisi (vert)",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "${volumes.sumOf{it.actualKm}.pretty()} km réalisés · ${volumes.sumOf{it.strengthSessions}} séances de musculation terminées ou partielles",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { week-- }, enabled = week > 0) {
                    Icon(Icons.Default.ChevronLeft, "Semaine précédente")
                }
                Text("Semaine ${week+1} / $count", Modifier.weight(1f))
                IconButton(onClick = { week++ }, enabled = week < count - 1) {
                    Icon(Icons.Default.ChevronRight, "Semaine suivante")
                }
            }
            Disclosure("Navigation avancée") {
                Choice(
                    "Aller à",
                    week,
                    (0 until count).toList(),
                    text = {
                        "Semaine ${it+1} · ${dateLabel(start.plusWeeks(it.toLong()).toString())}"
                    },
                    change = { week = it },
                )
                CheckLine("Afficher toute la préparation", all) { all = it }
            }
        }
        item {
            FlowRow {
                FilledTonalButton(
                    onClick = {
                        val weekStart = start.plusWeeks(week.toLong())
                        val suggested =
                            if (
                                today in weekStart..weekStart.plusDays(6) &&
                                    today in p.start.asDate()..p.end.asDate()
                            )
                                today
                            else maxOf(weekStart, p.start.asDate())
                        create(suggested.toString(), id)
                    }
                ) {
                    Text("Planifier une séance")
                }
            }
        }
        val selectedWeeks = if (all) (0 until count).toList() else listOf(week)
        selectedWeeks.forEach { i ->
            val v = volumes[i]
            val list = sessions.filter { it.date.asDate() in v.start..v.start.plusDays(6) }
            item {
                SectionTitle("S${i+1} · ${dateLabel(v.start.toString())}")
                Text(
                    "${v.plannedKm.pretty()} km prévus · ${v.actualKm.pretty()} km réalisés · ${v.strengthSessions} muscu",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (list.isEmpty())
                item {
                    Text(
                        "Aucune séance planifiée.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            items(list, key = { it.id }) { w ->
                WorkoutCard(w) { open(w.id) }
                val r = state.results[w.id]
                if (w.status.isResult && r != null)
                    Text(
                        "RPE ${r.rpe.ifBlank{"—"}} · ${r.feeling.ifBlank{"Sans ressenti"}}",
                        style = MaterialTheme.typography.bodySmall,
                    )
            }
        }
        val outside =
            sessions.filter {
                it.date.asDate() < start ||
                    it.date.asDate() > start.plusWeeks(count.toLong()).minusDays(1)
            }
        if (outside.isNotEmpty()) {
            item { SectionTitle("Séances associées hors période") }
            items(outside, key = { it.id }) { WorkoutCard(it) { open(it.id) } }
        }
        item {
            if (goal.notes.isNotBlank()) {
                Disclosure("Notes de préparation") { Text(goal.notes) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (edit) PreparationDialog(vm, p, goal, { edit = false })
    if (share) ExportDialog(vm, state, id) { share = false }
    if (associate) {
        var selected by rememberSaveable { mutableStateOf("") }
        FormDialog(
            "Associer une séance",
            { associate = false },
            {
                if (selected.isNotBlank()) {
                    vm.action { vm.repo.associate(selected, id) }
                    associate = false
                }
            },
        ) {
            Choice(
                "Séance",
                selected,
                listOf("") + state.workouts.filter { it.preparationId != id }.map { it.id },
                text = { key ->
                    state.workouts
                        .find { it.id == key }
                        ?.let { "${dateLabel(it.date)} · ${it.name}" } ?: "Choisir"
                },
                change = { selected = it },
            )
            Text(
                "Une séance appartient à une préparation. Cette action remplace son association actuelle."
            )
        }
    }
}

@Composable
fun ExportDialog(vm: CoachViewModel, state: CoachState, id: String, dismiss: () -> Unit) {
    var notes by rememberSaveable { mutableStateOf(false) }
    var performances by rememberSaveable { mutableStateOf(true) }
    var pdf by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    val context = LocalContext.current
    FormDialog(
        "Partager la préparation",
        dismiss,
        {
            if (!busy) {
                busy = true
                vm.action {
                    try {
                        val file =
                            withContext(Dispatchers.IO) {
                                AndroidExporter.write(
                                    context,
                                    PreparationExport.document(
                                        state,
                                        id,
                                        ExportOptions(notes, performances),
                                    ),
                                    pdf,
                                )
                            }
                        withContext(Dispatchers.Main) {
                            AndroidExporter.share(context, file)
                            dismiss()
                        }
                    } finally {
                        withContext(Dispatchers.Main) { busy = false }
                    }
                }
            }
        },
        if (busy) "Génération…" else "Générer et partager",
    ) {
        Choice(
            "Format",
            pdf,
            listOf(true, false),
            text = { if (it) "PDF lisible" else "JSON structuré v2" },
            change = { pdf = it },
        )
        CheckLine("Inclure les performances", performances) { performances = it }
        CheckLine("Inclure les notes et ressentis personnels", notes) { notes = it }
        Text(
            "Le fichier contient toutes les séances associées, semaine par semaine. Le PDF peut être lu sans installer Hybrid Coach."
        )
    }
}
