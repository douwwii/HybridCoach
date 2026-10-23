package fr.hybridcoach.backup

import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import fr.hybridcoach.garmin.*
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

const val BACKUP_FORMAT = "fr.hybridcoach.backup"
const val BACKUP_VERSION = 1
const val MAX_BACKUP_BYTES = 64 * 1024 * 1024

@Serializable
data class BackupData(
    val profile: AthleteProfile,
    val theme: String,
    val exercises: List<Exercise>,
    val templates: List<WorkoutTemplate>,
    val goals: List<TrainingGoal>,
    val preparations: List<TrainingPreparation>,
    val workouts: List<PlannedWorkout>,
    val results: List<ResultEntity>,
    val subGoals: List<PreparationSubGoal>,
    val workoutSubGoals: List<WorkoutSubGoal>,
    val garminActivities: List<GarminActivityEntity>,
    val garminSync: List<GarminSyncEntity>,
)

@Serializable
data class BackupDocument(
    val format: String,
    val version: Int,
    val databaseVersion: Int,
    val appVersion: String,
    val exportedAt: String,
    val data: BackupData,
)

/** This format is deliberately distinct from the shareable, partial preparation export. */
object BackupCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        prettyPrint = true
    }

    fun encode(document: BackupDocument): String {
        validate(document)
        val content = json.encodeToJsonElement(document).jsonObject
        val envelope = JsonObject(content + ("sha256" to JsonPrimitive(digest(content))))
        return json.encodeToString(JsonElement.serializer(), envelope).also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES) {
                "La sauvegarde dépasse 64 Mo."
            }
        }
    }

    fun decode(text: String): BackupDocument {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES) {
            "Le fichier dépasse 64 Mo."
        }
        try {
            checkDepth(text)
            val root =
                json.parseToJsonElement(text.removePrefix("\uFEFF")) as? JsonObject ?: invalid()
            require(root["format"] == JsonPrimitive(BACKUP_FORMAT)) {
                "Ce fichier n’est pas une sauvegarde complète Hybrid Coach. Un export de préparation ne peut pas être restauré ici."
            }
            require((root["version"] as? JsonPrimitive)?.intOrNull == BACKUP_VERSION) {
                "Version de sauvegarde non prise en charge. Mettez Hybrid Coach à jour."
            }
            val checksum = (root["sha256"] as? JsonPrimitive)?.content ?: invalid()
            val content = JsonObject(root - "sha256")
            require(checksum.matches(Regex("[a-f0-9]{64}")) && checksum == digest(content)) {
                "Le fichier a été modifié ou est incomplet : son contrôle d’intégrité a échoué."
            }
            return json.decodeFromJsonElement<BackupDocument>(content).also(::validate)
        } catch (e: BackupValidationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            // Serialization exceptions can include the user's private file contents.
            if (e is kotlinx.serialization.SerializationException) invalid()
            throw e
        } catch (_: Exception) {
            invalid()
        }
    }

    private fun canonical(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.toSortedMap().mapValues { canonical(it.value) })
            is JsonArray -> JsonArray(element.map(::canonical))
            else -> element
        }

    private fun digest(content: JsonElement): String =
        MessageDigest.getInstance("SHA-256")
            .digest(canonical(content).toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Bounds JSON nesting before deserializing a potentially hostile selected file. */
    fun checkDepth(text: String) {
        var quoted = false
        var escaped = false
        var depth = 0
        text.forEach { c ->
            if (quoted) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> quoted = false
                }
            } else
                when (c) {
                    '"' -> quoted = true
                    '{',
                    '[' -> {
                        depth++
                        require(depth <= 100) { "Le fichier est trop imbriqué." }
                    }
                    '}',
                    ']' -> depth--
                }
        }
    }

    fun validate(document: BackupDocument) {
        require(document.format == BACKUP_FORMAT && document.version == BACKUP_VERSION) {
            "Format de sauvegarde incompatible."
        }
        require(document.databaseVersion in 1..DATABASE_VERSION) {
            "Cette sauvegarde nécessite une version plus récente de Hybrid Coach."
        }
        require(document.appVersion.isNotBlank() && document.appVersion.length <= 100)
        Instant.parse(document.exportedAt)
        val d = document.data
        validateAthleteProfile(d.profile, d.theme)
        fun <T> unique(values: List<T>, key: (T) -> String): Set<String> {
            require(values.size <= 100000) { "La sauvegarde contient trop d’éléments." }
            val ids = values.map(key)
            require(
                ids.all { it.isNotBlank() && it.length <= 500 } && ids.distinct().size == ids.size
            ) {
                "La sauvegarde contient des identifiants absents ou en double."
            }
            return ids.toSet()
        }
        unique(d.exercises) { it.id }
        unique(d.templates) { it.id }
        val goals = unique(d.goals) { it.id }
        val preparations = unique(d.preparations) { it.id }
        val workouts = unique(d.workouts) { it.id }
        unique(d.results) { it.workoutId }
        val phases = unique(d.subGoals) { it.id }
        unique(d.workoutSubGoals) { "${it.workoutId}\u0000${it.subGoalId}" }
        unique(d.garminActivities) { it.key }
        unique(d.garminActivities) { it.remoteId }
        unique(d.garminSync) { it.accountId }
        fun plan(raw: String) {
            checkDepth(raw)
            validatePlan(parsePlan(raw))
        }
        fun workout(w: PlannedWorkout) {
            require(w.id.isNotBlank() && w.name.isNotBlank())
            w.date.asDate()
            w.plannedDate?.asDate()
            w.plannedTime?.let { LocalTime.parse(it) }
            require(w.source in setOf("MANUAL", "GARMIN"))
            plan(w.planJson)
        }
        fun result(raw: String) {
            checkDepth(raw)
            val value = parseResult(raw)
            // Preserve unfinished field input, notes and all recorded values exactly.
            require(
                value.sets.size <= 100000 &&
                    value.intervals.size <= 100000 &&
                    value.setCounts.size <= 10000
            )
            require(value.sets.all { it.index in 0..100000 && it.exerciseInstanceId.isNotBlank() })
            require(value.setCounts.values.all { it in 1..50 })
        }
        d.exercises.forEach { require(it.name.isNotBlank() && it.muscle.isNotBlank()) }
        d.templates.forEach {
            require(it.name.isNotBlank())
            plan(it.planJson)
        }
        d.goals.forEach {
            require(it.name.isNotBlank())
            it.raceDate.asDate()
            require(it.distanceKm == null || it.distanceKm.isFinite() && it.distanceKm > 0)
            require(it.targetSeconds == null || it.targetSeconds > 0)
        }
        d.preparations.forEach {
            require(
                it.goalId in goals && it.name.isNotBlank() && it.start.asDate() <= it.end.asDate()
            ) {
                "Une préparation est invalide ou son objectif est absent."
            }
        }
        d.workouts.forEach {
            workout(it)
            require(it.preparationId == null || it.preparationId in preparations) {
                "Une séance référence une préparation absente."
            }
        }
        d.results.forEach {
            require(it.workoutId in workouts) { "Un résultat référence une séance absente." }
            result(it.payload)
        }
        val prepByWorkout = d.workouts.associate { it.id to it.preparationId }
        val prepByPhase = d.subGoals.associate { it.id to it.preparationId }
        d.subGoals.forEach {
            require(
                it.name.isNotBlank() &&
                    it.preparationId in preparations &&
                    it.start.asDate() <= it.end.asDate()
            ) {
                "Une période est invalide ou sa préparation est absente."
            }
        }
        d.workoutSubGoals.forEach {
            require(
                it.workoutId in workouts &&
                    it.subGoalId in phases &&
                    prepByWorkout[it.workoutId] == prepByPhase[it.subGoalId]
            ) {
                "Une association de sous-objectif est invalide."
            }
        }
        val linked = d.garminActivities.mapNotNull { it.workoutId }
        require(linked.distinct().size == linked.size) {
            "Plusieurs activités Garmin sont associées à une même séance."
        }
        d.garminActivities.forEach { row ->
            require(row.key == "${row.accountId}:${row.remoteId}" && row.accountId.isNotBlank())
            require(row.resolution in setOf("PENDING", "AUTO", "CONFIRMED", "UNPLANNED", "IGNORED"))
            checkDepth(row.payload)
            val activity = codec.decodeFromString<GarminActivity>(row.payload)
            activity.validate()
            require(activity.id == row.remoteId && activity.fingerprint == row.fingerprint)
            require(row.workoutId == null || row.workoutId in workouts)
            require(!row.deleted || row.workoutId == null)
            row.originalWorkout?.let { raw ->
                checkDepth(raw)
                val w = codec.decodeFromString<PlannedWorkout>(raw)
                workout(w)
                require(w.id == row.workoutId)
            }
            row.originalResult?.let(::result)
        }
        d.garminSync.forEach { require(it.revision >= 0 && it.syncedAt >= 0) }
    }

    private fun invalid(): Nothing =
        throw BackupValidationException(
            "Le fichier de sauvegarde est illisible ou son contenu est invalide. Aucune restauration effectuée."
        )
}

class BackupValidationException(message: String) : IllegalArgumentException(message)
