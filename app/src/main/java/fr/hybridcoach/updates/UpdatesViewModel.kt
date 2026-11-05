package fr.hybridcoach.updates

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.hybridcoach.BuildConfig
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class UpdateStatus {
    IDLE,
    CHECKING,
    EMPTY,
    CURRENT,
    AVAILABLE,
    DOWNLOADING,
    READY,
    VERIFYING,
}

data class UpdateState(
    val status: UpdateStatus = UpdateStatus.IDLE,
    val release: AvailableUpdate? = null,
    val downloaded: File? = null,
    val bytes: Long = 0,
    val error: String? = null,
) {
    val busy
        get() =
            status in
                listOf(UpdateStatus.CHECKING, UpdateStatus.DOWNLOADING, UpdateStatus.VERIFYING)
}

class UpdatesViewModel
@JvmOverloads
constructor(
    application: Application,
    private val gateway: UpdateGateway = AndroidUpdateGateway(application),
) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(UpdateState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun check() {
        if (mutableState.value.busy) return
        mutableState.value = UpdateState(status = UpdateStatus.CHECKING)
        job =
            viewModelScope.launch {
                try {
                    val release = gateway.latest()
                    mutableState.value =
                        UpdateState(
                            status =
                                when {
                                    release == null -> UpdateStatus.EMPTY
                                    release.manifest.versionCode <= BuildConfig.VERSION_CODE ->
                                        UpdateStatus.CURRENT
                                    else -> UpdateStatus.AVAILABLE
                                },
                            release = release,
                        )
                } catch (e: CancellationException) {
                    mutableState.value = UpdateState()
                    throw e
                } catch (e: Exception) {
                    mutableState.value = UpdateState(error = message(e))
                }
            }
    }

    fun download() {
        val previous = mutableState.value
        val release = previous.release ?: return
        if (previous.busy || release.manifest.versionCode <= BuildConfig.VERSION_CODE) return
        if (Build.VERSION.SDK_INT < release.manifest.minSdk) {
            mutableState.value =
                previous.copy(
                    error =
                        "Une version Android plus récente est nécessaire pour cette mise à jour."
                )
            return
        }
        mutableState.value =
            previous.copy(
                status = UpdateStatus.DOWNLOADING,
                bytes = 0,
                error = null,
                downloaded = null,
            )
        job =
            viewModelScope.launch {
                try {
                    val file =
                        gateway.download(release) { bytes ->
                            mutableState.value = mutableState.value.copy(bytes = bytes)
                        }
                    mutableState.value =
                        mutableState.value.copy(status = UpdateStatus.READY, downloaded = file)
                } catch (e: CancellationException) {
                    mutableState.value = previous.copy(error = null)
                    throw e
                } catch (e: Exception) {
                    mutableState.value =
                        previous.copy(
                            status = UpdateStatus.AVAILABLE,
                            downloaded = null,
                            error = message(e),
                        )
                }
            }
    }

    fun install(ready: (File) -> Unit) {
        val previous = mutableState.value
        val release = previous.release ?: return
        val file = previous.downloaded ?: return
        if (previous.busy) return
        mutableState.value = previous.copy(status = UpdateStatus.VERIFYING, error = null)
        job =
            viewModelScope.launch {
                try {
                    gateway.verify(file, release)
                    mutableState.value = previous.copy(error = null)
                    ready(file)
                } catch (e: CancellationException) {
                    mutableState.value = previous
                    throw e
                } catch (e: Exception) {
                    file.delete()
                    mutableState.value =
                        previous.copy(
                            status = UpdateStatus.AVAILABLE,
                            downloaded = null,
                            error = message(e),
                        )
                }
            }
    }

    fun cancel() {
        job?.cancel()
    }

    fun error(message: String) {
        mutableState.value = mutableState.value.copy(error = message)
    }

    private fun message(e: Exception) =
        when (e) {
            is java.io.IOException ->
                "Connexion à GitHub impossible. Vérifiez votre accès Internet puis réessayez."
            is kotlinx.serialization.SerializationException ->
                "La publication GitHub contient des informations illisibles."
            else -> e.localizedMessage ?: "La vérification a échoué. Réessayez."
        }
}
