package fr.hybridcoach.garmin

import androidx.room.withTransaction
import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import java.time.Instant
import kotlinx.serialization.encodeToString

/** The only writer of Garmin associations. Each import/decision is one Room transaction. */
class GarminImporter(private val db: CoachDatabase) {
    private val dao = db.dao()

    /** Adopt the former bridge identity only when Garmin returned a shared global activity ID. */
    suspend fun importPersonal(snapshot: GarminSnapshot): Int =
        db.withTransaction {
            require(snapshot.accountId.matches(Regex("personal:[0-9]+")))
            val incoming = snapshot.activities.associateBy { it.id }
            val legacy =
                dao.allGarmin()
                    .filter {
                        it.accountId.matches(Regex("[a-f0-9]{64}")) && it.remoteId in incoming
                    }
                    .map { it.accountId }
                    .distinct()
            for (oldAccount in legacy) {
                dao.moveGarminAccount(oldAccount, snapshot.accountId)
                dao.deleteGarminSync(oldAccount)
            }
            // The old Python adapter used another canonical JSON format for fingerprints.
            // Re-hash identical measures without overwriting a user's local corrections.
            if (legacy.isNotEmpty()) {
                for (row in dao.allGarmin().filter { it.accountId == snapshot.accountId }) {
                    val current = incoming[row.remoteId] ?: continue
                    val old = codec.decodeFromString<GarminActivity>(row.payload)
                    if (
                        old.copy(
                            startedAt = Instant.parse(old.startedAt).toString(),
                            localDateTime =
                                java.time.LocalDateTime.parse(old.localDateTime).toString(),
                            fingerprint = current.fingerprint,
                        ) == current
                    ) {
                        dao.putGarmin(
                            row.copy(
                                payload = codec.encodeToString(current),
                                fingerprint = current.fingerprint,
                            )
                        )
                    }
                }
            }
            import(snapshot)
        }

    suspend fun import(snapshot: GarminSnapshot): Int =
        db.withTransaction {
            require(snapshot.complete && snapshot.accountId.isNotBlank() && snapshot.revision > 0) {
                "Synchronisation incomplète : aucune modification locale."
            }
            require(snapshot.activities.map { it.id }.distinct().size == snapshot.activities.size)
            require(snapshot.activities.none { it.id in snapshot.deletedIds })
            snapshot.activities.forEach { it.validate() }
            val oldSync = dao.syncForAccount(snapshot.accountId)
            require(oldSync == null || snapshot.revision >= oldSync.revision) {
                "Cette synchronisation Garmin est antérieure à la dernière importation. Recommencez la synchronisation."
            }
            val known = dao.allGarmin()
            val incomingIds = snapshot.activities.map { it.id }.toSet()
            require(
                known.none { it.accountId != snapshot.accountId && it.remoteId in incomingIds }
            ) {
                "Ces activités existent déjà sous une autre connexion Garmin. Aucune donnée n’a été dupliquée ; vérifiez le compte connecté."
            }
            var changes = 0
            snapshot.deletedIds.distinct().forEach { id ->
                val old = dao.garminActivity("${snapshot.accountId}:$id")
                if (old != null && !old.deleted) {
                    detach(old)
                    dao.putGarmin(
                        old.copy(
                            workoutId = null,
                            originalWorkout = null,
                            originalResult = null,
                            deleted = true,
                        )
                    )
                    changes++
                }
            }
            val workouts = dao.allWorkouts()
            val existing = dao.allGarmin().associateBy { it.key }
            val occupied = existing.values.mapNotNull { it.workoutId }.toMutableSet()
            val results = dao.allResults().associate { it.workoutId to parseResult(it.payload) }
            val matches =
                snapshot.activities
                    .filter {
                        val row = existing["${snapshot.accountId}:${it.id}"]
                        row == null || row.deleted || row.resolution == "PENDING"
                    }
                    .associate { it.id to GarminMatcher.match(it, workouts, results, occupied) }
            // Two activities claiming the same plan require review, even if each is plausible
            // alone.
            val claims = matches.values.mapNotNull { it.automatic }.groupingBy { it }.eachCount()
            snapshot.activities.forEach { activity ->
                val key = "${snapshot.accountId}:${activity.id}"
                val old = dao.garminActivity(key)
                if (old == null || old.fingerprint != activity.fingerprint || old.deleted) {
                    var row =
                        (old ?: GarminActivityEntity(key, snapshot.accountId, activity.id, "", ""))
                            .copy(
                                payload = codec.encodeToString(activity),
                                fingerprint = activity.fingerprint,
                                deleted = false,
                            )
                    dao.putGarmin(row)
                    if (row.resolution != "IGNORED") {
                        val linked = row.workoutId?.let { dao.workout(it) }
                        if (linked != null) {
                            val compatible =
                                linked.sport == activity.sport ||
                                    linked.sport == Sport.RACE && activity.sport == Sport.RUN
                            if (row.originalWorkout != null && !compatible) {
                                detach(row)
                                dao.putGarmin(
                                    row.copy(
                                        workoutId = null,
                                        resolution = "PENDING",
                                        originalWorkout = null,
                                        originalResult = null,
                                    )
                                )
                            } else apply(row, linked, activity)
                        } else if (old == null || old.deleted) {
                            val match =
                                matches[activity.id]
                                    ?: GarminMatcher.match(activity, workouts, results, occupied)
                            if (
                                match.automatic != null &&
                                    claims[match.automatic] == 1 &&
                                    match.automatic !in occupied
                            ) {
                                linkInternal(row, match.automatic, "AUTO")
                                occupied += match.automatic
                            } else if (match.candidates.isEmpty())
                                linkInternal(row, null, "UNPLANNED")
                        }
                    }
                    changes++
                }
            }
            dao.putSync(
                GarminSyncEntity(snapshot.accountId, snapshot.revision, System.currentTimeMillis())
            )
            changes
        }

    suspend fun resolve(key: String, workoutId: String?) =
        db.withTransaction {
            val row = requireNotNull(dao.garminActivity(key))
            require(!row.deleted)
            if (row.workoutId == workoutId && workoutId != null) return@withTransaction
            // Capture local annotations before removing a generated occurrence or restoring a plan.
            val annotations =
                row.workoutId?.let { dao.result(it)?.payload }?.let { parseResult(it) }
            detach(row)
            val cleared = row.copy(workoutId = null, originalWorkout = null, originalResult = null)
            dao.putGarmin(cleared)
            linkInternal(
                cleared,
                workoutId,
                if (workoutId == null) "UNPLANNED" else "CONFIRMED",
                annotations,
            )
        }

    suspend fun unlink(key: String, ignore: Boolean = false) =
        db.withTransaction {
            val row = requireNotNull(dao.garminActivity(key))
            detach(row)
            dao.putGarmin(
                row.copy(
                    workoutId = null,
                    originalWorkout = null,
                    originalResult = null,
                    resolution = if (ignore) "IGNORED" else "PENDING",
                )
            )
        }

    private suspend fun linkInternal(
        row: GarminActivityEntity,
        target: String?,
        resolution: String,
        annotations: WorkoutResult? = null,
    ) {
        val activity = codec.decodeFromString<GarminActivity>(row.payload)
        val original = target?.let { requireNotNull(dao.workout(it)) { "Séance introuvable." } }
        require(original == null || dao.garminForWorkout(original.id) == null) {
            "Cette séance est déjà associée à une autre activité Garmin."
        }
        require(original == null || original.status != Status.ACTIVE) {
            "Terminez la séance en cours avant de l’associer."
        }
        require(original == null || original.source != "GARMIN")
        require(
            original == null ||
                original.sport == activity.sport ||
                original.sport == Sport.RACE && activity.sport == Sport.RUN
        ) {
            "Le sport de la séance ne correspond pas à l’activité Garmin."
        }
        val workout =
            original
                ?: PlannedWorkout(
                    name = activity.name,
                    date = activity.date.toString(),
                    sport = activity.sport,
                    planJson = codec.encodeToString(WorkoutPlan()),
                )
        val result = dao.result(workout.id)?.payload
        val linked =
            row.copy(
                workoutId = workout.id,
                resolution = resolution,
                originalWorkout = original?.let { codec.encodeToString(it) },
                originalResult = result,
            )
        dao.putWorkout(workout)
        dao.putGarmin(linked)
        apply(linked, workout, activity, annotations)
    }

    private suspend fun apply(
        row: GarminActivityEntity,
        current: PlannedWorkout,
        activity: GarminActivity,
        annotations: WorkoutResult? = null,
    ) {
        val original = row.originalWorkout?.let { codec.decodeFromString<PlannedWorkout>(it) }
        dao.putWorkout(
            current.copy(
                date = activity.date.toString(),
                plannedDate = original?.planningDate?.toString(),
                source = "GARMIN",
                status = Status.DONE,
                sport = if (original == null) activity.sport else current.sport,
                name = if (original == null) activity.name else current.name,
            )
        )
        val result = parseResult(dao.result(current.id)?.payload)
        val notes =
            listOf(result.notes, annotations?.notes.orEmpty())
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString("\n")
        val started = Instant.parse(activity.startedAt).toEpochMilli()
        dao.putResult(
            ResultEntity(
                current.id,
                codec.encodeToString(
                    result.copy(
                        distanceKm = activity.distanceMeters?.div(1000)?.toString().orEmpty(),
                        durationSeconds = activity.durationSeconds.toString(),
                        averageHr = activity.averageHr?.toString().orEmpty(),
                        elevation = activity.elevationGain?.toString().orEmpty(),
                        startedAt = started,
                        finishedAt = started + (activity.durationSeconds * 1000).toLong(),
                        restUntil = null,
                        notes = notes,
                        rpe = result.rpe.ifBlank { annotations?.rpe.orEmpty() },
                        feeling = result.feeling.ifBlank { annotations?.feeling.orEmpty() },
                        fatigue = result.fatigue.ifBlank { annotations?.fatigue.orEmpty() },
                    )
                ),
            )
        )
    }

    private suspend fun detach(row: GarminActivityEntity) {
        val current = row.workoutId?.let { dao.workout(it) } ?: return
        // Release the unique association before deleting/reassigning its occurrence.
        dao.putGarmin(row.copy(workoutId = null))
        val original = row.originalWorkout?.let { codec.decodeFromString<PlannedWorkout>(it) }
        if (original == null) dao.deleteWorkout(current.id)
        else {
            dao.putWorkout(
                current.copy(
                    date = original.date,
                    plannedDate = original.plannedDate,
                    source = original.source,
                    status = original.status,
                )
            )
            val local = parseResult(dao.result(current.id)?.payload)
            val originalResult = row.originalResult?.let { parseResult(it) }
            val annotations =
                local.notes.isNotBlank() ||
                    local.rpe.isNotBlank() ||
                    local.fatigue.isNotBlank() ||
                    local.feeling.isNotBlank() ||
                    local.sets.isNotEmpty()
            if (originalResult == null && !annotations) dao.deleteResult(current.id)
            else
                dao.putResult(
                    ResultEntity(
                        current.id,
                        codec.encodeToString(
                            (originalResult ?: WorkoutResult()).copy(
                                notes = local.notes,
                                rpe = local.rpe,
                                fatigue = local.fatigue,
                                feeling = local.feeling,
                                sets = local.sets,
                                setCounts = local.setCounts,
                            )
                        ),
                    )
                )
        }
    }
}
