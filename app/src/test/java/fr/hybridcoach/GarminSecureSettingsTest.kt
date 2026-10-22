package fr.hybridcoach

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import fr.hybridcoach.garmin.*
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GarminSecureSettingsTest {
    @Test
    fun encryptedKeyRoundTripRotationAndDisconnectRemoveLegacyCredentials() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val aes = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val store = GarminSecureSettings(context) { aes }
        store.clear()
        val preferences = context.getSharedPreferences("garmin-personal", Context.MODE_PRIVATE)
        val legacy = context.getSharedPreferences("garmin-bridge", Context.MODE_PRIVATE)
        legacy.edit().putString("encrypted", "legacy-synthetic-secret").commit()
        val value = GarminPersonalSettings("synthetic.personal.key", "personal:42", "a".repeat(64))
        store.save(value)
        val firstCiphertext = preferences.getString("encrypted", null)!!
        assertFalse(firstCiphertext.contains(value.apiKey))
        assertFalse(firstCiphertext.contains("personal:42"))
        assertEquals(value, GarminSecureSettings(context) { aes }.read())
        assertTrue(legacy.all.isEmpty())
        store.save(value)
        assertNotEquals(firstCiphertext, preferences.getString("encrypted", null))
        store.save(value.copy(apiKey = "rotated.synthetic.key"))
        assertEquals("personal:42", store.read().accountId)
        store.clear()
        assertTrue(preferences.all.isEmpty())
        assertEquals("", store.read().apiKey)
    }

    @Test
    fun corruptedCiphertextHasActionableErrorWithoutSecret() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context
            .getSharedPreferences("garmin-personal", Context.MODE_PRIVATE)
            .edit()
            .putString("encrypted", "synthetic-corrupted-secret")
            .commit()
        val store = GarminSecureSettings(context) { SecretKeySpec(ByteArray(32), "AES") }
        val error = assertThrows(IllegalStateException::class.java) { store.read() }
        assertTrue(error.message!!.contains("Clé Garmin illisible"))
        assertFalse(error.message!!.contains("synthetic-corrupted-secret"))
        store.clear()
    }
}
