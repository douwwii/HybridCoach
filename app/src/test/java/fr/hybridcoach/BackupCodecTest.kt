package fr.hybridcoach

import fr.hybridcoach.backup.*
import fr.hybridcoach.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    @Test
    fun completeRoundTripPreservesIdsUnicodeDraftsAndGarminOriginals() {
        val document = BackupFixture.document()
        val json = BackupCodec.encode(document)
        assertEquals(document, BackupCodec.decode(json))
        assertTrue(json.contains("fr.hybridcoach.backup"))
        val restored = BackupCodec.decode(json).data
        assertEquals("à saisir", parseResult(restored.results.last().payload).sets.last().reps)
        assertEquals("weekly-series", restored.workouts.first().seriesId)
        assertEquals("outer", parsePlan(restored.workouts.first().planJson).running.last().id)
        assertTrue(restored.garminActivities.single().originalResult!!.contains("Avant Garmin"))
        assertEquals(3, restored.workoutSubGoals.size)
        assertFalse(json.contains("apiKey"))
        assertFalse(json.contains("access_token"))
    }

    @Test
    fun modifiedOrTruncatedFilesFailIntegrityWithoutLeakingPrivateNotes() {
        val json = BackupCodec.encode(BackupFixture.document())
        val modified = json.replace("Marathon", "Autre course")
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(modified) }
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                BackupCodec.decode(json.take(json.length / 2))
            }
        assertFalse(error.message!!.contains("Correction personnelle"))
    }

    @Test
    fun otherExportsAndFutureVersionsAreRejectedClearly() {
        val json = BackupCodec.encode(BackupFixture.document())
        val root = codec.parseToJsonElement(json).jsonObject
        val future = JsonObject(root + ("version" to JsonPrimitive(2))).toString()
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(future) }
                .message!!
                .contains("version", true)
        )
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                    BackupCodec.decode("""{"format":"fr.hybridcoach.preparation","version":2}""")
                }
                .message!!
                .contains("préparation")
        )
    }

    @Test
    fun checksumSurvivesWhitespaceAndReorderedObjectProperties() {
        val json = BackupCodec.encode(BackupFixture.document())
        fun reverse(value: JsonElement): JsonElement =
            when (value) {
                is JsonObject ->
                    JsonObject(
                        value.toList().reversed().associate { it.first to reverse(it.second) }
                    )
                is JsonArray -> JsonArray(value.map(::reverse))
                else -> value
            }
        val reordered = reverse(codec.parseToJsonElement(json)).toString()
        assertEquals(BackupFixture.document(), BackupCodec.decode("\uFEFF" + reordered))
    }

    @Test
    fun invalidReferencesAndDuplicateIdsAreRejectedBeforeRestore() {
        val original = BackupFixture.document()
        val invalid =
            listOf(
                original.copy(
                    data =
                        original.data.copy(
                            workouts =
                                original.data.workouts.map { it.copy(preparationId = "missing") }
                        )
                ),
                original.copy(
                    data =
                        original.data.copy(
                            exercises = original.data.exercises + original.data.exercises.first()
                        )
                ),
                original.copy(
                    data =
                        original.data.copy(
                            workoutSubGoals =
                                original.data.workoutSubGoals +
                                    original.data.workoutSubGoals.first()
                        )
                ),
                original.copy(
                    data =
                        original.data.copy(
                            garminActivities =
                                original.data.garminActivities.map {
                                    it.copy(workoutId = "missing")
                                }
                        )
                ),
            )
        invalid.forEach { d ->
            assertThrows(IllegalArgumentException::class.java) { BackupCodec.validate(d) }
        }
    }

    @Test
    fun malformedNestedPlansAndNewerDatabaseSchemasAreRejected() {
        val original = BackupFixture.document()
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.validate(original.copy(databaseVersion = 999))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.checkDepth("[".repeat(101) + "]".repeat(101))
        }
        val broken =
            original.copy(
                data =
                    original.data.copy(
                        templates =
                            original.data.templates.map {
                                it.copy(planJson = "private-invalid-json")
                            }
                    )
            )
        assertThrows(Exception::class.java) { BackupCodec.encode(broken) }
    }
}
