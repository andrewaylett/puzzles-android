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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uk.aylett.puzzles.engine.PuzzleEngine

/**
 * Owns the single [PuzzleEngine] for the process.
 *
 * Creating the engine loads and instantiates the WASM module, which is too expensive to repeat
 * per screen: it is shared by every ViewModel that needs it, which suspend on [engine] rather than
 * constructing their own. It is also discarded and rebuilt across the app's lifetime, not just the
 * process's — see [onTrimMemory].
 */
class PuzzlesApplication : Application() {
  val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val engineMutex = Mutex()
  private var engineDeferred: Deferred<PuzzleEngine>? = null

  // Every game screen keeps its own PuzzleSession alive for as long as it's on screen, so this
  // is the only way they learn that the engine underneath them is gone. There is no "new engine
  // ready" event, only "yours is stale" — recreating a session is only worth doing once a screen
  // actually needs the engine again (GameViewModel.onResume), not eagerly here.
  private val _engineInvalidated = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  val engineInvalidated: SharedFlow<Unit> = _engineInvalidated.asSharedFlow()

  /**
   * Returns the process's [PuzzleEngine], creating it lazily on first use or after
   * [onTrimMemory] discarded a previous instance.
   */
  suspend fun engine(): PuzzleEngine {
    val deferred =
      engineMutex.withLock {
        engineDeferred
          ?: applicationScope.async { PuzzleEngine.create(this@PuzzlesApplication) }
            .also { engineDeferred = it }
      }
    return deferred.await()
  }

  /**
   * Discards the WASM instance and its dedicated thread once no activity of ours is visible.
   *
   * [ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN] only fires once every activity has stopped, and
   * every [uk.aylett.puzzles.ui.game.GameViewModel] persists its session's state in `onPause`,
   * which always runs before that — so by the time this fires there is nothing left to save, and
   * the engine (a few MB of WASM linear memory plus a parked thread) is pure waste to keep
   * around while backgrounded. [engine] rebuilds it the next time a screen actually needs it.
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
      stale?.await()?.close()
    }
  }
}
