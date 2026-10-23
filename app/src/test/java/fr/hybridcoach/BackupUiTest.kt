package fr.hybridcoach

import android.app.Activity
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import fr.hybridcoach.backup.*
import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import fr.hybridcoach.ui.CoachViewModel
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CoachApplication::class, qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun openBackup() {
        compose.waitUntil(15000) {
            compose.runOnIdle {}

            compose.onAllNodesWithText("Un jour, un progrès.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Profil sportif").performClick()
        compose.onNodeWithText("Sauvegarde et transfert").performClick()
        compose.onNodeWithTag("backup-screen").assertExists()
    }

    private fun uri(name: String): android.net.Uri {
        val context = compose.activity
        FileProvider()
            .attachInfo(
                context,
                requireNotNull(
                    context.packageManager.resolveContentProvider("${context.packageName}.files", 0)
                ),
            )
        val file =
            File(context.cacheDir, "exports/$name").apply {
                parentFile!!.mkdirs()
                createNewFile()
            }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    @Test
    fun systemPickerExportPreviewConfirmationAndRecoveryWorkTogether() {
        openBackup()
        val vm = ViewModelProvider(compose.activity)[CoachViewModel::class.java]
        runBlocking {
            vm.repo.dao.putWorkout(
                PlannedWorkout(
                    "to-transfer",
                    "Séance à transférer",
                    "2026-10-23",
                    Sport.RUN,
                    notes = "Note privée exportée",
                    planJson = codec.encodeToString(WorkoutPlan()),
                )
            )
            vm.preferences.save(AthleteProfile(vma = "16"), "light")
        }
        val fileUri = uri("phone-transfer.json")
        compose.onNodeWithText("Exporter ma sauvegarde JSON").performClick()
        val shadow = Shadows.shadowOf(compose.activity)
        val export = shadow.nextStartedActivityForResult
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, export.intent.action)
        assertEquals("application/json", export.intent.type)
        compose.runOnIdle {
            assertTrue(
                compose.activity.activityResultRegistry.dispatchResult(
                    export.requestCode,
                    Activity.RESULT_OK,
                    Intent().setData(fileUri),
                )
            )
        }
        compose.waitUntil(15000) {
            compose.runOnIdle {}

            !vm.backupBusy.value && (vm.backupMessage.value != null || vm.backupError.value != null)
        }
        assertNull(vm.backupError.value)
        val exported = runBlocking {
            BackupService(compose.activity, vm.repo.db, vm.preferences).preview(fileUri)
        }
        assertEquals("to-transfer", exported.data.workouts.single().id)
        assertEquals("16", exported.data.profile.vma)
        runBlocking {
            vm.repo.dao.putWorkout(
                PlannedWorkout(
                    "extra",
                    "Ajout sur le téléphone cible",
                    "2026-10-24",
                    Sport.RUN,
                    planJson = codec.encodeToString(WorkoutPlan()),
                )
            )
            vm.preferences.save(AthleteProfile(vma = "12"), "dark")
        }
        compose.onNodeWithText("Restaurer un fichier JSON").performClick()
        val restore = shadow.nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, restore.intent.action)
        compose.runOnIdle {
            compose.activity.activityResultRegistry.dispatchResult(
                restore.requestCode,
                Activity.RESULT_OK,
                Intent().setData(fileUri),
            )
        }
        compose.waitUntil(15000) {
            compose.runOnIdle {}
            vm.backupPreview.value != null && !vm.backupBusy.value
        }
        assertEquals(2, runBlocking { vm.repo.dao.allWorkouts().size })
        compose.onNodeWithText("Remplacer et restaurer").assertIsNotEnabled()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("Remplacer et restaurer").assertIsNotEnabled()
        compose.onNodeWithTag("confirm-replace").performScrollTo().performClick()
        compose.onNodeWithText("Remplacer et restaurer").performScrollTo().performClick()
        compose.waitUntil(15000) {
            compose.runOnIdle {}
            !vm.backupBusy.value && vm.backupPreview.value == null
        }
        assertEquals(listOf("to-transfer"), runBlocking { vm.repo.dao.allWorkouts().map { it.id } })
        assertEquals("16", runBlocking { vm.preferences.profile.first().vma })
        assertTrue(vm.backupHasRecovery.value)
        compose.onNodeWithText("Exporter le carnet précédent").assertExists()
        compose.onNodeWithTag("backup-message").performScrollTo()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap =
                android.graphics.Bitmap.createBitmap(
                    view.width,
                    view.height,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
            view.draw(android.graphics.Canvas(bitmap))
            val directory =
                File(requireNotNull(System.getProperty("hybridcoach.artifactDir"))).apply {
                    mkdirs()
                }
            File(directory, "backup-transfer.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test
    fun cancelledPickerAndInvalidFileNeverTriggerRestoration() {
        openBackup()
        val vm = ViewModelProvider(compose.activity)[CoachViewModel::class.java]
        compose.onNodeWithText("Restaurer un fichier JSON").performClick()
        val shadow = Shadows.shadowOf(compose.activity)
        val cancelled = shadow.nextStartedActivityForResult
        compose.runOnIdle {
            compose.activity.activityResultRegistry.dispatchResult(
                cancelled.requestCode,
                Activity.RESULT_CANCELED,
                null,
            )
        }
        assertNull(vm.backupPreview.value)
        assertFalse(vm.backupBusy.value)
        val bad = uri("invalid-transfer.json")
        compose.activity.contentResolver.openOutputStream(bad)!!.use {
            it.write("{private invalid file".toByteArray())
        }
        compose.onNodeWithText("Restaurer un fichier JSON").performClick()
        val selected = shadow.nextStartedActivityForResult
        compose.runOnIdle {
            compose.activity.activityResultRegistry.dispatchResult(
                selected.requestCode,
                Activity.RESULT_OK,
                Intent().setData(bad),
            )
        }
        compose.waitUntil(15000) {
            compose.runOnIdle {}
            !vm.backupBusy.value && vm.backupError.value != null
        }
        assertNull(vm.backupPreview.value)
        assertEquals(0, runBlocking { vm.repo.dao.allWorkouts().size })
        assertFalse(vm.backupError.value!!.contains("private invalid"))
    }
}
