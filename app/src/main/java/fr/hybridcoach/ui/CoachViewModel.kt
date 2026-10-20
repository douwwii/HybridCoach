package fr.hybridcoach.ui

import android.app.Application
import androidx.lifecycle.*
import fr.hybridcoach.*
import fr.hybridcoach.data.*
import fr.hybridcoach.domain.*
import fr.hybridcoach.garmin.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

class CoachViewModel(application: Application) : AndroidViewModel(application) {
    val repo = (application as CoachApplication).repository
    val preferences = AthletePreferences(application)
    private val garminSettings = GarminSecureSettings(application)
    val garminConnection = MutableStateFlow(GarminConnection())
    val garminBusy = MutableStateFlow(false)
    val garminError = MutableStateFlow<String?>(null)
    val garminUrl = MutableStateFlow("")

    private fun garminOperation(block: suspend () -> Unit) {
        if (garminBusy.value) return
        garminBusy.value = true
        garminError.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is GarminGatewayException && e.code == "AUTH_REQUIRED")
                    garminConnection.value = GarminConnection()
                garminError.value = e.localizedMessage ?: "Synchronisation Garmin impossible."
            } finally {
                garminBusy.value = false
            }
        }
    }

    private suspend fun garminGateway(): GarminGateway {
        val settings = withContext(Dispatchers.IO) { garminSettings.read() }
        return GarminBridgeClient(settings)
    }

    fun refreshGarmin() = garminOperation {
        val settings = withContext(Dispatchers.IO) { garminSettings.read() }
        garminUrl.value = settings.url
        if (settings.url.isNotBlank())
            garminConnection.value = GarminBridgeClient(settings).status()
    }

    fun saveGarminSettings(url: String, key: String) = garminOperation {
        val old =
            runCatching { withContext(Dispatchers.IO) { garminSettings.read() } }
                .getOrDefault(GarminBridgeSettings())
        val value = GarminBridgeSettings(url.trim().trimEnd('/'), key.trim().ifBlank { old.apiKey })
        withContext(Dispatchers.IO) { garminSettings.save(value) }
        garminUrl.value = value.url
        garminConnection.value = GarminConnection()
        garminConnection.value = GarminBridgeClient(value).status()
    }

    fun connectGarmin(email: String, password: String, isCn: Boolean) = garminOperation {
        require(email.isNotBlank() && password.isNotBlank()) {
            "Saisissez votre compte et votre mot de passe Garmin."
        }
        garminConnection.value = garminGateway().login(email.trim(), password, isCn)
    }

    fun garminMfa(code: String) = garminOperation {
        val challenge =
            requireNotNull(garminConnection.value.challengeId) { "Recommencez la connexion." }
        garminConnection.value = garminGateway().completeMfa(challenge, code.trim())
    }

    fun disconnectGarmin() = garminOperation {
        garminGateway().disconnect()
        garminConnection.value = GarminConnection()
    }

    fun syncGarmin() = garminOperation {
        val snapshot = garminGateway().fetchActivities()
        val current = garminConnection.value.accountId
        require(current == null || current == snapshot.accountId) {
            "Le compte du service a changé. Actualisez la connexion avant de synchroniser."
        }
        val changed = GarminImporter(repo.db).import(snapshot)
        garminConnection.value = GarminConnection(connected = true, accountId = snapshot.accountId)
        notice("Garmin : $changed activités nouvelles, modifiées ou supprimées")
    }

    val error = MutableStateFlow<String?>(null)
    val pending = MutableStateFlow(0)
    val notices = MutableSharedFlow<String>(extraBufferCapacity = 4)

    fun notice(message: String) {
        notices.tryEmit(message)
    }

    val state =
        repo.state
            .catch { error.value = "Lecture impossible : ${it.localizedMessage}" }
            .stateIn(viewModelScope, SharingStarted.Eagerly, CoachState())
    val profile =
        preferences.profile
            .catch { error.value = "Profil illisible : ${it.localizedMessage}" }
            .stateIn(viewModelScope, SharingStarted.Eagerly, AthleteProfile())
    val theme =
        preferences.theme
            .catch { error.value = "Préférences illisibles : ${it.localizedMessage}" }
            .stateIn(viewModelScope, SharingStarted.Eagerly, "dark")
    private val operations = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        viewModelScope.launch {
            for (operation in operations) {
                try {
                    operation()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error.value = e.localizedMessage ?: "La sauvegarde a échoué. Réessayez."
                } finally {
                    pending.value -= 1
                }
            }
        }
        action { repo.seed() }
    }

    fun action(block: suspend () -> Unit) {
        pending.value += 1
        operations.trySend(block)
    }

    fun updateResult(id: String, block: (WorkoutResult) -> WorkoutResult) = action {
        repo.mutateResult(id, block)
    }

    fun updateSet(id: String, exercise: StrengthExercise, index: Int, block: (SetLog) -> SetLog) =
        updateResult(id) { r ->
            val old =
                r.sets.find { it.exerciseInstanceId == exercise.id && it.index == index }
                    ?: SetLog(exercise.id, index, load = exercise.load?.toString() ?: "")
            r.copy(
                sets =
                    r.sets.filterNot { it.exerciseInstanceId == exercise.id && it.index == index } +
                        block(old)
            )
        }

    fun checkSet(
        id: String,
        exercise: StrengthExercise,
        index: Int,
        load: String,
        reps: String,
        checked: Boolean,
    ) = action {
        repo.mutateResult(id) { r ->
            val set = SetLog(exercise.id, index, load, reps, checked)
            val next =
                r.copy(
                    sets =
                        r.sets.filterNot {
                            it.exerciseInstanceId == exercise.id && it.index == index
                        } + set
                )
            if (checked)
                validateResult(
                    next.copy(
                        distanceKm = "",
                        durationSeconds = "",
                        averageHr = "",
                        elevation = "",
                        rpe = "",
                        fatigue = "",
                        intervals = emptyList(),
                    )
                )
            next.copy(
                restUntil =
                    if (checked && exercise.restSeconds > 0)
                        System.currentTimeMillis() + exercise.restSeconds * 1000L
                    else r.restUntil
            )
        }
        val r = parseResult(repo.dao.result(id)?.payload)
        RestAlarm.schedule(getApplication(), id, r.restUntil)
    }

    fun rest(id: String, until: Long?) = action {
        repo.mutateResult(id) { it.copy(restUntil = until) }
        RestAlarm.schedule(getApplication(), id, until)
    }

    fun finish(id: String, status: Status, success: () -> Unit) = action {
        repo.finish(id, status)
        RestAlarm.schedule(getApplication(), id, null)
        withContext(Dispatchers.Main) { success() }
    }

    fun saveTemplate(template: WorkoutTemplate, success: () -> Unit) = action {
        require(template.name.isNotBlank())
        validatePlan(parsePlan(template.planJson))
        repo.dao.putTemplate(template)
        withContext(Dispatchers.Main) { success() }
    }
}
