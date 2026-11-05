package fr.hybridcoach

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import androidx.test.core.app.ApplicationProvider
import fr.hybridcoach.updates.AndroidUpdateGateway
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class UpdateIdentityTest {
    @Suppress("DEPRECATION")
    @Test
    fun archiveIdentityUsesAndroidPackageMetadataAndCertificateBytes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "identity.apk")
        val signature = Signature(byteArrayOf(1, 2, 3))
        val info =
            PackageInfo().apply {
                packageName = "fr.hybridcoach"
                versionCode = 10
                versionName = "1.7.1"
                applicationInfo =
                    ApplicationInfo().apply {
                        minSdkVersion = 26
                        targetSdkVersion = 35
                    }
                signatures = arrayOf(signature)
            }
        Shadows.shadowOf(context.packageManager).setPackageArchiveInfo(file.absolutePath, info)
        val identity = AndroidUpdateGateway.readIdentity(context, file)
        assertEquals(10L, identity.versionCode)
        assertEquals("fr.hybridcoach", identity.applicationId)
        assertEquals(26, identity.minSdk)
        assertEquals(
            setOf(
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString(
                    ""
                ) {
                    "%02x".format(it)
                }
            ),
            identity.certificates,
        )
    }
}
