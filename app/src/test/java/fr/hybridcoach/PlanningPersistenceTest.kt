package fr.hybridcoach

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PlanningPersistenceTest {
    private val context
        get() = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var db: CoachDatabase
    private lateinit var repo: CoachRepository
    private val filename = "planning-v2.db"

    @Before
    fun setup() {
        context.deleteDatabase(filename)
        open()
    }

    private fun open() {
        db =
            Room.databaseBuilder(context, CoachDatabase::class.java, filename)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
        repo = CoachRepository(db)
    }

    @After
    fun cleanup() {
        db.close()
        context.deleteDatabase(filename)
    }

    private suspend fun prepare() {
        repo.savePreparation(
            TrainingPreparation(
                id = "p",
                goalId = "g",
                name = "Marathon",
                start = "2026-08-17",
                end = "2026-12-15",
            ),
            TrainingGoal(id = "g", name = "Marathon", raceDate = "2026-12-15"),
        )
        repo.saveSubGoal(
            PreparationSubGoal(
                id = "a",
                preparationId = "p",
                name = "Vitesse",
                start = "2026-08-17",
                end = "2026-09-15",
            )
        )
        repo.saveSubGoal(
            PreparationSubGoal(
                id = "b",
                preparationId = "p",
                name = "VO2max",
                start = "2026-08-31",
                end = "2026-09-20",
            )
        )
    }

    private fun workout() =
        PlannedWorkout(
            id = "w",
            name = "Intervalles",
            date = "2026-09-01",
            sport = Sport.RUN,
            preparationId = "p",
            planJson =
                codec.encodeToString(
                    WorkoutPlan(
                        running =
                            listOf(
                                RunningStep(
                                    kind = StepKind.REPEAT,
                                    repetitions = 4,
                                    children = listOf(RunningStep(amount = 60.0)),
                                    recovery = RunningStep(kind = StepKind.RECOVERY, amount = 120.0),
                                )
                            )
                    )
                ),
        )

    @Test
    fun overlapsAndMultipleLinksSurviveReopenAndPeriodEdits() = runBlocking {
        prepare()
        repo.saveWorkout(workout(), null, emptySet(), false, setOf("a", "b"))
        val phase = repo.dao.subGoal("a")!!
        repo.saveSubGoal(phase.copy(end = "2026-09-30"))
        db.close()
        open()
        assertEquals(setOf("a", "b"), repo.dao.subGoalIds("w").toSet())
        assertEquals("2026-09-30", repo.dao.subGoal("a")!!.end)
        assertEquals(2, repo.state.first().subGoals.size)
        assertEquals(4, parsePlan(repo.dao.workout("w")!!.planJson).running.single().repetitions)
    }

    @Test
    fun duplicateWeekCopiesLinksAndMoveUsesSameCalendarRecord() = runBlocking {
        prepare()
        repo.saveWorkout(workout(), null, emptySet(), false, setOf("a", "b"))
        repo.copyWeek("2026-08-31".asDate(), "2026-09-07".asDate(), 1, "p")
        val copy = repo.dao.workouts().first().first { it.id != "w" }
        assertEquals(setOf("a", "b"), repo.dao.subGoalIds(copy.id).toSet())
        val originalStep = parsePlan(repo.dao.workout("w")!!.planJson).running.single()
        val copyStep = parsePlan(copy.planJson).running.single()
        assertNotEquals(originalStep.id, copyStep.id)
        assertNotEquals(originalStep.recovery!!.id, copyStep.recovery!!.id)
        repo.move(copy.id, "2026-09-09".asDate())
        assertEquals("2026-09-09", repo.state.first().workouts.first { it.id == copy.id }.date)
        assertEquals(2, repo.dao.workouts().first().size)
        assertNull(repo.dao.result(copy.id))
    }

    @Test
    fun recurrenceAssignsAllOccurrencesAndRejectsInvalidLinksAtomically() = runBlocking {
        prepare()
        repo.saveWorkout(
            workout(),
            "2026-09-15".asDate(),
            setOf(java.time.DayOfWeek.TUESDAY),
            false,
            setOf("a", "b"),
        )
        val instances = repo.dao.workouts().first()
        assertEquals(3, instances.size)
        instances.forEach { assertEquals(setOf("a", "b"), repo.dao.subGoalIds(it.id).toSet()) }
        val before = instances.first()
        try {
            repo.saveWorkout(
                before.copy(name = "Invalid"),
                null,
                emptySet(),
                false,
                setOf("missing"),
            )
            fail("Expected invalid association")
        } catch (_: IllegalArgumentException) {}
        assertEquals(before, repo.dao.workout(before.id))
        assertEquals(setOf("a", "b"), repo.dao.subGoalIds(before.id).toSet())
    }

    @Test
    fun reassociationAndDeletionRemoveIncompatibleLinksOnly() = runBlocking {
        prepare()
        repo.saveWorkout(workout(), null, emptySet(), false, setOf("a", "b"))
        repo.dao.deleteSubGoal("a")
        assertEquals(listOf("b"), repo.dao.subGoalIds("w"))
        assertNotNull(repo.dao.workout("w"))
        repo.associate("w", null)
        assertTrue(repo.dao.subGoalIds("w").isEmpty())
        repo.associate("w", "p")
        repo.assignSubGoals("w", setOf("b"))
        repo.delete("w")
        assertTrue(repo.dao.workoutSubGoals().first().isEmpty())
        assertNotNull(repo.dao.subGoal("b"))
    }

    @Test
    fun invalidPeriodDoesNotOverwriteExistingPhase() = runBlocking {
        prepare()
        val old = repo.dao.subGoal("a")!!
        try {
            repo.saveSubGoal(old.copy(start = "2027-01-01"))
            fail("Invalid period")
        } catch (_: IllegalArgumentException) {}
        assertEquals(old, repo.dao.subGoal("a"))
    }

    @Test
    fun pdfRendersSubGoalPeriodsAndStructuredRecoveries() = runBlocking {
        prepare()
        repo.saveWorkout(workout(), null, emptySet(), false, setOf("a", "b"))
        val document =
            fr.hybridcoach.export.PreparationExport.document(
                repo.state.first(),
                "p",
                fr.hybridcoach.export.ExportOptions(),
            )
        val pdf = fr.hybridcoach.export.AndroidExporter.write(context, document, true)
        org.apache.pdfbox.pdmodel.PDDocument.load(pdf).use { parsed ->
            val text = org.apache.pdfbox.text.PDFTextStripper().getText(parsed)
            assertTrue(text.contains("Vitesse"))
            assertTrue(text.contains("2026-09-15"))
            assertTrue(text.contains("Sous-objectif : VO2max"))
            assertTrue(text.contains("4 × bloc"))
            assertTrue(text.contains("Entre les répétitions uniquement"))
        }
        val output =
            java.io.File(
                requireNotNull(System.getProperty("hybridcoach.artifactDir")),
                "advanced-planning.pdf",
            )
        pdf.copyTo(output, overwrite = true)
        Unit
    }

    @Test
    fun realVersionOneFileMigratesWithoutLosingAnyExistingData() = runBlocking {
        db.close()
        context.deleteDatabase(filename)
        val schema =
            JSONObject(
                    javaClass.getResourceAsStream("/database-v1.json")!!.bufferedReader().use {
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
                "INSERT INTO room_master_table VALUES (42, ?)",
                arrayOf(schema.getString("identityHash")),
            )
            old.execSQL(
                "INSERT INTO goals VALUES ('g','Marathon','2026-12-15',42.195,12600,'Haute','notes objectif')"
            )
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
                    "description",
                    "Haute",
                    "notes séance",
                    Status.DONE.name,
                    "p",
                    w.planJson,
                    "series",
                ),
            )
            old.execSQL(
                "INSERT INTO results VALUES ('w', ?)",
                arrayOf(
                    codec.encodeToString(
                        WorkoutResult(distanceKm = "4.2", notes = "résultat conservé")
                    )
                ),
            )
            old.execSQL(
                "INSERT INTO templates VALUES ('t','Modèle','RUN','description',?)",
                arrayOf(w.planJson),
            )
            old.execSQL(
                "INSERT INTO exercises VALUES ('e','Squat','Quadriceps','','Barre','Squat',1)"
            )
            old.version = 1
        }
        open()
        val state = repo.state.first()
        assertEquals("Plan", state.preparations.single().name)
        assertEquals(Status.DONE, state.workouts.single().status)
        assertEquals("notes séance", state.workouts.single().notes)
        assertEquals("résultat conservé", state.results.getValue("w").notes)
        assertEquals("Modèle", state.templates.single().name)
        assertEquals("Squat", state.exercises.single().name)
        assertTrue(state.subGoals.isEmpty())
        assertTrue(state.workoutSubGoals.isEmpty())
        repo.saveSubGoal(
            PreparationSubGoal(
                preparationId = "p",
                name = "Seuil",
                start = "2026-09-01",
                end = "2026-09-15",
            )
        )
        assertEquals(1, repo.dao.subGoals().first().size)
    }
}
