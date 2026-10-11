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

import android.content.Context
import com.dylibso.chicory.runtime.Instance
import uk.aylett.puzzles.engine.wasm.PuzzlesModule
import uk.aylett.puzzles.engine.wasm.Puzzles_ModuleExports

/**
 * Owns the one WASM instance for the process and the 40-entry game list it carries.
 *
 * There is exactly one [com.dylibso.chicory.runtime.Instance] because one combined module covers
 * every puzzle (`wasmmid_game_count`/`wasmmid_game_at`); [openGame] hands out lightweight
 * [PuzzleSession]s that share it rather than each creating their own module.
 */
class PuzzleEngine private constructor(
  private val engineThread: EngineThread,
  private val hooks: EngineHooks,
  private val midend: WasmMidend,
  val games: List<GameInfo>,
) : AutoCloseable {
  companion object {
    /**
     * Instantiates the module and runs its start-up. Needs no device, so a JVM test can reach it;
     * must run on the thread that will own the instance.
     */
    internal fun instantiate(hooks: EngineHooks): WasmMidend {
      val imports = WasmImports(hooks)
      val instance: Instance =
        Instance
          .builder(PuzzlesModule.load())
          .withImportValues(imports.toImportValues())
          .withMachineFactory(PuzzlesModule::create)
          .withStart(false)
          // Sets up globals and data segments; it does not run `_initialize`, which is below.
          .withInitialize(true)
          .build()
      imports.memory = instance.memory()
      val exports = Puzzles_ModuleExports(instance)
      // The libc start-up this runs calls the WASI shims, which need `imports.memory`.
      exports._initialize()
      return WasmMidend(exports)
    }

    /**
     * Loads and instantiates the module, then lists its games. Runs entirely on the new engine
     * thread: nothing here should race a later call from that same thread.
     */
    suspend fun create(context: Context): PuzzleEngine {
      val engineThread = EngineThread()
      val hooks = EngineHooks()
      engineThread.diagnostics = hooks::recentStderr
      val midend = engineThread.run { instantiate(hooks) }
      val games =
        engineThread.run {
          val count = midend.gameCount()
          (0 until count).map { index ->
            val game = midend.gameAt(index)
            GameInfo(
              index = index,
              name = midend.gameName(game),
              canSolve = midend.gameCanSolve(game),
              canConfigure = midend.gameCanConfigure(game),
              canFormatAsText = midend.gameCanFormatAsTextEver(game),
            )
          }
        }
      return PuzzleEngine(engineThread, hooks, midend, games)
    }
  }

  /** Creates a fresh mid-end for `games[index]`. The caller owns the returned session. */
  suspend fun openGame(index: Int): PuzzleSession {
    val me = engineThread.run { midend.midendNew(index) }
    return PuzzleSession(engineThread, hooks, midend, me, games[index])
  }

  /** Shuts down the engine thread; any [PuzzleSession]s still open become unusable. */
  override fun close() {
    engineThread.close()
  }
}
