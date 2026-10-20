package fr.hybridcoach.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import fr.hybridcoach.garmin.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class PlanningView(val label: String) {
    DAY("Jour"),
    WEEK("Semaine"),
    MONTH("Mois"),
    ALL("Préparation complète"),
}

private val phaseColors =
    listOf(
        Color(0xFFB5F36D),
        Color(0xFFBBA6FF),
        Color(0xFFFFC77A),
        Color(0xFF8BDDDD),
        Color(0xFFFFB1C5),
    )

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlanningScreen(
    vm: CoachViewModel,
    state: CoachState,
    initialPreparation: String,
    open: (String) -> Unit,
    edit: (String) -> Unit,
    create: (date: String, preparation: String, template: String) -> Unit,
) {
    var prepId by rememberSaveable { mutableStateOf(initialPreparation) }
    val prep = state.preparations.find { it.id == prepId }
    var dateRaw by rememberSaveable { mutableStateOf(prep?.start ?: LocalDate.now().toString()) }
    val date = dateRaw.asDate()
    var viewRaw by rememberSaveable {
        mutableStateOf(if (prep != null) PlanningView.ALL.name else PlanningView.WEEK.name)
    }
    val view = PlanningView.valueOf(viewRaw)
    val sessions = state.workouts.filter { prep == null || it.preparationId == prepId }
    val phases = state.subGoals.filter { it.preparationId == prepId }
    var adding by rememberSaveable { mutableStateOf<String?>(null) }
    var copyWeek by remember { mutableStateOf(false) }
    var editPhase by remember { mutableStateOf<PreparationSubGoal?>(null) }
    var export by remember { mutableStateOf(false) }
    var showPhases by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val weekScroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(date.monday(), viewRaw) {
        if (view == PlanningView.WEEK)
            weekScroll.scrollTo(with(density) { ((date.dayOfWeek.value - 1) * 198).dp.roundToPx() })
    }
    val dayBounds = remember { mutableStateMapOf<LocalDate, Rect>() }
    var hover by remember { mutableStateOf<LocalDate?>(null) }
    var dragging by remember { mutableStateOf<String?>(null) }
    // Updated values keep the gesture handler valid when a date changes during navigation.
    val moveWorkout by
        rememberUpdatedState<(String, LocalDate) -> Unit>({ id, day ->
            vm.action {
                vm.repo.move(id, day)
                vm.notice("Séance déplacée au ${dateLabel(day.toString())}")
            }
        })
    LaunchedEffect(dateRaw, viewRaw, prepId) {
        if (view == PlanningView.ALL && prep != null) {
            val index =
                ChronoUnit.WEEKS.between(prep.start.asDate().monday(), date.monday()).toInt()
            val maxIndex =
                ChronoUnit.WEEKS.between(prep.start.asDate().monday(), prep.end.asDate().monday())
                    .toInt()
            if (index in 0..maxIndex && index > 0) listState.animateScrollToItem(index + 4)
        }
    }
    fun navigate(delta: Long) {
        dateRaw =
            when (view) {
                PlanningView.DAY -> date.plusDays(delta)
                PlanningView.WEEK -> date.plusWeeks(delta)
                PlanningView.MONTH -> date.plusMonths(delta)
                PlanningView.ALL -> date.plusWeeks(delta)
            }.toString()
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("planning-screen"),
        state = listState,
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "Organisez vos séances et vos phases de travail.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Choice(
                "Préparation",
                prepId,
                listOf("") + state.preparations.map { it.id },
                text = { key ->
                    state.preparations.find { it.id == key }?.name ?: "Toutes les séances"
                },
                change = {
                    prepId = it
                    state.preparations.find { p -> p.id == it }?.let { p -> dateRaw = p.start }
                },
            )
        }
        stickyHeader {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlanningView.entries.forEach { mode ->
                        FilterChip(
                            selected = view == mode,
                            onClick = { viewRaw = mode.name },
                            label = { Text(mode.label) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { navigate(-1) }) {
                        Icon(Icons.Default.ChevronLeft, "Période précédente")
                    }
                    Text(
                        when (view) {
                            PlanningView.MONTH ->
                                date.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRENCH))
                            PlanningView.WEEK ->
                                "${dateLabel(date.monday().toString())} → ${dateLabel(date.monday().plusDays(6).toString())}"
                            else -> dateLabel(dateRaw)
                        },
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    IconButton(onClick = { navigate(1) }) {
                        Icon(Icons.Default.ChevronRight, "Période suivante")
                    }
                }
            }
        }
        item {
            DateField("Aller à la date", dateRaw) { dateRaw = it }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { dateRaw = LocalDate.now().toString() }) {
                    Text("Aujourd’hui")
                }
                FilledTonalButton(onClick = { adding = dateRaw }) {
                    Icon(Icons.Default.Add, null)
                    Text("Ajouter une séance")
                }
                TextButton(onClick = { copyWeek = true }) { Text("Dupliquer la semaine") }
                if (prep != null) TextButton(onClick = { export = true }) { Text("Exporter") }
            }
        }
        if (prep != null) {
            item {
                val race = state.goals.find { it.id == prep.goalId }
                val days =
                    ChronoUnit.DAYS.between(
                        LocalDate.now(),
                        race?.raceDate?.asDate() ?: prep.end.asDate(),
                    )
                if (view == PlanningView.ALL || showPhases) {
                    Text(
                        "${prep.start} → ${prep.end} · ${race?.name ?: prep.name} · ${if (days >= 0) "J−$days" else "J+${-days}"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val total =
                        ChronoUnit.DAYS.between(prep.start.asDate(), prep.end.asDate())
                            .coerceAtLeast(1)
                    val progress =
                        ChronoUnit.DAYS.between(prep.start.asDate(), LocalDate.now()).toFloat() /
                            total
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                } else
                    Text(
                        "${race?.name ?: prep.name} · ${if (days >= 0) "J−$days" else "J+${-days}"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                SectionTitle(
                    if (view == PlanningView.ALL) "Périodes & sous-objectifs" else "Sous-objectifs"
                ) {
                    if (view != PlanningView.ALL)
                        TextButton(onClick = { showPhases = !showPhases }) {
                            Text(if (showPhases) "Réduire" else "Voir")
                        }
                    TextButton(
                        onClick = {
                            editPhase =
                                PreparationSubGoal(
                                    preparationId = prep.id,
                                    name = "",
                                    start = dateRaw,
                                    end = date.plusWeeks(2).minusDays(1).toString(),
                                    color = phases.size % phaseColors.size,
                                )
                        }
                    ) {
                        Text("Ajouter")
                    }
                }
                if (view == PlanningView.ALL || showPhases) {
                    if (phases.isEmpty())
                        Text(
                            "Ajoutez vos phases de travail. Leurs périodes peuvent se chevaucher.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    else SubGoalTimeline(prep, phases) { editPhase = it }
                }
            }
        } else if (view == PlanningView.ALL)
            item {
                Text(
                    "Choisissez une préparation pour voir sa frise et toutes ses semaines.",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        when (view) {
            PlanningView.MONTH ->
                item {
                    MonthGrid(
                        date,
                        sessions,
                        date,
                        { dateRaw = it.toString() },
                        { adding = it.toString() },
                    )
                }
            PlanningView.WEEK ->
                item {
                    WeekVolume(
                        sessions.filter {
                            it.planningDate in date.monday()..date.monday().plusDays(6)
                        }
                    )
                    Text(
                        "Appui long sur une séance, puis glissez vers un autre jour. Les flèches et le menu Déplacer restent disponibles.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        Modifier.horizontalScroll(weekScroll),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        repeat(7) { offset ->
                            val day = date.monday().plusDays(offset.toLong())
                            Box(
                                Modifier.width(190.dp)
                                    .heightIn(min = 300.dp)
                                    .onGloballyPositioned { dayBounds[day] = it.boundsInRoot() }
                                    .zIndex(
                                        if (
                                            sessions.any {
                                                it.id == dragging && it.date == day.toString()
                                            }
                                        )
                                            1f
                                        else 0f
                                    )
                                    .background(
                                        if (hover == day) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainer,
                                        RoundedCornerShape(16.dp),
                                    )
                            ) {
                                Column(
                                    Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    TextButton(onClick = { dateRaw = day.toString() }) {
                                        Text(dateLabel(day.toString()))
                                    }
                                    sessions
                                        .filter { it.date == day.toString() }
                                        .forEach { w ->
                                            key(w.id) {
                                                var translation by remember {
                                                    mutableStateOf(Offset.Zero)
                                                }
                                                var origin by remember {
                                                    mutableStateOf(Offset.Zero)
                                                }
                                                var bounds by remember { mutableStateOf(Rect.Zero) }
                                                val movable =
                                                    w.status in
                                                        listOf(
                                                            Status.PLANNED,
                                                            Status.POSTPONED,
                                                            Status.CANCELLED,
                                                        )
                                                Box(
                                                    Modifier.onGloballyPositioned {
                                                            bounds = it.boundsInRoot()
                                                        }
                                                        .offset {
                                                            IntOffset(
                                                                translation.x.roundToInt(),
                                                                translation.y.roundToInt(),
                                                            )
                                                        }
                                                        .pointerInput(w.id, movable) {
                                                            if (movable)
                                                                detectDragGesturesAfterLongPress(
                                                                    onDragStart = { position ->
                                                                        dragging = w.id
                                                                        origin =
                                                                            bounds.topLeft +
                                                                                position
                                                                        translation = Offset.Zero
                                                                    },
                                                                    onDrag = { change, amount ->
                                                                        change.consume()
                                                                        translation += amount
                                                                        hover =
                                                                            dayBounds.entries
                                                                                .firstOrNull {
                                                                                    it.key
                                                                                        .monday() ==
                                                                                        day
                                                                                            .monday() &&
                                                                                        it.value
                                                                                            .contains(
                                                                                                origin +
                                                                                                    translation
                                                                                            )
                                                                                }
                                                                                ?.key
                                                                    },
                                                                    onDragCancel = {
                                                                        dragging = null
                                                                        translation = Offset.Zero
                                                                        hover = null
                                                                    },
                                                                    onDragEnd = {
                                                                        dragging = null
                                                                        val destination = hover
                                                                        translation = Offset.Zero
                                                                        hover = null
                                                                        if (
                                                                            destination != null &&
                                                                                destination != day
                                                                        )
                                                                            moveWorkout(
                                                                                w.id,
                                                                                destination,
                                                                            )
                                                                    },
                                                                )
                                                        }
                                                ) {
                                                    PlanningWorkout(
                                                        vm,
                                                        state,
                                                        w,
                                                        open,
                                                        edit,
                                                        compact = true,
                                                    )
                                                }
                                            }
                                        }
                                    OutlinedButton(
                                        onClick = { adding = day.toString() },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("+ Séance")
                                    }
                                }
                            }
                        }
                    }
                }
            PlanningView.ALL -> {
                val start =
                    (prep?.start?.asDate() ?: sessions.minOfOrNull { it.date.asDate() } ?: date)
                        .monday()
                val end =
                    (prep?.end?.asDate() ?: sessions.maxOfOrNull { it.date.asDate() } ?: date)
                        .monday()
                val count = (ChronoUnit.WEEKS.between(start, end).toInt() + 1).coerceIn(1, 1040)
                items(count, key = { "week-$it" }) { index ->
                    val monday = start.plusWeeks(index.toLong())
                    val list = sessions.filter { it.date.asDate() in monday..monday.plusDays(6) }
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "S${index + 1} · ${dateLabel(monday.toString())}",
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                TextButton(
                                    onClick = {
                                        dateRaw =
                                            maxOf(monday, prep?.start?.asDate() ?: monday)
                                                .toString()
                                        viewRaw = PlanningView.WEEK.name
                                    }
                                ) {
                                    Text("Ouvrir")
                                }
                            }
                            WeekVolume(
                                sessions.filter { it.planningDate in monday..monday.plusDays(6) }
                            )
                            phases
                                .filter {
                                    it.start.asDate() <= monday.plusDays(6) &&
                                        it.end.asDate() >= monday
                                }
                                .forEach {
                                    Text(
                                        "• ${it.name}",
                                        color = phaseColors[it.color.mod(phaseColors.size)],
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            list.forEach { w -> PlanningWorkout(vm, state, w, open, edit) }
                            TextButton(
                                onClick = {
                                    adding =
                                        maxOf(monday, prep?.start?.asDate() ?: monday).toString()
                                }
                            ) {
                                Text("+ Séance dans cette semaine")
                            }
                        }
                    }
                }
                val outside =
                    if (prep == null) emptyList()
                    else
                        sessions.filter {
                            it.date.asDate() < start || it.date.asDate() > end.plusDays(6)
                        }
                if (outside.isNotEmpty()) item { SectionTitle("Séances associées hors période") }
                items(outside, key = { it.id }) { PlanningWorkout(vm, state, it, open, edit) }
            }
            PlanningView.DAY -> Unit
        }
        if (view == PlanningView.DAY || view == PlanningView.MONTH) {
            item {
                SectionTitle(dateLabel(dateRaw)) {
                    TextButton(onClick = { adding = dateRaw }) { Text("+ Séance") }
                }
                phases
                    .filter { date in it.start.asDate()..it.end.asDate() }
                    .forEach {
                        Text("• ${it.name}", color = phaseColors[it.color.mod(phaseColors.size)])
                    }
                WeekVolume(sessions.filter { it.planningDate.toString() == dateRaw })
            }
            val daySessions = sessions.filter { it.date == dateRaw }
            if (daySessions.isEmpty())
                item {
                    EmptyState("Journée libre", "Ajoutez une séance ou un modèle depuis ce jour.")
                }
            items(daySessions, key = { it.id }) { PlanningWorkout(vm, state, it, open, edit) }
        }
    }
    adding?.let { selected ->
        FormDialog(
            "Séance du ${dateLabel(selected)}",
            { adding = null },
            {
                adding = null
                create(selected, prepId, "")
            },
            "Nouvelle séance",
        ) {
            Text("Réutiliser un modèle")
            state.templates.forEach { template ->
                OutlinedButton(
                    onClick = {
                        adding = null
                        create(selected, prepId, template.id)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("${template.name} · ${template.sport.label}")
                }
            }
            if (state.templates.isEmpty())
                Text("Les séances enregistrées comme modèles apparaîtront ici.")
        }
    }
    if (copyWeek)
        CopyPlanningWeekDialog(vm, date, prepId.takeIf { it.isNotBlank() }) { copyWeek = false }
    editPhase?.let { phase ->
        SubGoalDialog(vm, phase, state.subGoals.any { it.id == phase.id }) { editPhase = null }
    }
    if (export && prep != null) ExportDialog(vm, state, prep.id) { export = false }
}

@Composable
private fun WeekVolume(workouts: List<PlannedWorkout>) {
    val planned = workouts.filter { it.hasPlannedContent && it.status != Status.CANCELLED }
    val km =
        planned
            .filter { it.sport in listOf(Sport.RUN, Sport.RACE) }
            .sumOf { SportMath.plannedMeters(parsePlan(it.planJson).running) } / 1000
    val seconds = planned.sumOf { SportMath.plannedSeconds(parsePlan(it.planJson).running) }
    Text(
        "${planned.size} séances · ${km.pretty()} km course prévus · ${SportMath.durationLabel(seconds.toLong())} connues",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun MonthGrid(
    date: LocalDate,
    workouts: List<PlannedWorkout>,
    selected: LocalDate,
    select: (LocalDate) -> Unit,
    add: (LocalDate) -> Unit,
) {
    val first = date.withDayOfMonth(1).monday()
    val last = date.withDayOfMonth(date.lengthOfMonth())
    val weeks = ChronoUnit.WEEKS.between(first, last.monday()).toInt() + 1
    Row(Modifier.fillMaxWidth()) {
        listOf("L", "M", "M", "J", "V", "S", "D").forEach {
            Text(it, Modifier.weight(1f).padding(8.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
    repeat(weeks) { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(7) { col ->
                val day = first.plusDays((row * 7 + col).toLong())
                val list = workouts.filter { it.date == day.toString() }
                Surface(
                    Modifier.weight(1f)
                        .height(76.dp)
                        .combinedClickable(onClick = { select(day) }, onLongClick = { add(day) }),
                    shape = RoundedCornerShape(8.dp),
                    color =
                        if (day == selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(Modifier.padding(5.dp)) {
                        Text(
                            day.dayOfMonth.toString(),
                            color =
                                if (day.month == date.month) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        list.take(3).forEach { w ->
                            Box(
                                Modifier.fillMaxWidth()
                                    .padding(top = 3.dp)
                                    .height(3.dp)
                                    .background(w.sport.color)
                            )
                        }
                        if (list.isNotEmpty())
                            Text("${list.size} s.", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Spacer(Modifier.height(3.dp))
    }
    Text(
        "Touchez un jour pour le détail · appui long pour ajouter une séance.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
fun SubGoalTimeline(
    prep: TrainingPreparation,
    goals: List<PreparationSubGoal>,
    edit: (PreparationSubGoal) -> Unit,
) {
    val start =
        minOf(prep.start.asDate(), goals.minOfOrNull { it.start.asDate() } ?: prep.start.asDate())
    val end = maxOf(prep.end.asDate(), goals.maxOfOrNull { it.end.asDate() } ?: prep.end.asDate())
    val days = ChronoUnit.DAYS.between(start, end).toInt() + 1
    val width = maxOf(320, days * 5).coerceAtMost(8000).dp
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(start.toString(), style = MaterialTheme.typography.labelSmall)
                Text(end.toString(), style = MaterialTheme.typography.labelSmall)
            }
            goals.forEach { goal ->
                val before = ChronoUnit.DAYS.between(start, goal.start.asDate()).toInt()
                val length =
                    ChronoUnit.DAYS.between(goal.start.asDate(), goal.end.asDate()).toInt() + 1
                Column(Modifier.fillMaxWidth().clickable { edit(goal) }) {
                    Text(
                        "${goal.name} · ${goal.start} → ${goal.end}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        Modifier.fillMaxWidth()
                            .height(14.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                RoundedCornerShape(7.dp),
                            )
                    ) {
                        if (before > 0) Spacer(Modifier.weight(before.toFloat()))
                        Box(
                            Modifier.weight(length.toFloat())
                                .fillMaxHeight()
                                .background(
                                    phaseColors[goal.color.mod(phaseColors.size)],
                                    RoundedCornerShape(7.dp),
                                )
                        )
                        val after = days - before - length
                        if (after > 0) Spacer(Modifier.weight(after.toFloat()))
                    }
                }
            }
        }
    }
    Text(
        "Chaque ligne représente une période. Touchez-la pour ajuster ses dates.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun SubGoalDialog(
    vm: CoachViewModel,
    original: PreparationSubGoal,
    existing: Boolean,
    dismiss: () -> Unit,
) {
    var name by rememberSaveable(original.id) { mutableStateOf(original.name) }
    var start by rememberSaveable(original.id) { mutableStateOf(original.start) }
    var end by rememberSaveable(original.id) { mutableStateOf(original.end) }
    var color by rememberSaveable(original.id) { mutableIntStateOf(original.color) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    FormDialog(
        if (existing) "Modifier le sous-objectif" else "Créer un sous-objectif",
        dismiss,
        {
            if (!busy)
                try {
                    require(name.isNotBlank()) { "Donnez un nom au sous-objectif." }
                    require(start.asDate() <= end.asDate()) { "La fin précède le début." }
                    busy = true
                    vm.action {
                        try {
                            vm.repo.saveSubGoal(
                                original.copy(name = name, start = start, end = end, color = color)
                            )
                            withContext(Dispatchers.Main) { dismiss() }
                        } finally {
                            busy = false
                        }
                    }
                } catch (e: Exception) {
                    error = e.localizedMessage
                }
        },
    ) {
        Field("Nom du sous-objectif", name, { name = it })
        DateField("Début", start) { start = it }
        DateField("Fin", end) { end = it }
        Choice(
            "Couleur",
            color,
            phaseColors.indices.toList(),
            text = { listOf("Vert", "Violet", "Orange", "Turquoise", "Rose")[it] },
            change = { color = it },
        )
        Text(
            "Les périodes peuvent se chevaucher. Les séances associées restent liées lorsque vous ajustez les dates."
        )
        if (existing)
            TextButton(
                onClick = {
                    vm.action {
                        vm.repo.dao.deleteSubGoal(original.id)
                        dismiss()
                    }
                }
            ) {
                Text("Supprimer le sous-objectif", color = MaterialTheme.colorScheme.error)
            }
        ErrorText(error)
    }
}

@Composable
private fun CopyPlanningWeekDialog(
    vm: CoachViewModel,
    source: LocalDate,
    prepId: String?,
    dismiss: () -> Unit,
) {
    var target by rememberSaveable { mutableStateOf(source.monday().plusWeeks(1).toString()) }
    var repeats by rememberSaveable { mutableStateOf("1") }
    var error by remember { mutableStateOf<String?>(null) }
    FormDialog(
        "Dupliquer la semaine",
        dismiss,
        {
            try {
                val count = requireNotNull(repeats.toIntOrNull()) { "Nombre de semaines invalide." }
                require(count in 1..104) { "Choisissez de 1 à 104 semaines." }
                require(target.asDate().monday() != source.monday()) {
                    "Choisissez une autre semaine."
                }
                vm.action {
                    vm.repo.copyWeek(source, target.asDate(), count, prepId)
                    withContext(Dispatchers.Main) { dismiss() }
                }
            } catch (e: Exception) {
                error = e.localizedMessage
            }
        },
        "Dupliquer",
    ) {
        Text(
            "Source : ${dateLabel(source.monday().toString())}. ${if (prepId == null) "Toutes les séances." else "Séances de cette préparation."}"
        )
        DateField("Semaine de destination", target) { target = it }
        Field("Nombre de semaines consécutives", repeats, { repeats = it }, true)
        Text(
            "Les blocs et sous-objectifs sont conservés. Chaque copie possède ses propres identifiants et aucun résultat."
        )
        ErrorText(error)
    }
}

@Composable
private fun PlanningWorkout(
    vm: CoachViewModel,
    state: CoachState,
    workout: PlannedWorkout,
    open: (String) -> Unit,
    edit: (String) -> Unit,
    compact: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<String?>(null) }
    val movable = workout.status !in listOf(Status.ACTIVE, Status.DONE, Status.PARTIAL)
    Column {
        if (compact)
            Card(onClick = { open(workout.id) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text(workout.name, style = MaterialTheme.typography.titleSmall)
                    Text(workout.sport.label, style = MaterialTheme.typography.labelSmall)
                    parsePlan(workout.planJson).running.forEach {
                        Text(
                            blockSummary(it),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        else WorkoutCard(workout) { open(workout.id) }
        val ids = state.workoutSubGoals.filter { it.workoutId == workout.id }.map { it.subGoalId }
        if (ids.isNotEmpty())
            Text(
                state.subGoals.filter { it.id in ids }.joinToString(" · ") { it.name },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (movable) TextButton(onClick = { edit(workout.id) }) { Text("Modifier") }
            Box {
                TextButton(onClick = { menu = true }) {
                    Text("Actions")
                    Icon(Icons.Default.ExpandMore, null)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Dupliquer") },
                        onClick = {
                            menu = false
                            operation = "Dupliquer"
                        },
                    )
                    if (movable)
                        DropdownMenuItem(
                            text = { Text("Déplacer") },
                            onClick = {
                                menu = false
                                operation = "Déplacer"
                            },
                        )
                    if (workout.preparationId != null)
                        DropdownMenuItem(
                            text = { Text("Sous-objectifs") },
                            onClick = {
                                menu = false
                                operation = "Sous-objectifs"
                            },
                        )
                    DropdownMenuItem(
                        text = { Text("Enregistrer comme modèle") },
                        onClick = {
                            menu = false
                            vm.action {
                                vm.repo.saveTemplate(workout)
                                vm.notice("Modèle enregistré")
                            }
                        },
                    )
                    if (movable)
                        DropdownMenuItem(
                            text = { Text("Supprimer") },
                            onClick = {
                                menu = false
                                operation = "Supprimer"
                            },
                        )
                }
            }
        }
    }
    operation
        ?.takeIf { it == "Sous-objectifs" }
        ?.let {
            var selected by
                remember(workout.id) {
                    mutableStateOf(
                        state.workoutSubGoals
                            .filter { it.workoutId == workout.id }
                            .map { it.subGoalId }
                            .toSet()
                    )
                }
            FormDialog(
                "Sous-objectifs de la séance",
                { operation = null },
                {
                    vm.action {
                        vm.repo.assignSubGoals(workout.id, selected)
                        operation = null
                    }
                },
            ) {
                state.subGoals
                    .filter { it.preparationId == workout.preparationId }
                    .forEach { goal ->
                        CheckLine(goal.name, goal.id in selected) { checked ->
                            selected = if (checked) selected + goal.id else selected - goal.id
                        }
                    }
            }
        }
    operation
        ?.takeIf { it != "Sous-objectifs" }
        ?.let { action ->
            var target by
                rememberSaveable(workout.id, action) {
                    mutableStateOf(
                        workout.date
                            .asDate()
                            .plusDays(if (action == "Dupliquer") 7 else 0)
                            .toString()
                    )
                }
            FormDialog(
                "$action la séance",
                { operation = null },
                {
                    vm.action {
                        when (action) {
                            "Dupliquer" -> vm.repo.duplicate(workout.id, target.asDate())
                            "Déplacer" -> vm.repo.move(workout.id, target.asDate())
                            "Supprimer" -> vm.repo.delete(workout.id)
                        }
                        withContext(Dispatchers.Main) { operation = null }
                    }
                },
                action,
            ) {
                Text(workout.name)
                if (action != "Supprimer") DateField("Date de destination", target) { target = it }
            }
        }
}
