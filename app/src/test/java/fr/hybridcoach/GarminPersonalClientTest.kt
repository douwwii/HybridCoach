package fr.hybridcoach

import fr.hybridcoach.domain.Sport
import fr.hybridcoach.domain.codec
import fr.hybridcoach.garmin.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GarminPersonalClientTest {
    private val key = "synthetic.personal-key"
    private val profile = """{"displayName":"synthetic-user-42"}"""
    private val token = """{"access_token":"synthetic-token","expires_in":86400}"""

    private class FakeTransport : GarminHttpTransport {
        val requests = mutableListOf<GarminHttpRequest>()
        var respond: (GarminHttpRequest) -> GarminHttpResponse = { error("Unexpected request") }

        override fun execute(request: GarminHttpRequest): GarminHttpResponse {
            requests += request
            return respond(request)
        }
    }

    private fun raw(id: Int = 1, owner: Int = 42): JsonObject = buildJsonObject {
        put("activityId", id)
        put("ownerId", owner)
        put("activityName", "Sortie synthétique")
        put("activityType", buildJsonObject { put("typeKey", "running") })
        put("startTimeGMT", "2026-10-21 22:30:00")
        put("startTimeLocal", "2026-10-22 00:30:00")
        put("distance", 10000.0)
        put("duration", 3000.0)
        put("movingDuration", 2990.0)
        put("elapsedDuration", 3100.0)
        put("averageHR", 150.0)
        put("elevationGain", 80.0)
    }

    private fun detail(id: Int = 1): JsonObject = buildJsonObject {
        put("activityId", id)
        put("userProfileId", 42)
        put("activityName", "Sortie synthétique")
        put("activityTypeDTO", raw()["activityType"]!!)
        put(
            "summaryDTO",
            JsonObject(
                raw().filterKeys {
                    it !in setOf("activityId", "ownerId", "activityName", "activityType")
                }
            ),
        )
    }

    private fun standard(fake: FakeTransport, list: String = "[${raw()}]") {
        fake.respond = { request ->
            GarminHttpResponse(
                200,
                when {
                    request.method == "POST" -> token
                    request.path.contains("userprofile") -> profile
                    request.path.contains("activitylist") -> list
                    else -> error("Unexpected path ${request.path}")
                },
            )
        }
    }

    private fun old(id: Int = 1, account: String = "personal:42") =
        GarminActivityEntity(
            "$account:$id",
            account,
            id.toString(),
            codec.encodeToString(GarminActivity.serializer(), normalizePersonalActivity(raw(id))),
            "a".repeat(64),
        )

    @Test
    fun officialEndpointsAndStableOwnerIdentityAcrossKeyRotation() = runBlocking {
        val fake = FakeTransport()
        standard(fake)
        val first = GarminPersonalClient(key, fake).connect()
        val second = GarminPersonalClient("another.synthetic-key", fake).connect()
        assertEquals("personal:42", first.accountId)
        assertEquals(first.accountId, second.accountId)
        assertEquals(first.profileHash, second.profileHash)
        assertEquals(64, first.profileHash!!.length)
        val request = fake.requests.first()
        assertEquals("POST", request.method)
        assertEquals("/personalapitoken-service/personal-api/personal-api-key/token", request.path)
        assertEquals("ApiKey $key", request.authorization)
        assertFalse(request.toString().contains(key))
        assertFalse(first.toString().contains(key))
        assertTrue(
            fake.requests
                .filter { it.method == "GET" }
                .all { it.authorization == "Bearer synthetic-token" }
        )
        assertTrue(fake.requests.none { it.path.contains(key) })
    }

    @Test
    fun tokenNegotiationDoesNotDemandJsonAndSupportsTextOrJsonResponses() = runBlocking {
        for (body in
            listOf(
                "synthetic-access-token",
                "\"synthetic-access-token\"",
                "{\"access_token\":\"synthetic-access-token\",\"expires_in\":86400}",
            )) {
            val fake = FakeTransport()
            fake.respond = { request ->
                when {
                    request.method == "POST" && request.accept == "application/json" ->
                        GarminHttpResponse(406, "not acceptable")
                    request.method == "POST" -> GarminHttpResponse(200, body)
                    request.path.contains("userprofile") -> GarminHttpResponse(200, profile)
                    else -> GarminHttpResponse(200, "[${raw()}]")
                }
            }
            val settings = GarminPersonalClient(key, fake).connect()
            assertEquals("personal:42", settings.accountId)
            assertEquals("*/*", fake.requests.first().accept)
            assertTrue(
                fake.requests
                    .filter { it.method == "GET" }
                    .all { it.authorization == "Bearer synthetic-access-token" }
            )
        }
    }

    @Test
    fun http406ReportsTheFailingStageWithoutDisclosingTheKeyOrBody() = runBlocking {
        for (target in listOf("token", "userprofile", "activitylist")) {
            val fake = FakeTransport()
            fake.respond = { request ->
                GarminHttpResponse(
                    if (request.path.contains(target)) 406 else 200,
                    when {
                        request.path.contains(target) -> "private-error-body-$key"
                        request.method == "POST" -> token
                        request.path.contains("userprofile") -> profile
                        else -> "[${raw()}]"
                    },
                )
            }
            val error =
                runCatching { GarminPersonalClient(key, fake).connect() }.exceptionOrNull()
                    as GarminGatewayException
            assertEquals("NOT_ACCEPTABLE", error.code)
            assertEquals(406, error.httpStatus)
            val expected =
                when (target) {
                    "token" -> "Obtention du jeton Garmin"
                    "userprofile" -> "Lecture du profil Garmin"
                    else -> "Lecture des activités Garmin"
                }
            assertEquals(expected, error.stage)
            assertTrue(error.message!!.contains("HTTP 406"))
            assertTrue(error.message!!.contains(expected))
            assertFalse(error.message!!.contains(key))
            assertFalse(error.message!!.contains("private-error-body"))
        }
    }

    @Test
    fun tokenErrorsDistinguishAuthenticationForbiddenAndProxy() = runBlocking {
        for ((status, code) in
            listOf(401 to "AUTH_REQUIRED", 403 to "FORBIDDEN", 407 to "PROXY_AUTH")) {
            val fake = FakeTransport()
            fake.respond = { GarminHttpResponse(status, "private-$key") }
            val error =
                runCatching { GarminPersonalClient(key, fake).connect() }.exceptionOrNull()
                    as GarminGatewayException
            assertEquals(code, error.code)
            assertEquals(status, error.httpStatus)
            assertEquals("Obtention du jeton Garmin", error.stage)
            assertFalse(error.message!!.contains(key))
        }
    }

    @Test
    fun htmlOrMalformedPlainTokenIsNotForwardedToTheProfileEndpoint() = runBlocking {
        for (body in
            listOf("<html>Denied</html>", "private invalid token", "", "token\u0000secret")) {
            val fake = FakeTransport()
            fake.respond = { GarminHttpResponse(200, body) }
            val error =
                runCatching { GarminPersonalClient(key, fake).connect() }.exceptionOrNull()
                    as GarminGatewayException
            assertEquals("INCOMPLETE", error.code)
            assertEquals(1, fake.requests.size)
            assertFalse(error.message!!.contains("Denied"))
        }
    }

    @Test
    fun rotatingKeyForAnEmptyAccountRetainsOnlyTheVerifiedPreviousIdentity() {
        val previous = GarminPersonalSettings("old-key", "personal:42", "profile-42")
        val sameAccount = GarminPersonalSettings("new-key", null, "profile-42")
        assertEquals("personal:42", sameAccount.preserveAccountFrom(previous).accountId)
        val otherAccount = GarminPersonalSettings("new-key", null, "profile-99")
        assertNull(otherAccount.preserveAccountFrom(previous).accountId)
        assertNull(GarminPersonalSettings("new-key").preserveAccountFrom(previous).accountId)
    }

    @Test
    fun normalizesDocumentedUnitsAndActivityLocalDate() {
        val activity = normalizePersonalActivity(raw())
        assertEquals(Sport.RUN, activity.sport)
        assertEquals("2026-10-21T22:30:00Z", activity.startedAt)
        assertEquals("2026-10-22", activity.date.toString())
        assertEquals(10000.0, activity.distanceMeters!!, 0.001)
        assertEquals(300.0, activity.averagePace!!, 0.001)
        assertEquals(150.0, activity.averageHr!!, 0.001)
        assertEquals(2990.0, activity.movingSeconds!!, 0.001)
        assertEquals(activity, normalizePersonalActivity(detail(), detail = true))
    }

    @Test
    fun fingerprintsAreStableAcrossPropertyOrderAndChangeWithRemoteMeasures() {
        val first = normalizePersonalActivity(raw())
        assertEquals(
            first.fingerprint,
            normalizePersonalActivity(JsonObject(raw().toList().reversed().toMap())).fingerprint,
        )
        val changed = JsonObject(raw() + ("distance" to JsonPrimitive(11000)))
        assertNotEquals(first.fingerprint, normalizePersonalActivity(changed).fingerprint)
    }

    @Test
    fun optionalMetricsAndNonRunningSportsDoNotInventValues() {
        val base =
            raw().filterKeys {
                it !in
                    setOf(
                        "distance",
                        "averageHR",
                        "elevationGain",
                        "movingDuration",
                        "elapsedDuration",
                    )
            }
        val strength =
            JsonObject(
                base + ("activityType" to buildJsonObject { put("typeKey", "strength_training") })
            )
        val activity = normalizePersonalActivity(strength)
        assertEquals(Sport.STRENGTH, activity.sport)
        assertNull(activity.distanceMeters)
        assertNull(activity.averagePace)
        assertNull(activity.averageHr)
        val cycling =
            JsonObject(base + ("activityType" to buildJsonObject { put("typeKey", "cycling") }))
        assertEquals(Sport.OTHER, normalizePersonalActivity(cycling).sport)
    }

    @Test
    fun invalidRemoteDataIsRejectedWithSanitizedError() {
        val invalid =
            listOf(
                JsonObject(raw() - "duration"),
                JsonObject(raw() + ("distance" to JsonPrimitive(-1))),
                JsonObject(raw() + ("averageHR" to JsonPrimitive(999))),
                JsonObject(raw() + ("activityId" to JsonPrimitive(true))),
                JsonObject(raw() + ("startTimeGMT" to JsonPrimitive("invalid-private-value"))),
            )
        invalid.forEach { value ->
            val error =
                assertThrows(GarminGatewayException::class.java) {
                    normalizePersonalActivity(value)
                }
            assertEquals("INCOMPLETE", error.code)
            assertFalse(error.message!!.contains("invalid-private-value"))
        }
    }

    @Test
    fun downloadsAllPagesWithoutMultisportChildren() = runBlocking {
        val fake = FakeTransport()
        standard(fake)
        val client = GarminPersonalClient(key, fake)
        val settings = client.connect()
        fake.respond = { request ->
            GarminHttpResponse(
                200,
                when {
                    request.path.contains("userprofile") -> profile
                    request.path.contains("start=0&") ->
                        JsonArray((1..99).map { raw(it) }).toString()
                    request.path.contains("start=99&") -> "[${raw(100)}]"
                    else -> error("Unexpected request ${request.path}")
                },
            )
        }
        val download = client.fetchActivities(settings, emptyList())
        assertEquals(100, download.activities.size)
        assertTrue(download.deletedIds.isEmpty())
        assertTrue(
            fake.requests
                .filter { it.path.contains("activitylist") }
                .all { "excludeChildren=true" in it.path }
        )
    }

    @Test
    fun duplicatePagesAndMixedOwnersAbortTheDownload() = runBlocking {
        for (mixed in listOf(false, true)) {
            val fake = FakeTransport()
            standard(fake)
            val client = GarminPersonalClient(key, fake)
            val settings = client.connect()
            fake.respond = { request ->
                GarminHttpResponse(
                    200,
                    when {
                        request.path.contains("userprofile") -> profile
                        mixed -> "[${raw(1)},${raw(2, 99)}]"
                        else -> JsonArray((1..99).map { raw(it) }).toString()
                    },
                )
            }
            val error =
                runCatching { client.fetchActivities(settings, emptyList()) }.exceptionOrNull()
            assertTrue(error is GarminGatewayException)
            assertEquals("INCOMPLETE", (error as GarminGatewayException).code)
        }
    }

    @Test
    fun deletionRequiresExplicit404AndTemporaryOmissionLoadsDetail() = runBlocking {
        val fake = FakeTransport()
        standard(fake)
        val client = GarminPersonalClient(key, fake)
        val settings = client.connect()
        fake.respond = { request ->
            when {
                request.path.contains("userprofile") -> GarminHttpResponse(200, profile)
                request.path.contains("activitylist") -> GarminHttpResponse(200, "[]")
                request.path.endsWith("/1") -> GarminHttpResponse(404, "ignored body")
                else -> GarminHttpResponse(200, detail(2).toString())
            }
        }
        val download =
            client.fetchActivities(settings, listOf(old(1), old(2), old(3, "personal:99")))
        assertEquals(listOf("1"), download.deletedIds)
        assertEquals(listOf("2"), download.activities.map { it.id })
        assertFalse(fake.requests.any { it.path.endsWith("/3") })
    }

    @Test
    fun forbiddenOrNetworkFailureNeverTurnsIntoDeletion() = runBlocking {
        for (status in listOf(403, 429, 500)) {
            val fake = FakeTransport()
            standard(fake)
            val client = GarminPersonalClient(key, fake)
            val settings = client.connect()
            fake.respond = { request ->
                when {
                    request.path.contains("userprofile") -> GarminHttpResponse(200, profile)
                    request.path.contains("activitylist") -> GarminHttpResponse(200, "[]")
                    else -> GarminHttpResponse(status, "private remote failure $key")
                }
            }
            val error =
                runCatching { client.fetchActivities(settings, listOf(old())) }.exceptionOrNull()
            assertTrue(error is GarminGatewayException)
            assertFalse(error!!.message!!.contains(key))
        }
    }

    @Test
    fun expiredTokenIsRenewedOnceAndCachedBeforeExpiry() = runBlocking {
        var clock = 1000L
        val fake = FakeTransport()
        standard(fake)
        val client = GarminPersonalClient(key, fake, now = { clock })
        val settings = client.connect()
        client.fetchActivities(settings, emptyList())
        assertEquals(1, fake.requests.count { it.method == "POST" })
        clock += 86400_000L
        client.fetchActivities(settings, emptyList())
        assertEquals(2, fake.requests.count { it.method == "POST" })
        var reject = true
        fake.respond = { request ->
            when {
                request.method == "POST" -> GarminHttpResponse(200, token)
                request.path.contains("userprofile") && reject -> {
                    reject = false
                    GarminHttpResponse(401, "")
                }
                request.path.contains("userprofile") -> GarminHttpResponse(200, profile)
                else -> GarminHttpResponse(200, "[${raw()}]")
            }
        }
        client.fetchActivities(settings, emptyList())
        assertEquals(3, fake.requests.count { it.method == "POST" })
    }

    @Test
    fun revokedKeyAndMalformedResponsesAreSanitized() = runBlocking {
        for (response in
            listOf(
                GarminHttpResponse(401, key),
                GarminHttpResponse(200, "<html>$key</html>"),
                GarminHttpResponse(200, """{"access_token":"x","expires_in":{}}"""),
            )) {
            val fake = FakeTransport()
            fake.respond = { response }
            val error = runCatching { GarminPersonalClient(key, fake).connect() }.exceptionOrNull()
            assertTrue(error is GarminGatewayException)
            assertFalse(error!!.message!!.contains(key))
        }
    }

    @Test
    fun emptyAccountIsConnectableAndDoesNotDeleteAnotherAccount() = runBlocking {
        val fake = FakeTransport()
        standard(fake, "[]")
        val client = GarminPersonalClient(key, fake)
        val settings = client.connect()
        assertNull(settings.accountId)
        val result = client.fetchActivities(settings, listOf(old()))
        assertNull(result.accountId)
        assertTrue(result.activities.isEmpty())
        assertTrue(result.deletedIds.isEmpty())
        assertFalse(fake.requests.any { it.path.startsWith("/activity-service/") })
    }

    @Test
    fun changedProfileOrOwnerRequiresReconnection() = runBlocking {
        for (changeProfile in listOf(true, false)) {
            val fake = FakeTransport()
            standard(fake)
            val client = GarminPersonalClient(key, fake)
            val settings = client.connect()
            fake.respond = { request ->
                GarminHttpResponse(
                    200,
                    when {
                        request.path.contains("userprofile") ->
                            if (changeProfile) """{"displayName":"someone-else"}""" else profile
                        else -> "[${raw(owner = 99)}]"
                    },
                )
            }
            val error =
                runCatching { client.fetchActivities(settings, emptyList()) }.exceptionOrNull()
            assertEquals("ACCOUNT_CHANGED", (error as GarminGatewayException).code)
        }
    }
}
