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
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
 * Preferences that several games share are additionally held once, by identity, in
 * [sharedSettings]; those values win over the per-game bytes whenever a session is opened.
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

  private val swapPressGestureKey = booleanPreferencesKey("swap_press_gesture")

  /**
   * Whether a quick tap should act as the mid-end's right click and a press-and-hold as its left
   * click, the reverse of the default. Applies across every puzzle, not per-game: it is a motor
   * preference about the gesture itself, not something particular to one puzzle's controls.
   */
  val swapPressGesture: Flow<Boolean> =
    context.dataStore.data.map { it[swapPressGestureKey] ?: false }

  suspend fun setSwapPressGesture(swapped: Boolean) {
    context.dataStore.edit { it[swapPressGestureKey] = swapped }
  }

  private val stylusTapsKey = booleanPreferencesKey("stylus_taps")

  /**
   * Whether pointer events carry the mid-end's `MOD_STYLUS` modifier. Games that set
   * `STYLUS_SUPPORT` (Loopy, Net, Light Up, Pattern, Tents) then let a single button cycle through
   * every cell state rather than toggling between two, so touch users can reach the third state
   * without a long press. The mid-end ignores the modifier for every other game. On by default
   * because a finger is a stylus-like device: there is no second button to hand.
   */
  val stylusTaps: Flow<Boolean> = context.dataStore.data.map { it[stylusTapsKey] ?: true }

  suspend fun setStylusTaps(enabled: Boolean) {
    context.dataStore.edit { it[stylusTapsKey] = enabled }
  }

  private val favouritesKey = stringSetPreferencesKey("favourite_game_names")

  /**
   * The puzzles the user has starred, by [uk.aylett.puzzles.engine.GameInfo.name] for the same
   * reason as [lastPlayedName]: an index would drift if upstream's ordering did.
   */
  val favourites: Flow<Set<String>> =
    context.dataStore.data.map { it[favouritesKey] ?: emptySet() }

  suspend fun setFavourite(
    name: String,
    favourite: Boolean,
  ) {
    context.dataStore.edit {
      val current = it[favouritesKey] ?: emptySet()
      it[favouritesKey] = if (favourite) current + name else current - name
    }
  }

  private val settingPrefix = "setting:"

  /**
   * Preference values shared between games, by identity (see [settingId]). Absent means the user
   * has never chosen, in which case each game keeps whatever its own saved preferences say.
   */
  val sharedSettings: Flow<Map<String, String>> =
    context.dataStore.data.map { all ->
      all
        .asMap()
        .mapNotNull { (key, value) ->
          if (key.name.startsWith(settingPrefix) && value is String) {
            key.name.removePrefix(settingPrefix) to value
          } else {
            null
          }
        }.toMap()
    }

  suspend fun setSharedSettings(values: Map<String, String>) {
    if (values.isEmpty()) return
    context.dataStore.edit { prefs ->
      values.forEach { (id, value) -> prefs[stringPreferencesKey(settingPrefix + id)] = value }
    }
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
