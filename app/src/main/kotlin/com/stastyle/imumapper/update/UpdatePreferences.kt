package com.stastyle.imumapper.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stastyle.imumapper.pipeline.core.TripMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

// One DataStore per name per process; the delegate must live at file level.
private val Context.updatesDataStore: DataStore<Preferences> by preferencesDataStore(name = "updates")

/**
 * The "updates" preferences store: when the updater last asked GitHub, which tag the user has
 * already dismissed, and the Settings screen's default trip mode. The settings keys live here
 * because this is the app's only DataStore and a second one for two keys is not worth a file.
 */
class UpdatePreferences(context: Context) {

    private val store: DataStore<Preferences> = context.applicationContext.updatesDataStore

    /** Preferences that read as empty when the file is unreadable, so a bad disk never crashes. */
    private val data: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val defaultTripMode: Flow<TripMode> = data.map { prefs ->
        val name = prefs[KEY_DEFAULT_TRIP_MODE]
        TripMode.entries.firstOrNull { it.name == name } ?: TripMode.POCKET
    }

    suspend fun lastCheckEpochMs(): Long = data.first()[KEY_LAST_CHECK_EPOCH_MS] ?: 0L

    /** The newest tag the user dismissed with "Later"; the automatic check stays quiet about it. */
    suspend fun lastSeenTag(): String? = data.first()[KEY_LAST_SEEN_TAG]

    suspend fun recordCheck(nowEpochMs: Long) {
        store.edit { it[KEY_LAST_CHECK_EPOCH_MS] = nowEpochMs }
    }

    suspend fun setLastSeenTag(tag: String) {
        store.edit { it[KEY_LAST_SEEN_TAG] = tag }
    }

    suspend fun setDefaultTripMode(mode: TripMode) {
        store.edit { it[KEY_DEFAULT_TRIP_MODE] = mode.name }
    }

    private companion object {
        val KEY_LAST_CHECK_EPOCH_MS = longPreferencesKey("last_check_epoch_ms")
        val KEY_LAST_SEEN_TAG = stringPreferencesKey("last_seen_tag")
        val KEY_DEFAULT_TRIP_MODE = stringPreferencesKey("default_trip_mode")
    }
}
