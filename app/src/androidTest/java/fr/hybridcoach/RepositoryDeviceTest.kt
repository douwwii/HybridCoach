package fr.hybridcoach

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import fr.hybridcoach.export.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryDeviceTest {
    private lateinit var db: CoachDatabase
    private lateinit var repo: CoachRepository
    private val context
        get() = ApplicationProvider.getApplicationContext<Context>()

    private val filename = "repository-verification.db"

    @Before
    fun setup() {
        context.deleteDatabase(filename)
        open()
    }

    private fun open() {
        db = Room.databaseBuilder(context, CoachDatabase::class.java, filename).build()
        repo = CoachRepository(db)
    }

    @After
    fun cleanup() {
        db.close()
        context.deleteDatabase(filename)
    }

    private fun workout(
        id: String = "w",
        date: String = "2026-10-06",
        status: Status = Status.PLANNED,
        seriesId: String? = null,
    ) =
        PlannedWorkout(
            id = id,
            name = "Test musculation",
            date = date,
            sport = Sport.STRENGTH,
            status = status,
            seriesId = seriesId,
            planJson =
                codec.encodeToString(
                    WorkoutPlan(
                        strength =
                            listOf(
                                StrengthExercise(
                                    id = "instance",
                                    exerciseId = "exercise",
                                    name = "Squat",
                                    muscle = "Quadriceps",
                                )
                            )
                    )
                ),
        )

    @Test
    fun interruptedSessionAndLogsSurviveDatabaseReopen() = runBlocking {
        repo.dao.putWorkout(workout())
        repo.start("w")
        repo.mutateResult("w") {
            it.copy(
                sets = listOf(SetLog("instance", 0, "80", "8", true)),
                notes = "persistée",
                restUntil = 9999999999999L,
            )
        }
        db.close()
        open()
        assertEquals(Status.ACTIVE, repo.dao.workout("w")!!.status)
        val r = parseResult(repo.dao.result("w")!!.payload)
        assertEquals("80", r.sets.single().load)
        assertEquals("persistée", r.notes)
        assertNotNull(r.startedAt)
        assertEquals(9999999999999L, r.restUntil)
        repo.finish("w", Status.PARTIAL)
        assertEquals(Status.PARTIAL, repo.dao.workout("w")!!.status)
        assertNull(parseResult(repo.dao.result("w")!!.payload).restUntil)
    }

    @Test
    fun futureRecurrenceEditPreservesCompletedAndActive() = runBlocking {
        val start = LocalDate.parse("2026-10-06")
        repo.saveWorkout(workout(), start.plusWeeks(3), setOf(DayOfWeek.TUESDAY), false)
        val occurrences = repo.dao.workouts().first()
        assertEquals(4, occurrences.size)
        repo.start(occurrences[0].id)
        repo.finish(occurrences[0].id, Status.DONE)
        repo.start(occurrences[1].id)
        repo.saveWorkout(occurrences[2].copy(name = "Nouvelle version"), null, emptySet(), true)
        val updated = repo.dao.workouts().first()
        assertEquals("Test musculation", updated[0].name)
        assertEquals("Test musculation", updated[1].name)
        assertEquals("Nouvelle version", updated[2].name)
        assertEquals("Nouvelle version", updated[3].name)
    }

    @Test
    fun weekCopiesAreIndependentAndHaveNoResults() = runBlocking {
        repo.dao.putWorkout(workout())
        repo.start("w")
        repo.finish("w", Status.DONE)
        repo.copyWeek(LocalDate.parse("2026-10-06"), LocalDate.parse("2026-10-12"), 2)
        val sessions = repo.dao.workouts().first()
        assertEquals(3, sessions.size)
        sessions
            .filter { it.id != "w" }
            .forEach {
                assertEquals(Status.PLANNED, it.status)
                assertNull(repo.dao.result(it.id))
                val source = parsePlan(repo.dao.workout("w")!!.planJson).strength.single()
                val copied = parsePlan(it.planJson).strength.single()
                assertNotEquals(source.id, copied.id)
                assertEquals(source.copy(id = copied.id), copied)
            }
    }

    @Test
    fun templateEditNeverChangesPlannedSnapshot() = runBlocking {
        val w = workout()
        val t =
            WorkoutTemplate(id = "t", name = "Version 1", sport = w.sport, planJson = w.planJson)
        repo.dao.putTemplate(t)
        repo.saveWorkout(w, null, emptySet(), false)
        repo.dao.putTemplate(
            t.copy(name = "Version 2", planJson = codec.encodeToString(WorkoutPlan()))
        )
        assertEquals(w.planJson, repo.dao.workout("w")!!.planJson)
    }

    @Test
    fun deletingWorkoutCascadesItsResults() = runBlocking {
        repo.dao.putWorkout(workout())
        repo.start("w")
        repo.delete("w")
        assertNull(repo.dao.result("w"))
        assertNull(repo.dao.workout("w"))
    }

    @Test
    fun invalidPreparationReferenceRollsBack() = runBlocking {
        try {
            repo.dao.putWorkout(workout().copy(preparationId = "missing"))
            fail("Expected foreign key error")
        } catch (_: android.database.sqlite.SQLiteConstraintException) {}
        assertTrue(repo.dao.workouts().first().isEmpty())
    }

    @Test
    fun completedPlanCannotBeRewritten() = runBlocking {
        repo.dao.putWorkout(workout())
        repo.start("w")
        repo.finish("w", Status.DONE)
        try {
            repo.saveWorkout(workout().copy(name = "Forbidden"), null, emptySet(), false)
            fail()
        } catch (_: IllegalArgumentException) {}
        assertEquals("Test musculation", repo.dao.workout("w")!!.name)
    }

    @Test
    fun pdfExportPaginatesAndFileProviderCanReadIt() = runBlocking {
        val goal =
            TrainingGoal(
                id = "g",
                name = "10 km",
                raceDate = "2026-12-01",
                notes = "Notes très longues. ".repeat(5000),
            )
        val prep =
            TrainingPreparation(
                id = "p",
                goalId = "g",
                name = "Test export",
                start = "2026-10-05",
                end = "2026-12-01",
            )
        val state =
            CoachState(
                goals = listOf(goal),
                preparations = listOf(prep),
                workouts = listOf(workout().copy(preparationId = "p")),
            )
        val pdf =
            AndroidExporter.write(
                context,
                PreparationExport.document(state, "p", ExportOptions(true)),
                true,
            )
        assertTrue(pdf.length() > 1000)
        PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use {
            renderer ->
            assertTrue(renderer.pageCount > 5)
            listOf(0, renderer.pageCount - 1).forEach { index ->
                renderer.openPage(index).use { page ->
                    val bitmap =
                        Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    assertTrue(pixels.any { it != Color.WHITE })
                    bitmap.recycle()
                }
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", pdf)
        assertTrue(
            context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }.size > 1000
        )
        pdf.delete()
        Unit
    }
}
