package fr.hybridcoach

import fr.hybridcoach.domain.codec
import fr.hybridcoach.updates.*
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

object UpdateFixtures {
    val bytes = "synthetic-apk-for-tests".toByteArray()
    val certificate = "a".repeat(64)

    fun manifest() =
        UpdateManifest(
            applicationId = BuildConfig.APPLICATION_ID,
            versionCode = BuildConfig.VERSION_CODE + 1L,
            versionName = "1.8.0",
            minSdk = 26,
            targetSdk = 35,
            signingCertificateSha256 = certificate,
            apk =
                UpdateApk(
                    "HybridCoach-1.8.0.apk",
                    "https://github.com/$UPDATE_REPOSITORY/releases/download/v1.8.0/HybridCoach-1.8.0.apk",
                    bytes.size.toLong(),
                    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                        "%02x".format(it)
                    },
                ),
            notes = "Nouveautés de test\n• Calendrier",
            sourceCommit = "b".repeat(40),
        )

    fun available(m: UpdateManifest = manifest()) =
        AvailableUpdate(m, "2026-11-05T10:00:00Z", "$RELEASES_URL/tag/v${m.versionName}")

    fun identity(m: UpdateManifest = manifest(), installed: Boolean = false) =
        ApkIdentity(
            m.applicationId,
            if (installed) BuildConfig.VERSION_CODE.toLong() else m.versionCode,
            if (installed) BuildConfig.VERSION_NAME else m.versionName,
            m.minSdk,
            m.targetSdk,
            setOf(certificate),
        )

    fun release(m: UpdateManifest = manifest()): JsonObject = buildJsonObject {
        put("tag_name", "v${m.versionName}")
        put("draft", false)
        put("prerelease", false)
        put("html_url", "$RELEASES_URL/tag/v${m.versionName}")
        put("published_at", "2026-11-05T10:00:00Z")
        putJsonArray("assets") {
            addJsonObject {
                put("name", UPDATE_METADATA_NAME)
                put(
                    "browser_download_url",
                    "https://github.com/$UPDATE_REPOSITORY/releases/download/v${m.versionName}/update.json",
                )
                put("size", 1234)
            }
            addJsonObject {
                put("name", m.apk.name)
                put("browser_download_url", m.apk.url)
                put("size", m.apk.size)
            }
        }
    }

    fun client(
        release: JsonObject = release(),
        m: UpdateManifest = manifest(),
        calls: MutableList<String> = mutableListOf(),
    ) =
        GitHubUpdateClient(
            UpdateTransport { url, _, out, progress ->
                calls += url
                val content =
                    when {
                        url == LATEST_RELEASE_API -> release.toString().toByteArray()
                        url.endsWith("/update.json") -> codec.encodeToString(m).toByteArray()
                        else -> bytes
                    }
                progress(content.size.toLong())
                out.write(content)
            }
        )
}
