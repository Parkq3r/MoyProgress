package ru.moyprogress.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import ru.moyprogress.domain.Preferences

private val Context.settingsStore by preferencesDataStore("settings")

class SettingsRepository(context: Context) {
    private val store = context.settingsStore
    private val theme = stringPreferencesKey("theme")
    private val dynamic = booleanPreferencesKey("dynamic")
    private val rest = intPreferencesKey("rest")
    private val progression = booleanPreferencesKey("progression")
    private val streak = intPreferencesKey("streak")
    private val effort = intPreferencesKey("effort")
    private val recovery = intPreferencesKey("recovery")

    val settings = store.data.catch {
        if (it is IOException) emit(emptyPreferences()) else throw it
    }.map {
        Preferences(it[theme] ?: "SYSTEM", it[dynamic] ?: true,
            (it[rest] ?: 90).coerceIn(15, 600), it[progression] ?: true,
            (it[streak] ?: 3).coerceIn(2, 6), (it[effort] ?: 7).coerceIn(4, 9),
            (it[recovery] ?: 2).coerceIn(1, 4))
    }

    suspend fun set(value: Preferences) {
        store.edit {
            it[theme] = value.theme
            it[dynamic] = value.dynamic
            it[rest] = value.rest.coerceIn(15, 600)
            it[progression] = value.progression
            it[streak] = value.streak.coerceIn(2, 6)
            it[effort] = value.maxEffort.coerceIn(4, 9)
            it[recovery] = value.recoveryDays.coerceIn(1, 4)
        }
    }
}
