package fr.hybridcoach.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import java.time.*
import java.time.format.*
import java.util.Locale

@Composable
fun TodayScreen(state: CoachState, open: (String) -> Unit, create: (String) -> Unit) {
    val today = LocalDate.now()
    val day = state.workouts.filter { it.date == today.toString() }
    val active = state.workouts.filter { it.status == Status.ACTIVE && it.date != today.toString() }
    val next =
        state.workouts
            .filter {
                it.date > today.toString() && it.status in listOf(Status.PLANNED, Status.POSTPONED)
            }
            .take(6)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 100.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            PageTitle(
                "Un jour, un progrès.",
                today
                    .format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH))
                    .replaceFirstChar { it.uppercase() },
            )
        }
        if (active.isNotEmpty()) {
            item { SectionTitle("Reprendre une séance") }
            items(active, key = { it.id }) { WorkoutCard(it) { open(it.id) } }
        }
        item { SectionTitle("Aujourd’hui") }
        if (day.isEmpty())
            item {
                EmptyState(
                    "Votre journée est libre",
                    "Planifiez votre prochaine séance ou profitez d’un jour de récupération.",
                ) {
                    FilledTonalButton(onClick = { create(today.toString()) }) {
                        Text("Planifier une séance")
                    }
                }
            }
        else items(day, key = { it.id }) { WorkoutCard(it) { open(it.id) } }
        item { SectionTitle("À venir") }
        if (next.isEmpty())
            item {
                Text(
                    "Vos prochaines séances apparaîtront ici.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        else items(next, key = { it.id }) { WorkoutCard(it) { open(it.id) } }
        val prep =
            state.preparations.firstOrNull {
                !it.archived && today in it.start.asDate()..it.end.asDate()
            }
        if (prep != null)
            item {
                Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            "LE FIL ROUGE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            prep.name,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(
                            "${java.time.temporal.ChronoUnit.DAYS.between(today,prep.end.asDate())} jours jusqu’à la fin de la préparation",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
    }
}

@Composable
fun CalendarScreen(
    vm: CoachViewModel,
    state: CoachState,
    open: (String) -> Unit,
    create: (String) -> Unit,
    planning: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val week = selected.asDate().monday()
    var copy by remember { mutableStateOf(false) }
    var jump by remember { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 100.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            FilledTonalButton(onClick = planning, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.EditCalendar, null)
                Text("Mode Planification")
            }
        }
        item { PageTitle("Votre semaine", "Course, force et récupération, au même endroit.") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selected = selected.asDate().minusWeeks(1).toString() }) {
                    Icon(Icons.Default.ChevronLeft, "Semaine précédente")
                }
                TextButton(onClick = { jump = true }, modifier = Modifier.weight(1f)) {
                    Text(
                        "${dateLabel(week.toString())} — ${dateLabel(week.plusDays(6).toString())}"
                    )
                }
                IconButton(onClick = { selected = selected.asDate().plusWeeks(1).toString() }) {
                    Icon(Icons.Default.ChevronRight, "Semaine suivante")
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(7) { index ->
                    val date = week.plusDays(index.toLong())
                    val count = state.workouts.count { it.date == date.toString() }
                    val chosen = selected == date.toString()
                    Surface(
                        onClick = { selected = date.toString() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        color =
                            if (chosen) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(
                            Modifier.padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                date
                                    .format(DateTimeFormatter.ofPattern("EE", Locale.FRENCH))
                                    .take(1)
                                    .uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                "${date.dayOfMonth}",
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                            Text(
                                if (count > 0) "•" else " ",
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
        item {
            Row {
                TextButton(onClick = { selected = LocalDate.now().toString() }) {
                    Text("Aujourd’hui")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { copy = true }) { Text("Répéter la semaine") }
            }
        }
        item {
            SectionTitle(dateLabel(selected)) {
                IconButton(onClick = { create(selected) }) {
                    Icon(Icons.Default.Add, "Planifier ce jour")
                }
            }
        }
        val list = state.workouts.filter { it.date == selected }
        if (list.isEmpty())
            item {
                EmptyState("Rien de prévu", "Ajoutez une séance à cette journée.") {
                    TextButton(onClick = { create(selected) }) { Text("Ajouter une séance") }
                }
            }
        else items(list, key = { it.id }) { WorkoutCard(it) { open(it.id) } }
        item { SectionTitle("Toute la semaine") }
        repeat(7) { index ->
            val day = week.plusDays(index.toLong())
            val sessions = state.workouts.filter { it.date == day.toString() }
            item {
                Column(
                    Modifier.fillMaxWidth()
                        .clickable { selected = day.toString() }
                        .padding(vertical = 6.dp)
                ) {
                    Text(
                        dateLabel(day.toString()),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        if (sessions.isEmpty()) "—" else sessions.joinToString(" · ") { it.name },
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
            }
        }
    }
    if (jump)
        FormDialog("Aller à une date", { jump = false }, { jump = false }, "Afficher") {
            DateField("Date", selected) { selected = it }
        }
    if (copy) {
        var target by rememberSaveable { mutableStateOf(week.plusWeeks(1).toString()) }
        var repeats by rememberSaveable { mutableStateOf("1") }
        var error by remember { mutableStateOf<String?>(null) }
        FormDialog(
            "Répéter cette semaine",
            { copy = false },
            {
                val n = repeats.toIntOrNull()
                if (n == null || n !in 1..104) error = "Choisissez 1 à 104 semaines."
                else {
                    vm.action { vm.repo.copyWeek(week, target.asDate(), n) }
                    copy = false
                }
            },
            "Créer les copies",
        ) {
            Text("Le contenu prévu est copié. Les performances restent dans la semaine d’origine.")
            DateField("Première semaine cible", target) { target = it }
            Field("Nombre de semaines", repeats, { repeats = it }, true)
            ErrorText(error)
        }
    }
}

@Composable
fun HistoryScreen(state: CoachState, open: (String) -> Unit) {
    var sport by rememberSaveable { mutableStateOf("Tous") }
    var prep by rememberSaveable { mutableStateOf("") }
    var period by rememberSaveable { mutableStateOf(90) }
    val today = LocalDate.now()
    val list =
        state.workouts
            .filter {
                it.status.isResult &&
                    (sport == "Tous" || it.sport.label == sport) &&
                    (prep.isBlank() || it.preparationId == prep) &&
                    (period == 0 || it.date.asDate() >= today.minusDays(period.toLong()))
            }
            .sortedByDescending { it.date }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 100.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { PageTitle("Votre carnet", "Le travail accompli, séance après séance.") }
        item {
            Choice(
                "Sport",
                sport,
                listOf("Tous") + Sport.entries.map { it.label },
                change = { sport = it },
            )
        }
        item {
            Choice(
                "Période",
                period,
                listOf(7, 30, 90, 365, 0),
                text = { if (it == 0) "Tout" else "$it jours" },
                change = { period = it },
            )
        }
        item {
            Choice(
                "Préparation",
                prep,
                listOf("") + state.preparations.map { it.id },
                text = { id -> state.preparations.find { it.id == id }?.name ?: "Toutes" },
                change = { prep = it },
            )
        }
        if (list.isEmpty())
            item {
                EmptyState(
                    "Le carnet attend vos séances",
                    "Vos séances terminées ou partiellement réalisées apparaîtront ici.",
                )
            }
        else items(list, key = { it.id }) { w -> WorkoutCard(w) { open(w.id) } }
    }
}

@Composable
fun TemplatesScreen(
    vm: CoachViewModel,
    state: CoachState,
    schedule: (String) -> Unit,
    edit: (String) -> Unit,
) {
    var delete by remember { mutableStateOf<WorkoutTemplate?>(null) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            PageTitle("Vos modèles", "Créez une fois. Planifiez à votre rythme.")
            Button(onClick = { edit("new") }) {
                Icon(Icons.Default.Add, null)
                Text("Créer un modèle")
            }
        }
        if (state.templates.isEmpty())
            item {
                EmptyState(
                    "Votre bibliothèque de séances",
                    "Enregistrez une séance comme modèle ou créez-en un ici.",
                )
            }
        items(state.templates, key = { it.id }) { t ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(t.name, style = MaterialTheme.typography.titleMedium)
                    Text(t.sport.label, color = MaterialTheme.colorScheme.primary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { schedule(t.id) }) { Text("Planifier") }
                        TextButton(onClick = { edit(t.id) }) { Text("Modifier") }
                        TextButton(
                            onClick = {
                                vm.action {
                                    vm.repo.dao.putTemplate(
                                        t.copy(id = newId(), name = "${t.name} (copie)")
                                    )
                                }
                            }
                        ) {
                            Text("Dupliquer")
                        }
                        TextButton(onClick = { delete = t }) { Text("Supprimer") }
                    }
                }
            }
        }
    }
    delete?.let { t ->
        AlertDialog(
            onDismissRequest = { delete = null },
            title = { Text("Supprimer ce modèle ?") },
            text = {
                Text("Les séances déjà planifiées sont indépendantes et conservent leur contenu.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.action { vm.repo.dao.deleteTemplate(t.id) }
                        delete = null
                    }
                ) {
                    Text("Supprimer")
                }
            },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Annuler") } },
        )
    }
}
