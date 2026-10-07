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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uk.aylett.puzzles.EngineProvider
import uk.aylett.puzzles.PuzzlesApplication
import uk.aylett.puzzles.data.AppPreferences
import uk.aylett.puzzles.engine.GameInfo

data class GameListUiState(
  val games: List<GameInfo> = emptyList(),
  val loading: Boolean = true,
  val filter: String = "",
  val favourites: Set<String> = emptySet(),
) {
  /** Favourites first, otherwise upstream's order; the sort is stable so neither group shuffles. */
  val visibleGames: List<GameInfo>
    get() =
      games
        .filter { filter.isBlank() || it.name.contains(filter, ignoreCase = true) }
        .sortedByDescending { it.name in favourites }
}

/** Backs the launcher screen: the flat list of all 40 puzzles the module carries. */
class GameListViewModel(
  private val engines: EngineProvider,
  private val prefs: AppPreferences,
) : ViewModel() {
  private val _uiState = MutableStateFlow(GameListUiState())
  val uiState: StateFlow<GameListUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      prefs.favourites.collect { _uiState.value = _uiState.value.copy(favourites = it) }
    }
    viewModelScope.launch {
      val engine = engines.engine()
      // Keep any filter typed while the engine was still loading.
      _uiState.value = _uiState.value.copy(games = engine.games, loading = false)
    }
  }

  fun onFilterChange(filter: String) {
    _uiState.value = _uiState.value.copy(filter = filter)
  }

  fun onToggleFavourite(game: GameInfo) {
    val favourite = game.name !in _uiState.value.favourites
    viewModelScope.launch { prefs.setFavourite(game.name, favourite) }
  }

  companion object {
    fun factory(application: Application): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(
          modelClass: Class<T>,
          extras: CreationExtras,
        ): T {
          @Suppress("UNCHECKED_CAST")
          return GameListViewModel(
            application as PuzzlesApplication,
            AppPreferences(application),
          ) as T
        }
      }
  }
}
