package com.karoo_decoupling.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import com.karoo_decoupling.extension.WBalSnapshot
import com.karoo_decoupling.extension.WBalSnapshotStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "wbal_settings")

/**
 * Persists the rider's W'bal inputs (Critical Power, W'max) via DataStore Preferences and
 * exposes them as a reactive [Flow] the extension observes. Mirrors the canonical Karoo
 * settings pattern used by sibling app ../7climb. Values are coerced to sane ranges on write.
 *
 * Also stores the open ride's [WBalSnapshot] so W'bal can resume after a mid-ride service restart.
 */
class SettingsRepository(context: Context) : WBalSnapshotStore {
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

    override suspend fun load(): WBalSnapshot? {
        val prefs = dataStore.data.first()
        val wBal = prefs[KEY_SNAPSHOT_WBAL] ?: return null
        val elapsed = prefs[KEY_SNAPSHOT_ELAPSED] ?: return null
        return WBalSnapshot(wBal, elapsed)
    }

    override suspend fun save(snapshot: WBalSnapshot) {
        dataStore.edit {
            it[KEY_SNAPSHOT_WBAL] = snapshot.wBalJoules
            it[KEY_SNAPSHOT_ELAPSED] = snapshot.elapsedSec
        }
    }

    override suspend fun clear() {
        dataStore.edit {
            it.remove(KEY_SNAPSHOT_WBAL)
            it.remove(KEY_SNAPSHOT_ELAPSED)
        }
    }

    companion object {
        private val KEY_SNAPSHOT_WBAL = doublePreferencesKey("wbal_snapshot_joules")
        private val KEY_SNAPSHOT_ELAPSED = doublePreferencesKey("wbal_snapshot_elapsed_sec")
        private val KEY_CP = intPreferencesKey("critical_power")
        private val KEY_WPRIME = intPreferencesKey("wprime_max")
    }
}
