package fr.hybridcoach.updates

import fr.hybridcoach.BuildConfig
import fr.hybridcoach.domain.codec
import java.net.URI
import java.time.Instant
import kotlinx.serialization.Serializable

const val UPDATE_REPOSITORY = "douwwii/HybridCoach"
const val RELEASES_URL = "https://github.com/$UPDATE_REPOSITORY/releases"
const val LATEST_RELEASE_API = "https://api.github.com/repos/$UPDATE_REPOSITORY/releases/latest"
const val MAX_APK_BYTES = 128L * 1024 * 1024
const val UPDATE_METADATA_NAME = "update.json"

@Serializable
data class UpdateApk(val name: String, val url: String, val size: Long, val sha256: String)

@Serializable
data class UpdateManifest(
    val format: String = "fr.hybridcoach.update",
    val schemaVersion: Int = 1,
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val targetSdk: Int,
    val signingCertificateSha256: String,
    val apk: UpdateApk,
    val notes: String,
    val sourceCommit: String,
)

data class AvailableUpdate(
    val manifest: UpdateManifest,
    val publishedAt: String,
    val releaseUrl: String,
)

object UpdateValidation {
    fun assetUrl(url: String, tag: String, name: String): Boolean =
        runCatching {
                val uri = URI(url)
                uri.scheme == "https" &&
                    uri.host == "github.com" &&
                    uri.port == -1 &&
                    uri.rawUserInfo == null &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null &&
                    uri.rawPath == "/$UPDATE_REPOSITORY/releases/download/$tag/$name"
            }
            .getOrDefault(false)

    fun manifest(raw: String, tag: String): UpdateManifest {
        require(raw.length <= 256 * 1024) { "Informations de mise à jour trop volumineuses." }
        val m = codec.decodeFromString<UpdateManifest>(raw)
        require(m.format == "fr.hybridcoach.update" && m.schemaVersion == 1) {
            "Format de mise à jour non pris en charge."
        }
        require(m.applicationId == BuildConfig.APPLICATION_ID) {
            "Cette publication concerne une autre application."
        }
        require(
            m.versionCode > 0 &&
                Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(m.versionName) &&
                tag == "v${m.versionName}"
        ) {
            "Numéro de version incohérent."
        }
        require(m.minSdk in 26..100 && m.targetSdk in m.minSdk..100) {
            "Compatibilité Android invalide."
        }
        require(
            m.apk.name == "HybridCoach-${m.versionName}.apk" && assetUrl(m.apk.url, tag, m.apk.name)
        ) {
            "Lien APK non autorisé."
        }
        require(m.apk.size in 1..MAX_APK_BYTES) { "Taille APK invalide." }
        require(
            Regex("[0-9a-f]{64}").matches(m.apk.sha256) &&
                Regex("[0-9a-f]{64}").matches(m.signingCertificateSha256)
        ) {
            "Empreinte de contrôle invalide."
        }
        require(
            m.notes.isNotBlank() &&
                m.notes.length <= 20000 &&
                Regex("[0-9a-f]{40}").matches(m.sourceCommit)
        ) {
            "Informations de publication incomplètes."
        }
        return m
    }

    fun publicationDate(raw: String): String = raw.also { Instant.parse(it) }
}
