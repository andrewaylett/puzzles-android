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

import com.dylibso.chicory.runtime.Instance

/**
 * A thin, literal wrapper over the mid-end's WASM exports.
 *
 * This class does no policy: it decodes/encodes the C ABI (pointers, out-params, module-owned
 * strings) and nothing more. One [WasmMidend] serves the whole [Instance] — `malloc`/`free` and
 * the game-listing exports (`wasmmid_game_count`/`wasmmid_game_at`) aren't tied to a particular
 * `midend *` — but every mid-end-scoped export still takes its `me` handle as an explicit
 * parameter, exactly as the C API does, so a caller juggling several handles never has to wonder
 * which one a call applies to.
 *
 * Every method here must run on the engine thread: it reads `instance.memory()` fresh on every
 * access (never caches a view or pointer across a call) because linear memory can grow underneath
 * a long-running instance.
 */
class WasmMidend(
  private val instance: Instance,
) {
  private fun memory() = instance.memory()

  private fun call(
    name: String,
    vararg args: Long,
  ): LongArray? = instance.export(name).apply(*args)

  private fun call0(
    name: String,
    vararg args: Long,
  ) {
    call(name, *args)
  }

  private fun call1(
    name: String,
    vararg args: Long,
  ): Int = call(name, *args)!![0].toInt()

  private fun callBool(
    name: String,
    vararg args: Long,
  ): Boolean = call1(name, *args) != 0

  // ---- Allocation -----------------------------------------------------------------------------

  fun malloc(size: Int): Int = call1("malloc", size.toLong())

  fun free(ptr: Int) {
    if (ptr != 0) call0("free", ptr.toLong())
  }

  fun freeCfg(ptr: Int) {
    if (ptr != 0) call0("free_cfg", ptr.toLong())
  }

  fun freeKeys(
    ptr: Int,
    n: Int,
  ) {
    if (ptr != 0) call0("free_keys", ptr.toLong(), n.toLong())
  }

  /** Reads a NUL-terminated string without taking ownership of [ptr]. */
  private fun readCString(ptr: Int): String = if (ptr == 0) "" else memory().readCString(ptr)

  /**
   * Reads a NUL-terminated string the module handed over as a fresh allocation (an error message,
   * a `dupstr`'d id) and frees it immediately afterwards, per the "anything the module allocates
   * is owned by the module" rule. Accessors into a still-alive struct (config items, preset menu
   * entries, key labels, the game name) must *not* go through this — freeing those would corrupt
   * the struct they point into.
   */
  private fun readOwnedCString(ptr: Int): String? {
    if (ptr == 0) return null
    val s = memory().readCString(ptr)
    free(ptr)
    return s
  }

  /**
   * Reads a string the module still owns, without freeing it.
   *
   * Upstream signals ownership through constness, and it is worth following exactly: `char *`
   * returns are freshly allocated and ours to free, while `const char *` returns — every error
   * message from `midend_solve`, `midend_game_id`, `midend_deserialise`, `midend_load_prefs` and
   * `identify_game` — are static string literals living in the module's data segment. Passing one
   * of those to `free` hands the allocator a pointer it never issued, corrupting the heap in a way
   * that surfaces much later and nowhere near the cause.
   */
  private fun readBorrowedCString(ptr: Int): String? {
    if (ptr == 0) return null
    return memory().readCString(ptr)
  }

  /** Allocates and writes a NUL-terminated copy of [s]; the caller owns the result until freed. */
  private fun writeCString(s: String): Int {
    val bytes = s.toByteArray(Charsets.UTF_8)
    val ptr = malloc(bytes.size + 1)
    memory().writeCString(ptr, s)
    return ptr
  }

  /** Runs [block] with a scratch buffer of [n] `int`s, freeing it however [block] exits. */
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

  fun gameCount(): Int = call1("wasmmid_game_count")

  fun gameAt(index: Int): Int = call1("wasmmid_game_at", index.toLong())

  fun gameName(game: Int): String = readCString(call1("wasmmid_game_name", game.toLong()))

  fun gameCanSolve(game: Int): Boolean = callBool("wasmmid_game_can_solve", game.toLong())

  fun gameCanConfigure(game: Int): Boolean = callBool("wasmmid_game_can_configure", game.toLong())

  fun gameCanFormatAsTextEver(game: Int): Boolean =
    callBool("wasmmid_game_can_format_as_text_ever", game.toLong())

  // ---- Lifecycle --------------------------------------------------------------------------------

  fun midendNew(gameIndex: Int): Int = call1("wasmmid_midend_new", gameIndex.toLong())

  fun midendFree(me: Int) = call0("midend_free", me.toLong())

  fun newGame(me: Int) = call0("midend_new_game", me.toLong())

  fun restartGame(me: Int) = call0("midend_restart_game", me.toLong())

  fun stopAnim(me: Int) = call0("midend_stop_anim", me.toLong())

  fun requestIdChanges(me: Int) = call0("wasmmid_midend_request_id_changes", me.toLong())

  fun whichGame(me: Int): Int = call1("midend_which_game", me.toLong())

  // ---- Params -------------------------------------------------------------------------------

  fun setParams(
    me: Int,
    params: Int,
  ) = call0("midend_set_params", me.toLong(), params.toLong())

  fun getParams(me: Int): Int = call1("midend_get_params", me.toLong())

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
      memory().writeI32(xPtr, width)
      memory().writeI32(yPtr, height)
      call(
        "midend_size",
        me.toLong(),
        xPtr.toLong(),
        yPtr.toLong(),
        if (userSize) 1L else 0L,
        java.lang.Double.doubleToRawLongBits(devicePixelRatio),
      )
      memory().readInt(xPtr) to memory().readInt(yPtr)
    }

  fun resetTilesize(me: Int) = call0("midend_reset_tilesize", me.toLong())

  fun tilesize(me: Int): Int = call1("midend_tilesize", me.toLong())

  // ---- Input and redraw -----------------------------------------------------------------------

  /** @return the mid-end's move-outcome code: not [GameStatus], but `process_key`'s own. */
  fun processKey(
    me: Int,
    x: Int,
    y: Int,
    button: Int,
  ): Int = call1("midend_process_key", me.toLong(), x.toLong(), y.toLong(), button.toLong())

  fun forceRedraw(me: Int) = call0("midend_force_redraw", me.toLong())

  fun redraw(me: Int) = call0("midend_redraw", me.toLong())

  /** @param elapsedSeconds real time since the timer was activated, as raw `float` bits. */
  fun timer(
    me: Int,
    elapsedSeconds: Float,
  ) = call0(
    "midend_timer",
    me.toLong(),
    java.lang.Float
      .floatToRawIntBits(elapsedSeconds)
      .toLong(),
  )

  fun freezeTimer(
    me: Int,
    tplus: Float,
  ) = call0(
    "midend_freeze_timer",
    me.toLong(),
    java.lang.Float
      .floatToRawIntBits(tplus)
      .toLong(),
  )

  /** @return RGB triples, `ncolours * 3` floats long. */
  fun colours(me: Int): FloatArray =
    withIntScratch(1) { nPtr ->
      val colours = call1("midend_colours", me.toLong(), nPtr.toLong())
      val n = memory().readInt(nPtr)
      FloatArray(n * 3) { i -> memory().readFloat(colours + i * 4) }
    }

  fun wantsStatusbar(me: Int): Boolean = callBool("midend_wants_statusbar", me.toLong())

  fun status(me: Int): Int = call1("midend_status", me.toLong())

  fun canUndo(me: Int): Boolean = callBool("midend_can_undo", me.toLong())

  fun canRedo(me: Int): Boolean = callBool("midend_can_redo", me.toLong())

  fun getCursorLocation(me: Int): IntArray? =
    withIntScratch(4) { scratch ->
      val xPtr = scratch
      val yPtr = scratch + 4
      val wPtr = scratch + 8
      val hPtr = scratch + 12
      val found =
        call1(
          "midend_get_cursor_location",
          me.toLong(),
          xPtr.toLong(),
          yPtr.toLong(),
          wPtr.toLong(),
          hPtr.toLong(),
        )
      if (found == 0) {
        null
      } else {
        intArrayOf(
          memory().readInt(xPtr),
          memory().readInt(yPtr),
          memory().readInt(wPtr),
          memory().readInt(hPtr),
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
      return readBorrowedCString(call1("midend_game_id", me.toLong(), idPtr.toLong()))
    } finally {
      free(idPtr)
    }
  }

  fun getGameId(me: Int): String = readOwnedCString(call1("midend_get_game_id", me.toLong())) ?: ""

  fun getRandomSeed(me: Int): String? =
    readOwnedCString(call1("midend_get_random_seed", me.toLong()))

  fun supersedeGameDesc(
    me: Int,
    desc: String,
    seed: String?,
  ) {
    val descPtr = writeCString(desc)
    val seedPtr = if (seed != null) writeCString(seed) else 0
    try {
      call0("midend_supersede_game_desc", me.toLong(), descPtr.toLong(), seedPtr.toLong())
    } finally {
      free(descPtr)
      free(seedPtr)
    }
  }

  fun canFormatAsTextNow(me: Int): Boolean = callBool("midend_can_format_as_text_now", me.toLong())

  fun textFormat(me: Int): String? = readOwnedCString(call1("midend_text_format", me.toLong()))

  /** @return an error message, or null on success. */
  fun solve(me: Int): String? = readBorrowedCString(call1("midend_solve", me.toLong()))

  fun rewriteStatusbar(
    me: Int,
    text: String,
  ): String {
    val textPtr = writeCString(text)
    try {
      return readOwnedCString(call1("midend_rewrite_statusbar", me.toLong(), textPtr.toLong()))
        ?: text
    } finally {
      free(textPtr)
    }
  }

  // ---- Presets (pull model) --------------------------------------------------------------------

  /** @return the root `preset_menu *`. */
  fun getPresets(me: Int): Int =
    withIntScratch(
      1,
    ) { idLimitPtr -> call1("midend_get_presets", me.toLong(), idLimitPtr.toLong()) }

  fun presetMenuNEntries(menu: Int): Int = call1("wasmmid_preset_menu_nentries", menu.toLong())

  fun presetMenuEntryTitle(
    menu: Int,
    i: Int,
  ): String = readCString(call1("wasmmid_preset_menu_entry_title", menu.toLong(), i.toLong()))

  fun presetMenuEntryId(
    menu: Int,
    i: Int,
  ): Int = call1("wasmmid_preset_menu_entry_id", menu.toLong(), i.toLong())

  /** @return a nested `preset_menu *`, or 0 if entry [i] is a leaf. */
  fun presetMenuEntrySubmenu(
    menu: Int,
    i: Int,
  ): Int = call1("wasmmid_preset_menu_entry_submenu", menu.toLong(), i.toLong())

  fun presetMenuEntryParams(
    menu: Int,
    i: Int,
  ): Int = call1("wasmmid_preset_menu_entry_params", menu.toLong(), i.toLong())

  fun presetMenuLookupById(
    menu: Int,
    id: Int,
  ): Int = call1("preset_menu_lookup_by_id", menu.toLong(), id.toLong())

  /** @return the active preset's id, or -1 if the current params don't match any preset. */
  fun whichPreset(me: Int): Int = call1("midend_which_preset", me.toLong())

  // ---- Config (pull model) ---------------------------------------------------------------------

  /** @return the `config_item *` and the dialog's window title. */
  fun getConfig(
    me: Int,
    which: Int,
  ): Pair<Int, String> =
    withIntScratch(1) { titlePtr ->
      val cfg = call1("midend_get_config", me.toLong(), which.toLong(), titlePtr.toLong())
      // The title string is owned by the mid-end's own state, not a fresh allocation: read only.
      cfg to readCString(memory().readInt(titlePtr))
    }

  fun configItemType(
    cfg: Int,
    i: Int,
  ): Int = call1("wasmmid_config_item_type", cfg.toLong(), i.toLong())

  fun configItemName(
    cfg: Int,
    i: Int,
  ): String = readCString(call1("wasmmid_config_item_name", cfg.toLong(), i.toLong()))

  fun configItemKw(
    cfg: Int,
    i: Int,
  ): String = readCString(call1("wasmmid_config_item_kw", cfg.toLong(), i.toLong()))

  fun configItemStringValue(
    cfg: Int,
    i: Int,
  ): String = readCString(call1("wasmmid_config_item_string_value", cfg.toLong(), i.toLong()))

  fun configItemSetString(
    cfg: Int,
    i: Int,
    value: String,
  ) {
    val valuePtr = writeCString(value)
    try {
      call0("wasmmid_config_item_set_string", cfg.toLong(), i.toLong(), valuePtr.toLong())
    } finally {
      free(valuePtr)
    }
  }

  fun configItemBoolValue(
    cfg: Int,
    i: Int,
  ): Boolean = callBool("wasmmid_config_item_bool_value", cfg.toLong(), i.toLong())

  fun configItemSetBool(
    cfg: Int,
    i: Int,
    value: Boolean,
  ) = call0("wasmmid_config_item_set_bool", cfg.toLong(), i.toLong(), if (value) 1L else 0L)

  /** @return the raw delimited string, e.g. `":Foo:Bar:Baz"`, split on its first char. */
  fun configItemChoices(
    cfg: Int,
    i: Int,
  ): String = readCString(call1("wasmmid_config_item_choices", cfg.toLong(), i.toLong()))

  fun configItemChoiceSelected(
    cfg: Int,
    i: Int,
  ): Int = call1("wasmmid_config_item_choice_selected", cfg.toLong(), i.toLong())

  fun configItemSetChoice(
    cfg: Int,
    i: Int,
    index: Int,
  ) = call0("wasmmid_config_item_set_choice", cfg.toLong(), i.toLong(), index.toLong())

  /** @return an error message, or null on success. */
  fun setConfig(
    me: Int,
    which: Int,
    cfg: Int,
  ): String? =
    readOwnedCString(call1("midend_set_config", me.toLong(), which.toLong(), cfg.toLong()))

  // ---- Keys ---------------------------------------------------------------------------------

  /** @return the `key_label *` and its length. */
  fun requestKeys(me: Int): Pair<Int, Int> =
    withIntScratch(1) { nPtr ->
      val keys = call1("midend_request_keys", me.toLong(), nPtr.toLong())
      keys to memory().readInt(nPtr)
    }

  /** @return the label text, or null if the mid-end wants [currentKeyLabel] used instead. */
  fun keyLabelText(
    keys: Int,
    i: Int,
  ): String? {
    val ptr = call1("wasmmid_key_label_text", keys.toLong(), i.toLong())
    return if (ptr == 0) null else readCString(ptr)
  }

  fun keyLabelButton(
    keys: Int,
    i: Int,
  ): Int = call1("wasmmid_key_label_button", keys.toLong(), i.toLong())

  fun currentKeyLabel(
    me: Int,
    button: Int,
  ): String = readCString(call1("midend_current_key_label", me.toLong(), button.toLong()))

  // ---- Save/load and preferences ----------------------------------------------------------------

  fun serialise(me: Int): ByteArray =
    withIntScratch(1) { outlenPtr ->
      val ptr = call1("wasmmid_serialise", me.toLong(), outlenPtr.toLong())
      val len = memory().readInt(outlenPtr)
      try {
        memory().readBytes(ptr, len)
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
      memory().write(bufPtr, bytes)
      return readBorrowedCString(
        call1("wasmmid_deserialise", me.toLong(), bufPtr.toLong(), bytes.size.toLong()),
      )
    } finally {
      free(bufPtr)
    }
  }

  fun savePrefs(me: Int): ByteArray =
    withIntScratch(1) { outlenPtr ->
      val ptr = call1("wasmmid_save_prefs", me.toLong(), outlenPtr.toLong())
      val len = memory().readInt(outlenPtr)
      try {
        memory().readBytes(ptr, len)
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
      memory().write(bufPtr, bytes)
      return readBorrowedCString(
        call1("wasmmid_load_prefs", me.toLong(), bufPtr.toLong(), bytes.size.toLong()),
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
        memory().write(bufPtr, bytes)
        val error =
          call1("wasmmid_identify_game", namePtrPtr.toLong(), bufPtr.toLong(), bytes.size.toLong())
        val namePtr = memory().readInt(namePtrPtr)
        val name = readOwnedCString(namePtr)
        // The error is a static string; only *name was allocated for us.
        readBorrowedCString(error)
        name
      } finally {
        free(bufPtr)
      }
    }
}
