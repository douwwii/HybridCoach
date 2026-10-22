package fr.hybridcoach.garmin

import androidx.room.*
import fr.hybridcoach.data.PlannedWorkout
import fr.hybridcoach.domain.*
import java.time.*
import kotlinx.serialization.Serializable

@Serializable
data class GarminActivity(
    val id: String,
    val name: String,
    val sport: Sport,
    val sportKey: String,
    val startedAt: String,
    val localDateTime: String,
    val distanceMeters: Double? = null,
    val durationSeconds: Double,
    val movingSeconds: Double? = null,
    val elapsedSeconds: Double? = null,
    val averagePace: Double? = null,
    val averageHr: Double? = null,
    val elevationGain: Double? = null,
    val fingerprint: String,
) {
    val date
        get() = LocalDateTime.parse(localDateTime).toLocalDate()

    fun validate() {
        require(id.isNotBlank() && id.length <= 200 && name.length <= 1000)
        Instant.parse(startedAt)
        LocalDateTime.parse(localDateTime)
        require(fingerprint.matches(Regex("[a-f0-9]{64}")))
        require(durationSeconds.isFinite() && durationSeconds >= 0)
        listOf(distanceMeters, movingSeconds, elapsedSeconds, averagePace, elevationGain).forEach {
            require(it == null || it.isFinite() && it >= 0)
        }
        require(averageHr == null || averageHr.isFinite() && averageHr in 0.0..300.0)
    }
}

@Serializable
data class GarminSnapshot(
    val accountId: String,
    val revision: Long,
    val activities: List<GarminActivity>,
    val deletedIds: List<String> = emptyList(),
    val complete: Boolean = true,
)

@Serializable
data class GarminConnection(val connected: Boolean = false, val accountId: String? = null)

@Serializable
data class GarminCandidate(val workoutId: String, val score: Double, val reasons: List<String>)

@Serializable
@Entity(
    tableName = "garminActivities",
    foreignKeys =
        [
            ForeignKey(
                entity = PlannedWorkout::class,
                parentColumns = ["id"],
                childColumns = ["workoutId"],
                onDelete = ForeignKey.SET_NULL,
            )
        ],
    indices =
        [
            Index(value = ["workoutId"], unique = true),
            Index("accountId"),
            Index(value = ["remoteId"], unique = true),
        ],
)
data class GarminActivityEntity(
    @PrimaryKey val key: String,
    val accountId: String,
    val remoteId: String,
    val payload: String,
    val fingerprint: String,
    val workoutId: String? = null,
    val resolution: String = "PENDING",
    val originalWorkout: String? = null,
    val originalResult: String? = null,
    val deleted: Boolean = false,
)

@Entity(tableName = "garminSync")
data class GarminSyncEntity(
    @PrimaryKey val accountId: String,
    val revision: Long,
    val syncedAt: Long,
)

val PlannedWorkout.planningDate: LocalDate
    get() = (plannedDate ?: date).asDate()
val PlannedWorkout.hasPlannedContent: Boolean
    get() = source != "GARMIN" || plannedDate != null

/** Stage two contract only: no upload implementation and no UI action. */
interface GarminWorkoutExporter {
    suspend fun export(workout: PlannedWorkout, plan: WorkoutPlan): String
}
