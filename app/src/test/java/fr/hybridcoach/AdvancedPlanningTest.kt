package fr.hybridcoach

import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import fr.hybridcoach.export.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class AdvancedPlanningTest {
    private fun nested(): RunningStep {
        val work =
            RunningStep(
                durationKind = DurationKind.DISTANCE,
                amount = 400.0,
                targetKind = TargetKind.PACE,
                target = 195.0,
                resolvedPace = 195.0,
            )
        val inner =
            RunningStep(
                kind = StepKind.REPEAT,
                repetitions = 4,
                children = listOf(work),
                recovery = RunningStep(kind = StepKind.RECOVERY, amount = 60.0),
            )
        return RunningStep(
            kind = StepKind.REPEAT,
            repetitions = 3,
            children = listOf(inner),
            recovery = RunningStep(kind = StepKind.RECOVERY, amount = 180.0, activeRecovery = false),
        )
    }

    @Test
    fun nestedRecoveriesOnlyOccurBetweenRepetitions() {
        val steps =
            listOf(
                RunningStep(kind = StepKind.WARMUP, amount = 1200.0),
                nested(),
                RunningStep(kind = StepKind.COOLDOWN, amount = 600.0),
            )
        validatePlan(WorkoutPlan(running = steps))
        val flat = SportMath.flatten(steps)
        assertEquals(12, flat.count { it.kind == StepKind.WORK })
        assertEquals(9, flat.count { it.kind == StepKind.RECOVERY && it.amount == 60.0 })
        assertEquals(2, flat.count { it.kind == StepKind.RECOVERY && it.amount == 180.0 })
        assertEquals(StepKind.COOLDOWN, flat.last().kind)
        assertEquals(4800.0, SportMath.plannedMeters(steps), .001)
        assertEquals(
            1200.0 + 12 * 78 + 9 * 60 + 2 * 180 + 600,
            SportMath.plannedSeconds(steps),
            .001,
        )
    }

    @Test
    fun distanceRecoveryAndSnapshotSurviveJsonAndCopy() {
        val block =
            nested()
                .copy(
                    recovery =
                        RunningStep(
                            kind = StepKind.RECOVERY,
                            durationKind = DurationKind.DISTANCE,
                            amount = 200.0,
                            targetKind = TargetKind.SPEED,
                            target = 10.0,
                        )
                )
        val resolved = SportMath.snapshot(block, AthleteProfile())
        assertEquals(5200.0, SportMath.plannedMeters(listOf(resolved)), .001)
        assertEquals(72.0, SportMath.plannedSeconds(listOf(resolved.recovery!!)), .001)
        val decoded =
            codec.decodeFromString<WorkoutPlan>(
                codec.encodeToString(WorkoutPlan(running = listOf(resolved)))
            )
        assertEquals(resolved, decoded.running.single())
        val copied = decoded.freshCopy().running.single()
        assertNotEquals(resolved.id, copied.id)
        assertNotEquals(
            resolved.children.single().children.single().id,
            copied.children.single().children.single().id,
        )
        assertNotEquals(resolved.recovery!!.id, copied.recovery!!.id)
        assertEquals(blockSummary(resolved), blockSummary(copied))
    }

    @Test
    fun duplicatingInnerBlockPreservesOrderAndIndependentIds() {
        val outer = nested()
        val doubled = duplicateStep(listOf(outer), outer.children.single().id).single()
        assertEquals(2, doubled.children.size)
        assertNotEquals(doubled.children[0].id, doubled.children[1].id)
        assertNotEquals(doubled.children[0].recovery!!.id, doubled.children[1].recovery!!.id)
        assertEquals(outer.children.single(), doubled.children[0])
        assertEquals(outer.id, doubled.id)
    }

    @Test
    fun invalidRecoveryAndExcessiveExpansionAreRejected() {
        listOf(
                nested().copy(recovery = RunningStep(amount = 10.0)),
                nested().copy(recovery = RunningStep(kind = StepKind.RECOVERY, amount = -1.0)),
                nested()
                    .copy(repetitions = 100, children = listOf(nested().copy(repetitions = 100))),
            )
            .forEach {
                assertThrows(IllegalArgumentException::class.java) {
                    validatePlan(WorkoutPlan(running = listOf(it)))
                }
            }
    }

    @Test
    fun exportIncludesOverlappingPhasesLinksAndRecoveryWithoutPrivateNotes() {
        val prep =
            TrainingPreparation(
                id = "p",
                goalId = "g",
                name = "Marathon",
                start = "2026-08-17",
                end = "2026-12-15",
            )
        val goals =
            listOf(
                PreparationSubGoal(
                    id = "a",
                    preparationId = "p",
                    name = "Vitesse",
                    start = "2026-08-17",
                    end = "2026-09-15",
                ),
                PreparationSubGoal(
                    id = "b",
                    preparationId = "p",
                    name = "VO2max",
                    start = "2026-08-31",
                    end = "2026-09-20",
                ),
            )
        val block =
            nested().copy(recovery = nested().recovery!!.copy(notes = "secret récupération"))
        val workout =
            PlannedWorkout(
                id = "w",
                name = "Fractionné",
                date = "2026-09-01",
                sport = Sport.OTHER,
                preparationId = "p",
                planJson = codec.encodeToString(WorkoutPlan(running = listOf(block))),
            )
        val state =
            CoachState(
                preparations = listOf(prep),
                goals = listOf(TrainingGoal(id = "g", name = "Marathon", raceDate = prep.end)),
                subGoals = goals,
                workouts = listOf(workout),
                workoutSubGoals = listOf(WorkoutSubGoal("w", "a"), WorkoutSubGoal("w", "b")),
            )
        val document = PreparationExport.document(state, "p", ExportOptions())
        val decoded = codec.decodeFromString<PreparationDocument>(PreparationExport.json(document))
        assertEquals(goals, decoded.subGoals)
        val exported = decoded.weeks.flatMap { it.sessions }.single()
        assertEquals(setOf("a", "b"), exported.subGoalIds.toSet())
        assertEquals("", exported.planned.running.single().recovery!!.notes)
        val text = PreparationExport.lines(decoded).joinToString("\n")
        assertTrue(text.contains("Vitesse : 2026-08-17 → 2026-09-15"))
        assertTrue(text.contains("Sous-objectif : VO2max"))
        assertTrue(text.contains("Entre les répétitions uniquement"))
        assertFalse(PreparationExport.json(decoded).contains("secret récupération"))
        assertEquals(2, decoded.version)
    }

    @Test
    fun legacyPlansAndVersionOneDocumentsRemainReadable() {
        val decoded =
            codec.decodeFromString<WorkoutPlan>(
                """{"running":[{"kind":"REPEAT","repetitions":4,"children":[{"amount":60}]}]}"""
            )
        assertNull(decoded.running.single().recovery)
        assertEquals(240.0, SportMath.plannedSeconds(decoded.running), .001)
        val raw =
            """{"version":1,"exportedAt":"2026-01-01","options":{},"goal":{"id":"g","name":"Course","raceDate":"2026-01-01"},"preparation":{"id":"p","goalId":"g","name":"Plan","start":"2026-01-01","end":"2026-01-01"},"weeks":[]}"""
        assertTrue(codec.decodeFromString<PreparationDocument>(raw).subGoals.isEmpty())
    }
}
