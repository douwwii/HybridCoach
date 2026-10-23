package fr.hybridcoach

import androidx.room.withTransaction
import fr.hybridcoach.backup.*
import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import fr.hybridcoach.garmin.*
import kotlinx.serialization.encodeToString

/** Synthetic data only; no personal user data is ever loaded by a test. */
object BackupFixture {
    fun document(): BackupDocument {
        val strength =
            StrengthExercise(
                "strength-instance",
                "custom-exercise",
                "Squat",
                "Quadriceps",
                load = 80.0,
                notes = "Note privée é🙂",
            )
        val run = RunningStep(id = "warmup", amount = 1200.0)
        val block =
            RunningStep(
                id = "outer",
                kind = StepKind.REPEAT,
                repetitions = 3,
                children =
                    listOf(
                        RunningStep(
                            id = "inner",
                            kind = StepKind.REPEAT,
                            repetitions = 4,
                            children =
                                listOf(
                                    RunningStep(
                                        id = "effort",
                                        durationKind = DurationKind.DISTANCE,
                                        amount = 400.0,
                                        targetKind = TargetKind.PACE,
                                        target = 195.0,
                                    )
                                ),
                            recovery =
                                RunningStep(
                                    id = "rest-short",
                                    kind = StepKind.RECOVERY,
                                    amount = 60.0,
                                ),
                        )
                    ),
                recovery = RunningStep(id = "rest-long", kind = StepKind.RECOVERY, amount = 180.0),
            )
        val original =
            PlannedWorkout(
                "run",
                "Seuil Garmin",
                "2026-10-22",
                Sport.RUN,
                notes = "Séance privée",
                preparationId = "prep",
                planJson = codec.encodeToString(WorkoutPlan(running = listOf(run, block))),
                seriesId = "weekly-series",
            )
        val actual =
            original.copy(
                date = "2026-10-23",
                plannedDate = original.date,
                plannedTime = "08:30",
                status = Status.DONE,
                source = "GARMIN",
            )
        val active =
            PlannedWorkout(
                "strength",
                "Force en cours",
                "2026-10-23",
                Sport.STRENGTH,
                status = Status.ACTIVE,
                preparationId = "prep",
                planJson = codec.encodeToString(WorkoutPlan(strength = listOf(strength))),
            )
        val activity =
            GarminActivity(
                "77",
                "Course synthétique",
                Sport.RUN,
                "running",
                "2026-10-23T06:30:00Z",
                "2026-10-23T08:30:00",
                10000.0,
                3000.0,
                averageHr = 148.0,
                fingerprint = "a".repeat(64),
            )
        return BackupDocument(
            BACKUP_FORMAT,
            BACKUP_VERSION,
            DATABASE_VERSION,
            BuildConfig.VERSION_NAME,
            "2026-10-23T09:00:00Z",
            BackupData(
                AthleteProfile(
                    vma = "16,5",
                    maxHr = "190",
                    pace10 = "240",
                    hrZones = "1:110-140",
                    paceZones = "1:300-360",
                ),
                "light",
                listOf(
                    Exercise("custom-exercise", "Squat", "Quadriceps", custom = true),
                    Exercise("reference", "Pompes", "Pectoraux"),
                ),
                listOf(
                    WorkoutTemplate(
                        "template",
                        "Modèle structuré",
                        Sport.RUN,
                        planJson = original.planJson,
                    )
                ),
                listOf(
                    TrainingGoal(
                        "goal",
                        "Marathon",
                        "2026-12-15",
                        42.195,
                        12600,
                        notes = "Objectif privé",
                    )
                ),
                listOf(
                    TrainingPreparation(
                        "prep",
                        "goal",
                        "Préparation archivée",
                        "2026-08-17",
                        "2026-12-15",
                        true,
                    )
                ),
                listOf(actual, active),
                listOf(
                    ResultEntity(
                        "run",
                        codec.encodeToString(
                            WorkoutResult(
                                distanceKm = "9.8",
                                durationSeconds = "3000",
                                notes = "Correction personnelle",
                            )
                        ),
                    ),
                    ResultEntity(
                        "strength",
                        codec.encodeToString(
                            WorkoutResult(
                                sets =
                                    listOf(
                                        SetLog(strength.id, 0, "80", "8", true),
                                        SetLog(strength.id, 1, "81", "à saisir", false),
                                    ),
                                setCounts = mapOf(strength.id to 4),
                                startedAt = 1792742400000L,
                                restUntil = 1792749999000L,
                                notes = "En cours",
                            )
                        ),
                    ),
                ),
                listOf(
                    PreparationSubGoal("phase1", "prep", "Vitesse", "2026-10-01", "2026-10-30"),
                    PreparationSubGoal("phase2", "prep", "Seuil", "2026-10-15", "2026-11-15", 2),
                ),
                listOf(
                    WorkoutSubGoal("run", "phase1"),
                    WorkoutSubGoal("run", "phase2"),
                    WorkoutSubGoal("strength", "phase2"),
                ),
                listOf(
                    GarminActivityEntity(
                        "personal:42:77",
                        "personal:42",
                        "77",
                        codec.encodeToString(activity),
                        activity.fingerprint,
                        workoutId = "run",
                        resolution = "CONFIRMED",
                        originalWorkout = codec.encodeToString(original),
                        originalResult = codec.encodeToString(WorkoutResult(notes = "Avant Garmin")),
                    )
                ),
                listOf(GarminSyncEntity("personal:42", 7, 1792742400000L)),
            ),
        )
    }

    suspend fun populate(db: CoachDatabase, document: BackupDocument = document()) =
        db.withTransaction {
            val dao = db.dao()
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
