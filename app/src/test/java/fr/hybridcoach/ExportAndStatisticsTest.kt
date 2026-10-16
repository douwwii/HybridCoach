package fr.hybridcoach

import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import fr.hybridcoach.export.*
import java.time.LocalDate
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ExportAndStatisticsTest {
    private val plan =
        WorkoutPlan(
            strength =
                listOf(
                    StrengthExercise(
                        id = "instance",
                        exerciseId = "ex",
                        name = "Squat",
                        muscle = "Quadriceps",
                        notes = "PRIVATE_EXERCISE",
                    )
                ),
            running =
                listOf(
                    RunningStep(
                        kind = StepKind.REPEAT,
                        repetitions = 2,
                        notes = "PRIVATE_BLOCK",
                        children =
                            listOf(
                                RunningStep(
                                    durationKind = DurationKind.DISTANCE,
                                    amount = 1000.0,
                                    notes = "PRIVATE_STEP",
                                )
                            ),
                    )
                ),
        )
    private val goal =
        TrainingGoal(id = "g", name = "10 km", raceDate = "2026-12-01", notes = "PRIVATE_GOAL")
    private val prep =
        TrainingPreparation(
            id = "p",
            goalId = "g",
            name = "Préparation",
            start = "2026-10-05",
            end = "2026-10-18",
        )
    private val workout =
        PlannedWorkout(
            id = "w",
            name = "Séance",
            date = "2026-10-06",
            sport = Sport.RUN,
            preparationId = "p",
            status = Status.DONE,
            notes = "PRIVATE_WORKOUT",
            planJson = codec.encodeToString(plan),
        )
    private val result =
        WorkoutResult(
            distanceKm = "3,5",
            durationSeconds = "1500",
            rpe = "7",
            notes = "PRIVATE_RESULT",
            feeling = "PRIVATE_FEELING",
            sets =
                listOf(
                    SetLog("instance", 0, "80", "10", true),
                    SetLog("instance", 1, "100", "5", false),
                ),
        )
    private val state =
        CoachState(
            workouts = listOf(workout),
            goals = listOf(goal),
            preparations = listOf(prep),
            results = mapOf("w" to result),
            loaded = true,
        )

    @Test
    fun notesAreRecursivelyRedacted() {
        val doc = PreparationExport.document(state, "p", ExportOptions(), "2026-10-16T12:00:00Z")
        assertFalse(PreparationExport.json(doc).contains("PRIVATE"))
        assertFalse(PreparationExport.lines(doc).joinToString().contains("PRIVATE"))
        assertEquals("7", doc.weeks.first().sessions.first().result!!.rpe)
    }

    @Test
    fun explicitNotesIncluded() {
        assertTrue(
            PreparationExport.json(PreparationExport.document(state, "p", ExportOptions(true)))
                .contains("PRIVATE_STEP")
        )
    }

    @Test
    fun resultsCanBeExcluded() {
        val doc = PreparationExport.document(state, "p", ExportOptions(performances = false))
        assertNull(doc.weeks.first().sessions.first().result)
        assertNull(doc.weeks.first().actualKm)
    }

    @Test
    fun versionedJsonRoundTrips() {
        val doc = PreparationExport.document(state, "p", ExportOptions(), "test")
        val decoded = codec.decodeFromString<PreparationDocument>(PreparationExport.json(doc))
        assertEquals(2, decoded.version)
        assertEquals(doc, decoded)
        assertEquals(2, decoded.weeks.size)
    }

    @Test
    fun outsideDatesAreNotSilentlyDropped() {
        val extra = workout.copy(id = "outside", date = "2026-11-01")
        val doc =
            PreparationExport.document(
                state.copy(workouts = listOf(workout, extra)),
                "p",
                ExportOptions(),
            )
        assertEquals(2, doc.weeks.sumOf { it.sessions.size })
        assertTrue(doc.weeks.size > 2)
    }

    @Test
    fun longNotesAndUnicodePreserved() {
        val text = "À l’effort — 夏 🏃\n".repeat(1000)
        val doc =
            PreparationExport.document(
                state.copy(goals = listOf(goal.copy(notes = text))),
                "p",
                ExportOptions(true),
            )
        assertEquals(
            text,
            codec.decodeFromString<PreparationDocument>(PreparationExport.json(doc)).goal.notes,
        )
    }

    @Test
    fun actualStatsExcludePlannedAndCancelled() {
        val changed =
            state.copy(
                workouts =
                    listOf(
                        workout,
                        workout.copy(id = "planned", status = Status.PLANNED),
                        workout.copy(id = "cancelled", status = Status.CANCELLED),
                    ),
                results = mapOf("w" to result, "planned" to result, "cancelled" to result),
            )
        val week = Statistics.weeks(changed, LocalDate.parse("2026-10-05"), 1).first()
        assertEquals(3.5, week.actualKm, 0.0)
        assertEquals(4.0, week.plannedKm, 0.0)
        assertEquals(1, week.sessions)
    }

    @Test
    fun onlyCheckedSetsCount() {
        val records = Statistics.exerciseHistory(state, "ex")
        assertEquals(1, records.size)
        assertEquals(800.0, records.single().volume, 0.0)
        val muscles = Statistics.muscles(state, LocalDate.MIN)
        assertEquals(1, muscles.single().sets)
    }

    @Test
    fun previousSessionUsesLatestCompleted() {
        val prior = workout.copy(id = "old", date = "2026-10-01")
        val s = state.copy(workouts = listOf(workout, prior), results = mapOf("old" to result))
        assertEquals(1, Statistics.previousSets(s, workout, plan.strength.single()).size)
    }

    @Test
    fun emptyHistoryHasNoArtificialData() {
        assertEquals(
            0.0,
            Statistics.weeks(CoachState(), LocalDate.now(), 4).sumOf { it.actualKm },
            0.0,
        )
        assertTrue(Statistics.exerciseHistory(CoachState(), "ex").isEmpty())
    }
}
