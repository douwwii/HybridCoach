package fr.hybridcoach.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import fr.hybridcoach.domain.*
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

private val Context.preferences by preferencesDataStore("athlete")

class AthletePreferences(private val context: Context) {
    private val profileKey = stringPreferencesKey("profile")
    private val themeKey = stringPreferencesKey("theme")
    val profile =
        context.preferences.data.map {
            it[profileKey]?.let { raw -> codec.decodeFromString<AthleteProfile>(raw) }
                ?: AthleteProfile()
        }
    val theme = context.preferences.data.map { it[themeKey] ?: "dark" }

    suspend fun save(profile: AthleteProfile, theme: String) {
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
        context.preferences.edit {
            it[profileKey] = codec.encodeToString(profile)
            it[themeKey] = theme
        }
    }
}
