package com.compvdo.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persisted user preferences via DataStore — the Android equivalent of settings.json.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "compvdo_settings")

class PreferencesRepo(private val context: Context) {

    companion object {
        private val KEY_MODE = stringPreferencesKey("compression_mode")
        private val KEY_DELETE_ORIGINAL = booleanPreferencesKey("delete_original")
        private val KEY_SORT_ORDER = stringPreferencesKey("sort_order")
    }

    val compressionMode: Flow<CompressionMode> = context.dataStore.data.map { prefs ->
        try {
            CompressionMode.valueOf(prefs[KEY_MODE] ?: CompressionMode.DEFAULT.name)
        } catch (_: Exception) {
            CompressionMode.DEFAULT
        }
    }

    val deleteOriginal: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_DELETE_ORIGINAL] ?: false
    }

    val sortOrder: Flow<MediaScanner.SortOrder> = context.dataStore.data.map { prefs ->
        try {
            MediaScanner.SortOrder.valueOf(prefs[KEY_SORT_ORDER] ?: MediaScanner.SortOrder.SAVINGS.name)
        } catch (_: Exception) {
            MediaScanner.SortOrder.SAVINGS
        }
    }

    suspend fun setCompressionMode(mode: CompressionMode) {
        context.dataStore.edit { it[KEY_MODE] = mode.name }
    }

    suspend fun setDeleteOriginal(delete: Boolean) {
        context.dataStore.edit { it[KEY_DELETE_ORIGINAL] = delete }
    }

    suspend fun setSortOrder(order: MediaScanner.SortOrder) {
        context.dataStore.edit { it[KEY_SORT_ORDER] = order.name }
    }
}
