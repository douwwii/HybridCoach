package fr.hybridcoach

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import fr.hybridcoach.ui.HybridTheme
import fr.hybridcoach.ui.UpdatesScreen
import fr.hybridcoach.updates.*
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CoachApplication::class, qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpdatesUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private class FakeGateway(val file: File) : UpdateGateway {
        var release: AvailableUpdate? = UpdateFixtures.available()
        var fail = false
        var failVerification = false
        var installedChecks = 0

        override suspend fun latest(): AvailableUpdate? {
            if (fail) throw java.io.IOException("offline")
            return release
        }

        override suspend fun download(update: AvailableUpdate, progress: (Long) -> Unit): File {
            file.parentFile!!.mkdirs()
            file.writeBytes(UpdateFixtures.bytes)
            progress(file.length())
            return file
        }

        override suspend fun verify(file: File, update: AvailableUpdate) {
            installedChecks++
            require(!failVerification) { "APK modifié : téléchargement nécessaire." }
        }
    }

    @Before
    fun ready() {
        androidx.test.espresso.IdlingPolicies.setMasterPolicyTimeout(
            15,
            java.util.concurrent.TimeUnit.SECONDS,
        )
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("Un jour, un progrès.").fetchSemanticsNodes().isNotEmpty()
        }
        Shadows.shadowOf(compose.activity.packageManager).setCanRequestPackageInstalls(false)
        val context = compose.activity.application
        FileProvider()
            .attachInfo(
                context,
                requireNotNull(
                    context.packageManager.resolveContentProvider("${context.packageName}.files", 0)
                ),
            )
    }

    private fun fake(gateway: FakeGateway): UpdatesViewModel {
        lateinit var vm: UpdatesViewModel
        compose.runOnIdle {
            vm = UpdatesViewModel(compose.activity.application, gateway)
            compose.activity.setContent {
                HybridTheme("dark") { Surface(Modifier.fillMaxSize()) { UpdatesScreen(vm) {} } }
            }
        }
        return vm
    }

    @Test
    fun githubCheckDownloadPermissionAndInstallerAreExplicit() {
        val file = File(compose.activity.cacheDir, "updates/ui.apk")
        val gateway = FakeGateway(file)
        fake(gateway)
        compose.onNodeWithText("Vérifier les mises à jour").performClick()
        compose.onNodeWithText("Une nouvelle version est disponible.").assertExists()
        compose.onNodeWithText("Version 1.7.1").assertExists()
        screenshot("github-updates")
        compose.onNodeWithText("Télécharger la mise à jour").performScrollTo().performClick()
        compose.onNodeWithText("Installer la mise à jour").performScrollTo().performClick()
        val permission = Shadows.shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, permission.intent.action)
        assertEquals(0, gateway.installedChecks)
        Shadows.shadowOf(compose.activity.packageManager).setCanRequestPackageInstalls(true)
        compose.runOnIdle {
            compose.activity.activityResultRegistry.dispatchResult(
                permission.requestCode,
                Activity.RESULT_CANCELED,
                Intent(),
            )
        }
        compose.waitUntil(15000) {
            compose.runOnIdle {}
            gateway.installedChecks == 1
        }
        val install = Shadows.shadowOf(compose.activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_VIEW, install.action)
        assertEquals("application/vnd.android.package-archive", install.type)
        assertEquals("content", install.data!!.scheme)
        assertEquals(compose.activity.packageName + ".files", install.data!!.authority)
        assertTrue(install.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNotNull(install.clipData)
        compose.onNodeWithText("Mise à jour installée").assertDoesNotExist()
    }

    @Test
    fun emptyReleaseOfflineRetryAndChangedApkCannotInstallSilently() {
        val gateway = FakeGateway(File(compose.activity.cacheDir, "updates/ui.apk"))
        val vm = fake(gateway)
        gateway.release = null
        compose.onNodeWithText("Vérifier les mises à jour").performClick()
        compose
            .onNodeWithText("Aucune version stable n’est encore publiée sur GitHub.")
            .assertExists()
        gateway.fail = true
        compose.onNodeWithText("Vérifier les mises à jour").performClick()
        compose
            .onNodeWithText(
                "Connexion à GitHub impossible. Vérifiez votre accès Internet puis réessayez."
            )
            .assertExists()
        gateway.fail = false
        gateway.release = UpdateFixtures.available()
        compose.onNodeWithText("Vérifier les mises à jour").performClick()
        compose.onNodeWithText("Télécharger la mise à jour").performScrollTo().performClick()
        gateway.failVerification = true
        Shadows.shadowOf(compose.activity.packageManager).setCanRequestPackageInstalls(true)
        compose.onNodeWithText("Installer la mise à jour").performScrollTo().performClick()
        compose.onNodeWithText("APK modifié : téléchargement nécessaire.").assertExists()
        assertFalse(gateway.file.exists())
        assertEquals(UpdateStatus.AVAILABLE, vm.state.value.status)
        assertNull(Shadows.shadowOf(compose.activity).nextStartedActivityForResult)
    }

    @Test
    fun profileOpensTheUpdateScreenWithoutStartingANetworkRequest() {
        compose.onNodeWithContentDescription("Profil sportif").performClick()
        compose.onNodeWithText("À propos et mises à jour").performClick()
        compose.onNodeWithTag("updates-screen").assertExists()
        compose.onNodeWithText("Vérifiez les versions publiées sur GitHub.").assertExists()
        compose.onNodeWithText("Hybrid Coach ${BuildConfig.VERSION_NAME}").assertExists()
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
