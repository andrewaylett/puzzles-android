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

import android.util.Log
import com.dylibso.chicory.runtime.Memory
import uk.aylett.puzzles.engine.wasm.Puzzles_Env
import uk.aylett.puzzles.engine.wasm.Puzzles_ModuleImports
import uk.aylett.puzzles.engine.wasm.Puzzles_WasiSnapshotPreview1

private const val LOG_TAG = "PuzzlesWasm"

/**
 * The mutable slot a running [Instance] calls back into.
 *
 * There is exactly one [Instance] (one WASM module can play any of the 40 puzzles), but imports
 * are wired up once, before any [uk.aylett.puzzles.engine.PuzzleSession] exists. Every call into
 * the module is serialised onto the engine thread (see `EngineThread`), so at any instant at most
 * one session is "the one making a call". Sessions can outlive their screens, so several may be
 * alive at once; each therefore binds itself here at the start of every call (see
 * `PuzzleSession.bindHooks`) rather than once at creation, or the newest session would receive
 * everyone's callbacks. It clears itself again on [PuzzleSession.close].
 */
class EngineHooks {
  var drawingSink: DrawingSink? = null
  var onActivateTimer: (() -> Unit)? = null
  var onDeactivateTimer: (() -> Unit)? = null
  var onIdsChanged: (() -> Unit)? = null

  /** What `wasmmid_default_colour` reports, packed ARGB; set by the session while it asks for a palette. */
  @Volatile var defaultColour: Int = ColourScheme.LIGHT_BACKGROUND

  private val stderrTail = ArrayDeque<String>()

  /**
   * Remembers the last few lines the module wrote to stderr. An `assert` in the C surfaces in
   * Kotlin only as "Trapped on unreachable instruction"; the assertion text, which names the
   * failed condition and line, goes to stderr just before it, so a failure report needs it.
   */
  fun recordStderr(line: String) =
    synchronized(stderrTail) {
      stderrTail.addLast(line)
      if (stderrTail.size > STDERR_TAIL_LINES) stderrTail.removeFirst()
    }

  fun recentStderr(): String = synchronized(stderrTail) { stderrTail.joinToString("\n") }

  private companion object {
    const val STDERR_TAIL_LINES = 20
  }
}

/**
 * The host side of the WASM module's imports, implementing the interfaces generated from it.
 *
 * The 28 imports and their exact signatures come from the module itself, so the build fails if
 * one is missing or changes shape; `wasm-midend.md` documents only 22 of them, the module also
 * pulls in `emscripten_notify_memory_growth` and five WASI shims. Use [toImportValues] to wire
 * it up.
 *
 * The generated interfaces carry no [com.dylibso.chicory.runtime.Instance], yet several imports
 * must read or write linear memory, and the instance cannot exist until its imports do. So
 * [memory] is bound once, straight after instantiation and before anything is run: the module's
 * `_initialize` calls the WASI shims, so it must not be run before then.
 */
class WasmImports(
  private val hooks: EngineHooks,
) : Puzzles_ModuleImports,
  Puzzles_Env,
  Puzzles_WasiSnapshotPreview1 {
  lateinit var memory: Memory

  override fun env(): Puzzles_Env = this

  override fun wasiSnapshotPreview1(): Puzzles_WasiSnapshotPreview1 = this

  // ---- env ------------------------------------------------------------------------------------

  override fun wasmmidFatalError(arg0: Int) {
    val message = memory.readCString(arg0)
    Log.e(LOG_TAG, "fatal error from puzzle engine: $message")
    // The module is a reactor with no proc_exit of its own; unwinding the current host call
    // with an exception is the only way to stop it, matching "report the message and stop".
    throw IllegalStateException("puzzle engine fatal error: $message")
  }

  override fun wasmmidGetDate64(arg0: Int) {
    // Two different high-entropy values: this seeds a fresh puzzle's RNG, and reusing one
    // clock reading for both halves would make every "new game" identical.
    memory.writeI32(arg0, System.currentTimeMillis().toInt())
    memory.writeI32(arg0 + 4, System.nanoTime().toInt())
  }

  override fun wasmmidDefaultColour(arg0: Int) {
    // The mid-end uses this to pick a border colour, and games derive their background and
    // highlight/lowlight from it (`game_mkhighlight`), so it is how a theme reaches them.
    val argb = hooks.defaultColour
    memory.writeF32(arg0, ((argb shr 16) and 0xFF) / 255f)
    memory.writeF32(arg0 + 4, ((argb shr 8) and 0xFF) / 255f)
    memory.writeF32(arg0 + 8, (argb and 0xFF) / 255f)
  }

  override fun wasmmidActivateTimer() {
    hooks.onActivateTimer?.invoke()
  }

  override fun wasmmidDeactivateTimer() {
    hooks.onDeactivateTimer?.invoke()
  }

  override fun wasmmidIdsChanged() {
    hooks.onIdsChanged?.invoke()
  }

  // The first argument of every drawing call is the C `drawing *`, unused: this front end has
  // exactly one drawing surface.

  override fun wasmmidDrawText(
    arg0: Int,
    arg1: Int,
    arg2: Int,
    arg3: Int,
    arg4: Int,
    arg5: Int,
    arg6: Int,
    arg7: Int,
  ) {
    hooks.drawingSink?.drawText(
      x = arg1,
      y = arg2,
      fontType = arg3,
      fontSize = arg4,
      align = arg5,
      colour = arg6,
      text = memory.readCString(arg7),
    )
  }

  override fun wasmmidDrawRect(
    arg0: Int,
    arg1: Int,
    arg2: Int,
    arg3: Int,
    arg4: Int,
    arg5: Int,
  ) {
    hooks.drawingSink?.drawRect(x = arg1, y = arg2, w = arg3, h = arg4, colour = arg5)
  }

  override fun wasmmidDrawLine(
    arg0: Int,
    arg1: Int,
    arg2: Int,
    arg3: Int,
    arg4: Int,
    arg5: Int,
  ) {
    hooks.drawingSink?.drawLine(x1 = arg1, y1 = arg2, x2 = arg3, y2 = arg4, colour = arg5)
  }

  override fun wasmmidDrawPolygon(
    arg0: Int,
    arg1: Int,
    arg2: Int,
    arg3: Int,
    arg4: Int,
  ) {
    val coords = IntArray(arg2 * 2) { i -> memory.readInt(arg1 + i * 4) }
    hooks.drawingSink?.drawPolygon(coords = coords, fillColour = arg3, outlineColour = arg4)
  }

  override fun wasmmidDrawCircle(
    arg0: Int,
    arg1: Int,
    arg2: Int,
    arg3: Int,
    arg4: Int,
    arg5: Int,
  ) {
    hooks.drawingSink?.drawCircle(
      cx = arg1,
      cy = arg2,
      radius = arg3,
      fillColour = arg4,
      outlineColour = arg5,
    )
  }

  override fun wasmmidDrawUpdate(
    arg0: Int,
    arg1: Int,
    arg2: Int,
    arg3: Int,
    arg4: Int,
  ) {
    hooks.drawingSink?.drawUpdate(x = arg1, y = arg2, w = arg3, h = arg4)
  }

  override fun wasmmidClip(
    arg0: Int,
    arg1: Int,
    arg2: Int,
    arg3: Int,
    arg4: Int,
  ) {
    hooks.drawingSink?.clip(x = arg1, y = arg2, w = arg3, h = arg4)
  }

  override fun wasmmidUnclip(arg0: Int) {
    hooks.drawingSink?.unclip()
  }

  override fun wasmmidStartDraw(arg0: Int) {
    hooks.drawingSink?.startDraw()
  }

  override fun wasmmidEndDraw(arg0: Int) {
    hooks.drawingSink?.endDraw()
  }

  override fun wasmmidStatusBar(
    arg0: Int,
    arg1: Int,
  ) {
    hooks.drawingSink?.statusBar(memory.readCString(arg1))
  }

  override fun wasmmidBlitterNew(
    arg0: Int,
    arg1: Int,
    arg2: Int,
  ): Int = hooks.drawingSink?.blitterNew(arg1, arg2) ?: 0

  override fun wasmmidBlitterFree(
    arg0: Int,
    arg1: Int,
  ) {
    hooks.drawingSink?.blitterFree(arg1)
  }

  override fun wasmmidBlitterSave(
    arg0: Int,
    arg1: Int,
    arg2: Int,
    arg3: Int,
  ) {
    hooks.drawingSink?.blitterSave(arg1, arg2, arg3)
  }

  override fun wasmmidBlitterLoad(
    arg0: Int,
    arg1: Int,
    arg2: Int,
    arg3: Int,
  ) {
    hooks.drawingSink?.blitterLoad(arg1, arg2, arg3)
  }

  override fun wasmmidDrawThickLine(
    arg0: Int,
    arg1: Float,
    arg2: Float,
    arg3: Float,
    arg4: Float,
    arg5: Float,
    arg6: Int,
  ) {
    hooks.drawingSink?.drawThickLine(
      thickness = arg1,
      x1 = arg2,
      y1 = arg3,
      x2 = arg4,
      y2 = arg5,
      colour = arg6,
    )
  }

  /** Undocumented but required to instantiate; nothing here caches a view of memory. */
  override fun emscriptenNotifyMemoryGrowth(arg0: Int) = Unit

  // ---- wasi_snapshot_preview1: shims libc pulls in; none do real I/O ---------------------------

  override fun environSizesGet(
    arg0: Int,
    arg1: Int,
  ): Int {
    memory.writeI32(arg0, 0)
    memory.writeI32(arg1, 0)
    return 0
  }

  override fun environGet(
    arg0: Int,
    arg1: Int,
  ): Int = 0

  override fun fdClose(arg0: Int): Int = 0

  override fun fdSeek(
    arg0: Int,
    arg1: Long,
    arg2: Int,
    arg3: Int,
  ): Int {
    memory.writeI32(arg3, 0)
    return 0
  }

  /** Decodes the iovecs libc hands us and forwards the text to logcat rather than dropping it. */
  override fun fdWrite(
    arg0: Int,
    arg1: Int,
    arg2: Int,
    arg3: Int,
  ): Int {
    val text = StringBuilder()
    var total = 0
    for (i in 0 until arg2) {
      val entry = arg1 + i * 8
      val bufPtr = memory.readInt(entry)
      val bufLen = memory.readInt(entry + 4)
      if (bufLen > 0) {
        text.append(String(memory.readBytes(bufPtr, bufLen), Charsets.UTF_8))
      }
      total += bufLen
    }
    memory.writeI32(arg3, total)
    if (text.isNotEmpty()) {
      val message = text.toString().trimEnd('\n')
      if (arg0 == 2) {
        Log.w(LOG_TAG, message)
        hooks.recordStderr(message)
      } else {
        Log.i(LOG_TAG, message)
      }
    }
    return 0
  }
}
