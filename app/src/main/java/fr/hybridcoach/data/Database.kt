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
    tableName = "subGoals",
    foreignKeys =
        [
            ForeignKey(
                entity = TrainingPreparation::class,
                parentColumns = ["id"],
                childColumns = ["preparationId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("preparationId")],
)
data class PreparationSubGoal(
    @PrimaryKey val id: String = newId(),
    val preparationId: String,
    val name: String,
    val start: String,
    val end: String,
    val color: Int = 0,
)

@Serializable
@Entity(
    tableName = "workoutSubGoals",
    primaryKeys = ["workoutId", "subGoalId"],
    foreignKeys =
        [
            ForeignKey(
                entity = PlannedWorkout::class,
                parentColumns = ["id"],
                childColumns = ["workoutId"],
                onDelete = ForeignKey.CASCADE,
            ),
            ForeignKey(
                entity = PreparationSubGoal::class,
                parentColumns = ["id"],
                childColumns = ["subGoalId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("subGoalId")],
)
data class WorkoutSubGoal(val workoutId: String, val subGoalId: String)

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

    @Query("SELECT * FROM subGoals ORDER BY start, name")
    fun subGoals(): Flow<List<PreparationSubGoal>>

    @Query("SELECT * FROM workoutSubGoals") fun workoutSubGoals(): Flow<List<WorkoutSubGoal>>

    @Query("SELECT * FROM subGoals WHERE id=:id")
    suspend fun subGoal(id: String): PreparationSubGoal?

    @Query("SELECT * FROM preparations WHERE id=:id")
    suspend fun preparation(id: String): TrainingPreparation?

    @Query("SELECT subGoalId FROM workoutSubGoals WHERE workoutId=:id")
    suspend fun subGoalIds(id: String): List<String>

    @Upsert suspend fun putSubGoal(value: PreparationSubGoal)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putLinks(value: List<WorkoutSubGoal>)

    @Query("DELETE FROM workoutSubGoals WHERE workoutId=:id") suspend fun clearLinks(id: String)

    @Query("DELETE FROM subGoals WHERE id=:id") suspend fun deleteSubGoal(id: String)

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
            PreparationSubGoal::class,
            WorkoutSubGoal::class,
        ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class CoachDatabase : RoomDatabase() {
    abstract fun dao(): CoachDao
}

// Additive migration: existing workouts, structured plans and results are untouched.
val MIGRATION_1_2 =
    object : androidx.room.migration.Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS subGoals (id TEXT NOT NULL, preparationId TEXT NOT NULL, name TEXT NOT NULL, start TEXT NOT NULL, end TEXT NOT NULL, color INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(preparationId) REFERENCES preparations(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_subGoals_preparationId ON subGoals(preparationId)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS workoutSubGoals (workoutId TEXT NOT NULL, subGoalId TEXT NOT NULL, PRIMARY KEY(workoutId, subGoalId), FOREIGN KEY(workoutId) REFERENCES workouts(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(subGoalId) REFERENCES subGoals(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_workoutSubGoals_subGoalId ON workoutSubGoals(subGoalId)"
            )
        }
    }
