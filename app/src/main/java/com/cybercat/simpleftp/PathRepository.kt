package com.cybercat.simpleftp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.pathDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "path"
)

class PathRepository(context: Context) {
    private val dataStore = context.applicationContext.pathDataStore

    val relativePath: Flow<String> = dataStore.data.map { preferences ->
        preferences[RELATIVE_PATH_KEY]?.cleanRelativePath() ?: DEFAULT_RELATIVE_PATH
    }

    suspend fun setRelativePath(path: String) {
        dataStore.edit { preferences ->
            preferences[RELATIVE_PATH_KEY] = path.cleanRelativePath()
        }
    }

    private companion object {
        val RELATIVE_PATH_KEY = stringPreferencesKey("relative_path")
    }
}

const val DEFAULT_RELATIVE_PATH = ""

fun String.cleanRelativePath(): String = split('/', '\\')
    .map(String::trim)
    .filter { it.isNotEmpty() && it != "." && it != ".." }
    .joinToString("/")
