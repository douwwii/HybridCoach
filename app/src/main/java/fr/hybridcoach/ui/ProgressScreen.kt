package fr.hybridcoach.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import java.time.LocalDate

@Composable
fun ProgressScreen(state: CoachState) {
    var period by rememberSaveable { mutableStateOf(12) }
    var prep by rememberSaveable { mutableStateOf("") }
    var exerciseId by rememberSaveable { mutableStateOf("") }
    val start = LocalDate.now().monday().minusWeeks(period - 1L)
    val filtered =
        state.copy(workouts = state.workouts.filter { prep.isBlank() || it.preparationId == prep })
    val weeks = Statistics.weeks(filtered, start, period)
    val records = Statistics.exerciseHistory(filtered, exerciseId, start)
    val muscles = Statistics.muscles(filtered, start)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PageTitle("Voir le chemin parcouru", "Uniquement vos résultats enregistrés.")
            Choice(
                "Période",
                period,
                listOf(1, 4, 12, 52),
                text = {
                    when (it) {
                        1 -> "Semaine"
                        4 -> "Mois"
                        12 -> "Trois mois"
                        else -> "Année"
                    }
                },
                change = { period = it },
            )
            Choice(
                "Préparation",
                prep,
                listOf("") + state.preparations.map { it.id },
                text = { id -> state.preparations.find { it.id == id }?.name ?: "Toutes" },
                change = { prep = it },
            )
        }
        item {
            SectionTitle("Toutes les activités réalisées")
            val completed =
                filtered.workouts.filter {
                    it.status.isResult && it.date.asDate() in start..LocalDate.now()
                }
            Text(
                "${completed.size} séances · ${completed.sumOf { filtered.results[it.id]?.distanceKm?.decimal() ?: 0.0 }.pretty()} km cumulés"
            )
            Text(
                "${SportMath.durationLabel(completed.sumOf { filtered.results[it.id]?.durationSeconds?.decimal() ?: 0.0 }.toLong())} d’entraînement"
            )
        }
        item {
            SectionTitle("Course à pied")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${weeks.sumOf{it.actualKm}.pretty()} km",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        "${SportMath.durationLabel(weeks.sumOf{it.runSeconds}.toLong())} de course · ${filtered.workouts.count{it.status.isResult && it.sport in listOf(Sport.RUN,Sport.RACE) && it.date.asDate()>=start}} séances"
                    )
                    Text("Volume hebdomadaire · km", style = MaterialTheme.typography.labelLarge)
                    VolumeChart(weeks.map { it.plannedKm }, weeks.map { it.actualKm })
                    Text(
                        "Vert : réalisé · gris : prévu connu",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            Text(
                "Le volume prévu des étapes en temps sans allure connue est exclu. Les résultats non saisis ne sont pas estimés.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val runs =
            filtered.workouts.filter {
                it.status.isResult &&
                    it.sport in listOf(Sport.RUN, Sport.RACE) &&
                    it.date.asDate() >= start
            }
        item { SectionTitle("Effort déclaré · RPE") }
        val effort =
            runs
                .mapNotNull { filtered.results[it.id]?.rpe?.decimal() }
                .groupingBy { it }
                .eachCount()
                .toSortedMap()
        if (effort.isEmpty())
            item { Text("Renseignez le RPE de vos sorties pour voir sa répartition.") }
        effort.forEach { (rpe, count) ->
            item { Text("RPE ${rpe.pretty()} · $count séance${if(count>1) "s" else ""}") }
        }
        item {
            Text(
                "Répartition par RPE saisi après la séance, sans estimation de zones physiologiques.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (runs.isNotEmpty()) item { SectionTitle("Dernières performances de course") }
        runs
            .sortedByDescending { it.date }
            .take(12)
            .forEach { w ->
                item {
                    val r = filtered.results[w.id]
                    val pace =
                        r?.durationSeconds?.decimal()?.let { seconds ->
                            r.distanceKm.decimal()?.takeIf { it > 0 }?.let { seconds / it }
                        }
                    Text(
                        "${dateLabel(w.date)} · ${w.name}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        "${r?.distanceKm?.ifBlank{"—"} ?: "—"} km · ${SportMath.paceLabel(pace)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        item { SectionTitle("Vue hybride") }
        weeks.forEach { w ->
            item {
                Row(Modifier.fillMaxWidth()) {
                    Text(dateLabel(w.start.toString()), Modifier.weight(1f))
                    Text(
                        "${w.actualKm.pretty()} km · ${w.strengthSessions} muscu",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item {
            SectionTitle("Musculation · par exercice")
            Choice(
                "Exercice",
                exerciseId,
                listOf("") + state.exercises.map { it.id },
                text = { id ->
                    state.exercises.find { it.id == id }?.name ?: "Choisir un exercice"
                },
                change = { exerciseId = it },
            )
        }
        if (records.isEmpty())
            item { Text("Aucune série validée pour cet exercice sur cette période.") }
        else {
            item {
                Card {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Record de charge : ${records.maxOf{it.load}.pretty()} kg")
                        Text("Meilleure série : ${records.maxOf{it.reps}} répétitions")
                        Text(
                            "1RM estimé : ${records.mapNotNull{it.estimated1RM}.maxOrNull()?.pretty()?:"—"} kg"
                        )
                        val volumes =
                            records
                                .groupBy { it.date }
                                .mapValues { it.value.sumOf { r -> r.volume } }
                        Text(
                            "Meilleur volume journalier : ${volumes.values.maxOrNull()?.pretty()?:"—"} kg"
                        )
                        Text(
                            "${filtered.workouts.count{w -> w.status.isResult && w.date.asDate()>=start && parsePlan(w.planJson).strength.any{it.exerciseId==exerciseId} && filtered.results[w.id]?.sets.orEmpty().any{log -> log.checked && parsePlan(w.planJson).strength.any{it.id==log.exerciseInstanceId && it.exerciseId==exerciseId}}}} séances avec cet exercice"
                        )
                        Text(
                            "Epley : charge × (1 + répétitions / 30), séries de 2 à 12 répétitions ; 1 répétition = charge. Estimation limitée aux modes standard et unilatéral. Le volume représente la charge saisie × les répétitions ; il exclut le poids du corps.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        VolumeChart(
                            emptyList(),
                            volumes.keys.sorted().map { d ->
                                records.filter { it.date == d }.maxOf { it.load }
                            },
                        )
                        Text(
                            "Évolution de la meilleure charge quotidienne",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            records
                .groupBy { it.date }
                .toSortedMap(compareByDescending { it })
                .forEach { (date, sets) ->
                    item {
                        Text(
                            "${dateLabel(date.toString())} · " +
                                sets.joinToString(" / ") { "${it.load.pretty()} kg × ${it.reps}" }
                        )
                    }
                }
        }
        item { SectionTitle("Séries et fréquence par muscle") }
        if (muscles.isEmpty()) item { Text("Validez des séries pour consulter leur répartition.") }
        muscles.forEach { m ->
            item {
                Text(
                    "${m.muscle} · ${m.sets} séries · ${m.sessions} séances · ${(m.sessions.toDouble()/period).pretty()}/sem."
                )
            }
        }
        item {
            Text(
                "Une série est attribuée au muscle principal. Séries cochées des séances terminées ou partielles uniquement.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun VolumeChart(planned: List<Double>, actual: List<Double>) {
    val green = MaterialTheme.colorScheme.primary
    val gray = MaterialTheme.colorScheme.outlineVariant
    val max = (planned + actual).maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    Canvas(Modifier.fillMaxWidth().height(130.dp).padding(top = 8.dp)) {
        val n = maxOf(planned.size, actual.size, 1)
        val step = size.width / n
        repeat(n) { i ->
            val p = (planned.getOrNull(i) ?: 0.0).toFloat() / max.toFloat() * size.height
            val a = (actual.getOrNull(i) ?: 0.0).toFloat() / max.toFloat() * size.height
            drawRect(gray, Offset(i * step + step * .08f, size.height - p), Size(step * .38f, p))
            drawRect(green, Offset(i * step + step * .5f, size.height - a), Size(step * .38f, a))
        }
    }
}
