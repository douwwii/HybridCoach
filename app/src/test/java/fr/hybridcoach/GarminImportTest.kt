package fr.hybridcoach

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import fr.hybridcoach.garmin.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GarminImportTest {
    private lateinit var db: CoachDatabase
    private lateinit var repo: CoachRepository
    private lateinit var importer: GarminImporter

    @Before
    fun setup() {
        db =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    CoachDatabase::class.java,
                )
                .build()
        repo = CoachRepository(db)
        importer = GarminImporter(db)
    }

    @After
    fun cleanup() {
        db.close()
    }

    private fun activity(
        id: String = "1",
        date: String = "2026-10-19",
        distance: Double = 11000.0,
        hash: String = "a",
    ) =
        GarminActivity(
            id,
            "Seuil",
            Sport.RUN,
            "running",
            date + "T06:00:00Z",
            date + "T08:00:00",
            distance,
            3300.0,
            fingerprint = hash.repeat(64),
        )

    private fun snapshot(
        vararg activities: GarminActivity,
        revision: Long = 1,
        deleted: List<String> = emptyList(),
        complete: Boolean = true,
    ) = GarminSnapshot("account", revision, activities.toList(), deleted, complete)

    private fun workout(id: String = "w", date: String = "2026-10-19") =
        PlannedWorkout(
            id = id,
            name = "Seuil",
            date = date,
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
    fun personalApiAdoptsBridgeLinksWithoutDuplicatingOrOverwritingLocalCorrections() =
        runBlocking {
            val legacy = "b".repeat(64)
            val remote = activity()
            repo.saveWorkout(workout(), null, emptySet(), false)
            importer.import(GarminSnapshot(legacy, 200, listOf(remote)))
            val row = db.dao().allGarmin().single()
            val result = db.dao().result(row.workoutId!!)!!
            val corrected =
                parseResult(result.payload).copy(distanceKm = "10.5", notes = "Correction locale")
            db.dao().putResult(result.copy(payload = codec.encodeToString(corrected)))
            val personal =
                remote.copy(localDateTime = "2026-10-19T08:00", fingerprint = "c".repeat(64))
            assertEquals(
                0,
                importer.importPersonal(GarminSnapshot("personal:42", 1, listOf(personal))),
            )
            val migrated = db.dao().allGarmin().single()
            assertEquals("personal:42:1", migrated.key)
            assertEquals(row.workoutId, migrated.workoutId)
            assertEquals(row.originalWorkout, migrated.originalWorkout)
            assertEquals(corrected, parseResult(db.dao().result(row.workoutId!!)!!.payload))
            assertEquals(1, db.dao().allWorkouts().size)
            assertNull(db.dao().syncForAccount(legacy))
            assertEquals(
                0,
                importer.importPersonal(GarminSnapshot("personal:42", 2, listOf(personal))),
            )
        }

    @Test
    fun personalApiMigrationRollsBackWhenSnapshotIsIncomplete() = runBlocking {
        val legacy = "b".repeat(64)
        importer.import(GarminSnapshot(legacy, 1, listOf(activity())))
        val before = db.dao().allGarmin().single()
        val failure =
            runCatching {
                    importer.importPersonal(
                        GarminSnapshot("personal:42", 1, listOf(activity()), complete = false)
                    )
                }
                .exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(before, db.dao().allGarmin().single())
        assertNotNull(db.dao().syncForAccount(legacy))
        assertNull(db.dao().syncForAccount("personal:42"))
    }

    @Test
    fun personalApiDoesNotAdoptAnUnprovenAccount() = runBlocking {
        val legacy = "b".repeat(64)
        importer.import(GarminSnapshot(legacy, 1, listOf(activity())))
        importer.importPersonal(GarminSnapshot("personal:42", 1, listOf(activity(id = "2"))))
        assertEquals(
            setOf(legacy, "personal:42"),
            db.dao().allGarmin().map { it.accountId }.toSet(),
        )
        assertNotNull(db.dao().syncForAccount(legacy))
    }

    @Test
    fun automaticImportPreservesPlanAndAvoidsDuplicateWorkoutsAndKilometers() = runBlocking {
        val original = workout()
        repo.saveWorkout(original, null, emptySet(), false)
        assertEquals(1, importer.import(snapshot(activity())))
        val state = repo.state.first()
        assertEquals(1, state.workouts.size)
        assertEquals(original.planJson, state.workouts.single().planJson)
        assertEquals(Status.DONE, state.workouts.single().status)
        assertEquals("GARMIN", state.workouts.single().source)
        assertEquals(
            11.0,
            Statistics.weeks(state, "2026-10-19".asDate(), 1).single().actualKm,
            .001,
        )
        assertEquals(
            12.0,
            Statistics.weeks(state, "2026-10-19".asDate(), 1).single().plannedKm,
            .001,
        )
        assertEquals(0, importer.import(snapshot(activity(), revision = 2)))
        assertEquals(1, repo.dao.allWorkouts().size)
    }

    @Test
    fun ambiguousAndCollidingActivitiesStayUncountedUntilResolved() = runBlocking {
        repo.dao.putWorkout(workout("a"))
        repo.dao.putWorkout(workout("b"))
        importer.import(snapshot(activity()))
        assertNull(repo.dao.garminActivity("account:1")!!.workoutId)
        assertEquals(
            0,
            Statistics.weeks(repo.state.first(), "2026-10-19".asDate(), 1).single().sessions,
        )
        importer.resolve("account:1", "a")
        assertEquals(
            1,
            Statistics.weeks(repo.state.first(), "2026-10-19".asDate(), 1).single().sessions,
        )
        assertEquals(2, repo.dao.allWorkouts().size)
    }

    @Test
    fun twoActivitiesCompetingForOnePlanAreBothReviewed() = runBlocking {
        repo.dao.putWorkout(workout())
        importer.import(snapshot(activity("1"), activity("2")))
        assertTrue(repo.dao.allGarmin().all { it.workoutId == null && it.resolution == "PENDING" })
        assertEquals(Status.PLANNED, repo.dao.workout("w")!!.status)
    }

    @Test
    fun manuallyCompletedSessionIsCountedOnceAndRestoredOnUnlink() = runBlocking {
        val original = workout().copy(status = Status.DONE)
        repo.dao.putWorkout(original)
        repo.dao.putResult(
            ResultEntity(
                "w",
                codec.encodeToString(
                    WorkoutResult(
                        distanceKm = "11",
                        durationSeconds = "3300",
                        notes = "notes locales",
                        rpe = "6",
                    )
                ),
            )
        )
        importer.import(snapshot(activity()))
        assertEquals(1, repo.dao.allWorkouts().size)
        importer.unlink("account:1")
        assertEquals(original, repo.dao.workout("w"))
        assertEquals("11", parseResult(repo.dao.result("w")!!.payload).distanceKm)
        assertEquals("notes locales", parseResult(repo.dao.result("w")!!.payload).notes)
        assertNull(repo.dao.garminActivity("account:1")!!.workoutId)
    }

    @Test
    fun realDateControlsActualStatsWhilePlannedDateRetainsOriginalWeek() = runBlocking {
        repo.dao.putWorkout(workout(date = "2026-10-18"))
        importer.import(snapshot(activity()))
        importer.resolve("account:1", "w")
        val w = repo.dao.workout("w")!!
        assertEquals("2026-10-19", w.date)
        assertEquals("2026-10-18", w.plannedDate)
        val weeks = Statistics.weeks(repo.state.first(), "2026-10-12".asDate(), 2)
        assertEquals(12.0, weeks[0].plannedKm, .001)
        assertEquals(0.0, weeks[0].actualKm, .001)
        assertEquals(0.0, weeks[1].plannedKm, .001)
        assertEquals(11.0, weeks[1].actualKm, .001)
    }

    @Test
    fun remoteEditsUpdateSameRecordButUnchangedSyncPreservesLocalCorrections() = runBlocking {
        importer.import(snapshot(activity()))
        val id = repo.dao.allGarmin().single().workoutId!!
        repo.mutateResult(id) {
            it.copy(notes = "ressenti personnel", rpe = "8", distanceKm = "10.9")
        }
        importer.import(snapshot(activity(), revision = 2))
        assertEquals("10.9", parseResult(repo.dao.result(id)!!.payload).distanceKm)
        importer.import(snapshot(activity(distance = 12500.0, hash = "b"), revision = 3))
        val result = parseResult(repo.dao.result(id)!!.payload)
        assertEquals("12.5", result.distanceKm)
        assertEquals("ressenti personnel", result.notes)
        assertEquals("8", result.rpe)
        assertEquals(id, repo.dao.allGarmin().single().workoutId)
    }

    @Test
    fun remoteDeletesRestorePlansAndRemoveOnlyGeneratedOccurrences() = runBlocking {
        repo.dao.putWorkout(workout())
        importer.import(snapshot(activity()))
        importer.import(snapshot(revision = 2, deleted = listOf("1")))
        assertEquals(Status.PLANNED, repo.dao.workout("w")!!.status)
        assertNull(repo.dao.result("w"))
        assertTrue(repo.dao.allGarmin().single().deleted)
        importer.import(snapshot(activity("2", date = "2026-10-25"), revision = 3))
        assertEquals(2, repo.dao.allWorkouts().size)
        importer.import(snapshot(revision = 4, deleted = listOf("2")))
        assertEquals(listOf("w"), repo.dao.allWorkouts().map { it.id })
    }

    @Test
    fun deletingOrIgnoringLocallyDoesNotReimportOnRemoteModification() = runBlocking {
        importer.import(snapshot(activity()))
        val id = repo.dao.allGarmin().single().workoutId!!
        repo.delete(id)
        importer.import(snapshot(activity(hash = "b"), revision = 2))
        assertTrue(repo.dao.allWorkouts().isEmpty())
        assertEquals("IGNORED", repo.dao.allGarmin().single().resolution)
    }

    @Test
    fun uniqueAssociationReassignmentRollbackAndInvalidSnapshot() = runBlocking {
        repo.dao.putWorkout(workout())
        importer.import(snapshot(activity("1"), activity("2", date = "2026-10-23")))
        try {
            importer.resolve("account:2", "w")
            fail("Already occupied")
        } catch (_: IllegalArgumentException) {}
        assertEquals(2, repo.dao.allWorkouts().size)
        assertEquals("w", repo.dao.garminActivity("account:1")!!.workoutId)
        try {
            importer.import(snapshot(activity("3"), revision = 2, complete = false))
            fail("Incomplete")
        } catch (_: IllegalArgumentException) {}
        assertEquals(2, repo.dao.allGarmin().size)
    }

    @Test
    fun unplannedOtherSportsHaveActualDurationButNoInventedPlannedVolume() = runBlocking {
        importer.import(snapshot(activity().copy(sport = Sport.OTHER, sportKey = "cycling")))
        val week = Statistics.weeks(repo.state.first(), "2026-10-19".asDate(), 1).single()
        assertEquals(1, week.sessions)
        assertEquals(3300.0, week.trainingSeconds, .001)
        assertEquals(0.0, week.plannedKm, .001)
        assertEquals(0.0, week.actualKm, .001)
    }

    @Test
    fun changingBridgeIdentityCannotDuplicateExistingRemoteIds() = runBlocking {
        importer.import(snapshot(activity()))
        try {
            importer.import(snapshot(activity()).copy(accountId = "different-service"))
            fail("Duplicate identity")
        } catch (_: IllegalArgumentException) {}
        assertEquals(1, repo.dao.allWorkouts().size)
    }

    @Test
    fun versionTwoMigrationPreservesPhasesLinksAndNestedPlans() = runBlocking {
        db.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val filename = "garmin-migration.db"
        context.deleteDatabase(filename)
        val schema =
            org.json
                .JSONObject(
                    javaClass.getResourceAsStream("/database-v2.json")!!.bufferedReader().use {
                        it.readText()
                    }
                )
                .getJSONObject("database")
        context.openOrCreateDatabase(filename, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(
                    entity
                        .getString("createSql")
                        .replace("\${TABLE_NAME}", entity.getString("tableName"))
                )
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(
                    indices
                        .getJSONObject(j)
                        .getString("createSql")
                        .replace("\${TABLE_NAME}", entity.getString("tableName"))
                )
            }
            old.execSQL(
                "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)"
            )
            old.execSQL(
                "INSERT INTO room_master_table VALUES (42,?)",
                arrayOf(schema.getString("identityHash")),
            )
            old.execSQL("INSERT INTO goals VALUES ('g','Marathon','2026-12-15',42.195,12600,'','')")
            old.execSQL(
                "INSERT INTO preparations VALUES ('p','g','Plan','2026-08-17','2026-12-15',0)"
            )
            val w = workout()
            old.execSQL(
                "INSERT INTO workouts VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf(
                    w.id,
                    w.name,
                    w.date,
                    w.sport.name,
                    "",
                    "",
                    "",
                    Status.PLANNED.name,
                    "p",
                    w.planJson,
                    null,
                ),
            )
            old.execSQL(
                "INSERT INTO subGoals VALUES ('phase','p','Vitesse','2026-08-17','2026-09-15',0)"
            )
            old.execSQL("INSERT INTO workoutSubGoals VALUES ('w','phase')")
            old.version = 2
        }
        db =
            Room.databaseBuilder(context, CoachDatabase::class.java, filename)
                .addMigrations(MIGRATION_2_3)
                .build()
        repo = CoachRepository(db)
        val state = repo.state.first()
        assertEquals("MANUAL", state.workouts.single().source)
        assertNull(state.workouts.single().plannedDate)
        assertEquals(
            workout().planJson.let { parsePlan(it).running.single().amount },
            parsePlan(state.workouts.single().planJson).running.single().amount,
        )
        assertEquals("phase", state.workoutSubGoals.single().subGoalId)
        assertEquals("Vitesse", state.subGoals.single().name)
        assertTrue(state.garminActivities.isEmpty())
        db.close()
        context.deleteDatabase(filename)
        Unit
    }

    @Test
    fun sportChangesUpdateUnplannedRecordsAndReopenIncompatibleAssociations() = runBlocking {
        repo.dao.putWorkout(workout())
        importer.import(snapshot(activity()))
        importer.import(
            snapshot(
                activity(hash = "b").copy(sport = Sport.STRENGTH, sportKey = "strength_training"),
                revision = 2,
            )
        )
        assertEquals(Status.PLANNED, repo.dao.workout("w")!!.status)
        assertNull(repo.dao.garminActivity("account:1")!!.workoutId)
        importer.resolve("account:1", null)
        assertEquals(
            Sport.STRENGTH,
            repo.dao.workout(repo.dao.garminActivity("account:1")!!.workoutId!!)!!.sport,
        )
        importer.import(
            snapshot(
                activity(hash = "c").copy(sport = Sport.OTHER, sportKey = "cycling"),
                revision = 3,
            )
        )
        assertEquals(
            Sport.OTHER,
            repo.dao.workout(repo.dao.garminActivity("account:1")!!.workoutId!!)!!.sport,
        )
    }
}
