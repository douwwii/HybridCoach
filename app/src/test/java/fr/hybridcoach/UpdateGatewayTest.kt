package fr.hybridcoach

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import fr.hybridcoach.updates.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpdateGatewayTest {
    private val context
        get() = ApplicationProvider.getApplicationContext<Context>()

    private val directory
        get() = File(context.cacheDir, "updates")

    private fun gateway(
        client: GitHubUpdateClient = UpdateFixtures.client(),
        wrongCertificate: Boolean = false,
    ) =
        AndroidUpdateGateway(context, client) { file ->
            UpdateFixtures.identity(installed = file == null).let {
                if (wrongCertificate && file != null) it.copy(certificates = setOf("c".repeat(64)))
                else it
            }
        }

    @Before
    fun setup() {
        directory.deleteRecursively()
    }

    @After
    fun cleanup() {
        directory.deleteRecursively()
    }

    @Test
    fun validDownloadIsVerifiedAndReusedWithoutAnotherTransfer(): Unit = runBlocking {
        val calls = mutableListOf<String>()
        val gateway = gateway(UpdateFixtures.client(calls = calls))
        val update = UpdateFixtures.available()
        val file = gateway.download(update) {}
        assertArrayEquals(UpdateFixtures.bytes, file.readBytes())
        assertEquals(file, gateway.download(update) {})
        assertEquals(listOf(update.manifest.apk.url), calls)
        assertFalse(File(directory, "download.part").exists())
        gateway.verify(file, update)
    }

    @Test
    fun corruptedAndInterruptedDownloadsNeverBecomeInstallable(): Unit = runBlocking {
        for (client in
            listOf(
                GitHubUpdateClient(
                    UpdateTransport { _, _, out, progress ->
                        out.write("bad".toByteArray())
                        progress(3)
                    }
                ),
                GitHubUpdateClient(
                    UpdateTransport { _, _, out, _ ->
                        out.write(UpdateFixtures.bytes)
                        throw java.io.IOException("offline")
                    }
                ),
            )) {
            try {
                gateway(client).download(UpdateFixtures.available()) {}
                fail("Expected rejection")
            } catch (_: Exception) {}
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun cancellingAStreamRemovesThePartialFile(): Unit = runBlocking {
        val client =
            GitHubUpdateClient(
                UpdateTransport { _, _, out, _ ->
                    out.write(UpdateFixtures.bytes)
                    throw CancellationException("cancel")
                }
            )
        assertThrows(CancellationException::class.java) {
            runBlocking { gateway(client).download(UpdateFixtures.available()) {} }
        }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun wrongSignatureAndTamperingAfterDownloadAreRejected(): Unit = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { gateway(wrongCertificate = true).download(UpdateFixtures.available()) {} }
        }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        val service = gateway()
        val file = service.download(UpdateFixtures.available()) {}
        file.writeBytes(ByteArray(UpdateFixtures.bytes.size))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.verify(file, UpdateFixtures.available()) }
        }
    }

    @Test
    fun wrongAppWrongVersionAndIncompatibleAndroidAreRejected() {
        directory.mkdirs()
        val file = File(directory, "test.apk").apply { writeBytes(UpdateFixtures.bytes) }
        val m = UpdateFixtures.manifest()
        val archive = UpdateFixtures.identity()
        val installed = UpdateFixtures.identity(installed = true)
        listOf(
                archive.copy(applicationId = "other.app"),
                archive.copy(versionCode = m.versionCode + 1),
                archive.copy(minSdk = 36),
                archive.copy(certificates = setOf("b".repeat(64))),
            )
            .forEach { wrong ->
                assertThrows(IllegalArgumentException::class.java) {
                    ApkValidation.verify(file, m, installed, wrong, 35)
                }
            }
        assertThrows(IllegalArgumentException::class.java) {
            ApkValidation.verify(file, m, installed.copy(versionCode = m.versionCode), archive, 35)
        }
    }

    @Test
    fun updateCleanupDoesNotTouchPersonalFilesOrExports(): Unit = runBlocking {
        val carnet = File(context.filesDir, "personal-test.txt").apply { writeText("carnet") }
        val export =
            File(context.cacheDir, "exports/backup-test.json").apply {
                parentFile!!.mkdirs()
                writeText("backup")
            }
        try {
            gateway().download(UpdateFixtures.available()) {}
            assertEquals("carnet", carnet.readText())
            assertEquals("backup", export.readText())
        } finally {
            carnet.delete()
            export.delete()
        }
    }
}
