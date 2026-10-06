package com.fragpicker.android.core.theme

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.preferences by preferencesDataStore(name = "appearance")

class ThemeRepository(context: Context) {
    private val dataStore = context.applicationContext.preferences
    private val modeKey = stringPreferencesKey("theme_mode")
    val mode = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { preferences ->
        ThemeMode.entries.firstOrNull { it.name == preferences[modeKey] } ?: ThemeMode.SYSTEM
    }

    suspend fun setMode(mode: ThemeMode) {
        dataStore.edit { it[modeKey] = mode.name }
    }
}
