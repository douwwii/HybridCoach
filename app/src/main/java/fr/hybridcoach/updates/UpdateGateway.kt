package fr.hybridcoach.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import fr.hybridcoach.BuildConfig
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ApkIdentity(
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val targetSdk: Int,
    val certificates: Set<String>,
)

fun sha256(file: File): String =
    file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

object ApkValidation {
    fun verify(
        file: File,
        manifest: UpdateManifest,
        installed: ApkIdentity,
        archive: ApkIdentity,
        sdk: Int,
    ) {
        require(
            file.isFile && file.length() == manifest.apk.size && sha256(file) == manifest.apk.sha256
        ) {
            "Le téléchargement est incomplet ou son empreinte est incorrecte. Téléchargez à nouveau l’APK."
        }
        require(
            archive.applicationId == installed.applicationId &&
                archive.applicationId == manifest.applicationId &&
                archive.versionCode == manifest.versionCode &&
                archive.versionName == manifest.versionName
        ) {
            "Le contenu de l’APK ne correspond pas à cette mise à jour."
        }
        require(archive.versionCode > installed.versionCode) {
            "Cette version est déjà installée ou plus ancienne."
        }
        require(
            archive.minSdk == manifest.minSdk &&
                archive.targetSdk == manifest.targetSdk &&
                sdk >= archive.minSdk
        ) {
            "Cette mise à jour n’est pas compatible avec votre version d’Android."
        }
        require(
            installed.certificates.size == 1 &&
                archive.certificates == installed.certificates &&
                manifest.signingCertificateSha256 in archive.certificates
        ) {
            "La signature de cet APK diffère de celle de votre installation. Aucune installation proposée ; votre carnet reste intact."
        }
    }
}

interface UpdateGateway {
    suspend fun latest(): AvailableUpdate?

    suspend fun download(update: AvailableUpdate, progress: (Long) -> Unit): File

    suspend fun verify(file: File, update: AvailableUpdate)
}

class AndroidUpdateGateway(
    private val context: Context,
    private val client: GitHubUpdateClient = GitHubUpdateClient(),
    private val identityReader: (File?) -> ApkIdentity = { file -> readIdentity(context, file) },
) : UpdateGateway {
    override suspend fun latest() = withContext(Dispatchers.IO) { client.latest() }

    override suspend fun download(update: AvailableUpdate, progress: (Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            require(
                update.manifest.versionCode > BuildConfig.VERSION_CODE &&
                    Build.VERSION.SDK_INT >= update.manifest.minSdk
            ) {
                "Cette mise à jour ne peut pas être installée sur ce téléphone."
            }
            val directory =
                File(context.cacheDir, "updates").apply { check(mkdirs() || isDirectory) }
            val destination = File(directory, "HybridCoach-${update.manifest.versionCode}.apk")
            if (destination.exists()) {
                try {
                    verify(destination, update)
                    progress(destination.length())
                    return@withContext destination
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    destination.delete()
                }
            }
            // Only one download is started by the ViewModel. Parts never become installable files.
            val part = File(directory, "download.part")
            try {
                directory.listFiles()?.filter { it != destination }?.forEach { it.delete() }
                val coroutine = currentCoroutineContext()
                part.outputStream().use { output ->
                    client.download(update, output) { bytes ->
                        coroutine.ensureActive()
                        progress(bytes)
                    }
                }
                coroutine.ensureActive()
                verify(part, update)
                check(part.renameTo(destination)) { "Impossible de conserver l’APK téléchargé." }
                destination
            } finally {
                part.delete()
            }
        }

    override suspend fun verify(file: File, update: AvailableUpdate) =
        withContext(Dispatchers.IO) {
            require(
                file.isFile &&
                    file.length() == update.manifest.apk.size &&
                    sha256(file) == update.manifest.apk.sha256
            ) {
                "APK incomplet ou modifié. Téléchargez à nouveau la mise à jour."
            }
            ApkValidation.verify(
                file,
                update.manifest,
                identityReader(null),
                identityReader(file),
                Build.VERSION.SDK_INT,
            )
        }

    companion object {
        @Suppress("DEPRECATION")
        fun readIdentity(context: Context, file: File?): ApkIdentity {
            val pm = context.packageManager
            val flags =
                if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                else PackageManager.GET_SIGNATURES
            val info: PackageInfo =
                requireNotNull(
                    if (file == null) pm.getPackageInfo(context.packageName, flags)
                    else pm.getPackageArchiveInfo(file.absolutePath, flags)
                ) {
                    "L’APK téléchargé n’est pas une application Android valide."
                }
            val signatures =
                if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners
                else info.signatures
            return ApkIdentity(
                info.packageName,
                if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
                else info.versionCode.toLong(),
                info.versionName.orEmpty(),
                requireNotNull(info.applicationInfo).minSdkVersion,
                requireNotNull(info.applicationInfo).targetSdkVersion,
                signatures
                    .orEmpty()
                    .map { signature ->
                        MessageDigest.getInstance("SHA-256")
                            .digest(signature.toByteArray())
                            .joinToString("") { "%02x".format(it) }
                    }
                    .toSet(),
            )
        }
    }
}
