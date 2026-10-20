package fr.hybridcoach.domain

import fr.hybridcoach.data.*
import fr.hybridcoach.garmin.*
import java.time.LocalDate

// Only completed or partially completed sessions contribute to actual training statistics.
data class WeeklyVolume(
    val start: LocalDate,
    val plannedKm: Double,
    val actualKm: Double,
    val strengthSessions: Int,
    val runSeconds: Double,
    val sessions: Int,
    val trainingSeconds: Double = 0.0,
)

data class ExerciseRecord(
    val date: LocalDate,
    val load: Double,
    val reps: Int,
    val volume: Double,
    val estimated1RM: Double?,
)

data class MuscleVolume(val muscle: String, val sets: Int, val sessions: Int)

object Statistics {
    fun weeks(
        state: CoachState,
        start: LocalDate,
        count: Int,
        preparationId: String? = null,
    ): List<WeeklyVolume> =
        (0 until count).map { index ->
            val day = start.monday().plusWeeks(index.toLong())
            val w =
                state.workouts.filter {
                    it.date.asDate() in day..day.plusDays(6) &&
                        (preparationId == null || it.preparationId == preparationId)
                }
            val done = w.filter { it.status.isResult }
            WeeklyVolume(
                day,
                state.workouts
                    .filter {
                        it.hasPlannedContent &&
                            it.planningDate in day..day.plusDays(6) &&
                            (preparationId == null || it.preparationId == preparationId) &&
                            it.sport in listOf(Sport.RUN, Sport.RACE) &&
                            it.status != Status.CANCELLED
                    }
                    .sumOf { SportMath.plannedMeters(parsePlan(it.planJson).running) / 1000 },
                done
                    .filter { it.sport in listOf(Sport.RUN, Sport.RACE) }
                    .sumOf { state.results[it.id]?.distanceKm?.decimal() ?: 0.0 },
                done.count { it.sport == Sport.STRENGTH },
                done
                    .filter { it.sport in listOf(Sport.RUN, Sport.RACE) }
                    .sumOf { state.results[it.id]?.durationSeconds?.decimal() ?: 0.0 },
                done.size,
                done.sumOf { state.results[it.id]?.durationSeconds?.decimal() ?: 0.0 },
            )
        }

    fun exerciseHistory(
        state: CoachState,
        exerciseId: String,
        from: LocalDate = LocalDate.MIN,
    ): List<ExerciseRecord> =
        state.workouts
            .filter { it.status.isResult && it.date.asDate() >= from }
            .flatMap { w ->
                val instances =
                    parsePlan(w.planJson)
                        .strength
                        .filter { it.exerciseId == exerciseId }
                        .associateBy { it.id }
                state.results[w.id]
                    ?.sets
                    .orEmpty()
                    .filter { it.checked && it.exerciseInstanceId in instances }
                    .mapNotNull { log ->
                        val load = log.load.decimal() ?: 0.0
                        val reps = log.reps.toIntOrNull() ?: return@mapNotNull null
                        ExerciseRecord(
                            w.date.asDate(),
                            load,
                            reps,
                            load * reps,
                            if (
                                instances[log.exerciseInstanceId]?.mode in
                                    listOf("Standard", "Unilatéral")
                            )
                                SportMath.estimated1RM(load, reps)
                            else null,
                        )
                    }
            }
            .sortedBy { it.date }

    fun muscles(state: CoachState, from: LocalDate): List<MuscleVolume> {
        val sets = mutableMapOf<String, Int>()
        val sessions = mutableMapOf<String, MutableSet<String>>()
        state.workouts
            .filter { it.status.isResult && it.date.asDate() >= from }
            .forEach { w ->
                val exercises = parsePlan(w.planJson).strength.associateBy { it.id }
                state.results[w.id]
                    ?.sets
                    .orEmpty()
                    .filter { it.checked }
                    .forEach { log ->
                        exercises[log.exerciseInstanceId]?.let { e ->
                            sets[e.muscle] = (sets[e.muscle] ?: 0) + 1
                            sessions.getOrPut(e.muscle) { mutableSetOf() }.add(w.id)
                        }
                    }
            }
        return sets
            .map { MuscleVolume(it.key, it.value, sessions[it.key]?.size ?: 0) }
            .sortedByDescending { it.sets }
    }

    fun previousSets(
        state: CoachState,
        workout: PlannedWorkout,
        exercise: StrengthExercise,
    ): List<SetLog> {
        val prior =
            state.workouts
                .filter {
                    it.id != workout.id &&
                        it.status.isResult &&
                        it.date <= workout.date &&
                        parsePlan(it.planJson).strength.any { e ->
                            e.exerciseId == exercise.exerciseId
                        }
                }
                .maxByOrNull { it.date + (state.results[it.id]?.finishedAt ?: 0) }
        val ids =
            prior
                ?.let {
                    parsePlan(it.planJson)
                        .strength
                        .filter { e -> e.exerciseId == exercise.exerciseId }
                        .map { e -> e.id }
                }
                .orEmpty()
        return state.results[prior?.id]
            ?.sets
            .orEmpty()
            .filter { it.exerciseInstanceId in ids && it.checked }
            .sortedBy { it.index }
    }
}

fun stepLabel(s: RunningStep): String {
    if (s.kind == StepKind.REPEAT) return "${s.repetitions} × bloc"
    val duration =
        when (s.durationKind) {
            DurationKind.TIME -> SportMath.durationLabel((s.amount ?: 0.0).toLong())
            DurationKind.DISTANCE -> "${(s.amount ?: 0.0).toInt()} m"
            DurationKind.MANUAL -> "Fin manuelle"
        }
    val target =
        when (s.targetKind) {
            TargetKind.NONE -> ""
            TargetKind.PACE_RANGE ->
                "${SportMath.paceLabel(s.target)} à ${SportMath.paceLabel(s.targetMax)}"
            TargetKind.REFERENCE -> "${s.reference} · ${SportMath.paceLabel(s.resolvedPace)}"
            TargetKind.VMA -> "${s.target}% VMA · ${SportMath.paceLabel(s.resolvedPace)}"
            TargetKind.PACE,
            TargetKind.SPEED -> SportMath.paceLabel(s.resolvedPace ?: s.target)
            TargetKind.HR_ZONE ->
                "Zone ${s.target?.toInt()} · ${s.resolvedLower?.toInt() ?: "—"}–${s.resolvedUpper?.toInt() ?: "—"} bpm"
            TargetKind.PACE_ZONE ->
                "Zone ${s.target?.toInt()} · ${SportMath.paceLabel(s.resolvedLower)} à ${SportMath.paceLabel(s.resolvedUpper)}"
            else -> {
                val unit =
                    when (s.targetKind) {
                        TargetKind.HR -> "bpm"
                        TargetKind.POWER -> "W"
                        TargetKind.CADENCE -> "pas/min"
                        else -> "RPE"
                    }
                "${s.target ?: ""} $unit"
            }
        }
    return "${s.kind.label} · $duration${if(target.isNotBlank()) " · $target" else ""}${if(s.kind==StepKind.RECOVERY) if(s.activeRecovery) " · active" else " · passive" else ""}"
}
