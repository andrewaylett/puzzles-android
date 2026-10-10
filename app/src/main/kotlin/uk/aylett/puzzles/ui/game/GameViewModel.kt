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
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uk.aylett.puzzles.EngineProvider
import uk.aylett.puzzles.PuzzlesApplication
import uk.aylett.puzzles.data.AppPreferences
import uk.aylett.puzzles.data.FailureReport
import uk.aylett.puzzles.data.RecoveryStore
import uk.aylett.puzzles.data.SavedGameStore
import uk.aylett.puzzles.data.overlay
import uk.aylett.puzzles.data.toSettings
import uk.aylett.puzzles.engine.ColourScheme
import uk.aylett.puzzles.engine.ConfigField
import uk.aylett.puzzles.engine.ConfigForm
import uk.aylett.puzzles.engine.ConfigSection
import uk.aylett.puzzles.engine.EngineFailure
import uk.aylett.puzzles.engine.GameStatus
import uk.aylett.puzzles.engine.PointerAction
import uk.aylett.puzzles.engine.PointerButton
import uk.aylett.puzzles.engine.PresetEntry
import uk.aylett.puzzles.engine.PuzzleEngine
import uk.aylett.puzzles.engine.PuzzleSession
import java.io.PrintWriter
import java.io.StringWriter

// How long a run of ticks must produce no visible change before the animation loop backs off from
// vsync pacing to a slow poll — sized in time rather than frame count so it means the same thing
// on a 60Hz and a 120Hz display. Below this, a couple of frames rounding to the same pixel mid
// animation doesn't cause a visible stutter when it snaps back.
private const val ANIMATION_IDLE_THRESHOLD_MS = 100L

// The slow-poll rate once backed off — fast enough to keep a ticking game clock (Mines, Inertia)
// looking live, far below display refresh rate so it costs nothing while idle.
private const val ANIMATION_BACKOFF_INTERVAL_MS = 200L

private const val LOG_TAG = "GameViewModel"

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
  private val engines: EngineProvider,
  private val gameIndex: Int,
  private val prefsStore: AppPreferences,
  private val savedGameStore: SavedGameStore,
  private val recoveryStore: RecoveryStore,
) : ViewModel() {
  private val _state = MutableStateFlow(GameUiState())
  val state: StateFlow<GameUiState> = _state.asStateFlow()

  private var session: PuzzleSession? = null

  // The engine `session` belongs to, kept so that it can be discarded if it breaks.
  private var engine: PuzzleEngine? = null

  // Set from restoring a save until the restored game has been drawn (or saved again) without
  // incident. While it is set, the save is the prime suspect for any engine failure, and the
  // matching marker on disk stands in for us if the process dies before we can say so.
  private var unconfirmedRestore: String? = null

  // Set once the session opens and its GameInfo is known; persistence is keyed on the game's
  // name rather than gameIndex, which is just upstream's list ordering and could shift.
  private var gameName: String? = null
  private val pendingSize = MutableStateFlow<PendingSize?>(null)

  // The surface the puzzle is presented on, held here rather than in the composable so it survives
  // the session being replaced: after the engine is recycled, onResume opens a new session while the
  // surface may already be live, and it has to be handed on or the screen stays blank. Main thread
  // only, like everything else here.
  private var surface: Surface? = null
  private var surfaceBackground = 0

  // The theme's palette, kept so that a replacement session opens already dressed for it.
  private var colourScheme = ColourScheme()

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

  // `init` and `onResume` both ask for a session, and on first launch onResume runs while init's
  // open is still waiting on the engine. Without this they each open one, and the loser's frame,
  // animation and sizing collectors are never cancelled — two sessions then fight over the one
  // displayed frame, which shows as constant flashing.
  private val openMutex = Mutex()

  // Saves the game as it changes, rather than at lifecycle edges that a swipe away from recents
  // does not leave time for. See GamePersister for why it lives in the application's scope.
  private val persister =
    GamePersister(
      scope = engines.applicationScope,
      saves = savedGameStore,
      prefs = prefsStore,
      onFailure = { failed, e -> viewModelScope.launch { handleFailure(failed, e, "saving") } },
      onSerialised = {
        // The persister runs elsewhere; the state this touches belongs to the main thread.
        withContext(Dispatchers.Main.immediate) { if (restoreMayConfirm) confirmRestore() }
      },
    )

  // Whether serialising the restored game is enough to clear its marker. Not until the game has
  // been laid out and drawn, or the player is leaving: an autosave a moment after the restore says
  // nothing about whether drawing it would trap, and the marker exists to catch exactly that.
  private var restoreMayConfirm = false

  init {
    viewModelScope.launch {
      prefsStore.swapPressGesture.collect { swapped ->
        _state.value = _state.value.copy(swapPressGesture = swapped)
      }
    }

    viewModelScope.launch {
      prefsStore.stylusTaps.collect { enabled ->
        _state.value = _state.value.copy(stylusTaps = enabled)
      }
    }

    // This ViewModel outlives a trip to the Settings screen, and with it the session. Without
    // pushing changes into the live session, the stale values it still holds would be written
    // back by the next save, silently undoing what Settings just changed.
    viewModelScope.launch {
      prefsStore.sharedSettings.collect { stored ->
        session?.takeIf { !it.engineClosed }?.let { s ->
          guarded(s, "applying settings", Unit) { overlaySettings(it, stored) }
        }
      }
    }

    viewModelScope.launch { openSession() }

    viewModelScope.launch {
      engines.engineInvalidated.collect {
        // Reopening happens lazily in onResume, the next time this screen actually needs the
        // engine, not eagerly here while still backgrounded.
        dropSession()
      }
    }
  }

  /**
   * Forgets a session whose engine thread is already gone. There's nothing left to free — the
   * whole WASM instance is garbage once dereferenced, and closing the session would try to hop
   * onto the dead thread.
   */
  private fun dropSession() {
    animationJob?.cancel()
    animationJob = null
    sessionJob?.cancel()
    persister.detach()
    session = null
  }

  /**
   * Opens a fresh [PuzzleSession] against the current engine and restores whatever was last
   * persisted for [gameName]. Callable more than once: [PuzzlesApplication.onTrimMemory] can
   * recycle the shared engine while this screen is backgrounded, and [onResume] calls this again
   * to replace a session whose engine thread no longer exists.
   */
  private suspend fun openSession(restore: Boolean = true) =
    openMutex.withLock {
      if (session == null) doOpenSession(restore, retriesLeft = 1)
    }

  /**
   * @param restore whether to try the saved game; false after a save has been given up on.
   * @param retriesLeft how many times an engine failure unrelated to the save may be retried on a
   *   fresh engine before giving up and letting it propagate.
   */
  private suspend fun doOpenSession(
    restore: Boolean,
    retriesLeft: Int,
  ) {
    val startedAt = SystemClock.elapsedRealtime()

    // Timing for each stage of opening: a lost save once turned out to hinge on what a session
    // looked like part way through this, and logcat showed nothing between launch and the first
    // pause.
    fun stage(what: String) =
      Log.i(LOG_TAG, "Open #$gameIndex: $what at +${SystemClock.elapsedRealtime() - startedAt}ms")
    stage("starting (restore=$restore, retriesLeft=$retriesLeft)")
    val engine = engines.engine()
    this.engine = engine
    val name = engine.games[gameIndex].name
    gameName = name
    stage("engine ready for $name")

    // A marker still present means a previous restore never finished, and not by any failure we
    // could catch. Trying it again would most likely die the same way, forever.
    val abandoned = restore && recoveryStore.wasRestoring(name)
    if (abandoned) {
      recoveryStore.clearRestoring(name)
      val report =
        recoveryStore.record(
          name,
          "The app stopped while restoring this game.",
          "A restore marker was left behind, so the previous attempt did not finish.",
          savedGameStore.take(name),
          startedFresh = true,
        )
      _state.value = _state.value.copy(failure = report)
    }
    val saved = if (restore && !abandoned) savedGameStore.load(name) else null

    val session: PuzzleSession
    var restored = false
    var restoreAttempted = false
    var savedPrefs: ByteArray? = null
    try {
      session = engine.openGame(gameIndex)
      this.session = session
      stage("session published, game not yet restored")
      savedPrefs = prefsStore.loadPrefs(name)
      val loadedPrefs = savedPrefs
      if (loadedPrefs != null) session.loadPrefs(loadedPrefs) else session.applyPrefDefaults()
      overlaySettings(session, prefsStore.sharedSettings.first())
      session.setColourScheme(colourScheme)
      // A restore only counts if it succeeded; a corrupt or stale save must still leave us able to
      // start a fresh game rather than sitting on an empty session forever.
      if (saved != null) {
        restoreAttempted = true
        recoveryStore.markRestoring(name)
        val error = session.deserialise(saved)
        restored = error == null
        if (restored) {
          Log.i(LOG_TAG, "Restored $name from ${saved.size} bytes")
        } else {
          Log.w(LOG_TAG, "Could not restore $name from ${saved.size} bytes: $error")
          recoveryStore.clearRestoring(name)
        }
      } else {
        Log.i(
          LOG_TAG,
          "No saved game to restore for $name (restore=$restore, abandoned=$abandoned)",
        )
      }
    } catch (e: EngineFailure) {
      // Whatever the engine was doing, it can no longer be trusted, and neither can the session
      // half set up on it. Only the save is blamed if restoring it is what broke.
      this.session = null
      recover(engine, name, "opening the game", e, quarantine = restoreAttempted)
      if (!restoreAttempted && retriesLeft <= 0) throw e
      return doOpenSession(restore && !restoreAttempted, retriesLeft - 1)
    }
    stage("restore settled (restored=$restored)")
    unconfirmedRestore = name.takeIf { restored }
    restoreMayConfirm = !restored
    // Coming back to a finished puzzle only shows a solved board with nothing left to do, so a won
    // game is treated as nothing to restore: leaving gameStarted false has the sizing collector
    // below generate a fresh one. Lost games stay, as the player may want to undo the mistake.
    gameStarted = restored && session.frames.value.status != GameStatus.WON
    prefsStore.setLastPlayedName(name)
    // Only now that any restore has settled: before it the session holds no game, or not the
    // player's, and saving it would replace their save with that.
    persister.attach(session, name, saved.takeIf { restored }, savedPrefs)
    surface?.let { session.attachSurface(it, surfaceBackground) }

    _state.value =
      _state.value.copy(
        loading = false,
        game = session.game,
        keyHints = guarded(session, "reading key hints", emptyList()) { it.keyHints() },
      )

    stage("open complete")
    sessionJob =
      viewModelScope.launch {
        launch {
          var hintsForGameId = _state.value.frame.gameId
          session.frames.collect { frame ->
            _state.value = _state.value.copy(frame = frame)
            // Key hints depend on the game's parameters (Dominosa's highest number, say), which a
            // preset or custom size changes after the session was opened; a new game id is the
            // cheapest signal that any of that may have happened.
            if (frame.gameId != hintsForGameId && frame.gameId.isNotEmpty()) {
              hintsForGameId = frame.gameId
              val hints = guarded(session, "reading key hints", null) { it.keyHints() }
              if (hints != null) _state.value = _state.value.copy(keyHints = hints)
            }
          }
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
            guarded(session, "sizing the game", Unit) {
              it.resize(w, h, density)
              restoreMayConfirm = true
              confirmRestore()
              // A session deliberately opens without a game, so that a saved one can be restored
              // without first paying to generate a throwaway. When there was nothing to restore,
              // this is where the first game comes from — after the size is known, so it is
              // generated at the size it will actually be played at.
              if (!gameStarted) {
                gameStarted = true
                it.newGame()
              }
            }
          }
        }
      }
  }

  /**
   * Makes [session]'s preferences agree with the app-wide settings. Skipped when nothing differs,
   * so the echo of our own write (see [applyConfig]) costs no redraw.
   */
  private suspend fun overlaySettings(
    session: PuzzleSession,
    stored: Map<String, String>,
  ) {
    val fields = session.config(ConfigSection.PREFS).fields
    val updated = fields.overlay(stored)
    if (updated != fields) session.applyConfig(ConfigSection.PREFS, updated)
  }

  private fun act(block: suspend (PuzzleSession) -> Unit) {
    viewModelScope.launch { guarded("handling input", Unit, block) }
  }

  /** Runs [block] on the current session, if there is one; [fallback] if there is not or it fails. */
  private suspend fun <T> guarded(
    stage: String,
    fallback: T,
    block: suspend (PuzzleSession) -> T,
  ): T = session?.let { guarded(it, stage, fallback, block) } ?: fallback

  /**
   * Runs [block] on [on], turning an [EngineFailure] into recovery instead of a crash. Every route
   * into the engine from here goes through this: they run on scopes where an uncaught exception
   * ends the process, and the player's game is not worth losing to a puzzle's bug.
   */
  private suspend fun <T> guarded(
    on: PuzzleSession,
    stage: String,
    fallback: T,
    block: suspend (PuzzleSession) -> T,
  ): T =
    try {
      block(on)
    } catch (e: EngineFailure) {
      handleFailure(on, e, stage)
      fallback
    }

  /** The restored game has survived being drawn, so the save is no longer under suspicion. */
  private suspend fun confirmRestore() {
    val name = unconfirmedRestore ?: return
    unconfirmedRestore = null
    recoveryStore.clearRestoring(name)
  }

  /**
   * [failed] broke the engine after it was open. Discards it and starts again from the last save —
   * or, if that save was still being restored, from nothing, since it is likely the cause.
   * Idempotent: several callers can notice the same failure, and only the first finds [failed] still
   * current.
   */
  private fun handleFailure(
    failed: PuzzleSession,
    e: EngineFailure,
    stage: String,
  ) {
    if (session !== failed) return
    dropSession()
    val engine = engine
    val name = gameName
    val quarantine = unconfirmedRestore != null
    unconfirmedRestore = null
    viewModelScope.launch {
      // A save still being written would put back the file `recover` is about to set aside.
      persister.drain()
      if (engine != null && name != null) recover(engine, name, stage, e, quarantine)
      openSession(restore = !quarantine)
    }
  }

  /**
   * Discards the broken [engine] and records what happened.
   *
   * @param quarantine whether to set the saved game aside as well; it stays recorded in the report
   *   rather than being deleted, as it is the only way to reproduce the failure.
   */
  private suspend fun recover(
    engine: PuzzleEngine,
    name: String,
    stage: String,
    e: EngineFailure,
    quarantine: Boolean,
  ) {
    Log.e(LOG_TAG, "Engine failure while $stage", e)
    engines.discardEngine(engine)
    val save = if (quarantine) savedGameStore.take(name) else null
    recoveryStore.clearRestoring(name)
    val report =
      recoveryStore.record(
        name,
        "The puzzle engine failed while $stage.",
        describe(e),
        save,
        startedFresh = quarantine,
      )
    _state.value = _state.value.copy(failure = report)
  }

  private fun describe(e: EngineFailure): String {
    val trace = StringWriter().also { e.cause.printStackTrace(PrintWriter(it)) }
    return "$trace\nEngine stderr:\n${e.stderr}"
  }

  fun dismissFailure() {
    _state.value = _state.value.copy(failure = null)
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
        val changed = guarded(session, "animating", null) { it.tick() } ?: break
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

  fun setColourScheme(scheme: ColourScheme) {
    colourScheme = scheme
    act { it.setColourScheme(scheme) }
  }

  fun onSizeChanged(
    widthPx: Int,
    heightPx: Int,
    density: Float,
  ) {
    pendingSize.value = PendingSize(widthPx, heightPx, density)
  }

  /**
   * The UI's surface is ready, or has changed size. Frames are presented straight to it from the
   * engine thread; nothing about them passes through [state].
   */
  fun attachSurface(
    surface: Surface,
    background: Int,
  ) {
    this.surface = surface
    surfaceBackground = background
    session?.attachSurface(surface, background)
  }

  /** The UI's surface is going away; the engine must not touch it once this returns. */
  fun detachSurface() {
    surface = null
    session?.detachSurface()
  }

  fun pointer(
    action: PointerAction,
    button: PointerButton,
    x: Int,
    y: Int,
  ) = act { it.pointer(action, button, x, y, stylus = _state.value.stylusTaps) }

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

  fun toggleStylusTaps() {
    viewModelScope.launch { prefsStore.setStylusTaps(!_state.value.stylusTaps) }
  }

  suspend fun presets(): List<PresetEntry> =
    guarded("listing presets", emptyList()) { it.presets() }

  suspend fun currentPresetId(): Int = guarded("reading the preset", -1) { it.currentPresetId() }

  fun applyPreset(id: Int) = act { it.applyPreset(id) }

  suspend fun config(section: Int): ConfigForm? =
    guarded("reading the configuration", null) { it.config(section) }

  suspend fun applyConfig(
    section: Int,
    fields: List<ConfigField>,
  ): String? {
    val error = guarded("applying the configuration", null) { it.applyConfig(section, fields) }
    // Preferences are app-wide where games share them, so record the choice for every game rather
    // than leaving it in this game's blob alone.
    if (error == null && section == ConfigSection.PREFS) {
      prefsStore.setSharedSettings(fields.toSettings())
    }
    return error
  }

  suspend fun gameId(): String = guarded("reading the game id", "") { it.gameId() }

  suspend fun setGameId(id: String): String? =
    guarded("setting the game id", null) {
      it.setGameId(id)
    }

  suspend fun exportBytes(): ByteArray? = guarded("saving to a file", null) { it.serialise() }

  suspend fun importBytes(bytes: ByteArray): String? =
    guarded("loading from a file", null) { it.deserialise(bytes) }

  fun onPause() {
    session?.setPaused(true)
    // The player may be leaving for good, and gets no more chances: do not wait out the debounce.
    restoreMayConfirm = true
    persister.flush()
  }

  fun onResume() {
    viewModelScope.launch {
      // The engine may have been recycled while backgrounded (see the engineInvalidated
      // collector in init); reopen against the current one before resuming ticking.
      // The engineInvalidated notification is delivered asynchronously and can lose the race with
      // a quick return to the app, so don't rely on it having cleared the session: ask the session
      // itself whether its engine survived.
      if (session?.engineClosed == true) dropSession()
      if (session == null) openSession()
      session?.setPaused(false)
    }
  }

  override fun onCleared() {
    // Queued on the engine thread before close() below queues its own, and that thread runs its
    // queue in order, so the save sees the game before it is freed.
    restoreMayConfirm = true
    persister.flush()
    persister.detach()
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
          return GameViewModel(
            application as PuzzlesApplication,
            gameIndex,
            AppPreferences(application),
            SavedGameStore(application),
            RecoveryStore(application),
          ) as T
        }
      }
  }
}
