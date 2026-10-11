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
package uk.aylett.puzzles

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uk.aylett.puzzles.data.AppPreferences
import uk.aylett.puzzles.data.CustomPresetStore
import uk.aylett.puzzles.engine.PuzzleEngine
import uk.aylett.puzzles.pool.AndroidPowerState
import uk.aylett.puzzles.pool.PoolKey
import uk.aylett.puzzles.pool.PoolPolicy
import uk.aylett.puzzles.pool.PoolScheduler
import uk.aylett.puzzles.pool.PuzzlePool
import uk.aylett.puzzles.pool.Refill
import java.io.File

private const val POOL_REQUEST_DEBOUNCE_MS = 5_000L

/**
 * What a ViewModel needs from the process-wide engine owner. An interface so that ViewModels can
 * be tested without instantiating the WASM module or an [Application].
 */
interface EngineProvider {
  /** Emits whenever the engine previously returned by [engine] has been closed. */
  val engineInvalidated: SharedFlow<Unit>

  suspend fun engine(): PuzzleEngine

  /**
   * A scope that outlives every screen. Work that must finish even if the screen that asked for it
   * is destroyed — writing a save, chiefly — belongs here rather than in a ViewModel's scope.
   */
  val applicationScope: CoroutineScope

  /**
   * Throws away [failed] after it broke (see [uk.aylett.puzzles.engine.EngineFailure]); the next
   * [engine] call builds a new one. Unlike [engineInvalidated] this does not notify anyone: the
   * caller is already replacing its own session and a notification would race with that.
   */
  suspend fun discardEngine(failed: PuzzleEngine)
}

/**
 * Owns the single [PuzzleEngine] for the process.
 *
 * Creating the engine loads and instantiates the WASM module, which is too expensive to repeat
 * per screen: it is shared by every ViewModel that needs it, which suspend on [engine] rather than
 * constructing their own. It is also discarded and rebuilt across the app's lifetime, not just the
 * process's — see [onTrimMemory].
 */
class PuzzlesApplication :
  Application(),
  EngineProvider {
  override val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /**
   * Process-wide because a DataStore must have exactly one instance per file, and every game screen
   * has its own ViewModel.
   */
  val customPresetStore: CustomPresetStore by lazy {
    CustomPresetStore(File(filesDir, "custom_presets.json"), applicationScope) { game, preset ->
      // Dropping off the list means nothing will ask for these games again.
      puzzlePool.discard(PoolKey(game, preset.fields))
    }
  }

  private val poolScheduler by lazy { PoolScheduler(this) }

  /**
   * Games generated ahead of time. Excluded from backup, as it is bulk that can be regenerated and
   * says nothing about the player. Process-wide because the engine thread reads it while the worker
   * writes it, and they must agree on the one lock.
   */
  val puzzlePool: PuzzlePool by lazy {
    PuzzlePool(
      File(noBackupFilesDir, "pregenerated"),
      onEmptied = { key ->
        // Decided now, not at start-up: the player may have plugged in or unplugged since.
        when (PoolPolicy.refillAfterEmptied(AndroidPowerState(this))) {
          Refill.FILL_ALL -> poolScheduler.requestFill()
          Refill.TOP_UP_ONE -> poolScheduler.requestTopUp(key)
          Refill.NONE -> Unit
        }
      },
      // A game had to be generated while the player waited, e.g. on a fresh install. Ask for a
      // fill, which waits for the charger like any other; nothing is made on battery for this.
      onMissed = { poolScheduler.requestFill() },
    )
  }

  @OptIn(FlowPreview::class)
  override fun onCreate() {
    super.onCreate()
    poolScheduler.schedulePeriodicFill()
    // The targets depend on which games are favourites and on the custom types in use, so a change
    // to either may mean pools to fill. The debounce lets a burst of edits settle into one request.
    applicationScope.launch {
      combine(AppPreferences(this@PuzzlesApplication).favourites, customPresetStore.all) { f, c ->
        f to c
      }.distinctUntilChanged()
        .debounce(POOL_REQUEST_DEBOUNCE_MS)
        .collect { poolScheduler.requestFill() }
    }
  }

  private val engineMutex = Mutex()
  private var engineDeferred: Deferred<PuzzleEngine>? = null

  // Every game screen keeps its own PuzzleSession alive for as long as it's on screen, so this
  // is the only way they learn that the engine underneath them is gone. There is no "new engine
  // ready" event, only "yours is stale" — recreating a session is only worth doing once a screen
  // actually needs the engine again (GameViewModel.onResume), not eagerly here.
  private val _engineInvalidated = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  override val engineInvalidated: SharedFlow<Unit> = _engineInvalidated.asSharedFlow()

  /**
   * Returns the process's [PuzzleEngine], creating it lazily on first use or after
   * [onTrimMemory] discarded a previous instance.
   */
  override suspend fun engine(): PuzzleEngine {
    val deferred =
      engineMutex.withLock {
        engineDeferred
          ?: applicationScope
            .async {
              PuzzleEngine.create(this@PuzzlesApplication).also { it.newGameSource = puzzlePool }
            }.also { engineDeferred = it }
      }
    return deferred.await()
  }

  override suspend fun discardEngine(failed: PuzzleEngine) {
    // Only if it is still the current one: another screen may already have replaced it, and a
    // healthy engine is not ours to close.
    val stale =
      engineMutex.withLock {
        val current = engineDeferred
        if (current != null && current.isCompleted && current.await() === failed) {
          engineDeferred = null
          failed
        } else {
          null
        }
      }
    runCatching { stale?.close() }
      .onFailure { Log.w("PuzzlesApplication", "Discarding the failed engine failed", it) }
  }

  /**
   * Discards the WASM instance and its dedicated thread once no activity of ours is visible.
   *
   * [ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN] only fires once every activity has stopped, and
   * every [uk.aylett.puzzles.ui.game.GameViewModel] flushes its save in `onPause`, which always
   * runs before that, having saved every change since as it happened. So nothing is left to write
   * by the time the engine goes, and it (a few MB of WASM linear memory plus a parked thread) is
   * pure waste to keep around while backgrounded. [engine] rebuilds it the next time a screen actually needs it.
   *
   * The comparison is `<`, not `!=`: levels are ordered by severity, and the system may report a
   * later one (`TRIM_MEMORY_BACKGROUND` and up) without ever delivering `UI_HIDDEN` itself. Each
   * of those implies the UI is hidden too, and the engine is just as safe to drop. The
   * `RUNNING_*` levels sit below `UI_HIDDEN` and mean we are still visible, so they are ignored.
   */
  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    if (level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return
    applicationScope.launch {
      val stale = engineMutex.withLock { engineDeferred.also { engineDeferred = null } }
      _engineInvalidated.emit(Unit)
      // If creating that engine failed there is nothing to close, and rethrowing here would take
      // the whole process down while it is backgrounded, for no benefit: the next engine() call
      // builds a fresh one regardless.
      runCatching { stale?.await()?.close() }
        .onFailure { Log.w("PuzzlesApplication", "Discarding the stale engine failed", it) }
    }
  }
}
