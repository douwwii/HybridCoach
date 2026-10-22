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
    val garminConfigured = MutableStateFlow(false)
    private var personalClient: GarminPersonalClient? = null
    private var personalClientKey: String? = null

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
                if (
                    e is GarminGatewayException &&
                        e.code in setOf("AUTH_REQUIRED", "ACCOUNT_CHANGED")
                ) {
                    personalClient?.clearToken()
                    garminConnection.value = GarminConnection()
                }
                garminError.value = e.localizedMessage ?: "Synchronisation Garmin impossible."
            } finally {
                garminBusy.value = false
            }
        }
    }

    private fun garminClient(key: String): GarminPersonalClient {
        if (personalClientKey != key) {
            personalClient?.clearToken()
            personalClient = GarminPersonalClient(key)
            personalClientKey = key
        }
        return requireNotNull(personalClient)
    }

    fun refreshGarmin() = garminOperation {
        val settings = withContext(Dispatchers.IO) { garminSettings.read() }
        garminConfigured.value = settings.apiKey.isNotBlank()
        if (settings.apiKey.isBlank()) {
            garminConnection.value = GarminConnection()
        } else {
            val verified = garminClient(settings.apiKey).connect()
            if (
                settings.profileHash != verified.profileHash ||
                    settings.accountId != null &&
                        verified.accountId != null &&
                        settings.accountId != verified.accountId
            ) {
                throw GarminGatewayException(
                    "ACCOUNT_CHANGED",
                    "Le compte Garmin a changé. Enregistrez à nouveau votre clé personnelle.",
                )
            }
            val updated = verified.copy(accountId = verified.accountId ?: settings.accountId)
            withContext(Dispatchers.IO) { garminSettings.save(updated) }
            garminConnection.value = GarminConnection(true, updated.accountId)
        }
    }

    fun connectGarmin(key: String) = garminOperation {
        val trimmed = key.trim()
        validatePersonalKey(trimmed)
        val client = GarminPersonalClient(trimmed)
        val verified = client.connect()
        val previous =
            runCatching { withContext(Dispatchers.IO) { garminSettings.read() } }
                .getOrDefault(GarminPersonalSettings())
        val updated = verified.preserveAccountFrom(previous)
        withContext(Dispatchers.IO) { garminSettings.save(updated) }
        personalClient?.clearToken()
        personalClient = client
        personalClientKey = trimmed
        garminConfigured.value = true
        garminConnection.value = GarminConnection(true, updated.accountId)
        notice("Clé personnelle Garmin connectée. Vous pouvez synchroniser vos activités.")
    }

    fun disconnectGarmin() = garminOperation {
        withContext(Dispatchers.IO) { garminSettings.clear() }
        personalClient?.clearToken()
        personalClient = null
        personalClientKey = null
        garminConfigured.value = false
        garminConnection.value = GarminConnection()
    }

    fun syncGarmin() = garminOperation {
        val settings = withContext(Dispatchers.IO) { garminSettings.read() }
        val known = repo.dao.allGarmin()
        val download = garminClient(settings.apiKey).fetchActivities(settings, known)
        val account = download.accountId
        if (account == null) {
            notice("Garmin : aucune activité disponible pour ce compte.")
        } else {
            val revision = (repo.dao.syncForAccount(account)?.revision ?: 0L) + 1
            val snapshot =
                GarminSnapshot(account, revision, download.activities, download.deletedIds)
            val changed = GarminImporter(repo.db).importPersonal(snapshot)
            if (settings.accountId != account)
                withContext(Dispatchers.IO) {
                    garminSettings.save(settings.copy(accountId = account))
                }
            garminConnection.value = GarminConnection(true, account)
            notice("Garmin : $changed activités nouvelles, modifiées ou supprimées")
        }
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
