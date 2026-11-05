package fr.hybridcoach.updates

import fr.hybridcoach.BuildConfig
import fr.hybridcoach.domain.codec
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.serialization.json.*

class UpdateHttpException(val status: Int) :
    IllegalStateException(
        when (status) {
            403,
            429 -> "GitHub limite temporairement les requêtes. Réessayez plus tard."
            else -> "GitHub est indisponible (HTTP $status). Réessayez plus tard."
        }
    )

fun interface UpdateTransport {
    fun transfer(url: String, maxBytes: Long, output: OutputStream, progress: (Long) -> Unit)
}

class GitHubUpdateTransport(
    private val open: (URI) -> HttpURLConnection = {
        it.toURL().openConnection() as HttpURLConnection
    }
) : UpdateTransport {
    override fun transfer(
        url: String,
        maxBytes: Long,
        output: OutputStream,
        progress: (Long) -> Unit,
    ) {
        var uri = URI(url)
        repeat(6) { attempt ->
            val allowed =
                if (attempt == 0) setOf("api.github.com", "github.com")
                else
                    setOf(
                        "github.com",
                        "release-assets.githubusercontent.com",
                        "objects.githubusercontent.com",
                        "github-releases.githubusercontent.com",
                    )
            require(
                uri.scheme == "https" &&
                    uri.host in allowed &&
                    uri.rawUserInfo == null &&
                    uri.port == -1 &&
                    uri.rawFragment == null
            ) {
                "Adresse de téléchargement non autorisée."
            }
            val connection = open(uri)
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.requestMethod = "GET"
                connection.setRequestProperty(
                    "User-Agent",
                    "HybridCoach/${BuildConfig.VERSION_NAME}",
                )
                connection.setRequestProperty(
                    "Accept",
                    if (uri.host == "api.github.com") "application/vnd.github+json"
                    else "application/octet-stream",
                )
                if (uri.host == "api.github.com")
                    connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location =
                        connection.getHeaderField("Location")
                            ?: error("Redirection GitHub incomplète.")
                    uri = uri.resolve(location)
                } else {
                    if (status != 200) throw UpdateHttpException(status)
                    require(connection.contentLengthLong <= maxBytes) {
                        "Fichier de mise à jour trop volumineux."
                    }
                    var total = 0L
                    connection.inputStream.use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = stream.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= maxBytes) { "Fichier de mise à jour trop volumineux." }
                            progress(total)
                            output.write(buffer, 0, n)
                        }
                    }
                    return
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Trop de redirections GitHub.")
    }
}

class GitHubUpdateClient(private val transport: UpdateTransport = GitHubUpdateTransport()) {
    private fun text(url: String, limit: Long): String =
        ByteArrayOutputStream().use { out ->
            transport.transfer(url, limit, out) {}
            out.toString("UTF-8")
        }

    fun latest(): AvailableUpdate? {
        val raw =
            try {
                text(LATEST_RELEASE_API, 1024 * 1024)
            } catch (e: UpdateHttpException) {
                if (e.status == 404) return null else throw e
            }
        val release = codec.parseToJsonElement(raw).jsonObject
        require(
            release["draft"]?.jsonPrimitive?.boolean == false &&
                release["prerelease"]?.jsonPrimitive?.boolean == false
        ) {
            "Cette publication n’est pas une version stable."
        }
        val tag = requireNotNull(release["tag_name"]?.jsonPrimitive?.content)
        require(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+").matches(tag)) {
            "Étiquette de version invalide."
        }
        val html = release["html_url"]?.jsonPrimitive?.content
        require(html == "$RELEASES_URL/tag/$tag") { "Publication GitHub inattendue." }
        val assets = release["assets"]?.jsonArray ?: error("Fichiers de publication manquants.")
        val metadata =
            assets
                .map { it.jsonObject }
                .filter { it["name"]?.jsonPrimitive?.content == UPDATE_METADATA_NAME }
        require(metadata.size == 1) {
            "Cette publication ne contient pas les informations de mise à jour. Consultez GitHub ou réessayez après sa préparation."
        }
        val url =
            metadata.single()["browser_download_url"]?.jsonPrimitive?.content
                ?: error("Lien de publication manquant.")
        require(UpdateValidation.assetUrl(url, tag, UPDATE_METADATA_NAME)) {
            "Lien de publication non autorisé."
        }
        val manifest = UpdateValidation.manifest(text(url, 256 * 1024), tag)
        val apkAssets =
            assets
                .map { it.jsonObject }
                .filter { it["name"]?.jsonPrimitive?.content == manifest.apk.name }
        require(
            apkAssets.size == 1 &&
                apkAssets.single()["browser_download_url"]?.jsonPrimitive?.content ==
                    manifest.apk.url &&
                apkAssets.single()["size"]?.jsonPrimitive?.long == manifest.apk.size
        ) {
            "APK absent ou publication incomplète."
        }
        val publishedAt =
            UpdateValidation.publicationDate(
                requireNotNull(release["published_at"]?.jsonPrimitive?.content)
            )
        return AvailableUpdate(manifest, publishedAt, html!!)
    }

    fun download(update: AvailableUpdate, output: OutputStream, progress: (Long) -> Unit) {
        transport.transfer(update.manifest.apk.url, update.manifest.apk.size, output, progress)
    }
}
