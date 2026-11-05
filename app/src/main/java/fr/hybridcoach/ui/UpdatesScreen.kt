package fr.hybridcoach.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.hybridcoach.BuildConfig
import fr.hybridcoach.updates.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun UpdatesScreen(vm: UpdatesViewModel, backup: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var waitingPermission by rememberSaveable { mutableStateOf(false) }
    val installer =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // The installer can close without an update. Never announce a successful installation
            // here.
        }
    val launchInstall: (File) -> Unit = { file ->
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            installer.launch(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newRawUri("Mise à jour Hybrid Coach", uri)
                }
            )
        } catch (_: Exception) {
            vm.error("Impossible d’ouvrir l’installateur Android sur ce téléphone.")
        }
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (waitingPermission) {
                waitingPermission = false
                if (context.packageManager.canRequestPackageInstalls()) vm.install(launchInstall)
                else
                    vm.error(
                        "Installation non autorisée. Vous pouvez réessayer ou télécharger l’APK depuis GitHub."
                    )
            }
        }
    val release = state.release
    val newer = release != null && release.manifest.versionCode > BuildConfig.VERSION_CODE
    val compatible = release == null || Build.VERSION.SDK_INT >= release.manifest.minSdk
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .testTag("updates-screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageTitle("À propos et mises à jour")
        Text(
            "Hybrid Coach ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            "Version installée · code ${BuildConfig.VERSION_CODE}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            when (state.status) {
                UpdateStatus.IDLE -> "Vérifiez les versions publiées sur GitHub."
                UpdateStatus.CHECKING -> "Recherche sur GitHub…"
                UpdateStatus.EMPTY -> "Aucune version stable n’est encore publiée sur GitHub."
                UpdateStatus.CURRENT ->
                    "Vous disposez de la version la plus récente proposée sur GitHub."
                UpdateStatus.AVAILABLE -> "Une nouvelle version est disponible."
                UpdateStatus.DOWNLOADING -> "Téléchargement de l’APK…"
                UpdateStatus.READY -> "APK vérifié et prêt à installer."
                UpdateStatus.VERIFYING -> "Vérification avant installation…"
            }
        )
        Button(onClick = vm::check, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Vérifier les mises à jour")
        }
        if (state.busy) {
            if (state.status == UpdateStatus.DOWNLOADING && release != null) {
                LinearProgressIndicator(
                    progress = {
                        (state.bytes.toFloat() / release.manifest.apk.size).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${state.bytes / 1024} / ${release.manifest.apk.size / 1024} Ko")
            } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            TextButton(onClick = vm::cancel) { Text("Annuler l’opération") }
        }
        ErrorText(state.error)
        if (newer) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Version ${release.manifest.versionName}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Publiée le ${Instant.parse(release.publishedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRENCH))} · ${(release.manifest.apk.size / 1024.0 / 1024).pretty()} Mo"
                    )
                    if (!compatible)
                        Text(
                            "Cette version nécessite Android API ${release.manifest.minSdk} ou plus récent.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    Disclosure("Nouveautés", initiallyExpanded = true) {
                        Text(release.manifest.notes)
                    }
                    if (state.status == UpdateStatus.READY) {
                        Button(
                            onClick = {
                                if (context.packageManager.canRequestPackageInstalls())
                                    vm.install(launchInstall)
                                else {
                                    try {
                                        waitingPermission = true
                                        permission.launch(
                                            Intent(
                                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                Uri.parse("package:${context.packageName}"),
                                            )
                                        )
                                    } catch (_: Exception) {
                                        waitingPermission = false
                                        vm.error(
                                            "Impossible d’ouvrir l’autorisation d’installation Android."
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Installer la mise à jour")
                        }
                        Text(
                            "Android peut demander d’autoriser Hybrid Coach à installer cette mise à jour. Vous confirmez ensuite dans l’installateur.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else
                        Button(
                            onClick = vm::download,
                            enabled = !state.busy && compatible,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Télécharger la mise à jour")
                        }
                }
            }
        }
        TextButton(
            onClick = {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(release?.releaseUrl ?: RELEASES_URL))
                    )
                } catch (_: Exception) {
                    vm.error("Aucun navigateur disponible pour ouvrir GitHub.")
                }
            }
        ) {
            Text("Voir les publications GitHub")
        }
        Disclosure("Données et installation") {
            Text(
                "La vérification contacte GitHub sans envoyer votre carnet ni votre clé Garmin. Le téléchargement contrôle l’empreinte, l’identité et la signature de l’APK. Une mise à jour compatible conserve les données locales."
            )
            TextButton(onClick = backup) { Text("Sauvegarder mon carnet") }
        }
    }
}
