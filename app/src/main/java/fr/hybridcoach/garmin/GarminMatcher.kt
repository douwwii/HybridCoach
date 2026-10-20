package fr.hybridcoach.garmin

import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import java.time.*
import java.time.temporal.ChronoUnit
import kotlin.math.*

data class GarminMatch(val candidates: List<GarminCandidate>, val automatic: String?)

object GarminMatcher {
    fun match(
        activity: GarminActivity,
        workouts: List<PlannedWorkout>,
        results: Map<String, WorkoutResult>,
        occupied: Set<String>,
    ): GarminMatch {
        val candidates =
            workouts
                .mapNotNull { w ->
                    if (
                        w.id in occupied ||
                            w.source == "GARMIN" ||
                            w.status == Status.CANCELLED ||
                            w.sport == Sport.REST
                    )
                        return@mapNotNull null
                    val compatible =
                        w.sport == activity.sport ||
                            w.sport == Sport.RACE && activity.sport == Sport.RUN
                    val dayGap = abs(ChronoUnit.DAYS.between(w.planningDate, activity.date))
                    if (!compatible || dayGap > 1) return@mapNotNull null
                    val reasons =
                        mutableListOf(
                            if (dayGap == 0L) "Même jour" else "À un jour de la date prévue",
                            "Sport compatible",
                        )
                    var score = (if (dayGap == 0L) .40 else .20) + .20
                    val plan = parsePlan(w.planJson)
                    val result = results[w.id].takeIf { w.status.isResult }
                    val expectedDistance =
                        result?.distanceKm?.decimal()?.times(1000)
                            ?: SportMath.plannedMeters(plan.running)
                    val expectedTime =
                        result?.durationSeconds?.decimal() ?: SportMath.plannedSeconds(plan.running)
                    fun similarity(a: Double, b: Double) =
                        (1 - abs(a - b) / max(a, b)).coerceIn(0.0, 1.0)
                    var measuredFit = false
                    if (expectedDistance > 0 && (activity.distanceMeters ?: 0.0) > 0) {
                        val fit = similarity(expectedDistance, activity.distanceMeters!!)
                        score += .20 * fit
                        measuredFit = fit >= .75
                        reasons += "Distance : écart ${((1-fit)*100).roundToInt()} %"
                    }
                    if (expectedTime > 0 && activity.durationSeconds > 0) {
                        val fit = similarity(expectedTime, activity.durationSeconds)
                        score += .15 * fit
                        measuredFit = measuredFit || fit >= .75
                        reasons += "Durée : écart ${((1-fit)*100).roundToInt()} %"
                    }
                    fun words(value: String) =
                        value
                            .lowercase()
                            .split(Regex("[^\\p{L}\\p{N}]+"))
                            .filter { it.length > 2 }
                            .toSet()
                    val a = words(activity.name)
                    val b = words(w.name)
                    if (a.isNotEmpty() && b.isNotEmpty())
                        score += .05 * a.intersect(b).size / a.union(b).size
                    w.plannedTime?.let {
                        val planned = LocalDateTime.of(w.planningDate, LocalTime.parse(it))
                        val minutes =
                            abs(
                                ChronoUnit.MINUTES.between(
                                    planned,
                                    LocalDateTime.parse(activity.localDateTime),
                                )
                            )
                        if (minutes <= 180) {
                            score += .1 * (1 - minutes / 180.0)
                            reasons += "Horaire proche"
                        }
                    }
                    if (!measuredFit || activity.sport == Sport.OTHER || w.status == Status.ACTIVE)
                        score = min(score, .74)
                    GarminCandidate(w.id, score.coerceAtMost(1.0), reasons)
                }
                .sortedWith(
                    compareByDescending<GarminCandidate> { it.score }.thenBy { it.workoutId }
                )
        val best = candidates.firstOrNull()
        val automatic =
            best
                ?.takeIf {
                    it.score >= .78 && it.score - (candidates.getOrNull(1)?.score ?: 0.0) >= .12
                }
                ?.workoutId
        return GarminMatch(candidates, automatic)
    }
}
