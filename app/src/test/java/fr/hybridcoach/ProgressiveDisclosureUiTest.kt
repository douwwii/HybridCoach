package fr.hybridcoach

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import fr.hybridcoach.data.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CoachApplication::class, qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProgressiveDisclosureUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val repo
        get() = (compose.activity.application as CoachApplication).repository

    @Before
    fun ready() {
        androidx.test.espresso.IdlingPolicies.setMasterPolicyTimeout(
            15,
            java.util.concurrent.TimeUnit.SECONDS,
        )
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("Un jour, un progrès.").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun profileValuesSurviveClosingSectionsAndSaveNormally() {
        compose.onNodeWithContentDescription("Profil sportif").performClick()
        compose.onNodeWithText("VMA (km/h)").assertDoesNotExist()
        compose.onNodeWithText("Vitesse (km/h)").assertDoesNotExist()
        screenshot("profile-essential")
        compose.onNodeWithText("Capacités physiques").performScrollTo().performClick()
        compose.onNodeWithText("VMA (km/h)").performScrollTo().performTextReplacement("15,5")
        compose.onNodeWithText("Capacités physiques").performScrollTo().performClick()
        compose.onNodeWithText("VMA (km/h)").assertDoesNotExist()
        compose.onNodeWithText("Capacités physiques").performScrollTo().performClick()
        compose.onNodeWithText("VMA (km/h)").assertTextContains("15,5")
        compose.onNodeWithText("Capacités physiques").performScrollTo().performClick()
        compose.onNodeWithText("Enregistrer le profil").performScrollTo().performClick()
        compose.waitUntil(15000) {
            compose.runOnIdle {}
            val raw = runBlocking { repo.dao.settings()?.profileJson }.orEmpty()
            "15,5" in raw
        }
    }

    @Test
    fun editorKeepsOptionalFieldsWhenFoldedAndSaved() {
        compose.onNodeWithContentDescription("Créer une séance").performClick()
        compose.onNodeWithText("Nom de la séance *").performTextInput("Séance compacte test")
        compose.onNodeWithText("Heure prévue (HH:mm, facultatif)").assertDoesNotExist()
        screenshot("editor-essential")
        compose
            .onNodeWithTag("workout-editor")
            .performScrollToNode(hasText("Organisation de la séance"))
        compose.onNodeWithText("Organisation de la séance").performClick()
        compose
            .onNodeWithText("Heure prévue (HH:mm, facultatif)")
            .performScrollTo()
            .performTextReplacement("18:45")
        compose.onNodeWithText("Organisation de la séance").performScrollTo().performClick()
        compose.onNodeWithText("Heure prévue (HH:mm, facultatif)").assertDoesNotExist()
        compose.onNodeWithTag("workout-editor").performScrollToNode(hasText("Notes personnelles"))
        compose.onNodeWithText("Notes personnelles").performClick()
        compose.onNodeWithText("Votre note").performScrollTo().performTextInput("Note à conserver")
        compose.onNodeWithText("Notes personnelles").performScrollTo().performClick()
        compose
            .onNodeWithTag("workout-editor")
            .performScrollToNode(hasText("Enregistrer la séance"))
        compose.onNodeWithText("Enregistrer la séance").performClick()
        compose.waitUntil(15000) {
            compose.runOnIdle {}
            runBlocking { repo.dao.allWorkouts().any { it.name == "Séance compacte test" } }
        }
        val saved = runBlocking {
            repo.dao.allWorkouts().single { it.name == "Séance compacte test" }
        }
        assertEquals("18:45", saved.plannedTime)
        assertEquals("Note à conserver", saved.notes)
    }

    @Test
    fun planningToolsAndDetailedStatisticsOpenOnlyOnRequest() {
        compose.onNodeWithText("Calendrier").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Toute la semaine"))
        compose.onNodeWithText("Toute la semaine").performScrollTo().performClick()
        compose
            .onNodeWithText("Toute la semaine")
            .assert(
                SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
                    "Déplié",
                )
            )
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithText("Mode Planification").performClick()
        compose.onNodeWithTag("planning-screen").performScrollToNode(hasText("Outils"))
        compose.onNodeWithText("Réajuster rapidement").assertDoesNotExist()
        compose.onNodeWithText("Dupliquer la semaine").assertDoesNotExist()
        screenshot("planning-essential")
        compose.onNodeWithText("Outils").performClick()
        compose.onNodeWithText("Réajuster rapidement").assertExists()
        compose.onNodeWithText("Réajuster rapidement").performClick()
        compose.onNodeWithText("Voir l’aperçu").assertExists()
        compose.onNodeWithText("Tout retirer").assertDoesNotExist()
        compose.onNodeWithText("Annuler").performClick()
        compose.onNodeWithContentDescription("Retour").performClick()
        compose.onNodeWithText("Progrès").performClick()
        compose.onNodeWithText("Période : Trois mois").assertDoesNotExist()
        compose.onNodeWithText("Filtres").performClick()
        compose.onNodeWithText("Période : Trois mois").assertExists()
    }

    private fun screenshot(name: String) =
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val target =
                File(requireNotNull(System.getProperty("hybridcoach.artifactDir")), "$name.png")
            target.parentFile?.mkdirs()
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Unit
        }
}
