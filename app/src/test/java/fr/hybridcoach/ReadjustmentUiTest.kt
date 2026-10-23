package fr.hybridcoach

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CoachApplication::class, qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadjustmentUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val repo
        get() = (compose.activity.application as CoachApplication).repository

    private val today = LocalDate.now()

    @Before
    fun setup() {
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("Un jour, un progrès.").fetchSemanticsNodes().isNotEmpty()
        }
        runBlocking {
            repo.savePreparation(
                TrainingPreparation(
                    "adjust-ui",
                    "goal-adjust",
                    "Préparation test",
                    today.toString(),
                    today.plusMonths(3).toString(),
                ),
                TrainingGoal("goal-adjust", "Marathon test", today.plusMonths(3).toString()),
            )
            val plan =
                WorkoutPlan(
                    running =
                        listOf(RunningStep(durationKind = DurationKind.DISTANCE, amount = 10000.0))
                )
            repo.dao.putWorkout(
                PlannedWorkout(
                    "adjust-w",
                    "Endurance à ajuster",
                    today.toString(),
                    Sport.RUN,
                    preparationId = "adjust-ui",
                    planJson = codec.encodeToString(plan),
                )
            )
            repo.dao.putWorkout(
                PlannedWorkout(
                    "adjust-protected",
                    "Séance terminée",
                    today.toString(),
                    Sport.RUN,
                    preparationId = "adjust-ui",
                    status = Status.DONE,
                    planJson = codec.encodeToString(plan),
                )
            )
        }
        compose.onNodeWithText("Calendrier").performClick()
        compose.onNodeWithText("Mode Planification").performClick()
        compose.onNodeWithText("Préparation : Toutes les séances").performClick()
        compose.onNodeWithText("Préparation test").performClick()
        compose.onNodeWithTag("planning-screen").performScrollToNode(hasText("Outils"))
        compose.onNodeWithText("Outils").performClick()
        compose.onNodeWithText("Réajuster rapidement").performClick()
    }

    private fun preview() {
        compose.onNodeWithText("Voir l’aperçu").performClick()
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("Aperçu du réajustement").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun cancelThenApplyShiftAndFindTheSameWorkoutInClassicCalendar() {
        compose.onNodeWithText("Voir l’aperçu").assertExists()
        compose
            .onNodeWithText("Décalage en jours (+ ou −)")
            .performScrollTo()
            .performTextReplacement("7")
        preview()
        compose.onNodeWithText("1 séance(s) modifiée(s) · 1 protégée(s)").assertExists()
        assertEquals(today.toString(), runBlocking { repo.dao.workout("adjust-w")!!.date })
        compose.onNodeWithText("Annuler").performClick()
        assertEquals(today.toString(), runBlocking { repo.dao.workout("adjust-w")!!.date })
        compose.onNodeWithText("Outils").performClick()
        compose.onNodeWithText("Réajuster rapidement").performClick()
        preview()
        screenshot("planning-readjustment")
        compose.onNodeWithText("Appliquer les changements").performClick()
        compose.waitUntil(15000) {
            compose.runOnIdle {}
            runBlocking { repo.dao.workout("adjust-w")!!.date == today.plusDays(7).toString() }
        }
        assertEquals(today.toString(), runBlocking { repo.dao.workout("adjust-protected")!!.date })
        compose.onNodeWithContentDescription("Retour").performClick()
        compose.onNodeWithContentDescription("Semaine suivante").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Endurance à ajuster"))
        compose.onNodeWithText("Endurance à ajuster").assertExists()
    }

    @Test
    fun lightenWeekAndEmptySelectionCannotBeApplied() {
        compose.onNodeWithText("Action : Décaler des séances").performClick()
        compose.onNodeWithText("Alléger une semaine").performClick()
        compose.onNodeWithText("Choisir les séances").performScrollTo().performClick()
        compose.onNodeWithText("Tout retirer").performScrollTo().performClick()
        preview()
        compose.onNodeWithText("Appliquer les changements").assertIsNotEnabled()
        compose.onNodeWithText("Modifier / recalculer").performScrollTo().performClick()
        compose.onNodeWithText("Choisir les séances").performScrollTo().performClick()
        compose.onNodeWithText("Tout sélectionner").performScrollTo().performClick()
        preview()
        compose.onNodeWithTag("readjustment-preview").assertExists()
        compose.onNodeWithText("Appliquer les changements").performClick()
        compose.waitUntil(15000) {
            compose.runOnIdle {}
            runBlocking {
                parsePlan(repo.dao.workout("adjust-w")!!.planJson).running.single().amount == 8000.0
            }
        }
        assertEquals(
            10000.0,
            runBlocking {
                parsePlan(repo.dao.workout("adjust-protected")!!.planJson).running.single().amount!!
            },
            .001,
        )
    }

    private fun screenshot(name: String) {
        compose.runOnIdle {
            val view =
                requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog().window)
                    .decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val target = File(System.getProperty("hybridcoach.artifactDir"), "$name.png")
            target.parentFile?.mkdirs()
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
