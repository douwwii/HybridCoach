package fr.hybridcoach

import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import fr.hybridcoach.garmin.GarminActivityEntity
import fr.hybridcoach.planning.*
import java.time.LocalDate
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ReadjustmentTest {
    private val today = LocalDate.of(2026, 10, 23)
    private val prep = TrainingPreparation("p", "g", "Marathon", "2026-10-01", "2026-12-15")
    private val goal = TrainingGoal("g", "Marathon", "2026-12-15")

    private fun workout(id: String = "w", date: String = today.toString()) =
        PlannedWorkout(
            id = id,
            name = id,
            date = date,
            sport = Sport.RUN,
            preparationId = prep.id,
            planJson =
                codec.encodeToString(
                    WorkoutPlan(
                        running =
                            listOf(
                                RunningStep(durationKind = DurationKind.DISTANCE, amount = 10000.0)
                            )
                    )
                ),
        )

    private fun source(workouts: List<PlannedWorkout> = listOf(workout())) =
        ReadjustmentSource(
            workouts,
            listOf(prep),
            listOf(goal),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
        )

    private fun request(mode: ReadjustmentMode = ReadjustmentMode.SHIFT) =
        ReadjustmentRequest(mode, "p", today, today.plusDays(6))

    @Test
    fun shiftPreservesIdsNotesSeriesPlansAndSelection() {
        val w =
            workout()
                .copy(
                    seriesId = "series",
                    plannedDate = today.toString(),
                    plannedTime = "18:00",
                    notes = "personnel",
                )
        val other = workout("excluded")
        val preview =
            Readjustments.preview(
                source(listOf(w, other)),
                request().copy(shiftDays = 14, excludedIds = setOf(other.id)),
                today,
            )
        assertEquals(
            w.copy(date = "2026-11-06", plannedDate = "2026-11-06"),
            preview.workouts.single().after,
        )
        assertEquals(w, preview.workouts.single().before)
        assertTrue(preview.phases.isEmpty())
    }

    @Test
    fun shiftIncludesMissedSessionsAndDetectsConflictsAcrossPreparationsAndPastDestinations() {
        val missed = workout(date = "2026-10-20")
        val other = workout("other", "2026-10-19").copy(preparationId = null)
        val preview =
            Readjustments.preview(
                source(listOf(missed, other)),
                request().copy(from = today.minusDays(3), shiftDays = -1),
                today,
            )
        assertEquals(1, preview.workouts.size)
        assertTrue(preview.warnings.any { "journée déjà occupée" in it })
        assertTrue(preview.warnings.any { "dans le passé" in it })
    }

    @Test
    fun completedActiveCancelledImportedAndDraftResultsAreProtected() {
        val workouts =
            listOf(Status.DONE, Status.PARTIAL, Status.ACTIVE, Status.CANCELLED).map {
                workout(it.name).copy(status = it)
            } +
                listOf(
                    workout("imported").copy(source = "GARMIN"),
                    workout("draft"),
                    workout("postponed").copy(status = Status.POSTPONED),
                )
        val preview =
            Readjustments.preview(
                source(workouts).copy(results = listOf(ResultEntity("draft", "draft"))),
                request(),
                today,
            )
        assertEquals(listOf("postponed"), preview.workouts.map { it.before.id })
        assertEquals(6, preview.protectedCount)
    }

    @Test
    fun lightenRecursesWithoutChangingTargetsRecoveriesIdsOrRepeatCounts() {
        val effort =
            RunningStep(
                durationKind = DurationKind.DISTANCE,
                amount = 400.0,
                targetKind = TargetKind.PACE,
                target = 195.0,
                resolvedPace = 195.0,
            )
        val recovery = RunningStep(kind = StepKind.RECOVERY, amount = 60.0)
        val inner =
            RunningStep(
                kind = StepKind.REPEAT,
                repetitions = 4,
                children = listOf(effort),
                recovery = recovery,
            )
        val outer =
            RunningStep(
                kind = StepKind.REPEAT,
                repetitions = 3,
                children = listOf(inner),
                recovery = recovery.copy(amount = 180.0),
            )
        val plan =
            WorkoutPlan(
                running =
                    listOf(
                        RunningStep(kind = StepKind.WARMUP, amount = 1200.0),
                        outer,
                        RunningStep(kind = StepKind.COOLDOWN, amount = 600.0),
                    ),
                strength =
                    listOf(
                        StrengthExercise(
                            exerciseId = "s",
                            name = "Squat",
                            muscle = "Jambes",
                            sets = 4,
                            load = 70.0,
                        )
                    ),
            )
        val reduced = Readjustments.lighten(plan, 25)
        val next = reduced.running[1].children.single().children.single()
        assertEquals(effort.copy(amount = 300.0), next)
        assertEquals(3, reduced.running[1].repetitions)
        assertEquals(4, reduced.running[1].children.single().repetitions)
        assertEquals(recovery, reduced.running[1].children.single().recovery)
        assertEquals(plan.running[0], reduced.running[0])
        assertEquals(plan.running[2], reduced.running[2])
        assertEquals(plan.strength.single().copy(sets = 3), reduced.strength.single())
        assertEquals(3600.0, SportMath.plannedMeters(reduced.running), .001)
        assertEquals(12, SportMath.flatten(reduced.running).count { it.kind == StepKind.WORK })
    }

    @Test
    fun freeStepsAndSingleSetsAreNotInventedAndEmptySelectionHasNoChanges() {
        val plan =
            WorkoutPlan(
                running = listOf(RunningStep(durationKind = DurationKind.MANUAL, amount = null)),
                strength =
                    listOf(
                        StrengthExercise(
                            exerciseId = "s",
                            name = "Squat",
                            muscle = "Jambes",
                            sets = 1,
                        )
                    ),
            )
        assertEquals(plan, Readjustments.lighten(plan, 90))
        val w = workout().copy(planJson = codec.encodeToString(plan))
        val preview =
            Readjustments.preview(source(listOf(w)), request(ReadjustmentMode.LIGHTEN), today)
        assertFalse(preview.hasChanges)
        assertEquals(1, preview.unchangedCount)
        assertFalse(
            Readjustments.preview(source(), request().copy(excludedIds = setOf("w")), today)
                .hasChanges
        )
    }

    @Test
    fun raceShiftsAllFutureDatesAndOverlappingPhasesButKeepsHistory() {
        val past = PreparationSubGoal("past", "p", "Passé", "2026-10-01", "2026-10-22")
        val ongoing = past.copy(id = "ongoing", name = "Seuil", end = "2026-11-01")
        val future =
            past.copy(id = "future", name = "Spécifique", start = "2026-10-25", end = "2026-11-15")
        val w = workout(date = "2026-12-01")
        val history = workout("finished", "2026-10-15").copy(status = Status.DONE)
        val p =
            Readjustments.preview(
                source(listOf(w, history)).copy(phases = listOf(past, ongoing, future)),
                request(ReadjustmentMode.RACE)
                    .copy(raceDate = LocalDate.parse("2026-12-22"), adjustPhases = true),
                today,
            )
        assertEquals("2026-12-22", p.goal!!.raceDate)
        assertEquals("2026-12-22", p.preparation!!.end)
        assertEquals(prep.start, p.preparation!!.start)
        assertEquals("2026-12-08", p.workouts.single().after.date)
        assertEquals(2, p.phases.size)
        assertEquals("2026-10-01", p.phases.first { it.after.id == "ongoing" }.after.start)
        assertEquals("2026-11-08", p.phases.first { it.after.id == "ongoing" }.after.end)
        assertEquals("2026-11-01", p.phases.first { it.after.id == "future" }.after.start)
    }

    @Test
    fun outsidePreparationCompetitionPhaseAndSharedGoalAreExplained() {
        val w = workout(date = "2026-12-15")
        val phase = PreparationSubGoal("phase", "p", "Seuil", "2026-12-01", "2026-12-15")
        val s =
            source(listOf(w))
                .copy(
                    phases = listOf(phase),
                    links = listOf(WorkoutSubGoal(w.id, phase.id)),
                    preparations = listOf(prep, prep.copy(id = "other", name = "Autre préparation")),
                )
        val shifted =
            Readjustments.preview(
                s,
                request().copy(from = LocalDate.parse(w.date), to = LocalDate.parse(w.date)),
                today,
            )
        assertTrue(shifted.warnings.any { "hors période" in it })
        assertTrue(shifted.warnings.any { "après la compétition" in it })
        assertTrue(shifted.warnings.any { "hors du sous-objectif" in it })
        val race =
            Readjustments.preview(
                s,
                request(ReadjustmentMode.RACE).copy(raceDate = LocalDate.parse("2026-12-22")),
                today,
            )
        assertTrue(race.warnings.any { "Objectif partagé" in it && "Autre préparation" in it })
    }

    @Test
    fun conflictsAreComputedAfterTheWholeBatchHasMoved() {
        val first = workout("first")
        val second = workout("second", today.plusDays(1).toString())
        val preview =
            Readjustments.preview(
                source(listOf(first, second)),
                request().copy(shiftDays = 1),
                today,
            )
        assertEquals(2, preview.workouts.size)
        assertTrue(preview.warnings.isEmpty())
    }

    @Test
    fun garminLinksProtectManualSessionsAndPhaseOnlyChangesWarnAboutRetainedLinks() {
        val w = workout()
        val phase =
            PreparationSubGoal(
                "phase",
                "p",
                "Vitesse",
                today.toString(),
                today.plusDays(2).toString(),
            )
        val activity =
            GarminActivityEntity("a:1", "a", "1", "payload", "fingerprint", workoutId = w.id)
        val s =
            source()
                .copy(
                    phases = listOf(phase),
                    links = listOf(WorkoutSubGoal(w.id, phase.id)),
                    garmin = listOf(activity),
                )
        val preview = Readjustments.preview(s, request().copy(adjustPhases = true), today)
        assertEquals(1, preview.protectedCount)
        assertTrue(preview.workouts.isEmpty())
        assertEquals(1, preview.phases.size)
        assertTrue(preview.warnings.any { "hors du sous-objectif" in it })
        assertEquals(activity, preview.source.garmin.single())
    }

    @Test
    fun invertedPhasesInvalidPercentAndInvalidDateChangesAreRejected() {
        val phase = PreparationSubGoal("phase", "p", "En cours", "2026-10-22", "2026-10-24")
        assertThrows(IllegalArgumentException::class.java) {
            Readjustments.preview(
                source().copy(phases = listOf(phase)),
                request().copy(shiftDays = -7, adjustPhases = true),
                today,
            )
        }
        listOf(0, 91).forEach { percent ->
            assertThrows(IllegalArgumentException::class.java) {
                Readjustments.lighten(WorkoutPlan(), percent)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            Readjustments.preview(source(), request().copy(shiftDays = 0), today)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Readjustments.preview(
                source(),
                request(ReadjustmentMode.RACE).copy(raceDate = today.minusDays(1)),
                today,
            )
        }
    }
}
