package fr.hybridcoach.planning

import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import fr.hybridcoach.garmin.*
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlinx.serialization.encodeToString

enum class ReadjustmentMode(val label: String) {
    SHIFT("Décaler des séances"),
    LIGHTEN("Alléger une semaine"),
    RACE("Changer la date de compétition"),
}

data class ReadjustmentRequest(
    val mode: ReadjustmentMode,
    val preparationId: String?,
    val from: LocalDate,
    val to: LocalDate,
    val shiftDays: Int = 7,
    val reductionPercent: Int = 20,
    val raceDate: LocalDate? = null,
    val adjustPhases: Boolean = false,
    val excludedIds: Set<String> = emptySet(),
)

// A consistent database snapshot also guards against edits/imports made after the preview.
data class ReadjustmentSource(
    val workouts: List<PlannedWorkout>,
    val preparations: List<TrainingPreparation>,
    val goals: List<TrainingGoal>,
    val phases: List<PreparationSubGoal>,
    val links: List<WorkoutSubGoal>,
    val results: List<ResultEntity>,
    val garmin: List<GarminActivityEntity>,
)

data class WorkoutChange(val before: PlannedWorkout, val after: PlannedWorkout)

data class PhaseChange(val before: PreparationSubGoal, val after: PreparationSubGoal)

data class ReadjustmentPreview(
    val request: ReadjustmentRequest,
    val today: LocalDate,
    val source: ReadjustmentSource,
    val workouts: List<WorkoutChange>,
    val phases: List<PhaseChange>,
    val preparation: TrainingPreparation?,
    val goal: TrainingGoal?,
    val protectedCount: Int,
    val unchangedCount: Int,
    val warnings: List<String>,
) {
    val hasChanges: Boolean
        get() = workouts.isNotEmpty() || phases.isNotEmpty() || preparation != null || goal != null
}

object Readjustments {
    fun inScope(workout: PlannedWorkout, request: ReadjustmentRequest): Boolean =
        (request.preparationId == null || workout.preparationId == request.preparationId) &&
            workout.date.asDate() >= request.from &&
            (request.mode == ReadjustmentMode.RACE || workout.date.asDate() <= request.to)

    fun editable(workout: PlannedWorkout, source: ReadjustmentSource): Boolean =
        workout.status in listOf(Status.PLANNED, Status.POSTPONED) &&
            workout.source != "GARMIN" &&
            source.results.none { it.workoutId == workout.id } &&
            source.garmin.none { it.workoutId == workout.id }

    /**
     * Reduce effort duration/distance and strength sets, preserving identities and targets.
     * Warmups, cooldowns, recoveries and repeat counts retain their exact structure.
     */
    fun lighten(plan: WorkoutPlan, percent: Int): WorkoutPlan {
        require(percent in 1..90) { "Choisissez une réduction entre 1 et 90 %." }
        val factor = 1.0 - percent / 100.0
        fun step(s: RunningStep): RunningStep =
            when {
                s.kind == StepKind.REPEAT -> s.copy(children = s.children.map(::step))
                s.kind == StepKind.WORK && s.durationKind != DurationKind.MANUAL ->
                    s.copy(amount = s.amount?.let { it * factor })
                else -> s
            }
        return plan
            .copy(
                running = plan.running.map(::step),
                strength =
                    plan.strength.map {
                        it.copy(sets = (it.sets * factor).roundToInt().coerceAtLeast(1))
                    },
            )
            .also(::validatePlan)
    }

    fun preview(
        source: ReadjustmentSource,
        request: ReadjustmentRequest,
        today: LocalDate,
    ): ReadjustmentPreview {
        require(request.from <= request.to) { "La fin de la période précède le début." }
        require(
            request.mode != ReadjustmentMode.SHIFT ||
                request.shiftDays in -730..730 && request.shiftDays != 0
        ) {
            "Choisissez un décalage entre −730 et 730 jours, différent de zéro."
        }
        require(request.mode != ReadjustmentMode.LIGHTEN || request.reductionPercent in 1..90) {
            "Choisissez une réduction entre 1 et 90 %."
        }
        val prep =
            request.preparationId?.let { id ->
                requireNotNull(source.preparations.find { it.id == id }) {
                    "Préparation introuvable."
                }
            }
        var nextPrep: TrainingPreparation? = null
        var nextGoal: TrainingGoal? = null
        var delta = request.shiftDays.toLong()
        val warnings = mutableListOf<String>()
        if (request.mode == ReadjustmentMode.RACE) {
            requireNotNull(prep) { "Choisissez une préparation pour changer la compétition." }
            val goal = requireNotNull(source.goals.find { it.id == prep.goalId })
            val race =
                requireNotNull(request.raceDate) { "Choisissez la nouvelle date de compétition." }
            delta = ChronoUnit.DAYS.between(goal.raceDate.asDate(), race)
            require(delta != 0L && delta in -730L..730L) {
                "Choisissez une autre date, à moins de deux ans de la date actuelle."
            }
            require(race >= today && race >= prep.start.asDate()) {
                "La compétition doit être à venir et après le début de préparation."
            }
            nextGoal = goal.copy(raceDate = race.toString())
            nextPrep = prep.copy(end = prep.end.asDate().plusDays(delta).toString())
            require(nextPrep.start.asDate() <= nextPrep.end.asDate()) {
                "La nouvelle fin de préparation précède son début."
            }
            val shared = source.preparations.filter { it.goalId == goal.id && it.id != prep.id }
            if (shared.isNotEmpty())
                warnings +=
                    "Objectif partagé : la date de compétition change aussi pour ${shared.joinToString { it.name }}. Leurs séances et périodes restent en place."
        }
        val phaseChanges =
            if (request.adjustPhases && request.mode != ReadjustmentMode.LIGHTEN) {
                requireNotNull(prep) {
                    "Choisissez une préparation pour ajuster les sous-objectifs."
                }
                source.phases
                    .filter { it.preparationId == prep.id && it.end.asDate() >= request.from }
                    .mapNotNull { old ->
                        // Completed phases remain historical; ongoing phases retain their start.
                        val after =
                            old.copy(
                                start =
                                    if (old.start.asDate() >= request.from)
                                        old.start.asDate().plusDays(delta).toString()
                                    else old.start,
                                end = old.end.asDate().plusDays(delta).toString(),
                            )
                        require(after.start.asDate() <= after.end.asDate()) {
                            "Le décalage inverse la période « ${old.name} ». Désactivez l’ajustement des sous-objectifs ou changez le décalage."
                        }
                        if (after == old) null else PhaseChange(old, after)
                    }
            } else emptyList()
        val scoped = source.workouts.filter { inScope(it, request) }
        val selected = scoped.filter { editable(it, source) && it.id !in request.excludedIds }
        val changes =
            selected
                .mapNotNull { old ->
                    val next =
                        if (request.mode == ReadjustmentMode.LIGHTEN) {
                            val plan = parsePlan(old.planJson)
                            val reduced = lighten(plan, request.reductionPercent)
                            if (plan == reduced) old
                            else old.copy(planJson = codec.encodeToString(reduced))
                        } else
                            old.copy(
                                date = old.date.asDate().plusDays(delta).toString(),
                                plannedDate = old.plannedDate?.asDate()?.plusDays(delta)?.toString(),
                            )
                    if (next == old) null else WorkoutChange(old, next)
                }
                .sortedWith(compareBy({ it.after.date }, { it.after.id }))
        val allAfter = source.workouts.associateBy { it.id }.toMutableMap()
        changes.forEach { allAfter[it.after.id] = it.after }
        val allPhases = source.phases.associateBy { it.id }.toMutableMap()
        phaseChanges.forEach { allPhases[it.after.id] = it.after }
        val prepsAfter = source.preparations.associateBy { it.id }.toMutableMap()
        nextPrep?.let { prepsAfter[it.id] = it }
        changes.forEach { change ->
            val w = change.after
            val day = w.date.asDate()
            if (request.mode != ReadjustmentMode.LIGHTEN) {
                val other =
                    allAfter.values.filter {
                        it.id != w.id && it.date == w.date && it.status != Status.CANCELLED
                    }
                if (other.isNotEmpty())
                    warnings +=
                        "${w.date} · ${w.name} : journée déjà occupée (${other.joinToString { it.name }})."
                if (day < today) warnings += "${w.name} : destination dans le passé (${w.date})."
            }
            val p = prepsAfter[w.preparationId]
            if (p != null && day !in p.start.asDate()..p.end.asDate())
                warnings += "${w.name} : hors période de préparation (${w.date})."
            val goal =
                p?.let {
                    nextGoal?.takeIf { g -> g.id == it.goalId }
                        ?: source.goals.find { g -> g.id == it.goalId }
                }
            if (goal != null && day > goal.raceDate.asDate())
                warnings += "${w.name} : après la compétition (${w.date})."
            source.links
                .filter { it.workoutId == w.id }
                .forEach linkLoop@{ link ->
                    val phase = allPhases[link.subGoalId] ?: return@linkLoop
                    if (day !in phase.start.asDate()..phase.end.asDate())
                        warnings +=
                            "${w.name} : hors du sous-objectif « ${phase.name} ». Le lien est conservé."
                }
        }
        phaseChanges.forEach { change ->
            val p = prepsAfter[change.after.preparationId]!!
            if (
                change.after.start.asDate() < p.start.asDate() ||
                    change.after.end.asDate() > p.end.asDate()
            )
                warnings +=
                    "Sous-objectif « ${change.after.name} » : dépasse la période de préparation."
        }
        val changedPhaseIds = phaseChanges.map { it.after.id }.toSet()
        val changedWorkoutIds = changes.map { it.after.id }.toSet()
        source.links
            .filter { it.subGoalId in changedPhaseIds && it.workoutId !in changedWorkoutIds }
            .forEach { link ->
                val phase = allPhases[link.subGoalId]!!
                val workout = allAfter[link.workoutId] ?: return@forEach
                if (workout.planningDate !in phase.start.asDate()..phase.end.asDate()) {
                    warnings +=
                        "${workout.name} : hors du sous-objectif « ${phase.name} ». Le lien est conservé."
                }
            }
        if (request.mode == ReadjustmentMode.RACE) {
            allAfter.values
                .filter {
                    it.preparationId == prep!!.id &&
                        it.date.asDate() > nextGoal!!.raceDate.asDate() &&
                        it.status != Status.CANCELLED &&
                        changes.none { c -> c.after.id == it.id }
                }
                .forEach {
                    warnings +=
                        "${it.name} : séance conservée après la nouvelle compétition (${it.date})."
                }
        }
        return ReadjustmentPreview(
            request,
            today,
            source,
            changes,
            phaseChanges,
            nextPrep,
            nextGoal,
            scoped.count { !editable(it, source) },
            selected.size - changes.size,
            warnings.distinct(),
        )
    }
}
