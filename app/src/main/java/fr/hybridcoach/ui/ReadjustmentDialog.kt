package fr.hybridcoach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import fr.hybridcoach.garmin.*
import fr.hybridcoach.planning.*
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.CancellationException

@Composable
fun ReadjustmentDialog(
    vm: CoachViewModel,
    state: CoachState,
    preparationId: String?,
    date: LocalDate,
    dismiss: () -> Unit,
) {
    val prep = state.preparations.find { it.id == preparationId }
    val goal = state.goals.find { it.id == prep?.goalId }
    var modeName by rememberSaveable { mutableStateOf(ReadjustmentMode.SHIFT.name) }
    val mode = ReadjustmentMode.valueOf(modeName)
    var from by rememberSaveable { mutableStateOf(date.toString()) }
    var to by rememberSaveable { mutableStateOf(date.plusDays(6).toString()) }
    var shift by rememberSaveable { mutableStateOf("7") }
    var percent by rememberSaveable { mutableStateOf("20") }
    var race by rememberSaveable { mutableStateOf(goal?.raceDate ?: date.toString()) }
    var phases by rememberSaveable { mutableStateOf(false) }
    var excluded by rememberSaveable(modeName, from, to) { mutableStateOf(emptyList<String>()) }
    var preview by remember { mutableStateOf<ReadjustmentPreview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scopeFrom = if (mode == ReadjustmentMode.LIGHTEN) from.asDate().monday() else from.asDate()
    val scopeTo =
        when (mode) {
            ReadjustmentMode.LIGHTEN -> scopeFrom.plusDays(6)
            ReadjustmentMode.RACE -> scopeFrom
            else -> to.asDate()
        }
    val selectionRequest = ReadjustmentRequest(mode, preparationId, scopeFrom, scopeTo)
    val source =
        ReadjustmentSource(
            state.workouts,
            state.preparations,
            state.goals,
            state.subGoals,
            state.workoutSubGoals,
            state.results.keys.map { ResultEntity(it, "") },
            state.garminActivities,
        )
    val candidates =
        state.workouts
            .filter { Readjustments.inScope(it, selectionRequest) }
            .sortedWith(compareBy({ it.date }, { it.name }))
    val editable = candidates.filter { Readjustments.editable(it, source) }
    FormDialog(
        if (preview == null) "Réajustement rapide" else "Aperçu du réajustement",
        dismiss = { if (!busy) dismiss() },
        confirm = {
            if (!busy && (preview == null || preview!!.hasChanges)) {
                val reviewed = preview
                val request =
                    runCatching {
                            selectionRequest.copy(
                                shiftDays =
                                    if (mode == ReadjustmentMode.SHIFT)
                                        requireNotNull(shift.toIntOrNull()) { "Décalage invalide." }
                                    else 7,
                                reductionPercent =
                                    if (mode == ReadjustmentMode.LIGHTEN)
                                        requireNotNull(percent.toIntOrNull()) {
                                            "Réduction invalide."
                                        }
                                    else 20,
                                raceDate = race.asDate(),
                                adjustPhases = phases,
                                excludedIds = excluded.toSet(),
                            )
                        }
                        .getOrElse {
                            error = it.localizedMessage
                            null
                        }
                if (request != null) {
                    busy = true
                    error = null
                    vm.action {
                        try {
                            if (reviewed == null) preview = vm.repo.previewReadjustment(request)
                            else {
                                vm.repo.applyReadjustment(reviewed)
                                vm.notice(
                                    "Réajustement appliqué · ${reviewed.workouts.size} séance(s) modifiée(s)"
                                )
                                dismiss()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.localizedMessage ?: "Le réajustement a échoué."
                        } finally {
                            busy = false
                        }
                    }
                }
            }
        },
        confirmEnabled = !busy && (preview == null || preview!!.hasChanges),
        confirmText =
            if (busy) "Patientez…"
            else if (preview == null) "Voir l’aperçu" else "Appliquer les changements",
    ) {
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Traitement en cours…")
        } else if (preview == null) {
            Text(prep?.name ?: "Toutes les séances du calendrier")
            Choice(
                "Action",
                mode,
                ReadjustmentMode.entries.filter { it != ReadjustmentMode.RACE || prep != null },
                text = { it.label },
            ) {
                modeName = it.name
                phases = false
                error = null
                if (it == ReadjustmentMode.RACE) from = maxOf(date, LocalDate.now()).toString()
            }
            when (mode) {
                ReadjustmentMode.SHIFT -> {
                    DateField("Du", from) { from = it }
                    DateField("Au (inclus)", to) { to = it }
                    TextButton(
                        onClick = {
                            to =
                                maxOf(
                                        from.asDate(),
                                        prep?.end?.asDate() ?: from.asDate(),
                                        state.workouts
                                            .filter {
                                                preparationId == null ||
                                                    it.preparationId == preparationId
                                            }
                                            .maxOfOrNull { it.date.asDate() } ?: from.asDate(),
                                    )
                                    .toString()
                        }
                    ) {
                        Text("Toute la suite du programme")
                    }
                    Field("Décalage en jours (+ ou −)", shift, { shift = it }, numeric = true)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(-7, 1, 7, 14).forEach { days ->
                            FilterChip(
                                shift == days.toString(),
                                onClick = { shift = days.toString() },
                                label = { Text("${if (days > 0) "+" else ""}$days j") },
                            )
                        }
                    }
                }
                ReadjustmentMode.LIGHTEN -> {
                    DateField("Semaine contenant le", from) { from = it }
                    Text(
                        "Semaine du ${dateLabel(scopeFrom.toString())} au ${dateLabel(scopeTo.toString())}"
                    )
                    Field("Réduction du volume (%)", percent, { percent = it }, numeric = true)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(10, 20, 30, 50).forEach { value ->
                            FilterChip(
                                percent == value.toString(),
                                onClick = { percent = value.toString() },
                                label = { Text("−$value %") },
                            )
                        }
                    }
                    InfoButton(
                        "Comment le volume est réduit",
                        "Réduit la durée ou distance des efforts et les séries de musculation (arrondies, minimum 1). Allures, charges, répétitions, échauffements, retours au calme et récupérations conservés. Les étapes libres restent inchangées.",
                    )
                }
                ReadjustmentMode.RACE -> {
                    Text("Compétition actuelle : ${dateLabel(goal!!.raceDate)}")
                    DateField("Nouvelle compétition", race) { race = it }
                    DateField("Déplacer les séances à partir du", from) { from = it }
                    Text(
                        "Les séances cochées et la fin de préparation seront décalées du même nombre de jours que la compétition. Le début de préparation reste en place."
                    )
                }
            }
            if (prep != null && mode != ReadjustmentMode.LIGHTEN) {
                CheckLine("Ajuster aussi les sous-objectifs", phases) { phases = it }
                if (phases)
                    Text(
                        "Périodes se terminant à partir du ${dateLabel(scopeFrom.toString())} : décalées du même nombre de jours. Une période déjà commencée conserve son début. Les périodes antérieures restent en place."
                    )
            }
            Text(
                "${editable.count { it.id !in excluded }} séance(s) sélectionnée(s) · ${candidates.size - editable.size} protégée(s)",
                style = MaterialTheme.typography.titleSmall,
            )
            Disclosure(
                "Choisir les séances",
                "${editable.count { it.id !in excluded }} sélectionnée(s) sur ${editable.size}",
            ) {
                Text(
                    "Les séances commencées, terminées, annulées, importées ou possédant un résultat sont protégées."
                )
                Row {
                    TextButton(onClick = { excluded = emptyList() }) { Text("Tout sélectionner") }
                    TextButton(onClick = { excluded = editable.map { it.id } }) {
                        Text("Tout retirer")
                    }
                }
                editable.forEach { workout ->
                    CheckLine(
                        "${dateLabel(workout.date)} · ${workout.name}",
                        workout.id !in excluded,
                    ) {
                        excluded = if (it) excluded - workout.id else excluded + workout.id
                    }
                }
            }
            if (editable.isEmpty()) Text("Aucune séance modifiable dans cette période.")
        } else {
            ReadjustmentDetails(preview!!)
            TextButton(
                onClick = {
                    preview = null
                    error = null
                }
            ) {
                Text("Modifier / recalculer")
            }
        }
        ErrorText(error)
    }
}

private fun volumeLabel(workouts: List<PlannedWorkout>): String {
    val plans =
        workouts
            .filter { it.status != Status.CANCELLED && it.hasPlannedContent }
            .map { parsePlan(it.planJson) }
    return String.format(
        Locale.FRENCH,
        "%d séance(s) · %.1f km · %.0f min · %d séries",
        plans.size,
        plans.sumOf { SportMath.plannedMeters(it.running) } / 1000,
        plans.sumOf { SportMath.plannedSeconds(it.running) } / 60,
        plans.sumOf { p -> p.strength.sumOf { it.sets } },
    )
}

@Composable
private fun ReadjustmentDetails(preview: ReadjustmentPreview) {
    Column(
        Modifier.testTag("readjustment-preview"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "${preview.workouts.size} séance(s) modifiée(s) · ${preview.protectedCount} protégée(s)",
            style = MaterialTheme.typography.titleMedium,
        )
        if (!preview.hasChanges)
            Text("Aucun changement à appliquer. Modifiez les paramètres ou la sélection.")
        if (preview.unchangedCount > 0)
            Text(
                "${preview.unchangedCount} séance(s) sélectionnée(s) inchangée(s) : aucun volume réductible ou arrondi identique."
            )
        preview.goal?.let {
            Text(
                "Compétition : ${preview.source.goals.first { g -> g.id == it.id }.raceDate} → ${it.raceDate}"
            )
        }
        preview.preparation?.let {
            Text(
                "Fin de préparation : ${preview.source.preparations.first { p -> p.id == it.id }.end} → ${it.end}"
            )
        }
        if (preview.warnings.isEmpty()) Text("Aucun conflit détecté.")
        else {
            Text(
                "${preview.warnings.size} point(s) à vérifier",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "Ces avertissements n’empêchent pas la validation. Plusieurs séances le même jour restent possibles."
            )
            preview.warnings.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
        }
        Disclosure(
            "Détail des changements",
            "${preview.workouts.size} séance(s) · ${preview.phases.size} période(s)",
        ) {
            preview.workouts.forEach { change ->
                Text(change.before.name, style = MaterialTheme.typography.titleSmall)
                if (change.before.date != change.after.date)
                    Text("${dateLabel(change.before.date)} → ${dateLabel(change.after.date)}")
                if (change.before.planJson != change.after.planJson) {
                    Text(
                        "Avant : ${volumeLabel(listOf(change.before))}\nAprès : ${volumeLabel(listOf(change.after))}"
                    )
                    val before = parsePlan(change.before.planJson)
                    val after = parsePlan(change.after.planJson)
                    after.running.forEachIndexed { index, step ->
                        if (step != before.running[index])
                            Text("${blockSummary(before.running[index])} → ${blockSummary(step)}")
                    }
                    after.strength.forEachIndexed { index, exercise ->
                        if (exercise.sets != before.strength[index].sets)
                            Text(
                                "${exercise.name} : ${before.strength[index].sets} → ${exercise.sets} séries"
                            )
                    }
                }
            }
            preview.phases.forEach {
                Text(
                    "${it.before.name} : ${it.before.start} → ${it.before.end}\nNouvelle période : ${it.after.start} → ${it.after.end}"
                )
            }
        }
        val weeks =
            preview.workouts
                .flatMap {
                    listOf(it.before.date.asDate().monday(), it.after.date.asDate().monday())
                }
                .distinct()
                .sorted()
        if (weeks.isNotEmpty()) {
            Disclosure("Volumes hebdomadaires prévus", "${weeks.size} semaine(s) concernée(s)") {
                Text("Volumes calculables uniquement ; les étapes libres ne sont pas estimées.")
                val replacements = preview.workouts.associate { it.after.id to it.after }
                val before =
                    preview.source.workouts.filter {
                        preview.request.preparationId == null ||
                            it.preparationId == preview.request.preparationId
                    }
                val after = before.map { replacements[it.id] ?: it }
                weeks.forEach { monday ->
                    Text(
                        "Semaine du ${dateLabel(monday.toString())}\nAvant : ${volumeLabel(before.filter { it.planningDate.monday() == monday })}\nAprès : ${volumeLabel(after.filter { it.planningDate.monday() == monday })}"
                    )
                }
            }
        }
        Text("Validez pour appliquer ces changements au calendrier.")
    }
}
