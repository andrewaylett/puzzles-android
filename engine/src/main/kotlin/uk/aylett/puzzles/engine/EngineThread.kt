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
package uk.aylett.puzzles.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * The single dedicated thread the WASM [com.dylibso.chicory.runtime.Instance] lives on.
 *
 * The instance is not reentrant and some calls (`midend_new_game` on a hard puzzle) block for
 * seconds, so every touch of it — input events, redraws, the timer tick, new-game generation —
 * must be serialised through here. Nothing outside this class may call into WASM.
 */
class EngineThread : AutoCloseable {
  private val executor =
    Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "puzzle-engine") }

  val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()

  /** For periodic work (the timer tick) that must still run serialised with everything else. */
  val scope: CoroutineScope = CoroutineScope(dispatcher + SupervisorJob())

  suspend fun <T> run(block: suspend () -> T): T = withContext(dispatcher) { block() }

  override fun close() {
    scope.cancel()
    dispatcher.close()
  }
}
