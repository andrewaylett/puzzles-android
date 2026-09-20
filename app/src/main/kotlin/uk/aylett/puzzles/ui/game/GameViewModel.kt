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
@file:OptIn(FlowPreview::class)

package uk.aylett.puzzles.ui.game

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import uk.aylett.puzzles.PuzzlesApplication
import uk.aylett.puzzles.data.AppPreferences
import uk.aylett.puzzles.data.SavedGameStore
import uk.aylett.puzzles.engine.ConfigField
import uk.aylett.puzzles.engine.ConfigForm
import uk.aylett.puzzles.engine.PointerAction
import uk.aylett.puzzles.engine.PointerButton
import uk.aylett.puzzles.engine.PresetEntry
import uk.aylett.puzzles.engine.PuzzleSession

/**
 * Owns the [PuzzleSession] for one puzzle. All engine access happens here or inside functions
 * this exposes; the composables that make up the game screen only ever read [state] and call
 * these methods, never the session directly.
 */
class GameViewModel(
  application: Application,
  private val gameIndex: Int,
) : AndroidViewModel(application) {
  private val prefsStore = AppPreferences(application)
  private val savedGameStore = SavedGameStore(application)

  private val _state = MutableStateFlow(GameUiState())
  val state: StateFlow<GameUiState> = _state.asStateFlow()

  private var session: PuzzleSession? = null

  // Set once the session opens and its GameInfo is known; persistence is keyed on the game's
  // name rather than gameIndex, which is just upstream's list ordering and could shift.
  private var gameName: String? = null
  private val pendingSize = MutableStateFlow<Pair<Int, Int>?>(null)

  /** Whether this session has a game yet, either restored from a save or freshly generated. */
  private var gameStarted = false

  init {
    viewModelScope.launch {
      val engine = getApplication<PuzzlesApplication>().engine()
      val session = engine.openGame(gameIndex)
      this@GameViewModel.session = session
      val name = session.game.name
      gameName = name

      prefsStore.loadPrefs(name)?.let { session.loadPrefs(it) }
      // A restore only counts if it succeeded; a corrupt or stale save must still leave us able to
      // start a fresh game rather than sitting on an empty session forever.
      gameStarted = savedGameStore.load(name)?.let { session.deserialise(it) == null } == true
      prefsStore.setLastPlayedName(name)

      _state.value =
        _state.value.copy(
          loading = false,
          game = session.game,
          keyHints = session.keyHints(),
        )

      launch {
        session.frames.collect { frame -> _state.value = _state.value.copy(frame = frame) }
      }

      // Debounced so a rotation animation's stream of intermediate sizes doesn't each trigger a
      // fresh (potentially multi-second) puzzle regeneration at that size.
      launch {
        pendingSize.filterNotNull().debounce(150).collectLatest { (w, h) ->
          session.resize(w, h)
          // A session deliberately opens without a game, so that a saved one can be restored
          // without first paying to generate a throwaway. When there was nothing to restore, this
          // is where the first game comes from — after the size is known, so it is generated at
          // the size it will actually be played at.
          if (!gameStarted) {
            gameStarted = true
            session.newGame()
          }
        }
      }
    }
  }

  private fun act(block: suspend (PuzzleSession) -> Unit) {
    viewModelScope.launch { session?.let { block(it) } }
  }

  fun onSizeChanged(
    widthPx: Int,
    heightPx: Int,
  ) {
    pendingSize.value = widthPx to heightPx
  }

  fun pointer(
    action: PointerAction,
    button: PointerButton,
    x: Int,
    y: Int,
  ) = act { it.pointer(action, button, x, y) }

  fun key(button: Int) = act { it.key(button) }

  fun undo() = act { it.undo() }

  fun redo() = act { it.redo() }

  fun newGame() = act { it.newGame() }

  fun restart() = act { it.restart() }

  fun solve() =
    act {
      val error = it.solve()
      if (error != null) _state.value = _state.value.copy(toastMessage = error)
    }

  fun clearToast() {
    _state.value = _state.value.copy(toastMessage = null)
  }

  suspend fun presets(): List<PresetEntry> = session?.presets() ?: emptyList()

  suspend fun currentPresetId(): Int = session?.currentPresetId() ?: -1

  fun applyPreset(id: Int) = act { it.applyPreset(id) }

  suspend fun config(section: Int): ConfigForm? = session?.config(section)

  suspend fun applyConfig(
    section: Int,
    fields: List<ConfigField>,
  ): String? = session?.applyConfig(section, fields)

  suspend fun gameId(): String = session?.gameId() ?: ""

  suspend fun setGameId(id: String): String? = session?.setGameId(id)

  suspend fun exportBytes(): ByteArray? = session?.serialise()

  suspend fun importBytes(bytes: ByteArray): String? = session?.deserialise(bytes)

  fun onPause() {
    session?.setPaused(true)
    persist()
  }

  fun onResume() {
    session?.setPaused(false)
  }

  private fun persist() {
    val session = session ?: return
    val name = gameName ?: return
    viewModelScope.launch {
      savedGameStore.save(name, session.serialise())
      prefsStore.savePrefs(name, session.savePrefs())
    }
  }

  override fun onCleared() {
    session?.close()
  }

  companion object {
    fun factory(
      application: Application,
      gameIndex: Int,
    ): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(
          modelClass: Class<T>,
          extras: CreationExtras,
        ): T {
          @Suppress("UNCHECKED_CAST")
          return GameViewModel(application, gameIndex) as T
        }
      }
  }
}
