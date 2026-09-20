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
package uk.aylett.puzzles.ui.list

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uk.aylett.puzzles.PuzzlesApplication
import uk.aylett.puzzles.engine.GameInfo

data class GameListUiState(
  val games: List<GameInfo> = emptyList(),
  val loading: Boolean = true,
  val filter: String = "",
) {
  val visibleGames: List<GameInfo>
    get() =
      if (filter.isBlank()) {
        games
      } else {
        games.filter { it.name.contains(filter, ignoreCase = true) }
      }
}

/** Backs the launcher screen: the flat list of all 40 puzzles the module carries. */
class GameListViewModel(
  application: Application,
) : AndroidViewModel(application) {
  private val _uiState = MutableStateFlow(GameListUiState())
  val uiState: StateFlow<GameListUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      val engine = (getApplication<PuzzlesApplication>()).engine()
      _uiState.value = GameListUiState(games = engine.games, loading = false)
    }
  }

  fun onFilterChange(filter: String) {
    _uiState.value = _uiState.value.copy(filter = filter)
  }
}
