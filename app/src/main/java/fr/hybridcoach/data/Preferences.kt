package fr.hybridcoach.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import fr.hybridcoach.domain.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString

private val Context.legacyPreferences by preferencesDataStore("athlete")

/** A one-time, non-destructive copy from the previous DataStore; Room is then authoritative. */
class AthletePreferences(
    context: Context,
    private val db: CoachDatabase,
    private val legacy: DataStore<Preferences> = context.legacyPreferences,
) {
    suspend fun ensureInitialized() {
        if (db.dao().settings() != null) return
        val old = legacy.data.first()
        val profileJson =
            old[stringPreferencesKey("profile")] ?: codec.encodeToString(AthleteProfile())
        // Never replace an unreadable legacy profile with defaults silently.
        codec.decodeFromString<AthleteProfile>(profileJson)
        val theme = old[stringPreferencesKey("theme")] ?: "dark"
        require(theme in setOf("dark", "light", "system")) { "Thème du profil illisible." }
        db.withTransaction {
            if (db.dao().settings() == null)
                db.dao()
                    .putSettings(AthleteSettingsEntity(profileJson = profileJson, theme = theme))
        }
    }

    private val data: Flow<AthleteSettingsEntity> = flow {
        ensureInitialized()
        emitAll(db.dao().settingsFlow().filterNotNull())
    }
    val profile = data.map { codec.decodeFromString<AthleteProfile>(it.profileJson) }
    val theme = data.map { it.theme }

    suspend fun save(profile: AthleteProfile, theme: String) {
        validateAthleteProfile(profile, theme)
        db.dao()
            .putSettings(
                AthleteSettingsEntity(profileJson = codec.encodeToString(profile), theme = theme)
            )
    }
}

fun validateAthleteProfile(profile: AthleteProfile, theme: String) {
    require(theme in setOf("dark", "light", "system")) { "Thème invalide." }
    listOf(
            profile.vma,
            profile.maxHr,
            profile.pace5,
            profile.pace10,
            profile.threshold,
            profile.half,
            profile.marathon,
        )
        .forEach { value ->
            require(value.isBlank() || value.decimal()?.let { it > 0 } == true) {
                "Les références doivent être des nombres positifs."
            }
        }
    require(profile.maxHr.isBlank() || profile.maxHr.decimal()!! <= 300)
    require(parseZones(profile.hrZones).values.all { it.second <= 300 }) {
        "Une zone FC dépasse 300 bpm."
    }
    parseZones(profile.paceZones)
}
