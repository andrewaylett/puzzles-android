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
package uk.aylett.puzzles.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uk.aylett.puzzles.data.AppPreferences

data class SettingsUiState(
  val swapPressGesture: Boolean = false,
  val stylusTaps: Boolean = true,
)

/**
 * The app-wide switches, as opposed to a puzzle's own preferences (which live in the game screen
 * because the mid-end defines them per game). These are the same two settings the game screen
 * offers inline, so changing one in either place is seen in the other.
 */
class SettingsViewModel(
  private val prefs: AppPreferences,
) : ViewModel() {
  val uiState: StateFlow<SettingsUiState> =
    combine(prefs.swapPressGesture, prefs.stylusTaps, ::SettingsUiState)
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

  fun setSwapPressGesture(swapped: Boolean) {
    viewModelScope.launch { prefs.setSwapPressGesture(swapped) }
  }

  fun setStylusTaps(enabled: Boolean) {
    viewModelScope.launch { prefs.setStylusTaps(enabled) }
  }

  companion object {
    fun factory(application: Application): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(
          modelClass: Class<T>,
          extras: CreationExtras,
        ): T {
          @Suppress("UNCHECKED_CAST")
          return SettingsViewModel(AppPreferences(application)) as T
        }
      }
  }
}
