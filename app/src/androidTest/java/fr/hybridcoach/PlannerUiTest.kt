package fr.hybridcoach

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannerUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun createStartLogAndResumeRun() {
        val name = "Footing UI ${System.currentTimeMillis()}"
        compose.waitUntil(15000) {
            compose
                .onAllNodesWithContentDescription("Créer une séance")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithContentDescription("Créer une séance").performClick()
        compose.onNodeWithText("Nom de la séance *").performTextInput(name)
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
        var id = ""
        runBlocking {
            withTimeout(10000) {
                id =
                    repo.dao
                        .workouts()
                        .first { it.any { w -> w.name == name } }
                        .first { it.name == name }
                        .id
                repo.dao.results().first { logs ->
                    logs.any { it.workoutId == id && parseResult(it.payload).distanceKm == "5,2" }
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
}
