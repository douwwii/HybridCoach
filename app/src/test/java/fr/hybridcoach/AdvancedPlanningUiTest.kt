package fr.hybridcoach

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.first
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
class AdvancedPlanningUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val repo
        get() = (compose.activity.application as CoachApplication).repository

    @Before
    fun timeout() {
        androidx.test.espresso.IdlingPolicies.setMasterPolicyTimeout(
            15,
            java.util.concurrent.TimeUnit.SECONDS,
        )
    }

    @Test
    fun composeNestedIntervalsForOtherSportAndPersistStructure() {
        compose.waitUntil(15000) {
            compose
                .onAllNodesWithContentDescription("Créer une séance")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithContentDescription("Créer une séance").performClick()
        compose.onNodeWithText("Nom de la séance *").performTextInput("Vélo intervalles UI")
        compose.onNodeWithText("Type : Course").performClick()
        compose.onNodeWithText("Autre sport").performClick()
        compose
            .onNodeWithTag("workout-editor")
            .performScrollToNode(hasText("Composer un fractionné"))
        compose.onNodeWithText("Composer un fractionné").performClick()
        compose.onNodeWithText("Distance d’effort (m)").performTextReplacement("400")
        compose.onNodeWithText("Allure (s/km)").performTextReplacement("195")
        compose
            .onNodeWithText("Récupération (s, 0 = aucune)")
            .performScrollTo()
            .performTextReplacement("60")
        compose.onNodeWithText("Nombre de blocs").performScrollTo().performTextReplacement("3")
        compose.onNodeWithText("Ajouter les blocs").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("Ajouter les blocs").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("workout-editor").performScrollToNode(hasText("3 × bloc"))
        screenshot("structured-intervals")
        compose
            .onNodeWithTag("workout-editor")
            .performScrollToNode(hasText("Enregistrer la séance"))
        compose.onNodeWithText("Enregistrer la séance").performClick()
        compose.waitUntil(15000) {
            runBlocking { repo.dao.workouts().first().any { it.name == "Vélo intervalles UI" } }
        }
        val workout = runBlocking {
            repo.dao.workouts().first().first { it.name == "Vélo intervalles UI" }
        }
        assertEquals(Sport.OTHER, workout.sport)
        val plan = parsePlan(workout.planJson)
        assertEquals(3, plan.running.size)
        val outer = plan.running[1]
        assertEquals(3, outer.repetitions)
        assertEquals(4, outer.children.single().repetitions)
        assertEquals(400.0, outer.children.single().children.single().amount!!, .001)
        assertEquals(60.0, outer.children.single().recovery!!.amount!!, .001)
        assertEquals(180.0, outer.recovery!!.amount!!, .001)
        assertEquals(12, SportMath.flatten(plan.running).count { it.kind == StepKind.WORK })
        runBlocking { repo.delete(workout.id) }
    }

    @Test
    fun switchViewsCreatePhaseAndReturnToSharedCalendar() {
        val today = LocalDate.now()
        runBlocking {
            repo.savePreparation(
                TrainingPreparation(
                    id = "planning-ui",
                    goalId = "goal-ui",
                    name = "Marathon UI",
                    start = today.toString(),
                    end = today.plusWeeks(12).toString(),
                ),
                TrainingGoal(
                    id = "goal-ui",
                    name = "Marathon",
                    raceDate = today.plusWeeks(12).toString(),
                ),
            )
            repo.saveSubGoal(
                PreparationSubGoal(
                    id = "speed-ui",
                    preparationId = "planning-ui",
                    name = "Vitesse UI",
                    start = today.toString(),
                    end = today.plusWeeks(3).toString(),
                )
            )
            repo.saveWorkout(
                PlannedWorkout(
                    id = "workout-ui",
                    name = "Fractionné partagé UI",
                    date = today.toString(),
                    sport = Sport.RUN,
                    preparationId = "planning-ui",
                    planJson =
                        codec.encodeToString(
                            WorkoutPlan(
                                running =
                                    listOf(
                                        RunningStep(
                                            kind = StepKind.REPEAT,
                                            repetitions = 4,
                                            children =
                                                listOf(
                                                    RunningStep(
                                                        durationKind = DurationKind.DISTANCE,
                                                        amount = 1000.0,
                                                    )
                                                ),
                                            recovery =
                                                RunningStep(
                                                    kind = StepKind.RECOVERY,
                                                    amount = 120.0,
                                                ),
                                        )
                                    )
                            )
                        ),
                ),
                null,
                emptySet(),
                false,
            )
        }
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("Calendrier").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Calendrier").performClick()
        compose.onNodeWithText("Mode Planification").performClick()
        compose.onNodeWithText("Préparation : Toutes les séances").performClick()
        compose.onNodeWithText("Marathon UI").performClick()
        compose.onNodeWithText("Mois").performClick()
        compose
            .onNodeWithTag("planning-screen")
            .performScrollToNode(
                hasText("Touchez un jour pour le détail · appui long pour ajouter une séance.")
            )
        screenshot("planning-month")
        compose.onNodeWithText("Jour", useUnmergedTree = true).performClick()
        compose
            .onNodeWithTag("planning-screen")
            .performScrollToNode(hasText("Fractionné partagé UI"))
        compose.onNodeWithText("Fractionné partagé UI").assertExists()
        compose.onNodeWithText("Préparation complète", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("planning-screen").performScrollToIndex(0)
        compose
            .onNodeWithTag("planning-screen")
            .performScrollToNode(hasText("Périodes & sous-objectifs"))
        compose.onNodeWithText("Ajouter", substring = false).performClick()
        compose.onNodeWithText("Nom du sous-objectif").performTextInput("Seuil UI")
        compose.onNodeWithText("Enregistrer").performClick()
        compose.waitUntil(15000) {
            runBlocking { repo.dao.subGoals().first().any { it.name == "Seuil UI" } }
        }
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("Nom du sous-objectif").fetchSemanticsNodes().isEmpty()
        }
        screenshot("planning-phases")
        compose.onNodeWithText("Semaine", useUnmergedTree = true).performClick()
        compose
            .onNodeWithTag("planning-screen")
            .performScrollToNode(
                hasText(
                    "Appui long sur une séance, puis glissez vers un autre jour. Les flèches et le menu Déplacer restent disponibles."
                )
            )
        compose
            .onNodeWithTag("planning-screen")
            .performScrollToNode(hasText("Fractionné partagé UI"))
        screenshot("planning-week")
        // Drag the selected Friday's card into the following day's visible drop column.
        compose.onNodeWithText("Fractionné partagé UI").performTouchInput {
            val start = center
            down(start)
            advanceEventTime(700)
            moveTo(start + androidx.compose.ui.geometry.Offset(390f, 0f), delayMillis = 500)
            up()
        }
        compose.waitUntil(10000) {
            runBlocking { repo.dao.workout("workout-ui")!!.date == today.plusDays(1).toString() }
        }
        compose.onNodeWithContentDescription("Retour").performClick()
        compose
            .onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("Fractionné partagé UI"))
        compose.onNodeWithText("Fractionné partagé UI").assertExists()
        assertEquals(
            today.plusDays(1).toString(),
            runBlocking { repo.dao.workout("workout-ui")!!.date },
        )
    }

    private fun screenshot(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val directory = File(requireNotNull(System.getProperty("hybridcoach.artifactDir")))
            directory.mkdirs()
            File(directory, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
