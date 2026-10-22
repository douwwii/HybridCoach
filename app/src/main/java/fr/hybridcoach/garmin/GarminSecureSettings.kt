package fr.hybridcoach.garmin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import fr.hybridcoach.domain.codec
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class GarminPersonalSettings(
    val apiKey: String = "",
    val accountId: String? = null,
    val profileHash: String? = null,
) {
    override fun toString() = "GarminPersonalSettings(accountId=$accountId, apiKey=<redacted>)"

    fun preserveAccountFrom(previous: GarminPersonalSettings): GarminPersonalSettings =
        if (accountId == null && profileHash != null && profileHash == previous.profileHash)
            copy(accountId = previous.accountId)
        else this
}

/** Personal key and verified account identity, encrypted with a non-exportable Android key. */
class GarminSecureSettings(context: Context, private val encryptionKey: (() -> SecretKey)? = null) {
    private val preferences = context.getSharedPreferences("garmin-personal", Context.MODE_PRIVATE)
    private val legacyPreferences =
        context.getSharedPreferences("garmin-bridge", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        encryptionKey?.let {
            return it()
        }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("hybrid-garmin", null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                                "hybrid-garmin",
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    )
                }
                .generateKey()
    }

    fun read(): GarminPersonalSettings {
        val raw = preferences.getString("encrypted", null) ?: return GarminPersonalSettings()
        try {
            val parts = raw.split('.')
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
            )
            return codec.decodeFromString(
                String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
            )
        } catch (_: Exception) {
            throw IllegalStateException(
                "Clé Garmin illisible. Enregistrez à nouveau votre clé personnelle."
            )
        }
    }

    fun clear() {
        check(preferences.edit().clear().commit()) { "Déconnexion non enregistrée." }
        check(legacyPreferences.edit().clear().commit()) { "Ancienne configuration non effacée." }
    }

    fun save(value: GarminPersonalSettings) {
        validatePersonalKey(value.apiKey)
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted =
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
                "." +
                Base64.encodeToString(
                    cipher.doFinal(codec.encodeToString(value).toByteArray(Charsets.UTF_8)),
                    Base64.NO_WRAP,
                )
        check(preferences.edit().putString("encrypted", encrypted).commit()) {
            "Configuration non enregistrée."
        }
        check(legacyPreferences.edit().clear().commit()) { "Ancienne configuration non effacée." }
    }
}
