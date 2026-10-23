package fr.hybridcoach.data

import androidx.room.withTransaction
import fr.hybridcoach.domain.parsePlan
import fr.hybridcoach.domain.validatePlan
import fr.hybridcoach.planning.*
import java.time.LocalDate

private suspend fun CoachRepository.readjustmentSource() =
    ReadjustmentSource(
        workouts = dao.allWorkouts().sortedBy { it.id },
        preparations = dao.allPreparations().sortedBy { it.id },
        goals = dao.allGoals().sortedBy { it.id },
        phases = dao.allSubGoals().sortedBy { it.id },
        links = dao.allLinks().sortedWith(compareBy({ it.workoutId }, { it.subGoalId })),
        results = dao.allResults().sortedBy { it.workoutId },
        garmin = dao.allGarmin().sortedBy { it.key },
    )

suspend fun CoachRepository.previewReadjustment(
    request: ReadjustmentRequest,
    today: LocalDate = LocalDate.now(),
): ReadjustmentPreview =
    db.withTransaction { Readjustments.preview(readjustmentSource(), request, today) }

suspend fun CoachRepository.applyReadjustment(
    preview: ReadjustmentPreview,
    today: LocalDate = LocalDate.now(),
) =
    db.withTransaction {
        require(preview.today == today && readjustmentSource() == preview.source) {
            "Le calendrier a changé depuis l’aperçu. Recalculez les changements avant de valider."
        }
        // Recompute from the same snapshot: only the exact, reviewed proposal can be committed.
        val verified = Readjustments.preview(preview.source, preview.request, today)
        require(verified == preview && verified.hasChanges) {
            "Aucun changement à appliquer. Recalculez l’aperçu."
        }
        verified.workouts.forEach { validatePlan(parsePlan(it.after.planJson)) }
        verified.goal?.let { dao.putGoal(it) }
        verified.preparation?.let { dao.putPreparation(it) }
        verified.phases.forEach { dao.putSubGoal(it.after) }
        dao.putWorkouts(verified.workouts.map { it.after })
    }
