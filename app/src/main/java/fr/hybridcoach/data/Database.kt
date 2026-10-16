package fr.hybridcoach.data

import androidx.room.*
import fr.hybridcoach.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "exercises")
data class Exercise(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val muscle: String,
    val secondary: String = "",
    val equipment: String = "",
    val movement: String = "",
    val custom: Boolean = false,
)

@Serializable
@Entity(tableName = "templates")
data class WorkoutTemplate(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val sport: Sport,
    val description: String = "",
    val planJson: String,
)

@Serializable
@Entity(tableName = "goals")
data class TrainingGoal(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val raceDate: String,
    val distanceKm: Double? = null,
    val targetSeconds: Int? = null,
    val priority: String = "",
    val notes: String = "",
)

@Serializable
@Entity(
    tableName = "preparations",
    foreignKeys =
        [
            ForeignKey(
                entity = TrainingGoal::class,
                parentColumns = ["id"],
                childColumns = ["goalId"],
                onDelete = ForeignKey.RESTRICT,
            )
        ],
    indices = [Index("goalId")],
)
data class TrainingPreparation(
    @PrimaryKey val id: String = newId(),
    val goalId: String,
    val name: String,
    val start: String,
    val end: String,
    val archived: Boolean = false,
)

@Serializable
@Entity(
    tableName = "workouts",
    foreignKeys =
        [
            ForeignKey(
                entity = TrainingPreparation::class,
                parentColumns = ["id"],
                childColumns = ["preparationId"],
                onDelete = ForeignKey.SET_NULL,
            )
        ],
    indices = [Index("preparationId"), Index("date"), Index("seriesId")],
)
data class PlannedWorkout(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val date: String,
    val sport: Sport,
    val description: String = "",
    val priority: String = "",
    val notes: String = "",
    val status: Status = Status.PLANNED,
    val preparationId: String? = null,
    val planJson: String,
    val seriesId: String? = null,
)

@Serializable
@Entity(
    tableName = "results",
    foreignKeys =
        [
            ForeignKey(
                entity = PlannedWorkout::class,
                parentColumns = ["id"],
                childColumns = ["workoutId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class ResultEntity(@PrimaryKey val workoutId: String, val payload: String)

class Converters {
    @TypeConverter fun sport(value: String): Sport = Sport.valueOf(value)

    @TypeConverter fun sport(value: Sport): String = value.name

    @TypeConverter fun status(value: String): Status = Status.valueOf(value)

    @TypeConverter fun status(value: Status): String = value.name
}

@Dao
interface CoachDao {
    @Query("SELECT * FROM exercises ORDER BY name COLLATE NOCASE")
    fun exercises(): Flow<List<Exercise>>

    @Query("SELECT * FROM workouts ORDER BY date, name") fun workouts(): Flow<List<PlannedWorkout>>

    @Query("SELECT * FROM templates ORDER BY name") fun templates(): Flow<List<WorkoutTemplate>>

    @Query("SELECT * FROM goals ORDER BY raceDate") fun goals(): Flow<List<TrainingGoal>>

    @Query("SELECT * FROM preparations ORDER BY start DESC")
    fun preparations(): Flow<List<TrainingPreparation>>

    @Query("SELECT * FROM results") fun results(): Flow<List<ResultEntity>>

    @Query("SELECT * FROM workouts WHERE id=:id") suspend fun workout(id: String): PlannedWorkout?

    @Query("SELECT * FROM results WHERE workoutId=:id")
    suspend fun result(id: String): ResultEntity?

    @Query("SELECT * FROM workouts WHERE date BETWEEN :start AND :end")
    suspend fun week(start: String, end: String): List<PlannedWorkout>

    @Query(
        "SELECT * FROM workouts WHERE seriesId=:series AND date>=:date AND status IN ('PLANNED','POSTPONED')"
    )
    suspend fun future(series: String, date: String): List<PlannedWorkout>

    @Upsert suspend fun putExercise(value: Exercise)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun seedExercises(values: List<Exercise>)

    @Upsert suspend fun putWorkout(value: PlannedWorkout)

    @Upsert suspend fun putWorkouts(values: List<PlannedWorkout>)

    @Upsert suspend fun putTemplate(value: WorkoutTemplate)

    @Upsert suspend fun putGoal(value: TrainingGoal)

    @Upsert suspend fun putPreparation(value: TrainingPreparation)

    @Upsert suspend fun putResult(value: ResultEntity)

    @Query("DELETE FROM workouts WHERE id=:id") suspend fun deleteWorkout(id: String)

    @Query("DELETE FROM templates WHERE id=:id") suspend fun deleteTemplate(id: String)
}

@Database(
    entities =
        [
            Exercise::class,
            WorkoutTemplate::class,
            TrainingGoal::class,
            TrainingPreparation::class,
            PlannedWorkout::class,
            ResultEntity::class,
        ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class CoachDatabase : RoomDatabase() {
    abstract fun dao(): CoachDao
}
