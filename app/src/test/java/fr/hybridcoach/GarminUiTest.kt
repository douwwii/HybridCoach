package fr.hybridcoach

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import fr.hybridcoach.garmin.*
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
class GarminUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun uncertainActivityIsConfirmedOnceAndDisplayedWithGarminOrigin() {
        androidx.test.espresso.IdlingPolicies.setMasterPolicyTimeout(
            15,
            java.util.concurrent.TimeUnit.SECONDS,
        )
        val repo = (compose.activity.application as CoachApplication).repository
        val today = LocalDate.now().toString()
        runBlocking {
            val plan =
                codec.encodeToString(
                    WorkoutPlan(
                        running =
                            listOf(
                                RunningStep(
                                    durationKind = DurationKind.DISTANCE,
                                    amount = 12000.0,
                                    resolvedPace = 300.0,
                                )
                            )
                    )
                )
            repo.dao.putWorkout(
                PlannedWorkout(
                    id = "ui-a",
                    name = "Seuil Garmin A",
                    date = today,
                    sport = Sport.RUN,
                    planJson = plan,
                )
            )
            repo.dao.putWorkout(
                PlannedWorkout(
                    id = "ui-b",
                    name = "Seuil Garmin B",
                    date = today,
                    sport = Sport.RUN,
                    planJson = plan,
                )
            )
            GarminImporter(repo.db)
                .import(
                    GarminSnapshot(
                        "ui-account",
                        1,
                        listOf(
                            GarminActivity(
                                "9",
                                "Sortie Garmin UI",
                                Sport.RUN,
                                "running",
                                today + "T06:00:00Z",
                                today + "T08:00:00",
                                11000.0,
                                3300.0,
                                fingerprint = "a".repeat(64),
                            )
                        ),
                    )
                )
        }
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("Un jour, un progrès.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Profil sportif").performClick()
        compose.onNodeWithText("Garmin Connect").performClick()
        compose
            .onNodeWithTag("garmin-screen")
            .performScrollToNode(hasText("Choisir une association"))
        compose.onNodeWithText("Choisir une association").performClick()
        compose.onNodeWithText("Séance : Activité non planifiée").performClick()
        compose.onNodeWithText("${dateLabel(today)} · Seuil Garmin A").performClick()
        compose.onNodeWithText("Confirmer").performClick()
        compose.waitUntil(10000) {
            runBlocking { repo.dao.garminActivity("ui-account:9")?.workoutId == "ui-a" }
        }
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("Confirmer").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("garmin-screen").performScrollToNode(hasText("Détail"))
        compose.onNodeWithText("Détail").performClick()
        compose.onNodeWithText("Seuil Garmin A").assertExists()
        compose.onNodeWithText("GARMIN CONNECT", substring = true).assertExists()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap =
                android.graphics.Bitmap.createBitmap(
                    view.width,
                    view.height,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
            view.draw(android.graphics.Canvas(bitmap))
            val directory = File(requireNotNull(System.getProperty("hybridcoach.artifactDir")))
            directory.mkdirs()
            File(directory, "garmin-session.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        runBlocking {
            assertEquals(2, repo.dao.allWorkouts().size)
            assertEquals(1, repo.dao.allWorkouts().count { it.status.isResult })
        }
    }

    private fun dateLabel(date: String) = fr.hybridcoach.ui.dateLabel(date)
}
