package com.karoo_decoupling.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "wbal_settings")

/**
 * Persists the rider's W'bal inputs (Critical Power, W'max) via DataStore Preferences and
 * exposes them as a reactive [Flow] the extension observes. Mirrors the canonical Karoo
 * settings pattern used by sibling app ../7climb. Values are coerced to sane ranges on write.
 */
class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.dataStore

    val settingsFlow: Flow<WBalSettings> = dataStore.data
        .map { prefs ->
            WBalSettings(
                criticalPower = prefs[KEY_CP] ?: WBalSettings.CP_DEFAULT,
                wPrimeMax = prefs[KEY_WPRIME] ?: WBalSettings.WPRIME_DEFAULT,
            )
        }
        .distinctUntilChanged()

    suspend fun updateCriticalPower(watts: Int) {
        dataStore.edit { it[KEY_CP] = watts.coerceIn(WBalSettings.CP_MIN, WBalSettings.CP_MAX) }
    }

    suspend fun updateWPrime(joules: Int) {
        dataStore.edit { it[KEY_WPRIME] = joules.coerceIn(WBalSettings.WPRIME_MIN, WBalSettings.WPRIME_MAX) }
    }

    companion object {
        private val KEY_CP = intPreferencesKey("critical_power")
        private val KEY_WPRIME = intPreferencesKey("wprime_max")
    }
}
