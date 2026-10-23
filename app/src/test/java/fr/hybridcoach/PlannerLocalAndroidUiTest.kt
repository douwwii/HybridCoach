package fr.hybridcoach

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CoachApplication::class, qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlannerLocalAndroidUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun configureIdleTimeout() {
        // Robolectric gives each test a new data directory while AndroidX caches provider roots.
        // Model the provider's process startup so a previous test cannot supply a stale root.
        val context = compose.activity.application
        val info =
            requireNotNull(
                context.packageManager.resolveContentProvider("${context.packageName}.files", 0)
            )
        androidx.core.content.FileProvider().attachInfo(context, info)
        androidx.test.espresso.IdlingPolicies.setMasterPolicyTimeout(
            15,
            java.util.concurrent.TimeUnit.SECONDS,
        )
    }

    @Test
    fun createStartLogAndResumeRun() {
        val name = "Footing UI ${System.currentTimeMillis()}"
        compose.waitUntil(15000) {
            compose
                .onAllNodesWithContentDescription("Créer une séance")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        screenshot("today")
        compose.onNodeWithContentDescription("Créer une séance").performClick()
        compose.onNodeWithText("Nom de la séance *").performTextInput(name)
        screenshot("editor")
        compose
            .onNodeWithTag("workout-editor")
            .performScrollToNode(hasText("Enregistrer la séance"))
        compose.onNodeWithText("Enregistrer la séance").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() &&
                compose
                    .onAllNodesWithContentDescription("Créer une séance")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
        compose.onNodeWithText(name).performClick()
        compose.onNodeWithText("Démarrer la séance").performClick()
        compose.waitUntil(10000) {
            compose
                .onAllNodesWithText("Sauvegarde automatique · vous pouvez quitter et reprendre.")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose
            .onNodeWithTag("workout-session")
            .performScrollToNode(hasText("Distance réalisée (km)"))
        compose.onNodeWithText("Distance réalisée (km)").performTextInput("5,2")
        val repo = (compose.activity.application as CoachApplication).repository
        val id = runBlocking { repo.dao.workouts().first().first { it.name == name }.id }
        compose.waitUntil(10000) {
            runBlocking {
                repo.dao.results().first().any {
                    it.workoutId == id && parseResult(it.payload).distanceKm == "5,2"
                }
            }
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15000) {
            compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
        }
        compose
            .onNodeWithTag("workout-session")
            .performScrollToNode(hasText("Distance réalisée (km)"))
        compose.onNodeWithText("Distance réalisée (km)").assertTextContains("5,2")
        runBlocking {
            assertEquals(Status.ACTIVE, repo.dao.workout(id)!!.status)
            repo.delete(id)
        }
    }

    @Test
    fun preparationStrengthAndSharingWorkflow() {
        val prepName = "Préparation test UI"
        val workoutName = "Force test UI"
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("Un jour, un progrès.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Prépas").performClick()
        compose.onNodeWithText("Créer une préparation").performClick()
        compose.onNodeWithText("Nom de la préparation *").performTextInput(prepName)
        compose.onNodeWithText("Compétition / objectif *").performTextInput("10 km test UI")
        compose.onNodeWithText("Distance (km)").performScrollTo().performTextInput("10")
        compose.onNodeWithText("Chrono cible (secondes)").performScrollTo().performTextInput("2088")
        compose.onNodeWithText("Enregistrer").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText(prepName).fetchSemanticsNodes().isNotEmpty() &&
                compose
                    .onAllNodesWithText("Créer une préparation")
                    .fetchSemanticsNodes()
                    .isNotEmpty() &&
                compose
                    .onAllNodesWithText("Nom de la préparation *")
                    .fetchSemanticsNodes()
                    .isEmpty()
        }
        compose.onNodeWithText(prepName).performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("preparation-screen").fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("preparation")
        compose
            .onNodeWithTag("preparation-screen")
            .performScrollToNode(hasText("Planifier une séance"))
        compose.onNodeWithText("Planifier une séance").performClick()
        compose.onNodeWithText("Nom de la séance *").performTextInput(workoutName)
        compose.onNodeWithText("Type : Course").performClick()
        compose.onNodeWithText("Musculation").performClick()
        compose.onNodeWithTag("workout-editor").performScrollToNode(hasText("Choisir un exercice"))
        compose.onNodeWithText("Choisir un exercice").performClick()
        compose.onNodeWithText("Rechercher").performTextInput("Squat")
        compose.onNode(hasText("Squat") and hasClickAction() and !hasSetTextAction()).performClick()
        compose.onNodeWithText("Charge cible (kg)").performTextInput("20")
        compose.onNodeWithText("Enregistrer").performClick()
        compose
            .onNodeWithTag("workout-editor")
            .performScrollToNode(hasText("Enregistrer la séance"))
        compose.onNodeWithText("Enregistrer la séance").performClick()
        val repo = (compose.activity.application as CoachApplication).repository
        compose.waitUntil(10000) {
            runBlocking { repo.dao.workouts().first().any { it.name == workoutName } }
        }
        val workout = runBlocking { repo.dao.workouts().first().first { it.name == workoutName } }
        val exercise = parsePlan(workout.planJson).strength.single()
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("preparation-screen").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("preparation-screen").performScrollToNode(hasText(workoutName))
        compose.onNodeWithText(workoutName).performClick()
        compose.onNodeWithText("Démarrer la séance").performClick()
        compose.waitUntil(10000) {
            compose
                .onAllNodesWithText("Sauvegarde automatique · vous pouvez quitter et reprendre.")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose
            .onNodeWithTag("workout-session")
            .performScrollToNode(hasTestTag("set-${exercise.id}-0-reps"))
        compose.onNodeWithTag("set-${exercise.id}-0-reps").performTextInput("8")
        compose.onNodeWithTag("set-${exercise.id}-0-load").performTextReplacement("22")
        compose.onNodeWithTag("set-${exercise.id}-0-check").performClick()
        compose.waitUntil(10000) {
            runBlocking {
                repo.dao.results().first().any {
                    it.workoutId == workout.id &&
                        parseResult(it.payload).sets.any { set ->
                            set.checked && set.load == "22" && set.reps == "8"
                        }
                }
            }
        }
        screenshot("strength")
        compose.onNodeWithTag("workout-session").performScrollToNode(hasText("Terminer la séance"))
        compose.onNodeWithText("Terminer la séance").performClick()
        compose.onNodeWithText("Partiellement terminée").performClick()
        compose.waitUntil(10000) {
            runBlocking {
                repo.dao.workouts().first().any {
                    it.id == workout.id && it.status == Status.PARTIAL
                }
            }
        }
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("preparation-screen").fetchSemanticsNodes().isNotEmpty()
        }
        compose
            .onNodeWithTag("preparation-screen")
            .performScrollToNode(hasText("Partager la préparation"))
        compose.onNodeWithText("Partager la préparation").performClick()
        compose.onNodeWithText("Générer et partager").performClick()
        var shared: android.content.Intent? = null
        compose.waitUntil(20000) {
            // The PDF completes on IO and posts its chooser launch to the Android main loop.
            compose.runOnIdle {}
            val viewModel =
                androidx.lifecycle
                    .ViewModelProvider(compose.activity)[
                        fr.hybridcoach.ui.CoachViewModel::class.java]
            check(viewModel.error.value == null) { "PDF share failed: ${viewModel.error.value}" }
            shared = org.robolectric.Shadows.shadowOf(compose.activity).nextStartedActivity
            shared != null
        }
        assertEquals(android.content.Intent.ACTION_CHOOSER, shared!!.action)
        val send =
            requireNotNull(
                shared!!.getParcelableExtra(
                    android.content.Intent.EXTRA_INTENT,
                    android.content.Intent::class.java,
                )
            )
        assertEquals("application/pdf", send.type)
        assertTrue(send.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val uri =
            requireNotNull(
                send.getParcelableExtra(
                    android.content.Intent.EXTRA_STREAM,
                    android.net.Uri::class.java,
                )
            )
        val exported =
            File(
                requireNotNull(System.getProperty("hybridcoach.artifactDir")),
                "preparation-workflow.pdf",
            )
        compose.activity.contentResolver.openInputStream(uri)!!.use { input ->
            exported.outputStream().use { input.copyTo(it) }
        }
        org.apache.pdfbox.pdmodel.PDDocument.load(exported).use { document ->
            val text = org.apache.pdfbox.text.PDFTextStripper().getText(document)
            assertTrue(text.contains(workoutName))
            assertTrue(text.contains("22 kg × 8"))
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val file = File(requireNotNull(System.getProperty("hybridcoach.artifactDir")), "$name.png")
        file.parentFile!!.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
