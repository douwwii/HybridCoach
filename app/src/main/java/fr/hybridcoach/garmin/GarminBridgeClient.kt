package fr.hybridcoach.garmin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import fr.hybridcoach.domain.codec
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable data class GarminBridgeSettings(val url: String = "", val apiKey: String = "")

/** Only the bridge address/key are retained, encrypted with a non-exportable Android key. */
class GarminSecureSettings(context: Context) {
    private val preferences = context.getSharedPreferences("garmin-bridge", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
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

    fun read(): GarminBridgeSettings {
        val raw = preferences.getString("encrypted", null) ?: return GarminBridgeSettings()
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
                "Configuration Garmin illisible. Enregistrez à nouveau l’adresse et la clé du service."
            )
        }
    }

    fun save(value: GarminBridgeSettings) {
        validateBridgeSettings(value)
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
    }
}

fun validateBridgeSettings(settings: GarminBridgeSettings) {
    val uri = runCatching { URI(settings.url) }.getOrNull()
    require(
        uri != null &&
            uri.scheme == "https" &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.query == null &&
            uri.fragment == null
    ) {
        "Saisissez l’adresse HTTPS du service Garmin, sans identifiants ni paramètres."
    }
    require(settings.apiKey.length in 32..512 && settings.apiKey.none { it.isWhitespace() }) {
        "La clé du service doit contenir au moins 32 caractères, sans espace."
    }
}

class GarminGatewayException(val code: String?, message: String) : IllegalStateException(message)

class GarminBridgeClient(private val settings: GarminBridgeSettings) : GarminGateway {
    @Serializable
    private data class Login(val email: String, val password: String, val isCn: Boolean)

    @Serializable private data class Mfa(val challengeId: String, val code: String)

    @Serializable
    private data class Failure(val code: String = "NETWORK", val message: String = "")

    private suspend fun request(method: String, path: String, body: String? = null): String =
        withContext(Dispatchers.IO) {
            validateBridgeSettings(settings)
            val connection =
                URI(settings.url.trimEnd('/') + "/v1/" + path).toURL().openConnection()
                    as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 15000
                connection.readTimeout = 240000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                connection.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                val stream =
                    if (status in 200..299) connection.inputStream else connection.errorStream
                val bytes = ByteArrayOutputStream()
                stream?.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        require(bytes.size() + n <= 32 * 1024 * 1024) {
                            "Réponse Garmin trop volumineuse."
                        }
                        bytes.write(buffer, 0, n)
                    }
                }
                val text = bytes.toString("UTF-8")
                if (status !in 200..299) {
                    val code =
                        runCatching { codec.decodeFromString<Failure>(text).code }.getOrNull()
                    throw GarminGatewayException(
                        code,
                        when (code) {
                            "AUTH_REQUIRED" ->
                                "Session Garmin expirée. Connectez à nouveau votre compte."
                            "MFA_EXPIRED" ->
                                "Le code de double authentification a expiré. Recommencez la connexion."
                            "RATE_LIMIT" -> "Garmin limite les requêtes. Réessayez plus tard."
                            "BAD_CREDENTIALS" ->
                                "Identifiants Garmin ou code de vérification refusés."
                            "BUSY" -> "Une opération Garmin est déjà en cours."
                            "INCOMPLETE" ->
                                "La récupération Garmin est incomplète. Vos données locales sont conservées."
                            else ->
                                if (status == 401) "Clé du service Garmin refusée."
                                else "Service Garmin indisponible (HTTP $status)."
                        },
                    )
                }
                text
            } catch (e: java.io.IOException) {
                throw IllegalStateException(
                    "Service Garmin inaccessible. Vérifiez la connexion et l’adresse HTTPS."
                )
            } finally {
                connection.disconnect()
            }
        }

    override suspend fun status(): GarminConnection =
        codec.decodeFromString(request("GET", "status"))

    override suspend fun login(email: String, password: String, isCn: Boolean): GarminConnection =
        codec.decodeFromString(
            request("POST", "login", codec.encodeToString(Login(email, password, isCn)))
        )

    override suspend fun completeMfa(challengeId: String, code: String): GarminConnection =
        codec.decodeFromString(request("POST", "mfa", codec.encodeToString(Mfa(challengeId, code))))

    override suspend fun disconnect() {
        request("DELETE", "session")
    }

    override suspend fun fetchActivities(): GarminSnapshot =
        codec.decodeFromString(request("POST", "sync", "{}"))
}
