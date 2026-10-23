package fr.hybridcoach

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import fr.hybridcoach.garmin.*
import fr.hybridcoach.planning.*
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReadjustmentPersistenceTest {
    private lateinit var db: CoachDatabase
    private lateinit var repo: CoachRepository
    private val today = LocalDate.parse("2026-10-23")
    private val context
        get() = ApplicationProvider.getApplicationContext<Context>()

    private fun open() {
        db = Room.databaseBuilder(context, CoachDatabase::class.java, "readjust-test.db").build()
        repo = CoachRepository(db)
    }

    @Before
    fun setup() = runBlocking {
        context.deleteDatabase("readjust-test.db")
        open()
        repo.savePreparation(
            TrainingPreparation("p", "g", "Marathon", "2026-10-01", "2026-12-15"),
            TrainingGoal("g", "Marathon", "2026-12-15"),
        )
        repo.saveSubGoal(PreparationSubGoal("phase", "p", "Spécifique", "2026-10-23", "2026-12-01"))
        repo.saveWorkout(
            PlannedWorkout(
                "w",
                "Intervalles",
                today.toString(),
                Sport.RUN,
                notes = "À conserver",
                preparationId = "p",
                seriesId = "series",
                planJson =
                    codec.encodeToString(WorkoutPlan(running = listOf(RunningStep(amount = 600.0)))),
            ),
            null,
            emptySet(),
            false,
            setOf("phase"),
        )
    }

    @After
    fun cleanup() {
        db.close()
        context.deleteDatabase("readjust-test.db")
    }

    private fun request(mode: ReadjustmentMode = ReadjustmentMode.SHIFT) =
        ReadjustmentRequest(mode, "p", today, today.plusDays(6))

    @Test
    fun previewDoesNotWriteAndCommitPersistsAcrossReopenWithLinksAndIds(): Unit = runBlocking {
        val before = repo.dao.workout("w")!!
        val preview = repo.previewReadjustment(request(), today)
        assertEquals(before, repo.dao.workout("w"))
        repo.applyReadjustment(preview, today)
        db.close()
        open()
        assertEquals(before.copy(date = "2026-10-30"), repo.dao.workout("w"))
        assertEquals(listOf("phase"), repo.dao.subGoalIds("w"))
        assertTrue(repo.dao.allResults().isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.applyReadjustment(preview, today) }
        }
    }

    @Test
    fun newCalendarConflictAfterPreviewBlocksAllWrites() = runBlocking {
        val preview =
            repo.previewReadjustment(
                request(ReadjustmentMode.RACE)
                    .copy(raceDate = LocalDate.parse("2026-12-22"), adjustPhases = true),
                today,
            )
        repo.dao.putWorkout(repo.dao.workout("w")!!.copy(id = "other", date = "2026-10-30"))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.applyReadjustment(preview, today) }
        }
        assertEquals("2026-12-15", repo.dao.allGoals().single().raceDate)
        assertEquals(today.toString(), repo.dao.workout("w")!!.date)
        assertEquals("2026-10-23", repo.dao.subGoal("phase")!!.start)
    }

    @Test
    fun resultCreatedAfterPreviewPreventsEditingItsPlan() = runBlocking {
        val preview = repo.previewReadjustment(request(ReadjustmentMode.LIGHTEN), today)
        repo.mutateResult("w") { it.copy(notes = "Brouillon personnel") }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.applyReadjustment(preview, today) }
        }
        assertEquals(
            600.0,
            parsePlan(repo.dao.workout("w")!!.planJson).running.single().amount!!,
            .001,
        )
        assertTrue(repo.previewReadjustment(request(), today).workouts.isEmpty())
    }

    @Test
    fun sqlFailureRollsBackGoalPreparationPhasesAndSessionsTogether() = runBlocking {
        val preview =
            repo.previewReadjustment(
                request(ReadjustmentMode.RACE)
                    .copy(raceDate = LocalDate.parse("2026-12-22"), adjustPhases = true),
                today,
            )
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_adjust BEFORE UPDATE ON workouts BEGIN SELECT RAISE(ABORT, 'test rollback'); END"
        )
        try {
            repo.applyReadjustment(preview, today)
            fail("Expected SQLite rejection")
        } catch (_: android.database.sqlite.SQLiteException) {}
        assertEquals("2026-12-15", repo.dao.allGoals().single().raceDate)
        assertEquals("2026-12-15", repo.dao.preparation("p")!!.end)
        assertEquals("2026-10-23", repo.dao.subGoal("phase")!!.start)
        assertEquals(today.toString(), repo.dao.workout("w")!!.date)
    }

    @Test
    fun lighteningPersistsStructuredPlanAndKeepsStepIdentity() = runBlocking {
        val before = parsePlan(repo.dao.workout("w")!!.planJson).running.single()
        repo.applyReadjustment(
            repo.previewReadjustment(
                request(ReadjustmentMode.LIGHTEN).copy(reductionPercent = 30),
                today,
            ),
            today,
        )
        db.close()
        open()
        assertEquals(
            before.copy(amount = 420.0),
            parsePlan(repo.dao.workout("w")!!.planJson).running.single(),
        )
    }
}
