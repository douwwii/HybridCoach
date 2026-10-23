package fr.hybridcoach.backup

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.room.withTransaction
import fr.hybridcoach.BuildConfig
import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

class BackupService(
    private val context: Context,
    private val db: CoachDatabase,
    private val preferences: AthletePreferences,
) {
    private val dao = db.dao()
    private val recovery = AtomicFile(File(context.filesDir, "backup/before-restore.json"))

    suspend fun snapshot(): BackupDocument =
        withContext(Dispatchers.IO) {
            preferences.ensureInitialized()
            db.withTransaction { capture() }
        }

    private suspend fun capture(): BackupDocument {
        val settings = requireNotNull(dao.settings()) { "Le profil n’est pas encore chargé." }
        return BackupDocument(
            BACKUP_FORMAT,
            BACKUP_VERSION,
            DATABASE_VERSION,
            BuildConfig.VERSION_NAME,
            Instant.now().toString(),
            BackupData(
                codec.decodeFromString(settings.profileJson),
                settings.theme,
                dao.allExercises(),
                dao.allTemplates(),
                dao.allGoals(),
                dao.allPreparations(),
                dao.allWorkouts(),
                dao.allResults(),
                dao.allSubGoals(),
                dao.allLinks(),
                dao.allGarmin(),
                dao.allGarminSync(),
            ),
        )
    }

    suspend fun export(uri: Uri): BackupDocument =
        withContext(Dispatchers.IO) {
            val document = snapshot()
            write(uri, BackupCodec.encode(document))
            document
        }

    suspend fun preview(uri: Uri): BackupDocument =
        withContext(Dispatchers.IO) {
            require(uri.scheme == "content") { "Choisissez le fichier avec le sélecteur Android." }
            try {
                val text =
                    context.contentResolver.openInputStream(uri)?.use(::readLimited)
                        ?: throw IllegalStateException("Impossible de lire ce fichier.")
                BackupCodec.decode(text)
            } catch (_: SecurityException) {
                throw IllegalStateException("Accès au fichier refusé. Sélectionnez-le à nouveau.")
            } catch (_: java.io.IOException) {
                throw IllegalStateException(
                    "Lecture du fichier impossible. Vérifiez qu’il est disponible sur ce téléphone."
                )
            }
        }

    /**
     * All data, including the profile/theme, commit together; a failure retains the previous DB.
     */
    suspend fun restore(document: BackupDocument) =
        withContext(Dispatchers.IO) {
            BackupCodec.validate(document)
            preferences.ensureInitialized()
            db.withTransaction {
                // A private, durable fallback is written before replacing anything. Failure aborts.
                writeRecovery(BackupCodec.encode(capture()))
                dao.clearGarminActivities()
                dao.clearGarminSync()
                dao.clearResults()
                dao.clearAllLinks()
                dao.clearWorkouts()
                dao.clearSubGoals()
                dao.clearPreparations()
                dao.clearGoals()
                dao.clearTemplates()
                dao.clearExercises()
                val d = document.data
                d.exercises.forEach { dao.putExercise(it) }
                d.goals.forEach { dao.putGoal(it) }
                d.preparations.forEach { dao.putPreparation(it) }
                d.subGoals.forEach { dao.putSubGoal(it) }
                d.templates.forEach { dao.putTemplate(it) }
                d.workouts.forEach { dao.putWorkout(it) }
                d.results.forEach { dao.putResult(it) }
                dao.putLinks(d.workoutSubGoals)
                d.garminActivities.forEach { dao.putGarmin(it) }
                d.garminSync.forEach { dao.putSync(it) }
                dao.putSettings(
                    AthleteSettingsEntity(
                        profileJson = codec.encodeToString(d.profile),
                        theme = d.theme,
                    )
                )
            }
        }

    fun hasRecovery(): Boolean =
        recovery.baseFile.exists() || File(recovery.baseFile.path + ".bak").exists()

    suspend fun exportRecovery(uri: Uri) =
        withContext(Dispatchers.IO) {
            val text =
                try {
                    recovery.openRead().use(::readLimited)
                } catch (_: java.io.IOException) {
                    throw IllegalStateException("Aucun carnet précédent disponible.")
                }
            BackupCodec.decode(text)
            write(uri, text)
        }

    private fun writeRecovery(text: String) {
        var output: java.io.FileOutputStream? = null
        try {
            output = recovery.startWrite()
            output.write(text.toByteArray(Charsets.UTF_8))
            recovery.finishWrite(output)
        } catch (_: java.io.IOException) {
            output?.let { recovery.failWrite(it) }
            throw IllegalStateException(
                "Impossible de conserver le carnet précédent. La restauration est annulée."
            )
        }
    }

    private fun write(uri: Uri, text: String) {
        require(uri.scheme == "content") { "Choisissez un emplacement avec le sélecteur Android." }
        try {
            val output =
                context.contentResolver.openOutputStream(uri, "wt")
                    ?: throw IllegalStateException(
                        "Impossible d’ouvrir le fichier pour l’enregistrement."
                    )
            output.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        } catch (_: SecurityException) {
            throw IllegalStateException(
                "Écriture du fichier refusée. Choisissez un autre emplacement."
            )
        } catch (_: java.io.IOException) {
            throw IllegalStateException(
                "Sauvegarde non terminée. Vérifiez l’espace disponible et choisissez un nouveau fichier."
            )
        }
    }

    private fun readLimited(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size().toLong() + count <= MAX_BACKUP_BYTES) {
                "Le fichier dépasse 64 Mo."
            }
            bytes.write(buffer, 0, count)
        }
        return bytes.toString("UTF-8")
    }
}
