package fr.hybridcoach.data

import androidx.room.withTransaction
import fr.hybridcoach.domain.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.encodeToString

// The library is reference content only. No workouts or performance results are seeded.
data class CoachState(
    val exercises: List<Exercise> = emptyList(),
    val workouts: List<PlannedWorkout> = emptyList(),
    val templates: List<WorkoutTemplate> = emptyList(),
    val goals: List<TrainingGoal> = emptyList(),
    val preparations: List<TrainingPreparation> = emptyList(),
    val results: Map<String, WorkoutResult> = emptyMap(),
    val loaded: Boolean = false,
    val subGoals: List<PreparationSubGoal> = emptyList(),
    val workoutSubGoals: List<WorkoutSubGoal> = emptyList(),
)

class CoachRepository(val db: CoachDatabase) {
    val dao = db.dao()
    private val baseState =
        combine(
            combine(dao.exercises(), dao.workouts(), dao.templates()) { e, w, t ->
                Triple(e, w, t)
            },
            combine(dao.goals(), dao.preparations(), dao.results()) { g, p, r -> Triple(g, p, r) },
        ) { a, b ->
            CoachState(
                a.first,
                a.second,
                a.third,
                b.first,
                b.second,
                b.third.associate { it.workoutId to parseResult(it.payload) },
                true,
            )
        }

    val state =
        combine(baseState, dao.subGoals(), dao.workoutSubGoals()) { base, goals, links ->
            base.copy(subGoals = goals, workoutSubGoals = links)
        }

    suspend fun saveSubGoal(value: PreparationSubGoal) =
        db.withTransaction {
            require(value.name.isNotBlank()) { "Donnez un nom au sous-objectif." }
            require(value.start.asDate() <= value.end.asDate()) { "La fin précède le début." }
            require(dao.preparation(value.preparationId) != null) { "Préparation introuvable." }
            val old = dao.subGoal(value.id)
            require(old == null || old.preparationId == value.preparationId)
            dao.putSubGoal(value.copy(name = value.name.trim()))
        }

    private suspend fun setLinks(
        workout: PlannedWorkout,
        ids: Set<String>?,
        sourceId: String = workout.id,
    ) {
        val selected =
            ids
                ?: dao.subGoalIds(sourceId)
                    .filter { dao.subGoal(it)?.preparationId == workout.preparationId }
                    .toSet()
        selected.forEach {
            require(
                dao.subGoal(it)?.preparationId == workout.preparationId &&
                    workout.preparationId != null
            ) {
                "Les sous-objectifs doivent appartenir à la préparation de la séance."
            }
        }
        dao.clearLinks(workout.id)
        dao.putLinks(selected.map { WorkoutSubGoal(workout.id, it) })
    }

    suspend fun assignSubGoals(id: String, ids: Set<String>) =
        db.withTransaction { setLinks(requireNotNull(dao.workout(id)), ids) }

    suspend fun seed() = dao.seedExercises(exerciseLibrary())

    suspend fun saveWorkout(
        workout: PlannedWorkout,
        end: LocalDate?,
        days: Set<DayOfWeek>,
        future: Boolean,
        subGoalIds: Set<String>? = null,
    ) =
        db.withTransaction {
            require(workout.name.isNotBlank()) { "Donnez un nom à la séance." }
            val date = workout.date.asDate()
            validatePlan(parsePlan(workout.planJson))
            suspend fun put(value: PlannedWorkout) {
                dao.putWorkout(value)
                setLinks(value, subGoalIds)
            }
            val existing = dao.workout(workout.id)
            if (existing != null) {
                require(existing.status !in listOf(Status.DONE, Status.PARTIAL, Status.ACTIVE)) {
                    "Le contenu d’une séance commencée est conservé. Dupliquez-la pour la modifier."
                }
                if (future && existing.seriesId != null) {
                    val shift = ChronoUnit.DAYS.between(existing.date.asDate(), date)
                    dao.future(existing.seriesId, existing.date).forEach { old ->
                        put(
                            workout.copy(
                                id = old.id,
                                date = old.date.asDate().plusDays(shift).toString(),
                                seriesId = old.seriesId,
                                status = old.status,
                            )
                        )
                    }
                } else put(workout.copy(seriesId = existing.seriesId))
            } else if (end != null) {
                val occurrences = Recurrence.weekly(date, end, days)
                require(occurrences.isNotEmpty()) { "Aucune occurrence dans cette période." }
                val series = newId()
                occurrences.forEach {
                    put(
                        workout.copy(
                            id = newId(),
                            date = it.toString(),
                            seriesId = series,
                            planJson = codec.encodeToString(parsePlan(workout.planJson).freshCopy()),
                        )
                    )
                }
            } else put(workout)
        }

    suspend fun savePreparation(p: TrainingPreparation, g: TrainingGoal) =
        db.withTransaction {
            require(p.name.isNotBlank() && g.name.isNotBlank()) { "Les noms sont obligatoires." }
            require(p.start.asDate() <= p.end.asDate() && g.raceDate.asDate() >= p.start.asDate()) {
                "Vérifiez les dates."
            }
            require(g.distanceKm == null || g.distanceKm.isFinite() && g.distanceKm > 0)
            require(g.targetSeconds == null || g.targetSeconds > 0)
            dao.putGoal(g)
            dao.putPreparation(p)
        }

    suspend fun mutateResult(id: String, transform: (WorkoutResult) -> WorkoutResult) =
        db.withTransaction {
            require(dao.workout(id) != null)
            val result = transform(parseResult(dao.result(id)?.payload))
            dao.putResult(ResultEntity(id, codec.encodeToString(result)))
        }

    suspend fun start(id: String) =
        db.withTransaction {
            val w = requireNotNull(dao.workout(id))
            require(w.status !in listOf(Status.CANCELLED, Status.DONE, Status.PARTIAL))
            mutateResult(id) {
                it.copy(startedAt = it.startedAt ?: System.currentTimeMillis(), finishedAt = null)
            }
            dao.putWorkout(w.copy(status = Status.ACTIVE))
        }

    suspend fun finish(id: String, status: Status) =
        db.withTransaction {
            val w = requireNotNull(dao.workout(id))
            require(status in listOf(Status.DONE, Status.PARTIAL))
            val r = parseResult(dao.result(id)?.payload)
            validateResult(r)
            dao.putResult(
                ResultEntity(
                    id,
                    codec.encodeToString(
                        r.copy(
                            finishedAt = r.finishedAt ?: System.currentTimeMillis(),
                            restUntil = null,
                        )
                    ),
                )
            )
            dao.putWorkout(w.copy(status = status))
        }

    suspend fun setStatus(id: String, status: Status) =
        db.withTransaction {
            val w = requireNotNull(dao.workout(id))
            require(!w.status.isResult && w.status != Status.ACTIVE)
            require(status in listOf(Status.PLANNED, Status.POSTPONED, Status.CANCELLED))
            dao.putWorkout(w.copy(status = status))
        }

    suspend fun duplicate(id: String, date: LocalDate) =
        db.withTransaction {
            val w = requireNotNull(dao.workout(id))
            val copy =
                w.copy(
                    id = newId(),
                    date = date.toString(),
                    status = Status.PLANNED,
                    seriesId = null,
                    notes = "",
                    planJson = codec.encodeToString(parsePlan(w.planJson).freshCopy()),
                )
            dao.putWorkout(copy)
            setLinks(copy, null, id)
        }

    suspend fun move(id: String, date: LocalDate) =
        db.withTransaction {
            val w = requireNotNull(dao.workout(id))
            require(!w.status.isResult && w.status != Status.ACTIVE)
            dao.putWorkout(w.copy(date = date.toString(), status = Status.PLANNED))
        }

    suspend fun copyWeek(
        source: LocalDate,
        target: LocalDate,
        repeats: Int,
        preparationId: String? = null,
    ) =
        db.withTransaction {
            require(repeats in 1..104)
            require(target.monday() != source.monday()) { "Choisissez une autre semaine." }
            val start = source.monday()
            val list =
                dao.week(start.toString(), start.plusDays(6).toString()).filter {
                    preparationId == null || it.preparationId == preparationId
                }
            require(list.isNotEmpty()) { "Cette semaine ne contient aucune séance." }
            val shift = ChronoUnit.DAYS.between(start, target.monday())
            repeat(repeats) { i ->
                list.forEach { old ->
                    val copy =
                        old.copy(
                            id = newId(),
                            date = old.date.asDate().plusDays(shift + 7L * i).toString(),
                            seriesId = null,
                            status = Status.PLANNED,
                            notes = "",
                            planJson = codec.encodeToString(parsePlan(old.planJson).freshCopy()),
                        )
                    dao.putWorkout(copy)
                    setLinks(copy, null, old.id)
                }
            }
        }

    suspend fun associate(id: String, preparationId: String?) =
        db.withTransaction {
            dao.workout(id)?.let {
                val updated = it.copy(preparationId = preparationId)
                dao.putWorkout(updated)
                setLinks(updated, null)
            }
        }

    suspend fun delete(id: String) = dao.deleteWorkout(id)

    suspend fun saveTemplate(w: PlannedWorkout) =
        dao.putTemplate(
            WorkoutTemplate(
                name = w.name,
                sport = w.sport,
                description = w.description,
                planJson = w.planJson,
            )
        )
}
