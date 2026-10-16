package fr.hybridcoach.export

import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class ExportOptions(val personalNotes: Boolean = false, val performances: Boolean = true)

@Serializable
data class ExportWorkout(
    val session: PlannedWorkout,
    val planned: WorkoutPlan,
    val result: WorkoutResult?,
    val subGoalIds: List<String> = emptyList(),
)

@Serializable
data class ExportWeek(
    val start: String,
    val end: String,
    val plannedKm: Double,
    val actualKm: Double?,
    val sessions: List<ExportWorkout>,
)

@Serializable
data class PreparationDocument(
    val format: String = "fr.hybridcoach.preparation",
    val version: Int = 2,
    val exportedAt: String,
    val options: ExportOptions,
    val goal: TrainingGoal,
    val preparation: TrainingPreparation,
    val weeks: List<ExportWeek>,
    val subGoals: List<PreparationSubGoal> = emptyList(),
)

object PreparationExport {
    fun document(
        state: CoachState,
        id: String,
        options: ExportOptions,
        timestamp: String = Instant.now().toString(),
    ): PreparationDocument {
        val prep = requireNotNull(state.preparations.find { it.id == id })
        val goal = requireNotNull(state.goals.find { it.id == prep.goalId })
        // Include all associated workouts, even ones outside preparation dates, to avoid silent
        // omissions.
        val sessions = state.workouts.filter { it.preparationId == id }
        val start =
            minOf(
                    prep.start.asDate(),
                    sessions.minOfOrNull { it.date.asDate() } ?: prep.start.asDate(),
                )
                .monday()
        val end =
            maxOf(prep.end.asDate(), sessions.maxOfOrNull { it.date.asDate() } ?: prep.end.asDate())
                .monday()
        val count = ChronoUnit.WEEKS.between(start, end).toInt() + 1
        val volumes = Statistics.weeks(state, start, count, id)
        return PreparationDocument(
            exportedAt = timestamp,
            options = options,
            goal = if (options.personalNotes) goal else goal.copy(notes = ""),
            preparation = prep,
            subGoals = state.subGoals.filter { it.preparationId == id },
            weeks =
                volumes.map { volume ->
                    ExportWeek(
                        volume.start.toString(),
                        volume.start.plusDays(6).toString(),
                        volume.plannedKm,
                        volume.actualKm.takeIf { options.performances },
                        sessions
                            .filter { it.date.asDate() in volume.start..volume.start.plusDays(6) }
                            .map { w ->
                                val plan =
                                    parsePlan(w.planJson).let { p ->
                                        if (options.personalNotes) p
                                        else
                                            p.copy(
                                                strength = p.strength.map { it.copy(notes = "") },
                                                running = stripSteps(p.running),
                                            )
                                    }
                                val safeW =
                                    w.copy(
                                        notes = if (options.personalNotes) w.notes else "",
                                        planJson = codec.encodeToString(plan),
                                    )
                                val r =
                                    state.results[w.id]
                                        ?.takeIf { options.performances }
                                        ?.let {
                                            if (options.personalNotes) it
                                            else it.copy(notes = "", feeling = "")
                                        }
                                ExportWorkout(
                                    safeW,
                                    plan,
                                    r,
                                    state.workoutSubGoals
                                        .filter { it.workoutId == w.id }
                                        .map { it.subGoalId },
                                )
                            },
                    )
                },
        )
    }

    private fun stripSteps(steps: List<RunningStep>): List<RunningStep> =
        steps.map {
            it.copy(
                notes = "",
                children = stripSteps(it.children),
                recovery = it.recovery?.let { r -> stripSteps(listOf(r)).single() },
            )
        }

    fun json(document: PreparationDocument): String = codec.encodeToString(document)

    fun lines(d: PreparationDocument): List<String> = buildList {
        add("HYBRID COACH")
        add(d.preparation.name)
        add("")
        add("OBJECTIF · ${d.goal.name}")
        add("Compétition : ${d.goal.raceDate}")
        add(
            "Distance : ${d.goal.distanceKm ?: "—"} km · Chrono : ${d.goal.targetSeconds?.let { SportMath.durationLabel(it.toLong()) } ?: "—"}"
        )
        add(
            "Allure cible : ${SportMath.paceLabel(d.goal.targetSeconds?.let { s -> d.goal.distanceKm?.let { s/it } })}"
        )
        add(
            "Préparation : ${d.preparation.start} → ${d.preparation.end} · ${d.weeks.size} semaines affichées"
        )
        add("Priorité : ${d.goal.priority.ifBlank { "—" }}")
        if (d.options.personalNotes && d.goal.notes.isNotBlank()) add("Notes : ${d.goal.notes}")
        add("")
        add("SOUS-OBJECTIFS · PÉRIODES DE TRAVAIL")
        d.subGoals.forEach { add("${it.name} : ${it.start} → ${it.end}") }
        add("")
        add("VUE GLOBALE")
        val all = d.weeks.flatMap { it.sessions }
        val done = all.count { it.session.status.isResult }
        add("$done / ${all.size} séances réalisées ou partielles")
        add(
            "Répartition : " +
                Sport.entries.joinToString(" · ") { sport ->
                    "${sport.label} ${all.count { it.session.sport==sport }}"
                }
        )
        d.weeks.forEachIndexed { i, w ->
            add(
                "S${i+1} · ${w.start} · prévu ${"%.1f".format(w.plannedKm)} km" +
                    (w.actualKm?.let { " · réalisé ${"%.1f".format(it)} km" } ?: "")
            )
        }
        d.weeks.forEachIndexed { index, week ->
            add("")
            add("SEMAINE ${index+1} · ${week.start} → ${week.end}")
            if (week.sessions.isEmpty()) add("Aucune séance.")
            week.sessions.forEach { e ->
                val w = e.session
                add("")
                add("${w.date} · ${w.name}")
                add("${w.sport.label} · ${w.status.label} · Priorité ${w.priority.ifBlank { "—" }}")
                if (w.description.isNotBlank()) add(w.description)
                d.subGoals
                    .filter { it.id in e.subGoalIds }
                    .forEach { add("Sous-objectif : ${it.name}") }
                fun steps(nodes: List<RunningStep>, level: Int) {
                    nodes.forEach { s ->
                        add("  ".repeat(level) + stepLabel(s))
                        if (s.notes.isNotBlank()) add(s.notes)
                        steps(s.children, level + 1)
                        s.recovery?.let {
                            add(
                                "  ".repeat(level + 1) +
                                    "Entre les répétitions uniquement : " +
                                    stepLabel(it)
                            )
                        }
                    }
                }
                steps(e.planned.running, 0)
                e.planned.strength.forEach { exercise ->
                    add(
                        "${exercise.group.takeIf { it.isNotBlank() }?.let { "[$it] " } ?: ""}${exercise.name} · ${exercise.sets} × ${exercise.reps} · ${exercise.load ?: "—"} kg"
                    )
                    add(
                        "Repos ${exercise.restSeconds}s · ${exercise.mode} · RIR ${exercise.rir ?: "—"} · RPE ${exercise.rpe ?: "—"} · Tempo ${exercise.tempo.ifBlank { "—" }}"
                    )
                    if (exercise.notes.isNotBlank()) add(exercise.notes)
                    e.result
                        ?.sets
                        ?.filter { it.exerciseInstanceId == exercise.id }
                        ?.sortedBy { it.index }
                        ?.forEach { log ->
                            add(
                                "  Série ${log.index+1} : ${log.load.ifBlank { "—" }} kg × ${log.reps.ifBlank { "—" }}${if(log.checked) " (validée)" else " (non validée)"}"
                            )
                        }
                }
                e.result?.let { r ->
                    if (w.sport in listOf(Sport.RUN, Sport.RACE, Sport.OTHER)) {
                        add(
                            "Réalisé : ${r.distanceKm.ifBlank { "—" }} km · ${r.durationSeconds.decimal()?.let { SportMath.durationLabel(it.toLong()) } ?: "—"}"
                        )
                        add(
                            "FC ${r.averageHr.ifBlank { "—" }} · D+ ${r.elevation.ifBlank { "—" }} m"
                        )
                    }
                    add("RPE ${r.rpe.ifBlank { "—" }} · Fatigue ${r.fatigue.ifBlank { "—" }}")
                    r.intervals
                        .sortedBy { it.ordinal }
                        .forEach {
                            add(
                                "Intervalle ${it.ordinal+1} : ${it.meters.ifBlank { "—" }} m · ${it.seconds.ifBlank { "—" }} s · FC ${it.hr.ifBlank { "—" }}"
                            )
                        }
                    if (r.feeling.isNotBlank()) add("Ressenti : ${r.feeling}")
                    if (r.notes.isNotBlank()) add("Notes : ${r.notes}")
                }
                if (w.notes.isNotBlank()) add("Notes de séance : ${w.notes}")
            }
        }
        add("")
        add("SYNTHÈSE")
        add("Volume prévu connu : ${"%.1f".format(d.weeks.sumOf { it.plannedKm })} km")
        if (d.options.performances)
            add("Volume réalisé saisi : ${"%.1f".format(d.weeks.sumOf { it.actualKm ?: 0.0 })} km")
        add(
            "$done séances réalisées ou partielles · ${all.count { it.session.status==Status.PLANNED || it.session.status==Status.POSTPONED }} à venir"
        )
        add(
            "Les distances prévues n’incluent que les étapes dont la distance est calculable. Les résultats manquants ne sont pas estimés."
        )
        add("Export ${d.exportedAt} · format v${d.version}")
    }
}
