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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uk.aylett.puzzles.EngineProvider
import uk.aylett.puzzles.PuzzlesApplication
import uk.aylett.puzzles.data.AppPreferences
import uk.aylett.puzzles.data.settingValue
import uk.aylett.puzzles.engine.ConfigSection

data class SettingsUiState(
  val swapPressGesture: Boolean = false,
  val stylusTaps: Boolean = true,
  /** Null until the puzzles have been asked about their preferences. */
  val catalogue: SettingsCatalogue? = null,
  val stored: Map<String, String> = emptyMap(),
)

/**
 * Every setting in the app: the two touch switches, and every puzzle's own preferences pooled by
 * identity (see [uk.aylett.puzzles.data.settingId]). A puzzle's own preferences dialog is the
 * subset of these that it has, and the two are views of the same stored values, so changing one
 * in either place is seen in the other.
 */
class SettingsViewModel(
  private val prefs: AppPreferences,
  private val engines: EngineProvider,
) : ViewModel() {
  private val catalogue = MutableStateFlow<SettingsCatalogue?>(null)

  val uiState: StateFlow<SettingsUiState> =
    combine(
      prefs.swapPressGesture,
      prefs.stylusTaps,
      catalogue,
      prefs.sharedSettings,
    ) { swap, stylus, catalogue, stored -> SettingsUiState(swap, stylus, catalogue, stored) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

  init {
    viewModelScope.launch { catalogue.value = loadCatalogue() }
  }

  /**
   * Asks each puzzle, without generating a game, what preferences it has and is using.
   *
   * Anything the user has never set is then stored with the value just resolved (see
   * [buildCatalogue]): until it is, games that disagreed would keep disagreeing, and Settings
   * would be showing a value that only one of them uses.
   */
  private suspend fun loadCatalogue(): SettingsCatalogue {
    val engine = engines.engine()
    val perGame =
      engine.games.map { game ->
        val session = engine.openGame(game.index)
        try {
          val saved = prefs.loadPrefs(game.name)
          if (saved != null) session.loadPrefs(saved) else session.applyPrefDefaults()
          game.name to session.config(ConfigSection.PREFS).fields
        } finally {
          session.close()
        }
      }
    val result = buildCatalogue(perGame)
    val stored = prefs.sharedSettings.first()
    prefs.setSharedSettings(
      result.all
        .filter { it.id !in stored }
        .mapNotNull { entry -> entry.field.settingValue()?.let { entry.id to it } }
        .toMap(),
    )
    return result
  }

  fun setSetting(
    id: String,
    value: String,
  ) {
    viewModelScope.launch { prefs.setSharedSettings(mapOf(id to value)) }
  }

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
          return SettingsViewModel(
            AppPreferences(application),
            application as PuzzlesApplication,
          ) as T
        }
      }
  }
}
