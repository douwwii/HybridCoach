package fr.hybridcoach.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import java.time.*
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

private fun optionalNumber(value: String): Double? {
    if (value.isBlank()) return null
    return requireNotNull(value.decimal()) { "Nombre invalide : $value" }
}

@Composable
fun WorkoutEditor(
    vm: CoachViewModel,
    state: CoachState,
    id: String,
    initialDate: String,
    templateParam: String?,
    prepParam: String?,
    back: () -> Unit,
) {
    val templateMode = templateParam == "edit"
    val original = state.workouts.find { it.id == id }.takeUnless { templateMode }
    val template = state.templates.find { it.id == if (templateMode) id else templateParam }
    val profile by vm.profile.collectAsStateWithLifecycle()
    val planInitial =
        original?.planJson
            ?: codec.encodeToString(
                template?.let { parsePlan(it.planJson).freshCopy() } ?: WorkoutPlan()
            )
    var planRaw by rememberSaveable(id, templateParam) { mutableStateOf(planInitial) }
    val plan = parsePlan(planRaw)
    fun change(p: WorkoutPlan) {
        planRaw = codec.encodeToString(p)
    }
    var name by
        rememberSaveable(id, templateParam) {
            mutableStateOf(original?.name ?: template?.name ?: "")
        }
    var sportName by
        rememberSaveable(id, templateParam) {
            mutableStateOf((original?.sport ?: template?.sport ?: Sport.RUN).name)
        }
    val sport = Sport.valueOf(sportName)
    var date by
        rememberSaveable(id, templateParam) { mutableStateOf(original?.date ?: initialDate) }
    var description by
        rememberSaveable(id, templateParam) {
            mutableStateOf(original?.description ?: template?.description ?: "")
        }
    var priority by rememberSaveable(id, templateParam) { mutableStateOf(original?.priority ?: "") }
    var notes by rememberSaveable(id, templateParam) { mutableStateOf(original?.notes ?: "") }
    var prep by
        rememberSaveable(id, templateParam) {
            mutableStateOf(original?.preparationId ?: prepParam ?: "")
        }
    var selectedGoals by
        rememberSaveable(id, templateParam) {
            mutableStateOf(
                state.workoutSubGoals
                    .filter { it.workoutId == id }
                    .joinToString(",") { it.subGoalId }
            )
        }
    var quickBlock by remember { mutableStateOf(false) }
    var plannedTime by
        rememberSaveable(id, templateParam) { mutableStateOf(original?.plannedTime ?: "") }
    var recurrence by rememberSaveable { mutableStateOf(false) }
    var end by rememberSaveable { mutableStateOf(date.asDate().plusWeeks(8).toString()) }
    var daysRaw by rememberSaveable { mutableStateOf(date.asDate().dayOfWeek.name) }
    val days = daysRaw.split(',').filter { it.isNotBlank() }.map { DayOfWeek.valueOf(it) }.toSet()
    var future by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var editingStrength by remember { mutableStateOf<StrengthExercise?>(null) }
    var stepEdit by remember { mutableStateOf<RunningStep?>(null) }
    var stepParent by remember { mutableStateOf<String?>(null) }
    var stepNew by remember { mutableStateOf(false) }
    val stableId by rememberSaveable { mutableStateOf(newId()) }
    fun save() {
        if (busy) return
        try {
            if (plannedTime.isNotBlank()) LocalTime.parse(plannedTime)
            require(name.isNotBlank()) { "Donnez un nom à la séance." }
            val snapshot =
                WorkoutPlan(
                    strength = if (sport == Sport.STRENGTH) plan.strength else emptyList(),
                    running =
                        if (sport in listOf(Sport.RUN, Sport.RACE, Sport.OTHER, Sport.STRENGTH))
                            plan.running.map { SportMath.snapshot(it, profile) }
                        else emptyList(),
                )
            validatePlan(snapshot)
            require(!recurrence || !end.asDate().isBefore(date.asDate())) {
                "La récurrence se termine avant son début."
            }
            require(!recurrence || days.isNotEmpty()) { "Choisissez au moins un jour." }
            busy = true
            vm.action {
                try {
                    if (templateMode)
                        vm.repo.dao.putTemplate(
                            WorkoutTemplate(
                                id = template?.id ?: stableId,
                                name = name.trim(),
                                sport = sport,
                                description = description,
                                planJson = codec.encodeToString(snapshot),
                            )
                        )
                    else
                        vm.repo.saveWorkout(
                            PlannedWorkout(
                                id = original?.id ?: stableId,
                                name = name.trim(),
                                date = date,
                                sport = sport,
                                description = description,
                                priority = priority,
                                notes = notes,
                                preparationId = prep.takeIf { it.isNotBlank() },
                                status = original?.status ?: Status.PLANNED,
                                planJson = codec.encodeToString(snapshot),
                                seriesId = original?.seriesId,
                                plannedTime = plannedTime.takeIf { it.isNotBlank() },
                            ),
                            end.asDate().takeIf { recurrence },
                            days,
                            future,
                            selectedGoals.split(',').filter { it.isNotBlank() }.toSet(),
                        )
                    withContext(Dispatchers.Main) { back() }
                } finally {
                    withContext(Dispatchers.Main) { busy = false }
                }
            }
        } catch (e: Exception) {
            error = e.localizedMessage
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().imePadding().testTag("workout-editor"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PageTitle(
                if (templateMode) "Composer un modèle"
                else if (original != null) "Ajuster la séance" else "Une nouvelle séance"
            )
            ErrorText(error)
        }
        item {
            Field("Nom de la séance *", name, { name = it })
            Choice(
                "Type",
                sport,
                Sport.entries,
                text = { it.label },
                change = { sportName = it.name },
            )
        }
        if (!templateMode) item { DateField("Date", date) { date = it } }
        if (sport == Sport.STRENGTH) {
            item {
                SectionTitle("Exercices ordonnés") {
                    TextButton(onClick = { picker = true }) {
                        Icon(Icons.Default.Add, null)
                        Text("Ajouter")
                    }
                }
            }
            if (plan.strength.isEmpty())
                item {
                    EmptyState(
                        "Composez votre séance",
                        "Choisissez des mouvements dans la bibliothèque.",
                    ) {
                        TextButton(onClick = { picker = true }) { Text("Choisir un exercice") }
                    }
                }
            itemsIndexed(plan.strength, key = { _, e -> e.id }) { index, e ->
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (e.group.isNotBlank())
                            Text(
                                "BLOC ${e.group} · ${plan.strength.count{it.group==e.group}} exercices",
                                color = MaterialTheme.colorScheme.secondary,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        Text(e.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${e.sets} × ${e.reps} · ${e.load?.let{"$it kg"}?:"Charge libre"} · ${e.restSeconds}s repos"
                        )
                        if (e.mode != "Standard")
                            Text(e.mode, style = MaterialTheme.typography.bodySmall)
                        Row {
                            IconButton(
                                onClick = {
                                    if (index > 0) {
                                        val list = plan.strength.toMutableList()
                                        java.util.Collections.swap(list, index, index - 1)
                                        change(plan.copy(strength = list))
                                    }
                                },
                                enabled = index > 0,
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, "Monter l’exercice")
                            }
                            IconButton(
                                onClick = {
                                    if (index < plan.strength.lastIndex) {
                                        val list = plan.strength.toMutableList()
                                        java.util.Collections.swap(list, index, index + 1)
                                        change(plan.copy(strength = list))
                                    }
                                },
                                enabled = index < plan.strength.lastIndex,
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, "Descendre l’exercice")
                            }
                            TextButton(onClick = { editingStrength = e }) { Text("Détails") }
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    change(
                                        plan.copy(
                                            strength = plan.strength.filterNot { it.id == e.id }
                                        )
                                    )
                                }
                            ) {
                                Icon(Icons.Default.DeleteOutline, "Retirer l’exercice")
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "Pour un biset, superset ou triset, donnez le même nom de bloc aux exercices concernés (ex. A). Les blocs sont regroupés pendant la séance.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (sport in listOf(Sport.RUN, Sport.RACE, Sport.OTHER, Sport.STRENGTH)) {
            item {
                SectionTitle("Étapes & répétitions") {
                    TextButton(
                        onClick = {
                            stepNew = true
                            stepParent = null
                            stepEdit = RunningStep()
                        }
                    ) {
                        Icon(Icons.Default.Add, null)
                        Text("Étape")
                    }
                }
            }
            item {
                FilledTonalButton(onClick = { quickBlock = true }) {
                    Icon(Icons.Default.Repeat, null)
                    Text("Composer un fractionné")
                }
            }
            if (plan.running.isEmpty())
                item {
                    Text(
                        "Ajoutez une étape ou un fractionné.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            item {
                RunningTree(
                    plan.running,
                    onEdit = {
                        stepNew = false
                        stepParent = null
                        stepEdit = it
                    },
                    onAdd = { parent ->
                        stepNew = true
                        stepParent = parent
                        stepEdit = RunningStep()
                    },
                    onDelete = { key ->
                        change(plan.copy(running = removeStep(plan.running, key)))
                    },
                    onDuplicate = { key ->
                        change(plan.copy(running = duplicateStep(plan.running, key)))
                    },
                    onMove = { key, delta ->
                        change(plan.copy(running = moveStep(plan.running, key, delta)))
                    },
                )
            }
            if (plan.running.isNotEmpty())
                item {
                    val snapshot = plan.running.map { SportMath.snapshot(it, profile) }
                    Text(
                        "Volume connu : ${(SportMath.plannedMeters(snapshot)/1000).pretty()} km · ${SportMath.durationLabel(SportMath.plannedSeconds(snapshot).toLong())}",
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
        }
        if (!templateMode) {
            item {
                Disclosure(
                    "Organisation de la séance",
                    listOfNotNull(
                            plannedTime.takeIf { it.isNotBlank() },
                            state.preparations.find { it.id == prep }?.name,
                        )
                        .joinToString(" · ")
                        .ifBlank { "Heure, préparation et sous-objectifs" },
                ) {
                    Field("Heure prévue (HH:mm, facultatif)", plannedTime, { plannedTime = it })
                    Choice(
                        "Préparation",
                        prep,
                        listOf("") + state.preparations.map { it.id },
                        text = { key ->
                            state.preparations.find { it.id == key }?.name ?: "Aucune"
                        },
                        change = {
                            prep = it
                            selectedGoals = ""
                        },
                    )
                    if (prep.isNotBlank()) {
                        Text(
                            "Sous-objectifs · sélection multiple",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        state.subGoals
                            .filter { it.preparationId == prep }
                            .forEach { goal ->
                                CheckLine(
                                    "${goal.name} · ${dateLabel(goal.start)} → ${dateLabel(goal.end)}",
                                    goal.id in selectedGoals.split(','),
                                ) { checked ->
                                    val ids =
                                        selectedGoals.split(',').filter { it.isNotBlank() }.toSet()
                                    selectedGoals =
                                        (if (checked) ids + goal.id else ids - goal.id)
                                            .joinToString(",")
                                }
                            }
                    }
                    Choice(
                        "Priorité",
                        priority,
                        listOf("", "Haute", "Normale", "Basse"),
                        text = { it.ifBlank { "Non définie" } },
                        change = { priority = it },
                    )
                }
            }
        }
        item {
            Disclosure(
                "Description de la séance",
                description.takeIf { it.isNotBlank() }?.take(70),
            ) {
                Field("Description", description, { description = it }, lines = 2)
            }
        }
        if (!templateMode) {
            item {
                Disclosure("Notes personnelles", notes.takeIf { it.isNotBlank() }?.take(70)) {
                    Field("Votre note", notes, { notes = it }, lines = 2)
                }
            }
            if (original == null || original.seriesId != null)
                item {
                    Disclosure(
                        "Répétition et occurrences",
                        if (recurrence) "Récurrence activée" else "Séance unique",
                    ) {
                        if (original == null) {
                            CheckLine("Récurrence hebdomadaire", recurrence) { recurrence = it }
                            if (recurrence) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    DateField("Jusqu’au", end) { end = it }
                                    FlowRow {
                                        DayOfWeek.entries.forEach { day ->
                                            FilterChip(
                                                selected = day in days,
                                                onClick = {
                                                    daysRaw =
                                                        (if (day in days) days - day
                                                            else days + day)
                                                            .joinToString(",") { it.name }
                                                },
                                                label = {
                                                    Text(
                                                        day.getDisplayName(
                                                            TextStyle.SHORT,
                                                            Locale.FRENCH,
                                                        )
                                                    )
                                                },
                                                modifier = Modifier.padding(end = 4.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (original?.seriesId != null) {
                            CheckLine("Appliquer aux occurrences futures non commencées", future) {
                                future = it
                            }
                            Text(
                                "Le décalage de date s’applique aussi aux occurrences futures.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
        }
        item {
            Button(onClick = { save() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (busy) "Enregistrement…"
                    else if (templateMode) "Enregistrer le modèle" else "Enregistrer la séance"
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (quickBlock)
        IntervalBlockDialog(profile, { quickBlock = false }) { steps ->
            change(plan.copy(running = plan.running + steps))
            quickBlock = false
        }
    if (picker)
        ExercisePicker(state.exercises, { picker = false }) { e ->
            picker = false
            editingStrength = StrengthExercise(exerciseId = e.id, name = e.name, muscle = e.muscle)
        }
    editingStrength?.let { e ->
        StrengthDialog(e, { editingStrength = null }) { next ->
            change(
                plan.copy(
                    strength =
                        if (plan.strength.any { it.id == next.id })
                            plan.strength.map { if (it.id == next.id) next else it }
                        else plan.strength + next
                )
            )
            editingStrength = null
        }
    }
    stepEdit?.let { s ->
        RunningStepDialog(s, profile, { stepEdit = null }) { next ->
            val list =
                if (stepNew)
                    if (stepParent == null) plan.running + next
                    else
                        updateStep(plan.running, stepParent!!) {
                            it.copy(children = it.children + next)
                        }
                else updateStep(plan.running, next.id) { next }
            change(plan.copy(running = list))
            stepEdit = null
        }
    }
}

@Composable
fun ExercisePicker(exercises: List<Exercise>, dismiss: () -> Unit, select: (Exercise) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var muscle by rememberSaveable { mutableStateOf("Tous") }
    AlertDialog(
        onDismissRequest = dismiss,
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(.92f),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Choisir un exercice") },
        text = {
            Column {
                Field("Rechercher", search, { search = it })
                Choice(
                    "Muscle",
                    muscle,
                    listOf("Tous") + exercises.map { it.muscle }.distinct().sorted(),
                    change = { muscle = it },
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(
                        exercises.filter {
                            it.name.contains(search, true) &&
                                (muscle == "Tous" || it.muscle == muscle)
                        },
                        key = { it.id },
                    ) { e ->
                        TextButton(onClick = { select(e) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(e.name)
                                Text(
                                    "${e.muscle} · ${e.equipment}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Fermer") } },
    )
}

@Composable
fun StrengthDialog(
    original: StrengthExercise,
    dismiss: () -> Unit,
    save: (StrengthExercise) -> Unit,
) {
    var sets by rememberSaveable { mutableStateOf(original.sets.toString()) }
    var reps by rememberSaveable { mutableStateOf(original.reps) }
    var load by rememberSaveable { mutableStateOf(original.load?.toString() ?: "") }
    var rest by rememberSaveable { mutableStateOf(original.restSeconds.toString()) }
    var rir by rememberSaveable { mutableStateOf(original.rir?.toString() ?: "") }
    var rpe by rememberSaveable { mutableStateOf(original.rpe?.toString() ?: "") }
    var tempo by rememberSaveable { mutableStateOf(original.tempo) }
    var notes by rememberSaveable { mutableStateOf(original.notes) }
    var mode by rememberSaveable { mutableStateOf(original.mode) }
    var group by rememberSaveable { mutableStateOf(original.group) }
    var error by remember { mutableStateOf<String?>(null) }
    FormDialog(
        original.name,
        dismiss,
        {
            try {
                val e =
                    original.copy(
                        sets = requireNotNull(sets.toIntOrNull()) { "Nombre de séries invalide." },
                        reps = reps,
                        load = optionalNumber(load),
                        restSeconds = requireNotNull(rest.toIntOrNull()) { "Repos invalide." },
                        rir = optionalNumber(rir),
                        rpe = optionalNumber(rpe),
                        tempo = tempo,
                        notes = notes,
                        mode = mode,
                        group = group.trim(),
                    )
                validatePlan(WorkoutPlan(strength = listOf(e)))
                save(e)
            } catch (e: Exception) {
                error = e.localizedMessage
            }
        },
    ) {
        Field("Séries *", sets, { sets = it }, true)
        Field("Répétitions / plage *", reps, { reps = it })
        Field("Charge cible (kg)", load, { load = it }, true)
        Field("Repos (secondes)", rest, { rest = it }, true)
        Disclosure("Options de l’exercice", "Mode, superset, effort, tempo et notes") {
            Choice(
                "Mode",
                mode,
                listOf("Standard", "Unilatéral", "Poids du corps", "Lesté", "Assisté"),
                change = { mode = it },
            )
            Field("Bloc superset / biset / triset", group, { group = it })
            Field("RIR cible", rir, { rir = it }, true)
            Field("RPE cible", rpe, { rpe = it }, true)
            Field("Tempo", tempo, { tempo = it })
            Field("Notes", notes, { notes = it }, lines = 2)
        }
        ErrorText(error)
    }
}

private fun updateStep(
    nodes: List<RunningStep>,
    id: String,
    transform: (RunningStep) -> RunningStep,
): List<RunningStep> =
    nodes.map {
        if (it.id == id) transform(it)
        else it.copy(children = updateStep(it.children, id, transform))
    }

private fun removeStep(nodes: List<RunningStep>, id: String): List<RunningStep> =
    nodes.filterNot { it.id == id }.map { it.copy(children = removeStep(it.children, id)) }

private fun moveStep(nodes: List<RunningStep>, id: String, delta: Int): List<RunningStep> {
    val index = nodes.indexOfFirst { it.id == id }
    if (index >= 0) {
        val list = nodes.toMutableList()
        val target = index + delta
        if (target in list.indices) java.util.Collections.swap(list, index, target)
        return list
    }
    return nodes.map { it.copy(children = moveStep(it.children, id, delta)) }
}

@Composable
fun RunningTree(
    nodes: List<RunningStep>,
    onEdit: (RunningStep) -> Unit,
    onAdd: (String) -> Unit,
    onDelete: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onDuplicate: (String) -> Unit,
    level: Int = 0,
) {
    nodes.forEachIndexed { index, s ->
        Card(Modifier.fillMaxWidth().padding(start = if (level > 0) 12.dp else 0.dp, top = 6.dp)) {
            Column(Modifier.padding(10.dp)) {
                Text(
                    stepLabel(s),
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (s.kind == StepKind.REPEAT) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.onSurface,
                )
                FlowRow {
                    IconButton(onClick = { onMove(s.id, -1) }, enabled = index > 0) {
                        Icon(Icons.Default.KeyboardArrowUp, "Monter l’étape")
                    }
                    IconButton(onClick = { onMove(s.id, 1) }, enabled = index < nodes.lastIndex) {
                        Icon(Icons.Default.KeyboardArrowDown, "Descendre l’étape")
                    }
                    TextButton(onClick = { onEdit(s) }) { Text("Modifier") }
                    IconButton(onClick = { onDuplicate(s.id) }) {
                        Icon(Icons.Default.ContentCopy, "Dupliquer le bloc")
                    }
                    IconButton(onClick = { onDelete(s.id) }) {
                        Icon(Icons.Default.Close, "Retirer l’étape")
                    }
                }
                if (s.kind == StepKind.REPEAT) {
                    RunningTree(s.children, onEdit, onAdd, onDelete, onMove, onDuplicate, level + 1)
                    s.recovery?.let {
                        Text(
                            "Entre les répétitions : ${stepLabel(it)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { onAdd(s.id) }, enabled = level < 5) {
                        Text("+ Étape dans ce bloc")
                    }
                }
            }
        }
    }
}

@Composable
fun RunningStepDialog(
    original: RunningStep,
    profile: AthleteProfile,
    dismiss: () -> Unit,
    save: (RunningStep) -> Unit,
) {
    var kind by rememberSaveable { mutableStateOf(original.kind.name) }
    var duration by rememberSaveable { mutableStateOf(original.durationKind.name) }
    var amount by rememberSaveable { mutableStateOf(original.amount?.toString() ?: "") }
    var targetKind by rememberSaveable { mutableStateOf(original.targetKind.name) }
    var target by rememberSaveable { mutableStateOf(original.target?.toString() ?: "") }
    var targetMax by rememberSaveable { mutableStateOf(original.targetMax?.toString() ?: "") }
    var reference by rememberSaveable { mutableStateOf(original.reference) }
    var active by rememberSaveable { mutableStateOf(original.activeRecovery) }
    var repetitions by rememberSaveable { mutableStateOf(original.repetitions.toString()) }
    var notes by rememberSaveable { mutableStateOf(original.notes) }
    var recoveryEnabled by rememberSaveable { mutableStateOf(original.recovery != null) }
    var recoveryAmount by rememberSaveable {
        mutableStateOf(original.recovery?.amount?.toString() ?: "120")
    }
    var recoveryKind by rememberSaveable {
        mutableStateOf((original.recovery?.durationKind ?: DurationKind.TIME).name)
    }
    var recoveryActive by rememberSaveable {
        mutableStateOf(original.recovery?.activeRecovery ?: true)
    }
    var error by remember { mutableStateOf<String?>(null) }
    val k = StepKind.valueOf(kind)
    val tk = TargetKind.valueOf(targetKind)
    fun make() =
        SportMath.snapshot(
            original.copy(
                kind = k,
                durationKind = DurationKind.valueOf(duration),
                amount =
                    if (k == StepKind.REPEAT || duration == DurationKind.MANUAL.name) null
                    else optionalNumber(amount),
                targetKind = tk,
                target =
                    if (k == StepKind.REPEAT || tk in listOf(TargetKind.NONE, TargetKind.REFERENCE))
                        null
                    else optionalNumber(target),
                targetMax =
                    if (tk == TargetKind.PACE_RANGE && k != StepKind.REPEAT)
                        optionalNumber(targetMax)
                    else null,
                reference = reference,
                activeRecovery = active,
                repetitions = repetitions.toIntOrNull() ?: 1,
                notes = notes,
                children = if (k == StepKind.REPEAT) original.children else emptyList(),
                recovery =
                    if (k == StepKind.REPEAT && recoveryEnabled)
                        RunningStep(
                            id = original.recovery?.id ?: newId(),
                            kind = StepKind.RECOVERY,
                            durationKind = DurationKind.valueOf(recoveryKind),
                            amount = optionalNumber(recoveryAmount),
                            activeRecovery = recoveryActive,
                        )
                    else null,
            ),
            profile,
        )
    FormDialog(
        "Étape structurée",
        dismiss,
        {
            try {
                val s = make()
                if (s.kind != StepKind.REPEAT) validatePlan(WorkoutPlan(running = listOf(s)))
                else
                    require(repetitions.toIntOrNull()?.let { it in 1..100 } == true) {
                        "1 à 100 répétitions."
                    }
                s.recovery?.let { validatePlan(WorkoutPlan(running = listOf(it))) }
                save(s)
            } catch (e: Exception) {
                error = e.localizedMessage
            }
        },
    ) {
        Choice("Étape", k, StepKind.entries, text = { it.label }, change = { kind = it.name })
        if (k == StepKind.REPEAT) {
            Field("Nombre de répétitions", repetitions, { repetitions = it }, true)
            Text("Ajoutez ensuite les étapes à l’intérieur du bloc.")
            CheckLine("Récupération entre les répétitions", recoveryEnabled) {
                recoveryEnabled = it
            }
            if (recoveryEnabled) {
                Choice(
                    "Récupération",
                    DurationKind.valueOf(recoveryKind),
                    listOf(DurationKind.TIME, DurationKind.DISTANCE),
                    text = { it.label },
                    change = { recoveryKind = it.name },
                )
                Field(
                    if (recoveryKind == DurationKind.TIME.name) "Récupération (secondes)"
                    else "Récupération (mètres)",
                    recoveryAmount,
                    { recoveryAmount = it },
                    true,
                )
                CheckLine("Récupération active", recoveryActive) { recoveryActive = it }
                Text("Aucune récupération ajoutée après la dernière répétition.")
            }
        } else {
            Choice(
                "Durée",
                DurationKind.valueOf(duration),
                DurationKind.entries,
                text = { it.label },
                change = { duration = it.name },
            )
            if (duration != DurationKind.MANUAL.name)
                Field(
                    if (duration == DurationKind.TIME.name) "Secondes" else "Mètres",
                    amount,
                    { amount = it },
                    true,
                )
            Choice(
                "Cible",
                tk,
                TargetKind.entries,
                text = { it.label },
                change = { targetKind = it.name },
            )
            if (tk == TargetKind.REFERENCE)
                Choice(
                    "Allure",
                    reference,
                    listOf("5 km", "10 km", "Seuil", "Semi", "Marathon"),
                    change = { reference = it },
                )
            else if (tk != TargetKind.NONE) Field(tk.label, target, { target = it }, true)
            if (tk == TargetKind.PACE_RANGE)
                Field(
                    "Allure maximale (s/km, valeur supérieure)",
                    targetMax,
                    { targetMax = it },
                    true,
                )
            if (k == StepKind.RECOVERY) CheckLine("Récupération active", active) { active = it }
            val preview = runCatching { make() }.getOrNull()
            if (preview?.resolvedPace != null)
                Text(
                    "Allure calculée : ${SportMath.paceLabel(preview.resolvedPace)}" +
                        (if (preview.durationKind == DurationKind.DISTANCE)
                            " · ${SportMath.durationLabel(SportMath.targetSeconds(preview.amount?:0.0,preview.resolvedPace).toLong())}"
                        else ""),
                    color = MaterialTheme.colorScheme.primary,
                )
        }
        Field("Notes d’étape", notes, { notes = it }, lines = 2)
        ErrorText(error)
    }
}
