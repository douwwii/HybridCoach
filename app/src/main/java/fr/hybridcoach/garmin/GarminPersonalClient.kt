package fr.hybridcoach.garmin

import fr.hybridcoach.domain.Sport
import fr.hybridcoach.domain.codec
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

const val GARMIN_PERSONAL_KEY_URL = "https://connect.garmin.com/app/settings/personalApiToken"
private const val API = "https://personal.connectapi.garmin.com"
private const val TOKEN = "/personalapitoken-service/personal-api/personal-api-key/token"
private const val PROFILE = "/userprofile-service/userprofile/settings"
private const val ACTIVITIES = "/activitylist-service/activities/search/activities"
private const val PAGE_SIZE = 99

fun validatePersonalKey(key: String) {
    require(key.isNotEmpty() && key.length <= 4096 && key.all { it.code in 33..126 }) {
        "Collez votre clé personnelle Garmin, sans espace ni retour à la ligne."
    }
}

class GarminGatewayException(
    val code: String?,
    message: String,
    val httpStatus: Int? = null,
    val stage: String? = null,
) : IllegalStateException(message)

/** Never include credentials in URLs, logs, exceptions, or generated data classes' toString. */
class GarminHttpRequest(val method: String, val path: String, val authorization: String) {
    // Match the documented curl request: do not demand JSON from the token endpoint.
    val accept: String = if (path == TOKEN) "*/*" else "application/json, text/plain, */*"

    override fun toString() = "GarminHttpRequest($method $path)"
}

class GarminHttpResponse(val status: Int, val body: String) {
    override fun toString() = "GarminHttpResponse(status=$status)"
}

fun interface GarminHttpTransport {
    fun execute(request: GarminHttpRequest): GarminHttpResponse
}

class GarminHttpsTransport(
    private val openConnection: (URI) -> HttpURLConnection = {
        it.toURL().openConnection() as HttpURLConnection
    }
) : GarminHttpTransport {
    override fun execute(request: GarminHttpRequest): GarminHttpResponse {
        require(request.path.startsWith('/') && !request.path.startsWith("//"))
        val uri = URI(API + request.path)
        require(uri.scheme == "https" && uri.host == "personal.connectapi.garmin.com")
        val connection = openConnection(uri)
        try {
            connection.requestMethod = request.method
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 15000
            connection.readTimeout = 60000
            connection.setRequestProperty("Authorization", request.authorization)
            connection.setRequestProperty("Accept", request.accept)
            connection.setRequestProperty(
                "User-Agent",
                "HybridCoach/${fr.hybridcoach.BuildConfig.VERSION_NAME} (Android; Personal API)",
            )
            connection.setRequestProperty("Cache-Control", "no-store")
            // The documented token endpoint is a POST with no body.
            if (request.method == "POST") {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(0)
                connection.outputStream.close()
            }
            val status = connection.responseCode
            if (status !in 200..299) return GarminHttpResponse(status, "")
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > 16 * 1024 * 1024) incomplete()
                    bytes.write(buffer, 0, count)
                }
            }
            return GarminHttpResponse(status, bytes.toString("UTF-8"))
        } catch (_: java.io.IOException) {
            throw GarminGatewayException(
                "NETWORK",
                "Garmin est inaccessible. Vérifiez votre connexion Internet.",
            )
        } finally {
            connection.disconnect()
        }
    }
}

data class GarminPersonalDownload(
    val accountId: String?,
    val activities: List<GarminActivity>,
    val deletedIds: List<String>,
)

/** Official Personal API only. The bearer token stays in memory and is renewed from the key. */
class GarminPersonalClient(
    private val apiKey: String,
    private val transport: GarminHttpTransport = GarminHttpsTransport(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var token: String? = null
    private var expiresAt = 0L

    private fun bearer(force: Boolean = false): String {
        validatePersonalKey(apiKey)
        if (!force && token != null && now() < expiresAt) return token!!
        val response = transport.execute(GarminHttpRequest("POST", TOKEN, "ApiKey $apiKey"))
        checkStatus(response.status, TOKEN)
        val body = response.body.trim()
        val json = if (body.startsWith('{')) parseObject(body) else null
        val value =
            when {
                json != null -> json.string("access_token") ?: incomplete()
                body.startsWith('"') ->
                    (parseJson(body) as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: incomplete()
                else -> body
            }
        // Bearer credentials follow RFC 6750's b64token grammar; never forward HTML/error text.
        if (value.length !in 1..16384 || !value.matches(Regex("[A-Za-z0-9._~+/-]+=*"))) incomplete()
        val seconds =
            if (json?.get("expires_in") == null) 86400L
            else (json["expires_in"] as? JsonPrimitive)?.longOrNull ?: incomplete()
        if (seconds !in 1..86400) incomplete()
        token = value
        expiresAt = now() + (seconds - minOf(seconds / 2, 120L)) * 1000
        return value
    }

    private fun request(path: String, allowNotFound: Boolean = false): JsonElement? {
        var response = transport.execute(GarminHttpRequest("GET", path, "Bearer ${bearer()}"))
        if (response.status == 401) {
            response =
                transport.execute(GarminHttpRequest("GET", path, "Bearer ${bearer(force = true)}"))
        }
        if (allowNotFound && response.status == 404) return null
        checkStatus(response.status, path)
        return parseJson(response.body)
    }

    private fun profileHash(): String {
        val profile = request(PROFILE) as? JsonObject ?: incomplete()
        val displayName = profile.string("displayName")?.takeIf { it.isNotBlank() } ?: incomplete()
        return sha256(displayName)
    }

    private fun page(offset: Int, size: Int = PAGE_SIZE): JsonArray =
        (request(
                "$ACTIVITIES?start=$offset&limit=$size&sortBy=startLocal&sortOrder=desc&excludeChildren=true"
            )
                as? JsonArray ?: incomplete())
            .also { if (it.size > size) incomplete() }

    suspend fun connect(): GarminPersonalSettings =
        withContext(Dispatchers.IO) {
            val profile = profileHash()
            val first = page(0, 1).firstOrNull()?.let { it as? JsonObject ?: incomplete() }
            GarminPersonalSettings(apiKey, first?.let { accountId(it) }, profile)
        }

    suspend fun fetchActivities(
        settings: GarminPersonalSettings,
        known: List<GarminActivityEntity>,
    ): GarminPersonalDownload =
        withContext(Dispatchers.IO) {
            // Also verifies ownership when a previously non-empty account now returns no
            // activities.
            if (settings.profileHash == null || settings.profileHash != profileHash()) {
                throw GarminGatewayException(
                    "ACCOUNT_CHANGED",
                    "Le compte Garmin a changé. Reconnectez votre clé personnelle.",
                )
            }
            val activities = linkedMapOf<String, GarminActivity>()
            var account: String? = null
            var complete = false
            for (offset in 0 until 100000 step PAGE_SIZE) {
                val values = page(offset)
                for (value in values) {
                    val raw = value as? JsonObject ?: incomplete()
                    val owner = accountId(raw)
                    if (account != null && account != owner) incomplete()
                    account = owner
                    val activity = normalizePersonalActivity(raw)
                    if (activities.put(activity.id, activity) != null) incomplete()
                }
                if (values.size < PAGE_SIZE) {
                    complete = true
                    break
                }
            }
            if (!complete) incomplete()
            if (account != null && settings.accountId != null && account != settings.accountId) {
                throw GarminGatewayException(
                    "ACCOUNT_CHANGED",
                    "Le compte Garmin a changé. Reconnectez votre clé personnelle.",
                )
            }
            account = account ?: settings.accountId
            // Old bridge accounts can only be adopted after a shared global activity ID proves
            // ownership.
            val legacyAccounts =
                known
                    .filter {
                        it.accountId.matches(Regex("[a-f0-9]{64}")) && it.remoteId in activities
                    }
                    .map { it.accountId }
                    .toSet()
            val deleted = mutableListOf<String>()
            for (old in
                known.filter {
                    !it.deleted && (it.accountId == account || it.accountId in legacyAccounts)
                }) {
                if (old.remoteId in activities) continue
                if (!old.remoteId.matches(Regex("[0-9]{1,20}"))) incomplete()
                val detail =
                    request("/activity-service/activity/${old.remoteId}", allowNotFound = true)
                if (detail == null) {
                    deleted += old.remoteId
                } else {
                    val raw = detail as? JsonObject ?: incomplete()
                    val detailOwner = raw.longId("userProfileId") ?: incomplete()
                    if ("personal:$detailOwner" != account) incomplete()
                    val normalized = normalizePersonalActivity(raw, detail = true)
                    if (normalized.id != old.remoteId) incomplete()
                    activities[normalized.id] = normalized
                }
            }
            GarminPersonalDownload(account, activities.values.toList(), deleted)
        }

    fun clearToken() {
        token = null
        expiresAt = 0
    }
}

private fun checkStatus(status: Int, path: String) {
    if (status in 200..299) return
    val stage =
        when {
            path == TOKEN -> "Obtention du jeton Garmin"
            path == PROFILE -> "Lecture du profil Garmin"
            path.startsWith(ACTIVITIES) -> "Lecture des activités Garmin"
            else -> "Lecture du détail Garmin"
        }
    val code =
        when (status) {
            401 -> "AUTH_REQUIRED"
            403 -> "FORBIDDEN"
            406 -> "NOT_ACCEPTABLE"
            407 -> "PROXY_AUTH"
            429 -> "RATE_LIMIT"
            else -> "HTTP"
        }
    val explanation =
        when (code) {
            "AUTH_REQUIRED" ->
                "Authentification refusée. Vérifiez que votre clé personnelle Garmin est complète, valide et non révoquée."
            "FORBIDDEN" ->
                "Garmin a refusé l’accès. Ce refus ne permet pas à lui seul de conclure que la clé est invalide."
            "NOT_ACCEPTABLE" ->
                "Garmin a refusé le format de la requête ou de sa réponse. La clé n’a pas été déclarée invalide."
            "PROXY_AUTH" ->
                "Le réseau demande l’authentification d’un proxy. Essayez un autre réseau ou vérifiez sa configuration."
            "RATE_LIMIT" -> "Garmin limite les requêtes. Réessayez plus tard."
            else -> "La requête Garmin a échoué."
        }
    throw GarminGatewayException(
        code,
        "$stage : HTTP $status. $explanation Aucune donnée sportive locale n’a été modifiée.",
        status,
        stage,
    )
}

private fun parseJson(body: String): JsonElement =
    try {
        codec.parseToJsonElement(body)
    } catch (_: Exception) {
        incomplete()
    }

private fun parseObject(body: String) = parseJson(body) as? JsonObject ?: incomplete()

private fun JsonObject.string(key: String) =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.longId(key: String): String? =
    (get(key) as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }?.toString()

private fun accountId(raw: JsonObject): String = "personal:${raw.longId("ownerId") ?: incomplete()}"

private fun incomplete(): Nothing =
    throw GarminGatewayException(
        "INCOMPLETE",
        "La réponse Garmin est incomplète ou invalide. Vos données locales sont conservées.",
    )

private fun sha256(value: String) =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(
        ""
    ) {
        "%02x".format(it)
    }

/** Units and both response shapes follow Garmin's published Personal API OpenAPI schema. */
fun normalizePersonalActivity(raw: JsonObject, detail: Boolean = false): GarminActivity {
    try {
        val metrics = if (detail) raw["summaryDTO"] as? JsonObject ?: incomplete() else raw
        fun number(key: String): Double? {
            val element = metrics[key] ?: return null
            if (element == JsonNull) return null
            val number = (element as? JsonPrimitive)?.doubleOrNull ?: incomplete()
            if (!number.isFinite() || number < 0) incomplete()
            return number
        }
        val id = raw.longId("activityId") ?: incomplete()
        val type =
            raw[if (detail) "activityTypeDTO" else "activityType"] as? JsonObject ?: incomplete()
        val sportKey =
            type.string("typeKey")?.takeIf { it.isNotBlank() && it.length <= 200 } ?: incomplete()
        val sport =
            when (sportKey) {
                "running",
                "trail_running",
                "treadmill_running",
                "track_running",
                "virtual_run",
                "ultra_run",
                "indoor_track" -> Sport.RUN
                "strength_training",
                "strength",
                "functional_strength_training" -> Sport.STRENGTH
                else -> Sport.OTHER
            }
        val distance = number("distance")
        val duration = number("duration") ?: incomplete()
        val gmt = metrics.string("startTimeGMT")?.replace(' ', 'T') ?: incomplete()
        val started =
            if (gmt.endsWith('Z')) Instant.parse(gmt)
            else LocalDateTime.parse(gmt).toInstant(ZoneOffset.UTC)
        val local =
            LocalDateTime.parse(metrics.string("startTimeLocal")?.replace(' ', 'T') ?: incomplete())
        val activity =
            GarminActivity(
                id = id,
                name = raw.string("activityName")?.takeIf { it.isNotBlank() } ?: sportKey,
                sport = sport,
                sportKey = sportKey,
                startedAt = started.toString(),
                localDateTime = local.toString(),
                distanceMeters = distance,
                durationSeconds = duration,
                movingSeconds = number("movingDuration"),
                elapsedSeconds = number("elapsedDuration"),
                averagePace = distance?.takeIf { it > 0 }?.let { duration * 1000 / it },
                averageHr = number("averageHR"),
                elevationGain = number("elevationGain"),
                fingerprint = "0".repeat(64),
            )
        activity.validate()
        return activity.copy(fingerprint = sha256(codec.encodeToString(activity)))
    } catch (e: GarminGatewayException) {
        throw e
    } catch (_: Exception) {
        incomplete()
    }
}
