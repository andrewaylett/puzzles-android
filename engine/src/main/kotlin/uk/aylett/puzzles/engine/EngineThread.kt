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

import kotlinx.coroutines.CancellationException
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

  /**
   * Supplies extra context for an [EngineFailure], set once the engine has somewhere to get it
   * from. Read on the engine thread, straight after the failure, so nothing else has run since.
   */
  @Volatile var diagnostics: () -> String = { "" }

  /**
   * Every call into WASM comes through here, so this is the one place a failure inside the module
   * is recognised. A trap (a failed `assert` in the C, say) is not an error return: it escapes as
   * an exception, and left alone it ends the process from whichever coroutine happened to be
   * calling. It is wrapped as an [EngineFailure] so callers can tell "the engine broke" from their
   * own bugs, and because after one the instance's heap and the mid-end that trapped are suspect:
   * the engine has to be discarded, not just the call retried.
   */
  suspend fun <T> run(block: suspend () -> T): T =
    withContext(dispatcher) {
      try {
        block()
      } catch (e: CancellationException) {
        throw e
      } catch (e: EngineFailure) {
        throw e
      } catch (e: Throwable) {
        throw EngineFailure(e, diagnostics())
      }
    }

  /**
   * True once [close] has run. Anything holding a session can use this to notice that its engine
   * has been recycled without having to wait for a notification, which may not have been
   * delivered yet.
   */
  val isClosed: Boolean get() = executor.isShutdown

  override fun close() {
    scope.cancel()
    dispatcher.close()
  }
}
