package fr.hybridcoach

import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import fr.hybridcoach.garmin.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class GarminMatcherTest {
    private fun activity() =
        GarminActivity(
            "1",
            "Seuil",
            Sport.RUN,
            "running",
            "2026-10-19T06:00:00Z",
            "2026-10-19T08:00:00",
            11000.0,
            3300.0,
            fingerprint = "a".repeat(64),
        )

    private fun workout(id: String = "w") =
        PlannedWorkout(
            id = id,
            name = "Seuil",
            date = "2026-10-19",
            sport = Sport.RUN,
            planJson =
                codec.encodeToString(
                    WorkoutPlan(
                        running =
                            listOf(
                                RunningStep(
                                    durationKind = DurationKind.DISTANCE,
                                    amount = 12000.0,
                                    targetKind = TargetKind.PACE,
                                    target = 300.0,
                                    resolvedPace = 300.0,
                                )
                            )
                    )
                ),
        )

    @Test
    fun toleratesElevenKilometersForTwelvePlanned() {
        assertEquals(
            "w",
            GarminMatcher.match(activity(), listOf(workout()), emptyMap(), emptySet()).automatic,
        )
    }

    @Test
    fun equallyLikelySessionsRequireConfirmation() {
        val match =
            GarminMatcher.match(
                activity(),
                listOf(workout("a"), workout("b")),
                emptyMap(),
                emptySet(),
            )
        assertNull(match.automatic)
        assertEquals(2, match.candidates.size)
    }

    @Test
    fun completedManualMeasurementsAreUsedForDeduplication() {
        val w = workout().copy(status = Status.DONE, planJson = codec.encodeToString(WorkoutPlan()))
        assertEquals(
            "w",
            GarminMatcher.match(
                    activity(),
                    listOf(w),
                    mapOf("w" to WorkoutResult(distanceKm = "11", durationSeconds = "3300")),
                    emptySet(),
                )
                .automatic,
        )
    }

    @Test
    fun excludesWrongSportDistantDaysAndAlreadyLinkedSessions() {
        assertTrue(
            GarminMatcher.match(
                    activity(),
                    listOf(
                        workout().copy(sport = Sport.STRENGTH),
                        workout("b").copy(date = "2026-10-16"),
                    ),
                    emptyMap(),
                    emptySet(),
                )
                .candidates
                .isEmpty()
        )
        assertTrue(
            GarminMatcher.match(activity(), listOf(workout()), emptyMap(), setOf("w"))
                .candidates
                .isEmpty()
        )
    }

    @Test
    fun uncertainOtherSportsAndActiveSessionsNeverAutoFinish() {
        assertNull(
            GarminMatcher.match(
                    activity().copy(sport = Sport.OTHER),
                    listOf(workout().copy(sport = Sport.OTHER)),
                    emptyMap(),
                    emptySet(),
                )
                .automatic
        )
        assertNull(
            GarminMatcher.match(
                    activity(),
                    listOf(workout().copy(status = Status.ACTIVE)),
                    emptyMap(),
                    emptySet(),
                )
                .automatic
        )
    }

    @Test
    fun optionalTimeBreaksOtherwiseEqualCandidates() {
        val match =
            GarminMatcher.match(
                activity(),
                listOf(
                    workout("a").copy(plannedTime = "08:00"),
                    workout("b").copy(plannedTime = "18:00"),
                ),
                emptyMap(),
                emptySet(),
            )
        assertEquals("a", match.candidates.first().workoutId)
    }

    @Test
    fun personalKeyRejectsWhitespaceAndControlCharacters() {
        listOf("", "a b", "a\nb", "a\rb", "clé", "x".repeat(4097)).forEach {
            assertThrows(IllegalArgumentException::class.java) { validatePersonalKey(it) }
        }
        validatePersonalKey("personal.key_123-456")
    }
}
