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
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
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

// How long a run of ticks must produce no visible change before the animation loop backs off from
// vsync pacing to a slow poll — sized in time rather than frame count so it means the same thing
// on a 60Hz and a 120Hz display. Below this, a couple of frames rounding to the same pixel mid
// animation doesn't cause a visible stutter when it snaps back.
private const val ANIMATION_IDLE_THRESHOLD_MS = 100L

// The slow-poll rate once backed off — fast enough to keep a ticking game clock (Mines, Inertia)
// looking live, far below display refresh rate so it costs nothing while idle.
private const val ANIMATION_BACKOFF_INTERVAL_MS = 200L

private data class PendingSize(
  val widthPx: Int,
  val heightPx: Int,
  val density: Float,
)

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
  private val pendingSize = MutableStateFlow<PendingSize?>(null)

  /** Whether this session has a game yet, either restored from a save or freshly generated. */
  private var gameStarted = false

  // The animation pacing loop, running only while session.animating is true. Owned here rather
  // than in PuzzleSession because pacing needs Choreographer/vsync timing, which only makes sense
  // on the UI thread; the session itself only ever decides whether ticking should happen at all.
  private var animationJob: Job? = null

  // The collectors bound to the current `session` (frames, animating, sizing), tracked separately
  // from viewModelScope so they can be torn down and restarted when the session is replaced — see
  // [openSession] and the engineInvalidated collector below.
  private var sessionJob: Job? = null

  init {
    viewModelScope.launch {
      prefsStore.swapPressGesture.collect { swapped ->
        _state.value = _state.value.copy(swapPressGesture = swapped)
      }
    }

    viewModelScope.launch { openSession() }

    viewModelScope.launch {
      getApplication<PuzzlesApplication>().engineInvalidated.collect {
        // The engine thread behind this session is already gone, so there's nothing left to
        // free — just drop the reference. Reopening happens lazily in onResume, the next time
        // this screen actually needs the engine, not eagerly here while still backgrounded.
        animationJob?.cancel()
        animationJob = null
        sessionJob?.cancel()
        session = null
      }
    }
  }

  /**
   * Opens a fresh [PuzzleSession] against the current engine and restores whatever was last
   * persisted for [gameName]. Callable more than once: [PuzzlesApplication.onTrimMemory] can
   * recycle the shared engine while this screen is backgrounded, and [onResume] calls this again
   * to replace a session whose engine thread no longer exists.
   */
  private suspend fun openSession() {
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

    sessionJob =
      viewModelScope.launch {
        launch {
          session.frames.collect { frame -> _state.value = _state.value.copy(frame = frame) }
        }

        launch {
          // Joined rather than just cancelled: collect() is sequential, so joining here
          // guarantees the old loop's last tick() has finished before a restart's new one begins
          // — without it, a deactivate immediately followed by a reactivate could leave two loops
          // both mid-tick(), racing over the same timerStartNanos and dirty flag on the engine
          // thread.
          session.animating.collect { active ->
            animationJob?.cancelAndJoin()
            animationJob = if (active) startAnimationLoop(session) else null
          }
        }

        // Debounced so a rotation animation's stream of intermediate sizes doesn't each trigger a
        // fresh (potentially multi-second) puzzle regeneration at that size.
        launch {
          pendingSize.filterNotNull().debounce(150).collectLatest { (w, h, density) ->
            session.resize(w, h, density)
            // A session deliberately opens without a game, so that a saved one can be restored
            // without first paying to generate a throwaway. When there was nothing to restore,
            // this is where the first game comes from — after the size is known, so it is
            // generated at the size it will actually be played at.
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

  /**
   * Paces [PuzzleSession.tick] while [PuzzleSession.animating] is true: once per display frame
   * ([awaitFrame]) as long as ticks are producing visible changes, backing off to a slow poll
   * ([ANIMATION_BACKOFF_INTERVAL_MS]) once a run of them hasn't ([ANIMATION_IDLE_THRESHOLD_MS]).
   * A tick handled inline elsewhere (a tap, say) sets the same dirty flag `tick()` reads, so it is
   * picked up on the next iteration and snaps the loop straight back to full rate — no separate
   * wake-up path needed for "refresh fast after input".
   *
   * Callers must join the previous loop's job before starting a new one (see the `animating`
   * collector) — two loops calling [PuzzleSession.tick] concurrently would race over the engine's
   * delta clock and dirty flag.
   */
  private fun startAnimationLoop(session: PuzzleSession): Job =
    viewModelScope.launch {
      var idleSinceNanos: Long? = null
      while (isActive) {
        val changed = session.tick()
        val now = System.nanoTime()
        idleSinceNanos =
          when {
            changed -> null
            idleSinceNanos == null -> now
            else -> idleSinceNanos
          }
        val idleMillis = idleSinceNanos?.let { (now - it) / 1_000_000 } ?: 0L
        if (idleMillis < ANIMATION_IDLE_THRESHOLD_MS) {
          awaitFrame()
        } else {
          delay(ANIMATION_BACKOFF_INTERVAL_MS)
        }
      }
    }

  fun onSizeChanged(
    widthPx: Int,
    heightPx: Int,
    density: Float,
  ) {
    pendingSize.value = PendingSize(widthPx, heightPx, density)
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

  fun toggleSwapPressGesture() {
    viewModelScope.launch { prefsStore.setSwapPressGesture(!_state.value.swapPressGesture) }
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
    viewModelScope.launch {
      // The engine may have been recycled while backgrounded (see the engineInvalidated
      // collector in init); reopen against the current one before resuming ticking.
      if (session == null) openSession()
      session?.setPaused(false)
    }
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
