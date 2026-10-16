package fr.hybridcoach.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import fr.hybridcoach.domain.*

/** Builds a real recursive tree, never expands repetitions into editable copies. */
@Composable
fun IntervalBlockDialog(
    profile: AthleteProfile,
    dismiss: () -> Unit,
    save: (List<RunningStep>) -> Unit,
) {
    var repetitions by rememberSaveable { mutableStateOf("4") }
    var sets by rememberSaveable { mutableStateOf("1") }
    var effort by rememberSaveable { mutableStateOf("1000") }
    var effortKind by rememberSaveable { mutableStateOf(DurationKind.DISTANCE.name) }
    var targetKind by rememberSaveable { mutableStateOf(TargetKind.PACE.name) }
    var target by rememberSaveable { mutableStateOf("210") }
    var recovery by rememberSaveable { mutableStateOf("120") }
    var recoveryKind by rememberSaveable { mutableStateOf(DurationKind.TIME.name) }
    var active by rememberSaveable { mutableStateOf(true) }
    var between by rememberSaveable { mutableStateOf("180") }
    var warmup by rememberSaveable { mutableStateOf("1200") }
    var cooldown by rememberSaveable { mutableStateOf("600") }
    var error by remember { mutableStateOf<String?>(null) }
    FormDialog(
        "Composer un fractionné",
        dismiss,
        {
            try {
                fun number(s: String) = requireNotNull(s.decimal()) { "Vérifiez les nombres." }
                val n = requireNotNull(repetitions.toIntOrNull()) { "Répétitions invalides." }
                val blocks = requireNotNull(sets.toIntOrNull()) { "Blocs invalides." }
                require(blocks in 1..100 && n in 1..100) { "1 à 100 répétitions / blocs." }
                val work =
                    RunningStep(
                        durationKind = DurationKind.valueOf(effortKind),
                        amount = number(effort),
                        targetKind = TargetKind.valueOf(targetKind),
                        target = if (targetKind == TargetKind.NONE.name) null else number(target),
                    )
                val restAmount = number(recovery)
                require(restAmount >= 0) { "La récupération doit être positive ou nulle." }
                val inner =
                    RunningStep(
                        kind = StepKind.REPEAT,
                        amount = null,
                        repetitions = n,
                        children = listOf(work),
                        recovery =
                            if (restAmount > 0)
                                RunningStep(
                                    kind = StepKind.RECOVERY,
                                    durationKind = DurationKind.valueOf(recoveryKind),
                                    amount = restAmount,
                                    activeRecovery = active,
                                )
                            else null,
                    )
                val betweenAmount = if (blocks > 1) number(between) else 0.0
                require(betweenAmount >= 0)
                val main =
                    if (blocks == 1) inner
                    else
                        RunningStep(
                            kind = StepKind.REPEAT,
                            amount = null,
                            repetitions = blocks,
                            children = listOf(inner),
                            recovery =
                                if (betweenAmount > 0)
                                    RunningStep(
                                        kind = StepKind.RECOVERY,
                                        amount = betweenAmount,
                                        activeRecovery = active,
                                    )
                                else null,
                        )
                val warm = if (warmup.isBlank()) 0.0 else number(warmup)
                val cool = if (cooldown.isBlank()) 0.0 else number(cooldown)
                require(warm >= 0 && cool >= 0)
                val steps =
                    buildList {
                            if (warm > 0) add(RunningStep(kind = StepKind.WARMUP, amount = warm))
                            add(main)
                            if (cool > 0) add(RunningStep(kind = StepKind.COOLDOWN, amount = cool))
                        }
                        .map { SportMath.snapshot(it, profile) }
                validatePlan(WorkoutPlan(running = steps))
                save(steps)
            } catch (e: Exception) {
                error = e.localizedMessage
            }
        },
        "Ajouter les blocs",
    ) {
        Text(
            "Un effort, une récupération, puis le nombre de répétitions. Les récupérations sont placées entre les répétitions uniquement."
        )
        Field("Nombre de répétitions", repetitions, { repetitions = it }, true)
        Choice(
            "Effort",
            DurationKind.valueOf(effortKind),
            listOf(DurationKind.DISTANCE, DurationKind.TIME),
            text = { it.label },
            change = { effortKind = it.name },
        )
        Field(
            if (effortKind == DurationKind.DISTANCE.name) "Distance d’effort (m)"
            else "Durée d’effort (s)",
            effort,
            { effort = it },
            true,
        )
        Choice(
            "Intensité",
            TargetKind.valueOf(targetKind),
            listOf(
                TargetKind.NONE,
                TargetKind.PACE,
                TargetKind.SPEED,
                TargetKind.HR,
                TargetKind.POWER,
                TargetKind.RPE,
                TargetKind.CADENCE,
            ),
            text = { it.label },
            change = { targetKind = it.name },
        )
        if (targetKind != TargetKind.NONE.name)
            Field(TargetKind.valueOf(targetKind).label, target, { target = it }, true)
        if (targetKind == TargetKind.PACE.name) Text("3:30/km = 210 s/km · 3:15/km = 195 s/km")
        Choice(
            "Récupération",
            DurationKind.valueOf(recoveryKind),
            listOf(DurationKind.TIME, DurationKind.DISTANCE),
            text = { it.label },
            change = { recoveryKind = it.name },
        )
        Field(
            if (recoveryKind == DurationKind.TIME.name) "Récupération (s, 0 = aucune)"
            else "Récupération (m, 0 = aucune)",
            recovery,
            { recovery = it },
            true,
        )
        CheckLine("Récupération active", active) { active = it }
        Field("Nombre de blocs", sets, { sets = it }, true)
        if ((sets.toIntOrNull() ?: 1) > 1)
            Field("Récupération entre blocs (s)", between, { between = it }, true)
        Field("Échauffement (s, facultatif)", warmup, { warmup = it }, true)
        Field("Retour au calme (s, facultatif)", cooldown, { cooldown = it }, true)
        ErrorText(error)
    }
}
