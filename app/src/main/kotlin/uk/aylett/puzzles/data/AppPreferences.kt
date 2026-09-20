/*
 * Copyright 2026 Andrew Aylett
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.aylett.puzzles.data

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "puzzles_prefs")

/**
 * Small cross-screen bits of state that need to survive a process death: which puzzle the user
 * was last playing, and each game's own mid-end preferences (font size, colour choices, and so
 * on, from [uk.aylett.puzzles.engine.ConfigSection.PREFS]).
 *
 * Both are keyed by [uk.aylett.puzzles.engine.GameInfo.name], not its `index`: the index is just
 * upstream's `list.c` ordering, which shifts if a puzzle is ever added or removed there, and a
 * stale index would silently reopen the wrong game.
 *
 * Mid-end prefs are opaque bytes ([uk.aylett.puzzles.engine.PuzzleSession.savePrefs]); Preferences
 * DataStore has no byte-array type, so they are stored base64-encoded.
 */
class AppPreferences(
  private val context: Context,
) {
  private val lastPlayedKey = stringPreferencesKey("last_played_game_name")

  val lastPlayedName: Flow<String?> =
    context.dataStore.data.map { it[lastPlayedKey] }

  suspend fun setLastPlayedName(name: String) {
    context.dataStore.edit { it[lastPlayedKey] = name }
  }

  private fun prefsKey(gameName: String) = stringPreferencesKey("prefs_$gameName")

  suspend fun loadPrefs(gameName: String): ByteArray? {
    val encoded = context.dataStore.data.first()[prefsKey(gameName)] ?: return null
    return Base64.decode(encoded, Base64.NO_WRAP)
  }

  suspend fun savePrefs(
    gameName: String,
    bytes: ByteArray,
  ) {
    val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
    context.dataStore.edit { it[prefsKey(gameName)] = encoded }
  }
}
