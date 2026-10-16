package fr.hybridcoach.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val codec = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
    allowSpecialFloatingPointValues = false
}

fun newId() = UUID.randomUUID().toString()

@Serializable
enum class Sport(val label: String) {
    RUN("Course"),
    STRENGTH("Musculation"),
    RACE("Compétition"),
    REST("Repos"),
    OTHER("Autre sport"),
}

@Serializable
enum class Status(val label: String) {
    PLANNED("Prévue"),
    ACTIVE("En cours"),
    DONE("Terminée"),
    PARTIAL("Partiellement terminée"),
    POSTPONED("Reportée"),
    CANCELLED("Annulée"),
}

val Status.isResult: Boolean
    get() = this == Status.DONE || this == Status.PARTIAL

@Serializable
data class StrengthExercise(
    val id: String = newId(),
    val exerciseId: String,
    val name: String,
    val muscle: String,
    val sets: Int = 3,
    val reps: String = "8–12",
    val load: Double? = null,
    val rir: Double? = null,
    val rpe: Double? = null,
    val restSeconds: Int = 90,
    val tempo: String = "",
    val notes: String = "",
    val mode: String = "Standard",
    val group: String = "",
)

@Serializable
enum class StepKind(val label: String) {
    WARMUP("Échauffement"),
    WORK("Effort"),
    RECOVERY("Récupération"),
    COOLDOWN("Retour au calme"),
    REPEAT("Répétitions"),
}

@Serializable
enum class DurationKind(val label: String) {
    TIME("Temps (secondes)"),
    DISTANCE("Distance (mètres)"),
    MANUAL("Fin manuelle"),
}

@Serializable
enum class TargetKind(val label: String) {
    NONE("Sans cible"),
    PACE("Allure (s/km)"),
    PACE_RANGE("Plage d’allure (s/km)"),
    SPEED("Vitesse (km/h)"),
    HR("FC (bpm)"),
    HR_ZONE("Zone FC"),
    PACE_ZONE("Zone d’allure"),
    POWER("Puissance (W)"),
    CADENCE("Cadence (pas/min)"),
    RPE("RPE"),
    VMA("% VMA"),
    REFERENCE("Référence d’allure"),
}

@Serializable
data class RunningStep(
    val id: String = newId(),
    val kind: StepKind = StepKind.WORK,
    val durationKind: DurationKind = DurationKind.TIME,
    val amount: Double? = 600.0,
    val targetKind: TargetKind = TargetKind.NONE,
    val target: Double? = null,
    val targetMax: Double? = null,
    val reference: String = "10 km",
    val resolvedPace: Double? = null,
    val resolvedLower: Double? = null,
    val resolvedUpper: Double? = null,
    val activeRecovery: Boolean = true,
    val repetitions: Int = 1,
    val children: List<RunningStep> = emptyList(),
    val recovery: RunningStep? = null, // Between repetitions only; never after the last one.
    val notes: String = "",
)

@Serializable
data class WorkoutPlan(
    val strength: List<StrengthExercise> = emptyList(),
    val running: List<RunningStep> = emptyList(),
)

@Serializable
data class SetLog(
    val exerciseInstanceId: String,
    val index: Int,
    val load: String = "",
    val reps: String = "",
    val checked: Boolean = false,
)

@Serializable
data class IntervalLog(
    val stepId: String,
    val ordinal: Int,
    val seconds: String = "",
    val meters: String = "",
    val hr: String = "",
)

@Serializable
data class WorkoutResult(
    val sets: List<SetLog> = emptyList(),
    val setCounts: Map<String, Int> = emptyMap(),
    val intervals: List<IntervalLog> = emptyList(),
    val distanceKm: String = "",
    val durationSeconds: String = "",
    val averageHr: String = "",
    val elevation: String = "",
    val rpe: String = "",
    val fatigue: String = "",
    val feeling: String = "",
    val notes: String = "",
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val restUntil: Long? = null,
)

@Serializable
data class AthleteProfile(
    val vma: String = "",
    val maxHr: String = "",
    val pace5: String = "",
    val pace10: String = "",
    val threshold: String = "",
    val half: String = "",
    val marathon: String = "",
    val hrZones: String = "",
    val paceZones: String = "",
) {
    fun referencePace(name: String): Double? =
        when (name) {
            "5 km" -> pace5
            "10 km" -> pace10
            "Seuil" -> threshold
            "Semi" -> half
            else -> marathon
        }.decimal()
}

fun String.decimal(): Double? = replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

fun LocalDate.monday(): LocalDate = with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

fun String.asDate(): LocalDate = LocalDate.parse(this)

fun parsePlan(raw: String): WorkoutPlan = codec.decodeFromString(raw)

fun parseResult(raw: String?): WorkoutResult =
    raw?.let { codec.decodeFromString<WorkoutResult>(it) } ?: WorkoutResult()

object SportMath {
    fun paceFromSpeed(kmh: Double): Double {
        require(kmh.isFinite() && kmh > 0)
        return 3600.0 / kmh
    }

    fun speedFromPace(secondsPerKm: Double): Double = paceFromSpeed(secondsPerKm)

    fun targetSeconds(meters: Double, pace: Double): Double {
        require(meters >= 0 && meters.isFinite() && pace > 0 && pace.isFinite())
        return meters / 1000 * pace
    }

    fun estimated1RM(load: Double, reps: Int): Double? =
        if (load > 0 && load.isFinite() && reps in 1..12)
            if (reps == 1) load else load * (1 + reps / 30.0)
        else null

    fun paceLabel(seconds: Double?): String =
        seconds
            ?.takeIf { it > 0 && it.isFinite() }
            ?.let {
                val n = kotlin.math.round(it).toLong()
                "%d:%02d/km".format(n / 60, n % 60)
            } ?: "—"

    fun durationLabel(seconds: Long): String =
        if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(seconds / 60, seconds % 60)

    fun resolvedPace(step: RunningStep, profile: AthleteProfile): Double? =
        when (step.targetKind) {
            TargetKind.PACE -> step.target
            TargetKind.PACE_RANGE ->
                step.target?.let { low -> step.targetMax?.let { (low + it) / 2 } }
            TargetKind.PACE_ZONE ->
                parseZones(profile.paceZones)[step.target?.toInt()]?.let {
                    (it.first + it.second) / 2
                }
            TargetKind.SPEED -> step.target?.takeIf { it > 0 }?.let(::paceFromSpeed)
            TargetKind.VMA ->
                profile.vma
                    .decimal()
                    ?.takeIf { it > 0 }
                    ?.let { v ->
                        step.target?.takeIf { it > 0 }?.let { paceFromSpeed(v * it / 100) }
                    }
            TargetKind.REFERENCE -> profile.referencePace(step.reference)
            else -> null
        }

    fun snapshot(step: RunningStep, profile: AthleteProfile): RunningStep =
        step.copy(
            resolvedPace = resolvedPace(step, profile),
            resolvedLower =
                if (step.targetKind == TargetKind.HR_ZONE)
                    parseZones(profile.hrZones)[step.target?.toInt()]?.first
                else if (step.targetKind == TargetKind.PACE_ZONE)
                    parseZones(profile.paceZones)[step.target?.toInt()]?.first
                else null,
            resolvedUpper =
                if (step.targetKind == TargetKind.HR_ZONE)
                    parseZones(profile.hrZones)[step.target?.toInt()]?.second
                else if (step.targetKind == TargetKind.PACE_ZONE)
                    parseZones(profile.paceZones)[step.target?.toInt()]?.second
                else null,
            children = step.children.map { snapshot(it, profile) },
            recovery = step.recovery?.let { snapshot(it, profile) },
        )

    fun plannedMeters(steps: List<RunningStep>): Double =
        steps.sumOf { s ->
            if (s.kind == StepKind.REPEAT)
                s.repetitions * plannedMeters(s.children) +
                    (s.repetitions - 1) * plannedMeters(listOfNotNull(s.recovery))
            else
                when (s.durationKind) {
                    DurationKind.DISTANCE -> s.amount ?: 0.0
                    DurationKind.TIME ->
                        s.resolvedPace?.takeIf { it > 0 }?.let { (s.amount ?: 0.0) / it * 1000 }
                            ?: 0.0
                    else -> 0.0
                }
        }

    fun plannedSeconds(steps: List<RunningStep>): Double =
        steps.sumOf { s ->
            if (s.kind == StepKind.REPEAT)
                s.repetitions * plannedSeconds(s.children) +
                    (s.repetitions - 1) * plannedSeconds(listOfNotNull(s.recovery))
            else
                when (s.durationKind) {
                    DurationKind.TIME -> s.amount ?: 0.0
                    DurationKind.DISTANCE ->
                        s.resolvedPace?.let { (s.amount ?: 0.0) / 1000 * it } ?: 0.0
                    else -> 0.0
                }
        }

    fun flatten(steps: List<RunningStep>): List<RunningStep> = buildList {
        fun visit(nodes: List<RunningStep>) {
            nodes.forEach {
                if (it.kind == StepKind.REPEAT)
                    repeat(it.repetitions) { index ->
                        visit(it.children)
                        if (index < it.repetitions - 1) visit(listOfNotNull(it.recovery))
                    }
                else add(it)
            }
        }
        visit(steps)
    }
}

object Recurrence {
    fun weekly(
        start: LocalDate,
        end: LocalDate,
        days: Set<DayOfWeek>,
        intervalWeeks: Int = 1,
    ): List<LocalDate> {
        require(!end.isBefore(start)) { "La fin doit suivre le début." }
        require(intervalWeeks in 1..52 && days.isNotEmpty())
        // A bounded creation operation; planning itself has no calendar horizon.
        require(java.time.temporal.ChronoUnit.DAYS.between(start, end) <= 3660) {
            "Créez les récurrences par périodes de dix ans maximum."
        }
        return generateSequence(start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(end) }
            .filter {
                it.dayOfWeek in days &&
                    java.time.temporal.ChronoUnit.WEEKS.between(start.monday(), it.monday()) %
                        intervalWeeks == 0L
            }
            .toList()
    }
}

fun validatePlan(plan: WorkoutPlan) {
    require(plan.strength.size <= 100) { "100 exercices maximum par séance." }
    plan.strength.forEach { e ->
        require(
            e.name.isNotBlank() &&
                e.sets in 1..50 &&
                e.restSeconds in 0..3600 &&
                e.reps.isNotBlank()
        ) {
            "Vérifiez les séries et le repos."
        }
        require(e.load == null || e.load.isFinite() && e.load >= 0)
        require(e.rir == null || e.rir in 0.0..10.0)
        require(e.rpe == null || e.rpe in 0.0..10.0)
    }
    fun visit(nodes: List<RunningStep>, depth: Int) {
        require(depth <= 5) { "Cinq niveaux de répétition maximum." }
        nodes.forEach { s ->
            if (s.kind == StepKind.REPEAT) {
                require(s.repetitions in 1..100 && s.children.isNotEmpty()) {
                    "Un bloc doit contenir des étapes et 1 à 100 répétitions."
                }
                visit(s.children, depth + 1)
                s.recovery?.let {
                    require(
                        it.kind == StepKind.RECOVERY && it.children.isEmpty() && it.recovery == null
                    )
                    visit(listOf(it), depth + 1)
                }
            } else {
                require(s.children.isEmpty() && s.recovery == null) {
                    "Seul un bloc contient des étapes."
                }
                require(
                    s.durationKind == DurationKind.MANUAL ||
                        (s.amount?.let { it.isFinite() && it > 0 } == true)
                ) {
                    "Indiquez une durée ou une distance positive."
                }
                if (s.targetKind !in listOf(TargetKind.NONE, TargetKind.REFERENCE))
                    require(s.target?.let { it.isFinite() && it > 0 } == true) {
                        "Cible manquante ou invalide."
                    }
                if (s.targetKind == TargetKind.PACE_RANGE)
                    require(s.targetMax != null && s.targetMax >= s.target!!) {
                        "Vérifiez la plage d’allure."
                    }
                if (s.targetKind == TargetKind.RPE) require(s.target!! <= 10)
                if (s.targetKind in listOf(TargetKind.HR_ZONE, TargetKind.PACE_ZONE)) {
                    require(s.target!! in 1.0..10.0 && s.target % 1.0 == 0.0)
                    require(s.resolvedLower != null && s.resolvedUpper != null) {
                        "Définissez cette zone dans le profil sportif."
                    }
                }
                if (s.targetKind in listOf(TargetKind.VMA, TargetKind.REFERENCE))
                    require(s.resolvedPace != null && s.resolvedPace > 0) {
                        "Complétez la référence dans le profil sportif."
                    }
            }
        }
    }
    visit(plan.running, 0)
    // Count first without expanding potentially hostile nested repetitions.
    fun count(nodes: List<RunningStep>): Long =
        nodes
            .sumOf {
                if (it.kind == StepKind.REPEAT)
                    (it.repetitions * count(it.children) +
                            (it.repetitions - 1) * count(listOfNotNull(it.recovery)))
                        .coerceAtMost(10001)
                else 1L
            }
            .coerceAtMost(10001)
    require(count(plan.running) <= 10000) { "10 000 étapes développées maximum." }
}

fun validateResult(r: WorkoutResult) {
    fun number(value: String, min: Double = 0.0, max: Double = Double.MAX_VALUE) {
        require(value.isBlank() || value.decimal()?.let { it in min..max } == true) {
            "Valeur numérique invalide : $value"
        }
    }
    number(r.distanceKm)
    number(r.durationSeconds)
    number(r.averageHr, 0.0, 300.0)
    number(r.elevation)
    number(r.rpe, 0.0, 10.0)
    number(r.fatigue, 0.0, 10.0)
    r.sets
        .filter { it.checked }
        .forEach {
            number(it.load)
            require(it.reps.toIntOrNull()?.let { n -> n in 0..1000 } == true) {
                "Répétitions requises pour une série validée."
            }
        }
    r.intervals.forEach {
        number(it.seconds)
        number(it.meters)
        number(it.hr, 0.0, 300.0)
    }
}

/** User-defined zones, one per line: 1:120-140. No imposed physiological formula. */
fun parseZones(raw: String): Map<Int, Pair<Double, Double>> {
    val zones = linkedMapOf<Int, Pair<Double, Double>>()
    raw.lineSequence()
        .filter { it.isNotBlank() }
        .forEach { line ->
            val match =
                Regex("""\s*(\d{1,2})\s*:\s*([0-9.,]+)\s*-\s*([0-9.,]+)\s*""").matchEntire(line)
            require(match != null) { "Zone invalide. Exemple : 1:120-140, une zone par ligne." }
            val index = match.groupValues[1].toInt()
            val low = requireNotNull(match.groupValues[2].decimal())
            val high = requireNotNull(match.groupValues[3].decimal())
            require(index in 1..10 && low > 0 && high >= low && !zones.containsKey(index)) {
                "Vérifiez le numéro et les bornes de chaque zone."
            }
            zones[index] = low to high
        }
    return zones
}

/** Preserve immutable templates while giving every new occurrence its own step identities. */
fun RunningStep.freshCopy(): RunningStep =
    copy(id = newId(), children = children.map { it.freshCopy() }, recovery = recovery?.freshCopy())

fun WorkoutPlan.freshCopy(): WorkoutPlan =
    copy(
        strength = strength.map { it.copy(id = newId()) },
        running = running.map { it.freshCopy() },
    )

fun duplicateStep(nodes: List<RunningStep>, id: String): List<RunningStep> =
    nodes.flatMap {
        if (it.id == id) listOf(it, it.freshCopy())
        else listOf(it.copy(children = duplicateStep(it.children, id)))
    }

/** A compact recursive summary also used by the classic calendar. */
fun blockSummary(step: RunningStep): String =
    if (step.kind == StepKind.REPEAT)
        "${step.repetitions} × (${step.children.joinToString(" + ") { blockSummary(it) }})" +
            (step.recovery?.let { " · entre : ${stepLabel(it)}" } ?: "")
    else stepLabel(step)
