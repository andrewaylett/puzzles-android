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

import com.dylibso.chicory.runtime.Memory
import uk.aylett.puzzles.engine.wasm.Puzzles_ModuleExports

/**
 * A thin, literal wrapper over the mid-end's WASM exports.
 *
 * This class does no policy: it decodes/encodes the C ABI (pointers, out-params, module-owned
 * strings) and nothing more. One [WasmMidend] serves the whole instance — `malloc`/`free` and
 * the game-listing exports (`wasmmid_game_count`/`wasmmid_game_at`) aren't tied to a particular
 * `midend *` — but every mid-end-scoped export still takes its `me` handle as an explicit
 * parameter, exactly as the C API does, so a caller juggling several handles never has to wonder
 * which one a call applies to.
 *
 * The exports themselves are bound by the build-time generated [Puzzles_ModuleExports], which
 * resolves every one by name when it is constructed (so a module missing an export fails at
 * start-up, not on the first call that needs it) and does the `long[]` marshalling, including the
 * raw-bits encoding of `float` and `double` arguments. What is left here is the part the module's
 * signatures can't express: which `int`s are pointers, which strings are ours to free.
 *
 * Every value crosses as the 32-bit C ABI: pointers, `int`, `bool` and `float` pass directly, and
 * strings are NUL-terminated bytes in linear memory. Anything the module allocates (`midend *`,
 * `char *`, `config_item *`) is owned by the module and must be released through the matching
 * export (`free`, `free_cfg`, `free_keys`), never a host-side allocator, which would hand the
 * module's allocator a pointer it never issued. Hence the explicit `free*` methods below.
 *
 * Every method here must run on the engine thread. The [Memory] object is held for the life of the
 * instance: growth happens inside it, so it stays valid. What must not be kept across a call into
 * WASM is anything derived from it — a `ByteBuffer`, a view, a raw array — as growth can replace
 * the storage underneath those.
 */
class WasmMidend(
  private val exports: Puzzles_ModuleExports,
) {
  private val memory: Memory = exports.memory()

  private fun Int.toBool(): Boolean = this != 0

  // ---- Allocation -----------------------------------------------------------------------------

  fun malloc(size: Int): Int = exports.malloc(size)

  fun free(ptr: Int) {
    if (ptr != 0) exports.free(ptr)
  }

  fun freeCfg(ptr: Int) {
    if (ptr != 0) exports.freeCfg(ptr)
  }

  fun freeKeys(
    ptr: Int,
    n: Int,
  ) {
    if (ptr != 0) exports.freeKeys(ptr, n)
  }

  /** Reads a NUL-terminated string without taking ownership of [ptr]. */
  private fun readCString(ptr: Int): String = if (ptr == 0) "" else memory.readCString(ptr)

  /**
   * Reads a NUL-terminated string the module handed over as a fresh allocation (an error message,
   * a `dupstr`'d id) and frees it immediately afterwards, per the "anything the module allocates
   * is owned by the module" rule. Accessors into a still-alive struct (config items, preset menu
   * entries, key labels, the game name) must *not* go through this — freeing those would corrupt
   * the struct they point into.
   */
  private fun readOwnedCString(ptr: Int): String? {
    if (ptr == 0) return null
    val s = memory.readCString(ptr)
    free(ptr)
    return s
  }

  /**
   * Reads a string the module still owns, without freeing it.
   *
   * Upstream signals ownership through constness, and it is worth following exactly: `char *`
   * returns are freshly allocated and ours to free, while `const char *` returns — every error
   * message from `midend_solve`, `midend_game_id`, `midend_deserialise`, `midend_set_config`,
   * `midend_load_prefs` and `identify_game` (though not the name it returns through its
   * out-parameter, which is allocated) — are static string literals in the module's data
   * segment. Passing one of those to `free` hands the allocator a pointer it never issued,
   * corrupting the heap in a way that surfaces much later and nowhere near the cause.
   */
  private fun readBorrowedCString(ptr: Int): String? {
    if (ptr == 0) return null
    return memory.readCString(ptr)
  }

  /** Allocates and writes a NUL-terminated copy of [s]; the caller owns the result until freed. */
  private fun writeCString(s: String): Int {
    val bytes = s.toByteArray(Charsets.UTF_8)
    val ptr = malloc(bytes.size + 1)
    memory.writeCString(ptr, s)
    return ptr
  }

  /**
   * Runs [block] with a scratch buffer of [n] `int`s, freeing it however [block] exits.
   *
   * Several exports return values through `int *` out-parameters (`midend_size`,
   * `midend_colours`, `wasmmid_serialise`, `midend_get_cursor_location`). The pointer has to be
   * into the module's own memory, so the buffer comes from its `malloc` export, and the results
   * are read back out of it before it is freed.
   */
  private inline fun <T> withIntScratch(
    n: Int,
    block: (Int) -> T,
  ): T {
    val ptr = malloc(4 * n)
    try {
      return block(ptr)
    } finally {
      free(ptr)
    }
  }

  // ---- Game listing (combined module only) -----------------------------------------------------

  fun gameCount(): Int = exports.wasmmidGameCount()

  fun gameAt(index: Int): Int = exports.wasmmidGameAt(index)

  fun gameName(game: Int): String = readCString(exports.wasmmidGameName(game))

  fun gameCanSolve(game: Int): Boolean = exports.wasmmidGameCanSolve(game).toBool()

  fun gameCanConfigure(game: Int): Boolean = exports.wasmmidGameCanConfigure(game).toBool()

  fun gameCanFormatAsTextEver(game: Int): Boolean =
    exports.wasmmidGameCanFormatAsTextEver(game).toBool()

  // ---- Lifecycle --------------------------------------------------------------------------------

  fun midendNew(gameIndex: Int): Int = exports.wasmmidMidendNew(gameIndex)

  fun midendFree(me: Int) = exports.midendFree(me)

  fun newGame(me: Int) = exports.midendNewGame(me)

  fun restartGame(me: Int) = exports.midendRestartGame(me)

  fun stopAnim(me: Int) = exports.midendStopAnim(me)

  fun requestIdChanges(me: Int) = exports.wasmmidMidendRequestIdChanges(me)

  fun whichGame(me: Int): Int = exports.midendWhichGame(me)

  // ---- Params -------------------------------------------------------------------------------

  fun setParams(
    me: Int,
    params: Int,
  ) = exports.midendSetParams(me, params)

  fun getParams(me: Int): Int = exports.midendGetParams(me)

  /**
   * Sizes the game to fit [width]x[height] units, returning the size the mid-end will actually
   * draw at, in the same units.
   *
   * [width]/[height] are *not* physical pixels: fixed-pixel constants inside several back ends
   * (untangle's grab-handle `CIRCLE_RADIUS`, for one) don't scale with tile size, so handing the
   * mid-end a raw-physical-pixel budget shrinks touch targets as display density rises — a puzzle
   * generated for a 3x screen gets the same 6-pixel handle as one generated for a 1x screen, but
   * it's a third the physical size. `PuzzleSession` therefore works in a `floor(density)`-reduced
   * space and scales every draw call back up by that factor, so callers here pass pre-divided
   * dimensions. [devicePixelRatio] is passed through for the record — `midend_size` only consults
   * it on the `userSize = false` path (see `convert_tilesize` in `midend.c`), which this front end
   * never takes, so it has no effect on what comes back.
   */
  fun size(
    me: Int,
    width: Int,
    height: Int,
    userSize: Boolean = false,
    devicePixelRatio: Double = 1.0,
  ): Pair<Int, Int> =
    withIntScratch(2) { scratch ->
      val xPtr = scratch
      val yPtr = scratch + 4
      memory.writeI32(xPtr, width)
      memory.writeI32(yPtr, height)
      exports.midendSize(me, xPtr, yPtr, if (userSize) 1 else 0, devicePixelRatio)
      memory.readInt(xPtr) to memory.readInt(yPtr)
    }

  fun resetTilesize(me: Int) = exports.midendResetTilesize(me)

  fun tilesize(me: Int): Int = exports.midendTilesize(me)

  // ---- Input and redraw -----------------------------------------------------------------------

  /** @return the mid-end's move-outcome code: not [GameStatus], but `process_key`'s own. */
  fun processKey(
    me: Int,
    x: Int,
    y: Int,
    button: Int,
  ): Int = exports.midendProcessKey(me, x, y, button)

  fun forceRedraw(me: Int) = exports.midendForceRedraw(me)

  fun redraw(me: Int) = exports.midendRedraw(me)

  /** @param elapsedSeconds the time since the previous tick (see `midend_timer`). */
  fun timer(
    me: Int,
    elapsedSeconds: Float,
  ) = exports.midendTimer(me, elapsedSeconds)

  fun freezeTimer(
    me: Int,
    tplus: Float,
  ) = exports.midendFreezeTimer(me, tplus)

  /** @return RGB triples, `ncolours * 3` floats long. */
  fun colours(me: Int): FloatArray =
    withIntScratch(1) { nPtr ->
      val colours = exports.midendColours(me, nPtr)
      val n = memory.readInt(nPtr)
      FloatArray(n * 3) { i -> memory.readFloat(colours + i * 4) }
    }

  fun wantsStatusbar(me: Int): Boolean = exports.midendWantsStatusbar(me).toBool()

  fun status(me: Int): Int = exports.midendStatus(me)

  fun canUndo(me: Int): Boolean = exports.midendCanUndo(me).toBool()

  fun canRedo(me: Int): Boolean = exports.midendCanRedo(me).toBool()

  fun getCursorLocation(me: Int): IntArray? =
    withIntScratch(4) { scratch ->
      val xPtr = scratch
      val yPtr = scratch + 4
      val wPtr = scratch + 8
      val hPtr = scratch + 12
      val found =
        exports.midendGetCursorLocation(me, xPtr, yPtr, wPtr, hPtr)
      if (found == 0) {
        null
      } else {
        intArrayOf(
          memory.readInt(xPtr),
          memory.readInt(yPtr),
          memory.readInt(wPtr),
          memory.readInt(hPtr),
        )
      }
    }

  // ---- Game id / seed / text format ------------------------------------------------------------

  /** @return an error message, or null on success. */
  fun gameId(
    me: Int,
    id: String,
  ): String? {
    val idPtr = writeCString(id)
    try {
      return readBorrowedCString(exports.midendGameId(me, idPtr))
    } finally {
      free(idPtr)
    }
  }

  fun getGameId(me: Int): String = readOwnedCString(exports.midendGetGameId(me)) ?: ""

  fun getRandomSeed(me: Int): String? = readOwnedCString(exports.midendGetRandomSeed(me))

  fun supersedeGameDesc(
    me: Int,
    desc: String,
    seed: String?,
  ) {
    val descPtr = writeCString(desc)
    val seedPtr = if (seed != null) writeCString(seed) else 0
    try {
      exports.midendSupersedeGameDesc(me, descPtr, seedPtr)
    } finally {
      free(descPtr)
      free(seedPtr)
    }
  }

  fun canFormatAsTextNow(me: Int): Boolean = exports.midendCanFormatAsTextNow(me).toBool()

  fun textFormat(me: Int): String? = readOwnedCString(exports.midendTextFormat(me))

  /** @return an error message, or null on success. */
  fun solve(me: Int): String? = readBorrowedCString(exports.midendSolve(me))

  fun rewriteStatusbar(
    me: Int,
    text: String,
  ): String {
    val textPtr = writeCString(text)
    try {
      return readOwnedCString(exports.midendRewriteStatusbar(me, textPtr))
        ?: text
    } finally {
      free(textPtr)
    }
  }

  // ---- Presets (pull model) --------------------------------------------------------------------

  /** @return the root `preset_menu *`. */
  fun getPresets(me: Int): Int =
    withIntScratch(1) { idLimitPtr -> exports.midendGetPresets(me, idLimitPtr) }

  fun presetMenuNEntries(menu: Int): Int = exports.wasmmidPresetMenuNentries(menu)

  fun presetMenuEntryTitle(
    menu: Int,
    i: Int,
  ): String = readCString(exports.wasmmidPresetMenuEntryTitle(menu, i))

  fun presetMenuEntryId(
    menu: Int,
    i: Int,
  ): Int = exports.wasmmidPresetMenuEntryId(menu, i)

  /** @return a nested `preset_menu *`, or 0 if entry [i] is a leaf. */
  fun presetMenuEntrySubmenu(
    menu: Int,
    i: Int,
  ): Int = exports.wasmmidPresetMenuEntrySubmenu(menu, i)

  fun presetMenuEntryParams(
    menu: Int,
    i: Int,
  ): Int = exports.wasmmidPresetMenuEntryParams(menu, i)

  fun presetMenuLookupById(
    menu: Int,
    id: Int,
  ): Int = exports.presetMenuLookupById(menu, id)

  /** @return the active preset's id, or -1 if the current params don't match any preset. */
  fun whichPreset(me: Int): Int = exports.midendWhichPreset(me)

  // ---- Config (pull model) ---------------------------------------------------------------------

  /** @return the `config_item *` and the dialog's window title. */
  fun getConfig(
    me: Int,
    which: Int,
  ): Pair<Int, String> =
    withIntScratch(1) { titlePtr ->
      val cfg = exports.midendGetConfig(me, which, titlePtr)
      // The title string is owned by the mid-end's own state, not a fresh allocation: read only.
      cfg to readCString(memory.readInt(titlePtr))
    }

  fun configItemType(
    cfg: Int,
    i: Int,
  ): Int = exports.wasmmidConfigItemType(cfg, i)

  fun configItemName(
    cfg: Int,
    i: Int,
  ): String = readCString(exports.wasmmidConfigItemName(cfg, i))

  fun configItemKw(
    cfg: Int,
    i: Int,
  ): String = readCString(exports.wasmmidConfigItemKw(cfg, i))

  fun configItemStringValue(
    cfg: Int,
    i: Int,
  ): String = readCString(exports.wasmmidConfigItemStringValue(cfg, i))

  fun configItemSetString(
    cfg: Int,
    i: Int,
    value: String,
  ) {
    val valuePtr = writeCString(value)
    try {
      exports.wasmmidConfigItemSetString(cfg, i, valuePtr)
    } finally {
      free(valuePtr)
    }
  }

  fun configItemBoolValue(
    cfg: Int,
    i: Int,
  ): Boolean = exports.wasmmidConfigItemBoolValue(cfg, i).toBool()

  fun configItemSetBool(
    cfg: Int,
    i: Int,
    value: Boolean,
  ) = exports.wasmmidConfigItemSetBool(cfg, i, if (value) 1 else 0)

  /** @return the raw delimited string, e.g. `":Foo:Bar:Baz"`, split on its first char. */
  fun configItemChoices(
    cfg: Int,
    i: Int,
  ): String = readCString(exports.wasmmidConfigItemChoices(cfg, i))

  fun configItemChoiceSelected(
    cfg: Int,
    i: Int,
  ): Int = exports.wasmmidConfigItemChoiceSelected(cfg, i)

  fun configItemSetChoice(
    cfg: Int,
    i: Int,
    index: Int,
  ) = exports.wasmmidConfigItemSetChoice(cfg, i, index)

  /** @return an error message, or null on success. */
  fun setConfig(
    me: Int,
    which: Int,
    cfg: Int,
  ): String? =
    // A `const char *` static literal, so it must not be freed (see AGENTS.md).
    readBorrowedCString(exports.midendSetConfig(me, which, cfg))

  // ---- Keys ---------------------------------------------------------------------------------

  /** @return the `key_label *` and its length. */
  fun requestKeys(me: Int): Pair<Int, Int> =
    withIntScratch(1) { nPtr ->
      val keys = exports.midendRequestKeys(me, nPtr)
      keys to memory.readInt(nPtr)
    }

  /** @return the label text, or null if the mid-end wants [currentKeyLabel] used instead. */
  fun keyLabelText(
    keys: Int,
    i: Int,
  ): String? {
    val ptr = exports.wasmmidKeyLabelText(keys, i)
    return if (ptr == 0) null else readCString(ptr)
  }

  fun keyLabelButton(
    keys: Int,
    i: Int,
  ): Int = exports.wasmmidKeyLabelButton(keys, i)

  fun currentKeyLabel(
    me: Int,
    button: Int,
  ): String = readCString(exports.midendCurrentKeyLabel(me, button))

  // ---- Save/load and preferences ----------------------------------------------------------------

  fun serialise(me: Int): ByteArray =
    withIntScratch(1) { outlenPtr ->
      val ptr = exports.wasmmidSerialise(me, outlenPtr)
      val len = memory.readInt(outlenPtr)
      try {
        memory.readBytes(ptr, len)
      } finally {
        free(ptr)
      }
    }

  /** @return an error message, or null on success. */
  fun deserialise(
    me: Int,
    bytes: ByteArray,
  ): String? {
    val bufPtr = malloc(bytes.size)
    try {
      memory.write(bufPtr, bytes)
      return readBorrowedCString(
        exports.wasmmidDeserialise(me, bufPtr, bytes.size),
      )
    } finally {
      free(bufPtr)
    }
  }

  fun savePrefs(me: Int): ByteArray =
    withIntScratch(1) { outlenPtr ->
      val ptr = exports.wasmmidSavePrefs(me, outlenPtr)
      val len = memory.readInt(outlenPtr)
      try {
        memory.readBytes(ptr, len)
      } finally {
        free(ptr)
      }
    }

  /** @return an error message, or null on success. */
  fun loadPrefs(
    me: Int,
    bytes: ByteArray,
  ): String? {
    val bufPtr = malloc(bytes.size)
    try {
      memory.write(bufPtr, bytes)
      return readBorrowedCString(
        exports.wasmmidLoadPrefs(me, bufPtr, bytes.size),
      )
    } finally {
      free(bufPtr)
    }
  }

  /** @return the game name a buffer identifies as, or null if no game claims it. */
  fun identifyGame(bytes: ByteArray): String? =
    withIntScratch(1) { namePtrPtr ->
      val bufPtr = malloc(bytes.size)
      try {
        memory.write(bufPtr, bytes)
        val error =
          exports.wasmmidIdentifyGame(namePtrPtr, bufPtr, bytes.size)
        val namePtr = memory.readInt(namePtrPtr)
        val name = readOwnedCString(namePtr)
        // The error is a static string; only *name was allocated for us.
        readBorrowedCString(error)
        name
      } finally {
        free(bufPtr)
      }
    }
}
