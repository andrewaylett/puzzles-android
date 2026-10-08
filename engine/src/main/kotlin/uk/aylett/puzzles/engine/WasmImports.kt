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
import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Memory
import com.dylibso.chicory.runtime.WasmFunctionHandle
import com.dylibso.chicory.wasm.types.FunctionType
import com.dylibso.chicory.wasm.types.ValType

private const val LOG_TAG = "PuzzlesWasm"

/** A `void` WASM export/import returns `null` from Chicory; a non-void one returns `long[N]`. */
private val VOID_RESULT: LongArray? = null

/**
 * The mutable slot a running [Instance] calls back into.
 *
 * There is exactly one [Instance] (one WASM module can play any of the 40 puzzles), but imports
 * are wired up once, before any [uk.aylett.puzzles.engine.PuzzleSession] exists. Every call into
 * the module is serialised onto the engine thread (see `EngineThread`), so at any instant at most
 * one session is "the one making a call" — that session installs itself here for the duration and
 * clears itself again on [PuzzleSession.close]; there is no need for per-call multiplexing.
 */
class EngineHooks {
  var drawingSink: DrawingSink? = null
  var onActivateTimer: (() -> Unit)? = null
  var onDeactivateTimer: (() -> Unit)? = null
  var onIdsChanged: (() -> Unit)? = null

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
 * Builds the [ImportValues] the WASM module requires to instantiate.
 *
 * The 28 imports and their exact signatures come from disassembling `puzzles-midend.wasm` (see
 * `wasm-midend.md` and the repository's own `AGENTS.md`), not from the upstream documentation
 * alone: the module also pulls in `emscripten_notify_memory_growth` and five WASI shims that the
 * mid-end docs don't mention, and the module fails to instantiate if any import is missing.
 */
class WasmImports(
  private val hooks: EngineHooks,
) {
  fun build(): ImportValues =
    ImportValues
      .builder()
      .addFunction(
        *envFunctions(),
        *wasiFunctions(),
      ).build()

  private fun envFunctions(): Array<HostFunction> =
    arrayOf(
      env("wasmmid_fatal_error", i(1)) { instance, args ->
        val message = instance.memory().readCString(args[0].toInt())
        Log.e(LOG_TAG, "fatal error from puzzle engine: $message")
        // The module is a reactor with no proc_exit of its own; unwinding the current host call
        // with an exception is the only way to stop it, matching "report the message and stop".
        throw IllegalStateException("puzzle engine fatal error: $message")
      },
      env("wasmmid_get_date_64", i(1)) { instance, args ->
        // Two different high-entropy values: this seeds a fresh puzzle's RNG, and reusing one
        // clock reading for both halves would make every "new game" identical.
        val memory = instance.memory()
        val ptr = args[0].toInt()
        memory.writeI32(ptr, System.currentTimeMillis().toInt())
        memory.writeI32(ptr + 4, System.nanoTime().toInt())
        VOID_RESULT
      },
      env("wasmmid_default_colour", i(1)) { instance, args ->
        val memory = instance.memory()
        val ptr = args[0].toInt()
        // A light neutral grey; the mid-end uses this only to pick a border colour for the parts
        // of the window it doesn't draw into itself.
        memory.writeF32(ptr, 0.9f)
        memory.writeF32(ptr + 4, 0.9f)
        memory.writeF32(ptr + 8, 0.9f)
        VOID_RESULT
      },
      env("wasmmid_activate_timer", i()) { _, _ ->
        hooks.onActivateTimer?.invoke()
        VOID_RESULT
      },
      env("wasmmid_deactivate_timer", i()) { _, _ ->
        hooks.onDeactivateTimer?.invoke()
        VOID_RESULT
      },
      env("wasmmid_ids_changed", i()) { _, _ ->
        hooks.onIdsChanged?.invoke()
        VOID_RESULT
      },
      env("wasmmid_draw_text", i(8)) { instance, args ->
        // args[0] is the C `drawing *`, unused: this front end has exactly one drawing surface.
        val text = instance.memory().readCString(args[7].toInt())
        hooks.drawingSink?.drawText(
          x = args[1].toInt(),
          y = args[2].toInt(),
          fontType = args[3].toInt(),
          fontSize = args[4].toInt(),
          align = args[5].toInt(),
          colour = args[6].toInt(),
          text = text,
        )
        VOID_RESULT
      },
      env("wasmmid_draw_rect", i(6)) { _, args ->
        hooks.drawingSink?.drawRect(
          x = args[1].toInt(),
          y = args[2].toInt(),
          w = args[3].toInt(),
          h = args[4].toInt(),
          colour = args[5].toInt(),
        )
        VOID_RESULT
      },
      env("wasmmid_draw_line", i(6)) { _, args ->
        hooks.drawingSink?.drawLine(
          x1 = args[1].toInt(),
          y1 = args[2].toInt(),
          x2 = args[3].toInt(),
          y2 = args[4].toInt(),
          colour = args[5].toInt(),
        )
        VOID_RESULT
      },
      env("wasmmid_draw_polygon", i(5)) { instance, args ->
        val memory = instance.memory()
        val coordsPtr = args[1].toInt()
        val nPoints = args[2].toInt()
        val coords = IntArray(nPoints * 2) { i -> memory.readInt(coordsPtr + i * 4) }
        hooks.drawingSink?.drawPolygon(
          coords = coords,
          fillColour = args[3].toInt(),
          outlineColour = args[4].toInt(),
        )
        VOID_RESULT
      },
      env("wasmmid_draw_circle", i(6)) { _, args ->
        hooks.drawingSink?.drawCircle(
          cx = args[1].toInt(),
          cy = args[2].toInt(),
          radius = args[3].toInt(),
          fillColour = args[4].toInt(),
          outlineColour = args[5].toInt(),
        )
        VOID_RESULT
      },
      env("wasmmid_draw_update", i(5)) { _, args ->
        hooks.drawingSink?.drawUpdate(
          x = args[1].toInt(),
          y = args[2].toInt(),
          w = args[3].toInt(),
          h = args[4].toInt(),
        )
        VOID_RESULT
      },
      env("wasmmid_clip", i(5)) { _, args ->
        hooks.drawingSink?.clip(
          x = args[1].toInt(),
          y = args[2].toInt(),
          w = args[3].toInt(),
          h = args[4].toInt(),
        )
        VOID_RESULT
      },
      env("wasmmid_unclip", i(1)) { _, _ ->
        hooks.drawingSink?.unclip()
        VOID_RESULT
      },
      env("wasmmid_start_draw", i(1)) { _, _ ->
        hooks.drawingSink?.startDraw()
        VOID_RESULT
      },
      env("wasmmid_end_draw", i(1)) { _, _ ->
        hooks.drawingSink?.endDraw()
        VOID_RESULT
      },
      env("wasmmid_status_bar", i(2)) { instance, args ->
        val text = instance.memory().readCString(args[1].toInt())
        hooks.drawingSink?.statusBar(text)
        VOID_RESULT
      },
      HostFunction(
        "env",
        "wasmmid_blitter_new",
        FunctionType.of(arrayOf(I32, I32, I32), arrayOf(I32)),
        WasmFunctionHandle { _, args ->
          val handle = hooks.drawingSink?.blitterNew(args[1].toInt(), args[2].toInt()) ?: 0
          longArrayOf(handle.toLong())
        },
      ),
      env("wasmmid_blitter_free", i(2)) { _, args ->
        hooks.drawingSink?.blitterFree(args[1].toInt())
        VOID_RESULT
      },
      env("wasmmid_blitter_save", i(4)) { _, args ->
        hooks.drawingSink?.blitterSave(args[1].toInt(), args[2].toInt(), args[3].toInt())
        VOID_RESULT
      },
      env("wasmmid_blitter_load", i(4)) { _, args ->
        hooks.drawingSink?.blitterLoad(args[1].toInt(), args[2].toInt(), args[3].toInt())
        VOID_RESULT
      },
      HostFunction(
        "env",
        "wasmmid_draw_thick_line",
        FunctionType.of(arrayOf(I32, F32, F32, F32, F32, F32, I32), emptyArray()),
        WasmFunctionHandle { _, args ->
          hooks.drawingSink?.drawThickLine(
            thickness = Float.fromBits(args[1].toInt()),
            x1 = Float.fromBits(args[2].toInt()),
            y1 = Float.fromBits(args[3].toInt()),
            x2 = Float.fromBits(args[4].toInt()),
            y2 = Float.fromBits(args[5].toInt()),
            colour = args[6].toInt(),
          )
          VOID_RESULT
        },
      ),
      env("emscripten_notify_memory_growth", i(1)) { _, _ -> VOID_RESULT },
    )

  /** WASI shims libc pulls in, undocumented by `wasm-midend.md`. None do real I/O. */
  private fun wasiFunctions(): Array<HostFunction> =
    arrayOf(
      wasi("environ_sizes_get", i(2), i(1)) { instance, args ->
        val memory = instance.memory()
        memory.writeI32(args[0].toInt(), 0)
        memory.writeI32(args[1].toInt(), 0)
        longArrayOf(0)
      },
      wasi("environ_get", i(2), i(1)) { _, _ -> longArrayOf(0) },
      wasi("fd_close", i(1), i(1)) { _, _ -> longArrayOf(0) },
      wasi("fd_seek", listOf(I32, I64, I32, I32), i(1)) { instance, args ->
        instance.memory().writeI32(args[3].toInt(), 0)
        longArrayOf(0)
      },
      wasi("fd_write", i(4), i(1)) { instance, args -> fdWrite(instance.memory(), args) },
    )

  /** Decodes the iovecs libc hands us and forwards the text to logcat rather than dropping it. */
  private fun fdWrite(
    memory: Memory,
    args: LongArray,
  ): LongArray {
    val fd = args[0].toInt()
    val iovsPtr = args[1].toInt()
    val iovsLen = args[2].toInt()
    val nwrittenPtr = args[3].toInt()
    val text = StringBuilder()
    var total = 0
    for (i in 0 until iovsLen) {
      val entry = iovsPtr + i * 8
      val bufPtr = memory.readInt(entry)
      val bufLen = memory.readInt(entry + 4)
      if (bufLen > 0) {
        text.append(String(memory.readBytes(bufPtr, bufLen), Charsets.UTF_8))
      }
      total += bufLen
    }
    memory.writeI32(nwrittenPtr, total)
    if (text.isNotEmpty()) {
      val message = text.toString().trimEnd('\n')
      if (fd == 2) {
        Log.w(LOG_TAG, message)
        hooks.recordStderr(message)
      } else {
        Log.i(LOG_TAG, message)
      }
    }
    return longArrayOf(0)
  }

  private fun env(
    name: String,
    params: List<ValType>,
    body: WasmFunctionHandle,
  ) = HostFunction("env", name, FunctionType.of(params.toTypedArray(), emptyArray()), body)

  private fun wasi(
    name: String,
    params: List<ValType>,
    returns: List<ValType>,
    body: WasmFunctionHandle,
  ) = HostFunction(
    "wasi_snapshot_preview1",
    name,
    FunctionType.of(params.toTypedArray(), returns.toTypedArray()),
    body,
  )

  private fun i(count: Int = 0): List<ValType> = List(count) { I32 }

  private companion object {
    val I32: ValType = ValType.I32
    val I64: ValType = ValType.I64
    val F32: ValType = ValType.F32
  }
}
