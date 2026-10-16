package fr.hybridcoach.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

val Lime = Color(0xFFB5F36D)
val Sport.color: Color
    get() =
        when (this) {
            Sport.RUN -> Color(0xFFB5F36D)
            Sport.STRENGTH -> Color(0xFFBBA6FF)
            Sport.RACE -> Color(0xFFFFC77A)
            Sport.REST -> Color(0xFF99AEA4)
            Sport.OTHER -> Color(0xFF8BDDDD)
        }

@Composable
fun HybridTheme(theme: String, content: @Composable () -> Unit) {
    val dark = if (theme == "system") isSystemInDarkTheme() else theme != "light"
    val colors =
        if (dark)
            darkColorScheme(
                primary = Lime,
                primaryContainer = Color(0xFF304723),
                onPrimaryContainer = Lime,
                surfaceContainerLowest = Color(0xFF0B110D),
                surfaceContainerLow = Color(0xFF17211B),
                surfaceContainer = Color(0xFF1C2820),
                surfaceContainerHigh = Color(0xFF223027),
                surfaceContainerHighest = Color(0xFF2A392E),
                outlineVariant = Color(0xFF3A4B3F),
                onSurfaceVariant = Color(0xFFB8C8BA),
                onPrimary = Color(0xFF203212),
                background = Color(0xFF101713),
                surface = Color(0xFF17211B),
                surfaceVariant = Color(0xFF243128),
                secondary = Color(0xFFBBA6FF),
            )
        else
            lightColorScheme(
                primary = Color(0xFF426B20),
                background = Color(0xFFF5F8F2),
                surface = Color.White,
                secondary = Color(0xFF675099),
            )
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}

fun dateLabel(date: String) =
    date.asDate().format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH))

@Composable
fun PageTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        subtitle?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
fun SectionTitle(title: String, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        action?.invoke()
    }
}

@Composable
fun EmptyState(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Spa, null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action?.invoke()
        }
    }
}

@Composable
fun WorkoutCard(w: PlannedWorkout, click: () -> Unit) {
    Card(
        onClick = click,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(4.dp)
                    .height(48.dp)
                    .background(w.sport.color, RoundedCornerShape(4.dp))
            )
            Column(
                Modifier.weight(1f).padding(start = 14.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    "${w.sport.label.uppercase(Locale.FRENCH)} · ${dateLabel(w.date)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(w.name, style = MaterialTheme.typography.titleMedium)
                val steps = remember(w.planJson) { parsePlan(w.planJson).running }
                if (steps.isNotEmpty())
                    Text(
                        steps.joinToString(" · ") { blockSummary(it) },
                        maxLines = 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                Text(
                    w.status.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.ChevronRight, "Ouvrir la séance")
        }
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    change: (String) -> Unit,
    numeric: Boolean = false,
    modifier: Modifier = Modifier,
    lines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = change,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = lines == 1,
        minLines = lines,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text
            ),
        shape = RoundedCornerShape(12.dp),
    )
}

@Composable
fun <T> Choice(
    label: String,
    value: T,
    values: List<T>,
    text: (T) -> String = { it.toString() },
    change: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label : ${text(value)}", Modifier.weight(1f))
            Icon(Icons.Default.ExpandMore, null)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = 350.dp),
        ) {
            values.forEach { item ->
                DropdownMenuItem(
                    text = { Text(text(item)) },
                    onClick = {
                        change(item)
                        open = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, value: String, change: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.CalendarMonth, null)
        Spacer(Modifier.width(8.dp))
        Text("$label · ${value.asDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}")
    }
    if (show) {
        val dateState =
            rememberDatePickerState(
                initialSelectedDateMillis =
                    value.asDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            )
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        dateState.selectedDateMillis?.let {
                            change(
                                Instant.ofEpochMilli(it)
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDate()
                                    .toString()
                            )
                        }
                        show = false
                    }
                ) {
                    Text("Choisir")
                }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Annuler") } },
        ) {
            DatePicker(state = dateState, showModeToggle = true)
        }
    }
}

@Composable
fun CheckLine(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { change(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = change)
        Text(label, Modifier.weight(1f))
    }
}

@Composable
fun FormDialog(
    title: String,
    dismiss: () -> Unit,
    confirm: () -> Unit,
    confirmText: String = "Enregistrer",
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = dismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(.92f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Column(
                    Modifier.fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismiss) { Text("Annuler") }
                    TextButton(onClick = confirm) { Text(confirmText) }
                }
            }
        }
    }
}

@Composable
fun ErrorText(error: String?) {
    error?.let {
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

fun Double.pretty() = "%.1f".format(Locale.FRENCH, this)
