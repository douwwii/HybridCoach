package fr.hybridcoach.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.hybridcoach.data.CoachState
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
fun BackupScreen(vm: CoachViewModel, state: CoachState) {
    val busy by vm.backupBusy.collectAsStateWithLifecycle()
    val error by vm.backupError.collectAsStateWithLifecycle()
    val message by vm.backupMessage.collectAsStateWithLifecycle()
    val preview by vm.backupPreview.collectAsStateWithLifecycle()
    val hasRecovery by vm.backupHasRecovery.collectAsStateWithLifecycle()
    var exportPrevious by rememberSaveable { mutableStateOf(false) }
    var confirmed by remember(preview) { mutableStateOf(false) }
    BackHandler(enabled = busy) { /* Keep the current operation visible until it finishes. */ }
    val export =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null) vm.exportBackup(uri, exportPrevious)
        }
    val restore =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.previewBackup(uri)
        }
    fun selectExport(previous: Boolean) {
        exportPrevious = previous
        val suffix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        try {
            export.launch(
                "HybridCoach_${if (previous) "carnet_precedent" else "sauvegarde"}_$suffix.json"
            )
        } catch (_: ActivityNotFoundException) {
            vm.backupError.value = "Aucun sélecteur de fichiers disponible sur ce téléphone."
        }
    }
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .testTag("backup-screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PageTitle(
            "Votre carnet vous suit",
            "Sauvegardez vos données pour les retrouver sur un autre téléphone.",
        )
        Text(
            "${state.workouts.size} séances · ${state.preparations.size} préparations · ${state.templates.size} modèles"
        )
        Text(
            "Le JSON contient les séances, résultats, notes, modèles, exercices, objectifs, périodes, associations Garmin, profil sportif et apparence."
        )
        Text(
            "Il contient vos données personnelles en clair. Les clés et identifiants de connexion ne sont pas transférés ; reconnectez Garmin sur le nouveau téléphone.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        ErrorText(error)
        message?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("backup-message"),
            )
        }
        Button(
            onClick = { selectExport(false) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Exporter ma sauvegarde JSON")
        }
        OutlinedButton(
            onClick = {
                try {
                    restore.launch(arrayOf("*/*"))
                } catch (_: ActivityNotFoundException) {
                    vm.backupError.value =
                        "Aucun sélecteur de fichiers disponible sur ce téléphone."
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Restaurer un fichier JSON")
        }
        preview?.let { document ->
            val d = document.data
            Card(Modifier.fillMaxWidth().testTag("backup-preview")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle("Vérifier avant de restaurer")
                    val date =
                        Instant.parse(document.exportedAt)
                            .atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("dd/MM/yyyy à HH:mm"))
                    Text("Sauvegarde du $date · Hybrid Coach ${document.appVersion}")
                    Text(
                        "${d.workouts.size} séances · ${d.results.size} résultats · ${d.preparations.size} préparations"
                    )
                    Text(
                        "${d.templates.size} modèles · ${d.exercises.count { it.custom }} exercices personnels · ${d.subGoals.size} sous-objectifs"
                    )
                    Text(
                        "Profil sportif et apparence inclus. ${d.garminActivities.size} activités Garmin conservées."
                    )
                    Text(
                        "La restauration remplace entièrement le carnet et le profil de ce téléphone. Elle ne fusionne pas les données. Une copie du carnet précédent sera conservée dans l’application avant le remplacement."
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Checkbox(
                            confirmed,
                            { confirmed = it },
                            enabled = !busy,
                            modifier = Modifier.testTag("confirm-replace"),
                        )
                        Text(
                            "Je souhaite remplacer le carnet de ce téléphone.",
                            Modifier.weight(1f),
                        )
                    }
                    Button(
                        onClick = vm::restoreBackup,
                        enabled = !busy && confirmed,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Remplacer et restaurer")
                    }
                    TextButton(onClick = vm::dismissBackupPreview, enabled = !busy) {
                        Text("Annuler la restauration")
                    }
                }
            }
        }
        if (hasRecovery) {
            SectionTitle("Carnet avant la dernière restauration")
            Text(
                "Cette copie reste sur ce téléphone. Exportez-la puis sélectionnez le JSON obtenu pour revenir au carnet précédent.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { selectExport(true) }, enabled = !busy) {
                Text("Exporter le carnet précédent")
            }
        }
        SectionTitle("Changer de téléphone")
        Text(
            "1. Exportez le JSON sur l’ancien téléphone.\n2. Transférez ce fichier vers le nouveau.\n3. Installez Hybrid Coach, puis ouvrez Profil sportif → Sauvegarde et transfert.\n4. Sélectionnez le JSON, vérifiez son contenu et confirmez la restauration."
        )
    }
}
