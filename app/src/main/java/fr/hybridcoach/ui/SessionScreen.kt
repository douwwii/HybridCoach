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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.hybridcoach.RestAlarm
import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import kotlinx.coroutines.delay

@Composable
fun SessionScreen(
    vm: CoachViewModel,
    state: CoachState,
    id: String,
    edit: (String) -> Unit,
    back: () -> Unit,
) {
    val w = state.workouts.find { it.id == id }
    if (w == null) {
        EmptyState("Séance supprimée", "Revenez au calendrier.") {
            TextButton(onClick = back) { Text("Retour") }
        }
        return
    }
    val plan = parsePlan(w.planJson)
    val result = state.results[id] ?: WorkoutResult()
    val active = w.status == Status.ACTIVE
    var correcting by rememberSaveable(id) { mutableStateOf(false) }
    val editable = active || correcting
    var move by remember { mutableStateOf(false) }
    var duplicate by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var finish by remember { mutableStateOf(false) }
    var intervals by rememberSaveable(id) { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val context = LocalContext.current
    LaunchedEffect(active, result.startedAt, result.restUntil) {
        if (active) {
            while (true) {
                now = System.currentTimeMillis()
                val until = result.restUntil
                if (until != null && now >= until) {
                    RestAlarm.fire(context, id, until)
                    vm.rest(id, null)
                    break
                }
                delay(500)
            }
        }
    }
    val elapsed = ((result.finishedAt ?: now) - (result.startedAt ?: now)).coerceAtLeast(0) / 1000
    LazyColumn(
        Modifier.fillMaxSize().imePadding().testTag("workout-session"),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PageTitle(w.name, "${w.sport.label} · ${dateLabel(w.date)} · ${w.status.label}")
            if (w.priority.isNotBlank())
                Text("Priorité : ${w.priority}", style = MaterialTheme.typography.bodySmall)
            if (w.description.isNotBlank()) Text(w.description)
        }
        item {
            FlowRow {
                if (!w.status.isResult && !active && w.status != Status.CANCELLED) {
                    Button(onClick = { vm.action { vm.repo.start(id) } }) {
                        Icon(Icons.Default.PlayArrow, null)
                        Text("Démarrer la séance")
                    }
                    TextButton(onClick = { edit(id) }) { Text("Modifier") }
                }
                if (w.status.isResult)
                    TextButton(
                        onClick = {
                            if (correcting)
                                vm.action {
                                    validateResult(parseResult(vm.repo.dao.result(id)?.payload))
                                    correcting = false
                                }
                            else correcting = true
                        }
                    ) {
                        Text(if (correcting) "Fin des corrections" else "Corriger les résultats")
                    }
                TextButton(onClick = { duplicate = true }) { Text("Dupliquer") }
                TextButton(
                    onClick = {
                        vm.action {
                            vm.repo.saveTemplate(w)
                            vm.notice("Modèle ajouté à la bibliothèque")
                        }
                    }
                ) {
                    Text("Enregistrer comme modèle")
                }
                if (!active && !w.status.isResult)
                    TextButton(onClick = { move = true }) { Text("Déplacer") }
                TextButton(onClick = { delete = true }) { Text("Supprimer") }
            }
        }
        if (active) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row {
                            Icon(
                                Icons.Default.Timer,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "  ${SportMath.durationLabel(elapsed)}",
                                style = MaterialTheme.typography.headlineSmall,
                            )
                        }
                        Text(
                            "Sauvegarde automatique · vous pouvez quitter et reprendre.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        result.restUntil?.let { until ->
                            val remaining = ((until - now + 999) / 1000).coerceAtLeast(0)
                            Text(
                                "Repos · ${SportMath.durationLabel(remaining)}",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Row {
                                TextButton(
                                    onClick = {
                                        vm.rest(
                                            id,
                                            maxOf(until, System.currentTimeMillis()) + 30000,
                                        )
                                    }
                                ) {
                                    Text("+ 30 s")
                                }
                                TextButton(onClick = { vm.rest(id, null) }) {
                                    Text("Passer le repos")
                                }
                            }
                        }
                    }
                }
            }
        } else if (result.startedAt != null)
            item {
                Text(
                    "Durée de séance : ${SportMath.durationLabel(elapsed)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        if (plan.strength.isNotEmpty()) {
            item { SectionTitle("Votre séance de force") }
            // Consecutive members of the same named superset render under one shared block header.
            val ordered =
                plan.strength.sortedBy { e ->
                    if (e.group.isBlank()) plan.strength.indexOf(e)
                    else plan.strength.indexOfFirst { it.group == e.group }
                }
            ordered.forEachIndexed { index, e ->
                if (e.group.isNotBlank() && (index == 0 || ordered[index - 1].group != e.group))
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "BLOC ${e.group} · ${when(plan.strength.count{it.group==e.group}){2->"Biset / superset"
3->"Triset"
else->"Circuit"}}",
                                modifier = Modifier.padding(14.dp),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                item(key = "exercise-${e.id}") {
                    Column {
                        Text(e.name, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${e.sets} × ${e.reps} · ${e.load?.let{"$it kg"}?:"Charge libre"} · repos ${e.restSeconds}s",
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "${e.mode} · RIR ${e.rir?:"—"} · RPE ${e.rpe?:"—"} · tempo ${e.tempo.ifBlank{"—"}}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (e.notes.isNotBlank())
                            Text(e.notes, style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.padding(top = 12.dp)) {
                            Text(
                                "Série / précédente",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                "Charge kg",
                                Modifier.width(90.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                "Rép.",
                                Modifier.width(76.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Spacer(Modifier.width(48.dp))
                        }
                    }
                }
                val previous = Statistics.previousSets(state, w, e)
                val count = result.setCounts[e.id] ?: e.sets
                items(count, key = { i -> "${e.id}-$i" }) { i ->
                    val log =
                        result.sets.find { it.exerciseInstanceId == e.id && it.index == i }
                            ?: SetLog(e.id, i, load = e.load?.toString() ?: "")
                    val prior = previous.getOrNull(i)
                    SetRow(
                        log,
                        prior,
                        editable,
                        onLoad = { vm.updateSet(id, e, i) { old -> old.copy(load = it) } },
                        onReps = { vm.updateSet(id, e, i) { old -> old.copy(reps = it) } },
                        onCheck = { load, reps, checked ->
                            vm.checkSet(id, e, i, load, reps, checked)
                        },
                    )
                }
                if (editable)
                    item {
                        Row {
                            TextButton(
                                onClick = {
                                    vm.updateResult(id) { r ->
                                        val n = r.setCounts[e.id] ?: e.sets
                                        r.copy(
                                            setCounts =
                                                r.setCounts + (e.id to (n + 1).coerceAtMost(50))
                                        )
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Add, null)
                                Text("Série")
                            }
                            TextButton(
                                onClick = {
                                    vm.updateResult(id) { r ->
                                        val n = r.setCounts[e.id] ?: e.sets
                                        require(n > 1) { "Conservez au moins une série." }
                                        require(
                                            r.sets.none {
                                                it.exerciseInstanceId == e.id &&
                                                    it.index == n - 1 &&
                                                    it.checked
                                            }
                                        ) {
                                            "Décochez la dernière série avant de la retirer."
                                        }
                                        r.copy(
                                            setCounts = r.setCounts + (e.id to n - 1),
                                            sets =
                                                r.sets.filterNot {
                                                    it.exerciseInstanceId == e.id &&
                                                        it.index == n - 1
                                                },
                                        )
                                    }
                                }
                            ) {
                                Text("Retirer la dernière")
                            }
                        }
                    }
            }
        }
        if (plan.running.isNotEmpty()) {
            item {
                SectionTitle("Le programme de course")
                RunPlanSummary(plan.running)
            }
        }
        if (w.sport in listOf(Sport.RUN, Sport.RACE)) {
            item {
                SectionTitle("Résultats de course")
                Text(
                    "Tous les champs sont facultatifs. Durée en secondes.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                ResultField("Distance réalisée (km)", result.distanceKm, editable, true) { value ->
                    vm.updateResult(id) { it.copy(distanceKm = value) }
                }
            }
            item {
                ResultField("Durée (secondes)", result.durationSeconds, editable, true) { value ->
                    vm.updateResult(id) { it.copy(durationSeconds = value) }
                }
            }
            item {
                val pace =
                    result.durationSeconds.decimal()?.let { s ->
                        result.distanceKm.decimal()?.takeIf { it > 0 }?.let { s / it }
                    }
                val planned = SportMath.plannedMeters(plan.running) / 1000
                val targetTime = SportMath.plannedSeconds(plan.running)
                Text(
                    "Allure moyenne : ${SportMath.paceLabel(pace)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Prévu connu : ${planned.pretty()} km · ${SportMath.durationLabel(targetTime.toLong())}",
                    style = MaterialTheme.typography.bodySmall,
                )
                result.distanceKm.decimal()?.let {
                    if (planned > 0)
                        Text(
                            "Écart distance : ${(it-planned).pretty()} km",
                            style = MaterialTheme.typography.bodySmall,
                        )
                }
                if (result.durationSeconds.decimal() != null && targetTime > 0)
                    Text(
                        "Écart durée connue : ${(result.durationSeconds.decimal()!!-targetTime).pretty()} s",
                        style = MaterialTheme.typography.bodySmall,
                    )
            }
            item {
                ResultField("FC moyenne (bpm)", result.averageHr, editable, true) { value ->
                    vm.updateResult(id) { it.copy(averageHr = value) }
                }
                ResultField("Dénivelé positif (m)", result.elevation, editable, true) { value ->
                    vm.updateResult(id) { it.copy(elevation = value) }
                }
            }
            if (plan.running.isNotEmpty())
                item {
                    TextButton(onClick = { intervals = !intervals }) {
                        Text(
                            if (intervals) "Masquer les intervalles"
                            else "Renseigner les intervalles"
                        )
                    }
                }
            if (intervals) {
                val flat = SportMath.flatten(plan.running)
                itemsIndexed(flat, key = { i, s -> "interval-$i-${s.id}" }) { i, s ->
                    val log = result.intervals.find { it.ordinal == i } ?: IntervalLog(s.id, i)
                    IntervalRow(s, log, editable) { updated ->
                        vm.updateResult(id) { r ->
                            val old = r.intervals.find { it.ordinal == i } ?: IntervalLog(s.id, i)
                            val next = updated(old)
                            r.copy(intervals = r.intervals.filterNot { it.ordinal == i } + next)
                        }
                    }
                }
                item {
                    val times = result.intervals.sumOf { it.seconds.decimal() ?: 0.0 }
                    val meters = result.intervals.sumOf { it.meters.decimal() ?: 0.0 }
                    if (times > 0 && meters > 0)
                        Text(
                            "Allure des intervalles renseignés : ${SportMath.paceLabel(times/meters*1000)}"
                        )
                }
            }
        }
        item {
            SectionTitle("Le ressenti")
            ResultField("RPE (0–10)", result.rpe, editable, true) { v ->
                vm.updateResult(id) { it.copy(rpe = v) }
            }
            ResultField("Fatigue (0–10)", result.fatigue, editable, true) { v ->
                vm.updateResult(id) { it.copy(fatigue = v) }
            }
            ResultField("Comment vous êtes-vous senti ?", result.feeling, editable) { v ->
                vm.updateResult(id) { it.copy(feeling = v) }
            }
            ResultField("Notes de séance", result.notes, editable, lines = 3) { v ->
                vm.updateResult(id) { it.copy(notes = v) }
            }
            if (w.notes.isNotBlank()) Text("Notes planifiées : ${w.notes}")
        }
        item {
            Choice(
                "Préparation",
                w.preparationId ?: "",
                listOf("") + state.preparations.map { it.id },
                text = { key -> state.preparations.find { it.id == key }?.name ?: "Aucune" },
                change = { value ->
                    vm.action { vm.repo.associate(id, value.takeIf { it.isNotBlank() }) }
                },
            )
        }
        if (!active && !w.status.isResult)
            item {
                Choice(
                    "Statut",
                    w.status,
                    listOf(Status.PLANNED, Status.POSTPONED, Status.CANCELLED),
                    text = { it.label },
                    change = { status -> vm.action { vm.repo.setStatus(id, status) } },
                )
            }
        if (active)
            item {
                Button(onClick = { finish = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.CheckCircle, null)
                    Text("Terminer la séance")
                }
            }
        if (correcting)
            item {
                Button(
                    onClick = {
                        vm.action {
                            validateResult(parseResult(vm.repo.dao.result(id)?.payload))
                            correcting = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Valider les corrections")
                }
            }
        item { Spacer(Modifier.height(32.dp)) }
    }
    if (move || duplicate) {
        var date by rememberSaveable {
            mutableStateOf(w.date.asDate().plusDays(if (duplicate) 7 else 1).toString())
        }
        FormDialog(
            if (duplicate) "Dupliquer la séance" else "Déplacer la séance",
            {
                move = false
                duplicate = false
            },
            {
                val copy = duplicate
                vm.action {
                    if (copy) vm.repo.duplicate(id, date.asDate())
                    else vm.repo.move(id, date.asDate())
                }
                move = false
                duplicate = false
            },
        ) {
            DateField("Date cible", date) { date = it }
            if (duplicate) Text("Le contenu prévu est copié sans les performances.")
        }
    }
    if (delete)
        AlertDialog(
            onDismissRequest = { delete = false },
            title = { Text("Supprimer cette séance ?") },
            text = { Text("La séance et ses performances seront supprimées définitivement.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.action {
                            vm.repo.delete(id)
                            RestAlarm.schedule(context, id, null)
                        }
                        delete = false
                        back()
                    }
                ) {
                    Text("Supprimer")
                }
            },
            dismissButton = { TextButton(onClick = { delete = false }) { Text("Conserver") } },
        )
    if (finish)
        AlertDialog(
            onDismissRequest = { finish = false },
            title = { Text("Bravo pour le travail accompli") },
            text = {
                Text(
                    "Choisissez comment enregistrer cette séance. Les séries non cochées restent non réalisées."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        finish = false
                        vm.finish(id, Status.DONE) { back() }
                    }
                ) {
                    Text("Terminée")
                }
            },
            dismissButton = {
                Column {
                    TextButton(
                        onClick = {
                            finish = false
                            vm.finish(id, Status.PARTIAL) { back() }
                        }
                    ) {
                        Text("Partiellement terminée")
                    }
                    TextButton(onClick = { finish = false }) { Text("Continuer la séance") }
                }
            },
        )
}

@Composable
private fun SetRow(
    log: SetLog,
    previous: SetLog?,
    editable: Boolean,
    onLoad: (String) -> Unit,
    onReps: (String) -> Unit,
    onCheck: (String, String, Boolean) -> Unit,
) {
    var load by rememberSaveable(log.exerciseInstanceId, log.index) { mutableStateOf(log.load) }
    var reps by rememberSaveable(log.exerciseInstanceId, log.index) { mutableStateOf(log.reps) }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("${log.index+1}", fontWeight = FontWeight.Bold)
            Text(
                previous?.let { "${it.load.ifBlank{"0"}} kg × ${it.reps}" } ?: "—",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedTextField(
            value = load,
            onValueChange = {
                load = it
                onLoad(it)
            },
            enabled = editable,
            modifier =
                Modifier.width(84.dp).testTag("set-${log.exerciseInstanceId}-${log.index}-load"),
            singleLine = true,
            keyboardOptions =
                androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                ),
        )
        OutlinedTextField(
            value = reps,
            onValueChange = {
                reps = it
                onReps(it)
            },
            enabled = editable,
            modifier =
                Modifier.width(70.dp).testTag("set-${log.exerciseInstanceId}-${log.index}-reps"),
            singleLine = true,
            keyboardOptions =
                androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                ),
        )
        Checkbox(
            log.checked,
            onCheckedChange = { onCheck(load, reps, it) },
            enabled = editable,
            modifier = Modifier.testTag("set-${log.exerciseInstanceId}-${log.index}-check"),
        )
    }
}

@Composable
private fun ResultField(
    label: String,
    value: String,
    editable: Boolean,
    numeric: Boolean = false,
    lines: Int = 1,
    change: (String) -> Unit,
) {
    var local by rememberSaveable(label) { mutableStateOf(value) }
    // A field owns its typing buffer; every edit is queued for transactional persistence.
    if (editable)
        Field(
            label,
            local,
            {
                local = it
                change(it)
            },
            numeric,
            lines = lines,
        )
    else if (value.isNotBlank())
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value)
        }
}

@Composable
private fun IntervalRow(
    step: RunningStep,
    log: IntervalLog,
    editable: Boolean,
    update: ((IntervalLog) -> IntervalLog) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "${log.ordinal+1}. ${stepLabel(step)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            ResultField("Durée intervalle ${log.ordinal+1} (s)", log.seconds, editable, true) { v ->
                update { it.copy(seconds = v) }
            }
            ResultField("Distance intervalle ${log.ordinal+1} (m)", log.meters, editable, true) { v
                ->
                update { it.copy(meters = v) }
            }
            ResultField("FC intervalle ${log.ordinal+1}", log.hr, editable, true) { v ->
                update { it.copy(hr = v) }
            }
        }
    }
}

@Composable
private fun RunPlanSummary(steps: List<RunningStep>, depth: Int = 0) {
    steps.forEach { s ->
        Column(Modifier.padding(start = (depth.coerceAtMost(1) * 12).dp, top = 6.dp)) {
            Text(
                stepLabel(s),
                color =
                    if (s.kind == StepKind.REPEAT) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.onSurface,
            )
            if (s.notes.isNotBlank()) Text(s.notes, style = MaterialTheme.typography.bodySmall)
            RunPlanSummary(s.children, depth + 1)
        }
    }
}
