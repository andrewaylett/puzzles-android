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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uk.aylett.puzzles.engine.PuzzleEngine

/**
 * Owns the single [PuzzleEngine] for the process.
 *
 * Creating the engine loads and instantiates the WASM module, which is too expensive to repeat
 * per screen or survive only as long as one activity: it lives for the process, and every
 * ViewModel that needs it suspends on [engineDeferred] rather than constructing its own.
 */
class PuzzlesApplication : Application() {
  /** Outlives any single activity; the engine itself is closed only when the process dies. */
  val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val engineDeferred = CompletableDeferred<PuzzleEngine>()

  override fun onCreate() {
    super.onCreate()
    applicationScope.launch {
      engineDeferred.complete(PuzzleEngine.create(this@PuzzlesApplication))
    }
  }

  suspend fun engine(): PuzzleEngine = engineDeferred.await()
}
