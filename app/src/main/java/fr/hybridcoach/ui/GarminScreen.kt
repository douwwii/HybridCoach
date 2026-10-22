package fr.hybridcoach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import fr.hybridcoach.garmin.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
private fun SecretField(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = change,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun GarminScreen(vm: CoachViewModel, state: CoachState, open: (String) -> Unit) {
    val connection by vm.garminConnection.collectAsStateWithLifecycle()
    val busy by vm.garminBusy.collectAsStateWithLifecycle()
    val error by vm.garminError.collectAsStateWithLifecycle()
    val configured by vm.garminConfigured.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    // The personal API key never enters saved-instance state.
    var apiKey by remember { mutableStateOf("") }
    var showIgnored by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var disconnect by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshGarmin() }
    val account = connection.accountId
    val activities =
        state.garminActivities.filter {
            !it.deleted && (account == null || it.accountId == account)
        }
    val pending = activities.filter { it.resolution == "PENDING" && it.workoutId == null }
    val sync =
        state.garminSync
            .filter { account == null || it.accountId == account }
            .maxByOrNull { it.syncedAt }
    LazyColumn(
        Modifier.fillMaxSize().imePadding().testTag("garmin-screen"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PageTitle(
                "Garmin Connect",
                "Retrouvez vos activités réalisées et comparez-les à votre programme.",
            )
            Text(
                when {
                    connection.connected -> "Connexion directe à Garmin active"
                    else -> "Compte non connecté"
                },
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Dernière synchronisation : " +
                    (sync?.let {
                        Instant.ofEpochMilli(it.syncedAt)
                            .atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                    } ?: "Jamais")
            )
            Text(
                "${activities.count { it.workoutId != null }} activités importées · ${pending.size} à confirmer"
            )
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            ErrorText(error)
            Row {
                TextButton(onClick = vm::refreshGarmin, enabled = !busy) {
                    Text("Actualiser l’état")
                }
                if (connection.connected)
                    TextButton(onClick = { disconnect = true }, enabled = !busy) {
                        Text("Déconnecter")
                    }
            }
            Button(
                onClick = vm::syncGarmin,
                enabled = !busy && connection.connected,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "Opération en cours…" else "Synchroniser Garmin")
            }
        }
        item {
            SectionTitle("Clé personnelle Garmin")
            Text(
                "Connectez votre compte directement depuis ce téléphone. Aucun serveur à installer. Créez une clé dans Garmin Connect, puis collez-la ci-dessous.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { uriHandler.openUri(GARMIN_PERSONAL_KEY_URL) }, enabled = !busy) {
                Text("Créer une clé dans Garmin Connect")
            }
            SecretField("Clé personnelle Garmin", apiKey) { apiKey = it }
            Button(
                onClick = {
                    vm.connectGarmin(apiKey)
                    apiKey = ""
                },
                enabled = !busy && apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (configured) "Remplacer la clé Garmin" else "Connecter Garmin")
            }
            Text(
                "Votre clé est chiffrée sur ce téléphone. Aucun mot de passe Garmin n’est demandé. À son expiration, générez une nouvelle clé puis remplacez-la ici. La synchronisation se lance avec le bouton ci-dessus.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            SectionTitle("Activités à examiner")
            Text(
                "Les activités incertaines ne sont pas comptabilisées tant que vous n’avez pas confirmé leur association ou choisi de les importer hors programme.",
                style = MaterialTheme.typography.bodySmall,
            )
            CheckLine("Afficher aussi les activités ignorées", showIgnored) { showIgnored = it }
        }
        val review =
            activities.filter {
                it.resolution == "PENDING" || showIgnored && it.resolution == "IGNORED"
            }
        if (review.isEmpty()) item { Text("Aucune activité en attente.") }
        items(review, key = { "review-${it.key}" }) { row ->
            val activity = codec.decodeFromString<GarminActivity>(row.payload)
            Card(onClick = { selected = row.key }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(activity.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${dateLabel(activity.date.toString())} · ${activity.sportKey} · ${(activity.distanceMeters?.div(1000) ?: 0.0).pretty()} km · ${SportMath.durationLabel(activity.durationSeconds.toLong())}"
                    )
                    Text(
                        if (row.resolution == "IGNORED") "Ignorée · toucher pour réexaminer"
                        else "Choisir une association"
                    )
                }
            }
        }
        item { SectionTitle("Activités importées") }
        items(activities.filter { it.workoutId != null }, key = { "linked-${it.key}" }) { row ->
            val activity = codec.decodeFromString<GarminActivity>(row.payload)
            Column {
                Text(activity.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${dateLabel(activity.date.toString())} · " +
                        if (row.originalWorkout == null) "Hors programme"
                        else "Associée au programme"
                )
                FlowRow {
                    TextButton(onClick = { open(row.workoutId!!) }) { Text("Détail") }
                    TextButton(onClick = { selected = row.key }) { Text("Association") }
                }
            }
        }
    }
    if (disconnect)
        FormDialog(
            "Déconnecter Garmin",
            { disconnect = false },
            {
                vm.disconnectGarmin()
                disconnect = false
            },
            "Déconnecter",
        ) {
            Text(
                "La clé et la connexion seront effacées de ce téléphone. Les activités déjà importées resteront dans votre calendrier. Pour révoquer la clé elle-même, supprimez-la également dans Garmin Connect."
            )
        }
    selected?.let { key -> GarminAssociationDialog(vm, state, key) { selected = null } }
}

@Composable
fun GarminAssociationDialog(
    vm: CoachViewModel,
    state: CoachState,
    key: String,
    dismiss: () -> Unit,
) {
    val row = state.garminActivities.find { it.key == key } ?: return
    val activity = codec.decodeFromString<GarminActivity>(row.payload)
    val occupied =
        state.garminActivities
            .filter { it.workoutId != row.workoutId }
            .mapNotNull { it.workoutId }
            .toSet()
    val candidates =
        GarminMatcher.match(activity, state.workouts, state.results, occupied).candidates
    var target by rememberSaveable(key) { mutableStateOf(row.workoutId ?: "") }
    var all by rememberSaveable(key) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val choices =
        if (all)
            state.workouts.filter {
                it.id !in occupied && it.status != Status.ACTIVE && it.source != "GARMIN"
            }
        else candidates.mapNotNull { c -> state.workouts.find { it.id == c.workoutId } }
    FormDialog(
        "Associer l’activité Garmin",
        dismiss,
        {
            if (!busy) {
                busy = true
                vm.action {
                    try {
                        GarminImporter(vm.repo.db).resolve(key, target.takeIf { it.isNotBlank() })
                        dismiss()
                    } finally {
                        busy = false
                    }
                }
            }
        },
        "Confirmer",
    ) {
        Text("${activity.name} · ${activity.date} · ${activity.sportKey}")
        Text(
            "Une association remplace les mesures réelles de la séance et conserve son programme initial. Une séance déjà terminée manuellement ne sera comptée qu’une fois."
        )
        Choice(
            "Séance",
            target,
            (listOf("") + choices.map { it.id } + listOfNotNull(row.workoutId)).distinct(),
            text = { id ->
                state.workouts.find { it.id == id }?.let { "${dateLabel(it.date)} · ${it.name}" }
                    ?: "Activité non planifiée"
            },
            change = { target = it },
        )
        CheckLine("Rechercher dans toutes les séances", all) { all = it }
        candidates.forEach { candidate ->
            state.workouts
                .find { it.id == candidate.workoutId }
                ?.let {
                    Text(
                        "${it.name} : ${candidate.reasons.joinToString(" · ")}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
        }
        if (row.workoutId != null)
            TextButton(
                onClick = {
                    vm.action {
                        GarminImporter(vm.repo.db).unlink(key)
                        dismiss()
                    }
                }
            ) {
                Text("Dissocier et réexaminer")
            }
        TextButton(
            onClick = {
                vm.action {
                    GarminImporter(vm.repo.db).unlink(key, ignore = true)
                    dismiss()
                }
            }
        ) {
            Text("Ignorer cette activité")
        }
        Text(
            "Ignorer ou dissocier restaure la date et les mesures locales antérieures de la séance planifiée. Aucune suppression n’est envoyée à Garmin.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
