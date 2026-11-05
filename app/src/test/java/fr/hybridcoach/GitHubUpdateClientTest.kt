package fr.hybridcoach

import fr.hybridcoach.domain.codec
import fr.hybridcoach.updates.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GitHubUpdateClientTest {
    @Test
    fun stableReleaseLoadsMetadataAndCorrectAssetWithoutCredentials() {
        val calls = mutableListOf<String>()
        val update = UpdateFixtures.client(calls = calls).latest()!!
        assertEquals(UpdateFixtures.manifest(), update.manifest)
        assertEquals(
            listOf(
                LATEST_RELEASE_API,
                "https://github.com/$UPDATE_REPOSITORY/releases/download/v1.7.1/update.json",
            ),
            calls,
        )
        assertTrue(update.manifest.notes.contains("Calendrier"))
    }

    @Test
    fun absentReleaseIsDifferentFromRateLimitAndServerFailure() {
        fun client(status: Int) =
            GitHubUpdateClient(UpdateTransport { _, _, _, _ -> throw UpdateHttpException(status) })
        assertNull(client(404).latest())
        listOf(403, 429, 500).forEach { status ->
            assertThrows(UpdateHttpException::class.java) { client(status).latest() }
        }
    }

    @Test
    fun draftsPrereleasesAndWrongRepositoryAreRejected() {
        val release = UpdateFixtures.release()
        listOf(
                release.toMutableMap().apply { put("draft", JsonPrimitive(true)) },
                release.toMutableMap().apply { put("prerelease", JsonPrimitive(true)) },
                release.toMutableMap().apply {
                    put(
                        "html_url",
                        JsonPrimitive("https://github.com/other/repo/releases/tag/v1.7.1"),
                    )
                },
            )
            .forEach {
                assertThrows(IllegalArgumentException::class.java) {
                    UpdateFixtures.client(JsonObject(it)).latest()
                }
            }
    }

    @Test
    fun missingMetadataAndMissingApkCannotOfferAnUpdate() {
        val release = UpdateFixtures.release()
        val assets = release["assets"]!!.jsonArray
        listOf(
                JsonArray(listOf(assets[0])),
                JsonArray(listOf(assets[1])),
                JsonArray(listOf(assets[0], assets[0], assets[1])),
            )
            .forEach { incomplete ->
                assertThrows(IllegalArgumentException::class.java) {
                    UpdateFixtures.client(
                            JsonObject(release.toMutableMap().apply { put("assets", incomplete) })
                        )
                        .latest()
                }
            }
    }

    @Test
    fun maliciousUrlsAndAmbiguousAssetNamesAreRejected() {
        val valid = UpdateFixtures.manifest()
        listOf(
                valid.apk.url.replace("https:", "http:"),
                valid.apk.url.replace("github.com", "github.com.attacker.test"),
                valid.apk.url + "?next=other",
                valid.apk.url + "#fragment",
                valid.apk.url.replace("/HybridCoach/releases/", "/Other/releases/"),
                valid.apk.url.replace("/download/", "/download/../download/"),
            )
            .forEach { url ->
                assertThrows(IllegalArgumentException::class.java) {
                    UpdateValidation.manifest(
                        codec.encodeToString(valid.copy(apk = valid.apk.copy(url = url))),
                        "v1.7.1",
                    )
                }
            }
    }

    @Test
    fun unsupportedFormatWrongAppAndBadDigestAreRejected() {
        val m = UpdateFixtures.manifest()
        listOf(
                m.copy(schemaVersion = 2),
                m.copy(format = "backup"),
                m.copy(applicationId = "other.app"),
                m.copy(apk = m.apk.copy(sha256 = "bad")),
                m.copy(apk = m.apk.copy(size = MAX_APK_BYTES + 1)),
                m.copy(sourceCommit = "unknown"),
                m.copy(versionCode = 0),
            )
            .forEach {
                assertThrows(IllegalArgumentException::class.java) {
                    UpdateValidation.manifest(codec.encodeToString(it), "v1.7.1")
                }
            }
    }

    @Test
    fun tagVersionMismatchAndAssetSizeMismatchAreRejected() {
        val m = UpdateFixtures.manifest()
        assertThrows(IllegalArgumentException::class.java) {
            UpdateValidation.manifest(codec.encodeToString(m), "v1.7.2")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateFixtures.client(m = m.copy(apk = m.apk.copy(size = m.apk.size + 1))).latest()
        }
    }
}
