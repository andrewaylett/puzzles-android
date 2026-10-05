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

import android.graphics.Bitmap
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import uk.aylett.puzzles.engine.render.BitmapDrawingSink
import java.util.concurrent.atomic.AtomicLong

private const val DOMINOSA = "Dominosa"
private const val LOG_TAG = "PuzzleSession"

/** Upstream's keyword for the "keep square selected after pencil mark" preference. */
private const val KEEP_PENCIL_HIGHLIGHT_KW = "pencil-keep-highlight"

/**
 * One game in progress: a `midend *` handle, the surface it draws into, and the published
 * [PuzzleFrame]s the UI observes.
 *
 * Everything that touches the mid-end runs on [engineThread]. [EngineHooks] is shared with every
 * other session on the same [PuzzleEngine], but only one session is ever "the one making a call"
 * at a time (calls are fully serialised), so installing this session's callbacks there for the
 * duration of its lifetime is enough — no per-call routing is needed.
 */
class PuzzleSession internal constructor(
  private val engineThread: EngineThread,
  private val hooks: EngineHooks,
  private val midend: WasmMidend,
  private val me: Int,
  val game: GameInfo,
) : AutoCloseable {
  private val _frames = MutableStateFlow(PuzzleFrame())
  val frames: StateFlow<PuzzleFrame> = _frames.asStateFlow()

  private val sink =
    BitmapDrawingSink(
      onFrame = { width, height ->
        framesPresented.incrementAndGet()
        publish { it.copy(width = width, height = height) }
      },
      onStatus = { text -> publish { it.copy(statusText = text) } },
    )

  // The mid-end asserts rather than returning an error if asked to draw, report a status or hand
  // back a game id before a game exists (`me->desc` is NULL until then), and an assert inside WASM
  // surfaces as an opaque "unreachable" trap. A session is deliberately created without generating
  // a game, so that the UI can restore a saved one instead of paying to generate a throwaway, and
  // this flag is what keeps that state legal.
  private var hasGame = false

  // The space the UI last offered us, remembered so that a change of parameters can re-run the
  // mid-end's sizing without waiting for a layout pass that will never come.
  private var viewportWidth = 0
  private var viewportHeight = 0
  private var density = 1f

  // floor(density), never less than 1. Several back ends draw fixed-pixel-sized UI elements —
  // untangle's grab-handle CIRCLE_RADIUS, for one — that don't scale with tile size, so sizing the
  // mid-end directly in physical pixels leaves those elements a fixed *physical* size that shrinks
  // as density rises. Instead the mid-end is sized in this reduced space, and every draw call the
  // sink receives is scaled back up by the same integer, growing those fixed-pixel elements with
  // density the way the rest of the puzzle already does. It must be an integer: the back ends tile
  // adjacent rectangles edge-to-edge in integer coordinates, and a fractional scale would land
  // those edges mid-pixel, seaming every cell boundary and disagreeing on blitter save/load
  // roundtrips (see AGENTS.md on the four games that keep blitters).
  private var scale = 1

  private var paused = false
  private var timerWanted = false
  private var timerStartNanos = 0L

  // True whenever the mid-end wants a running timer and we're not backgrounded. Driven by
  // [onActivateTimer]/[onDeactivateTimer] (the mid-end's own signal that an animation or a
  // ticking game clock needs servicing) and by [setPaused] (the activity's visibility). The UI
  // owns *how fast* to call [tick] while this is true — see [tick]'s doc — this session only
  // says whether ticking should be happening at all.
  private val _animating = MutableStateFlow(false)
  val animating: StateFlow<Boolean> = _animating.asStateFlow()

  init {
    hooks.drawingSink = sink
    hooks.onActivateTimer = ::onActivateTimer
    hooks.onDeactivateTimer = ::onDeactivateTimer
    hooks.onIdsChanged =
      { /* Nothing is cached from the id/seed today; ready for a future need. */ }
  }

  /**
   * How many frames the mid-end has finished drawing, whether or not a surface was there to show
   * them. Kept out of [frames] so that equality there can drop redundant updates; tests and
   * diagnostics use it to see that drawing is happening.
   */
  val framesPresented = AtomicLong()

  private fun publish(transform: (PuzzleFrame) -> PuzzleFrame) {
    _frames.update(transform)
  }

  /**
   * Starts presenting frames to [surface], filling what the puzzle doesn't cover with [background].
   * Safe to call from the UI thread, and again whenever the surface is resized.
   *
   * The surface is attached immediately, so the engine thread's very next frame reaches it, but the
   * current frame is repainted asynchronously on the engine thread, which owns the bitmap it comes
   * from. If the engine is busy generating a game, the surface stays blank until it is free.
   */
  fun attachSurface(
    surface: Surface,
    background: Int,
  ) {
    sink.presenter.attach(surface, background)
    engineThread.scope.launch { sink.represent() }
  }

  /**
   * Stops using the surface. Once this returns the engine thread will not touch it again, which is
   * what `surfaceDestroyed` requires, so it may be called from there on the UI thread.
   */
  fun detachSurface() {
    sink.presenter.attach(null)
  }

  /** A copy of the pixels last drawn, or null before the first frame. For tests and diagnostics. */
  suspend fun snapshot(): Bitmap? = engineThread.run { sink.snapshot() }

  private fun syncMeta() {
    if (!hasGame) return
    publish {
      it.copy(
        canUndo = midend.canUndo(me),
        canRedo = midend.canRedo(me),
        status = midend.status(me),
        gameId = midend.getGameId(me),
      )
    }
  }

  private fun applyPalette() {
    val rgb = midend.colours(me)
    val packed =
      IntArray(rgb.size / 3) { i ->
        val r = (rgb[i * 3] * 255f).toInt().coerceIn(0, 255)
        val g = (rgb[i * 3 + 1] * 255f).toInt().coerceIn(0, 255)
        val b = (rgb[i * 3 + 2] * 255f).toInt().coerceIn(0, 255)
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
      }
    sink.setPalette(packed)
  }

  private fun redrawAndSync() {
    if (!hasGame) return
    midend.redraw(me)
    syncMeta()
  }

  /**
   * Generates a game, and records that one now exists.
   *
   * Every route to a playable state goes through here or through a successful restore, so that
   * [hasGame] cannot drift out of step with the mid-end.
   */
  private fun startNewGame() {
    midend.newGame(me)
    hasGame = true
  }

  private fun setBusy(busy: Boolean) {
    publish { it.copy(busy = busy) }
  }

  /**
   * Re-runs the mid-end's sizing for the current viewport and parameters.
   *
   * This has to happen whenever the *parameters* change as well as whenever the viewport does: a
   * different preset means a different grid, hence a different tile size and surface. Upstream
   * does the same thing — `menu_config_event` in `gtk.c` calls `resize_fe()` immediately after
   * `midend_new_game()`. Miss it and the new game is drawn at the old tile size into a surface
   * sized for the old parameters, which `Canvas` silently clips.
   *
   * `user_size = true` makes the mid-end search for the *largest* tile size that fits, rather than
   * capping at the game's preferred size; the preferred size is tuned for a desktop window and
   * leaves a small grid as a postage stamp on a phone. What it returns is what it will actually
   * draw — usually a little smaller than offered — so the surface is allocated to that.
   */
  private fun applySize() {
    // midend_size finishes by calling the back end's set_size on the current draw state, and
    // midend_new leaves that NULL until a game exists — so sizing before generating hands the back
    // end a NULL draw state. Upstream orders it the other way round for exactly this reason
    // (emcc.c creates the game, *then* sets the canvas size). The games that keep blitters —
    // Inertia, Map, Pegs and Signpost — are the ones that notice, via an assert in set_size.
    if (!hasGame) return
    if (viewportWidth <= 0 || viewportHeight <= 0) return
    // Before sizing, not after: set_size may allocate blitters, which are sized by this scale.
    sink.useScale(scale)
    val (drawWidth, drawHeight) =
      midend.size(
        me,
        viewportWidth / scale,
        viewportHeight / scale,
        userSize = true,
        devicePixelRatio = density.toDouble(),
      )
    sink.resize(drawWidth * scale, drawHeight * scale, scale)
  }

  suspend fun resize(
    widthPx: Int,
    heightPx: Int,
    density: Float = 1f,
  ) = engineThread.run {
    // Recorded even when there is no game yet: whichever call generates the first one will size it
    // to this viewport, so an early resize is remembered rather than lost.
    viewportWidth = widthPx
    viewportHeight = heightPx
    this.density = density
    this.scale = density.toInt().coerceAtLeast(1)
    if (!hasGame) return@run
    applySize()
    applyPalette()
    midend.forceRedraw(me)
    redrawAndSync()
  }

  suspend fun newGame() =
    engineThread.run {
      setBusy(true)
      startNewGame()
      applySize()
      applyPalette()
      midend.forceRedraw(me)
      redrawAndSync()
      setBusy(false)
    }

  suspend fun restart() =
    engineThread.run {
      midend.restartGame(me)
      redrawAndSync()
    }

  suspend fun undo() =
    engineThread.run {
      midend.processKey(me, 0, 0, Buttons.UI_UNDO)
      redrawAndSync()
    }

  suspend fun redo() =
    engineThread.run {
      midend.processKey(me, 0, 0, Buttons.UI_REDO)
      redrawAndSync()
    }

  /** @return an error message, or null on success. */
  suspend fun solve(): String? =
    engineThread.run {
      setBusy(true)
      val error = midend.solve(me)
      redrawAndSync()
      setBusy(false)
      error
    }

  /** @param x @param y physical pixels within the rendered bitmap, as the UI hands them to us. */
  suspend fun pointer(
    action: PointerAction,
    button: PointerButton,
    x: Int,
    y: Int,
    stylus: Boolean = false,
  ) = engineThread.run {
    // Floor, not truncate: a drag just off the top or left edge is negative, and rounding it
    // towards zero would report it as inside the first cell.
    midend.processKey(
      me,
      Math.floorDiv(x, scale),
      Math.floorDiv(y, scale),
      buttonCode(action, button, stylus),
    )
    redrawAndSync()
  }

  suspend fun key(button: Int) =
    engineThread.run {
      midend.processKey(me, 0, 0, button)
      redrawAndSync()
    }

  suspend fun presets(): List<PresetEntry> =
    engineThread.run {
      val menu = midend.getPresets(me)
      // Reading a preset's parameters means loading them into the mid-end, which is the only way to
      // see them; put the current ones back afterwards. midend_set_config only records parameters,
      // so the game in progress is undisturbed.
      val original = readConfigForm(ConfigSection.SETTINGS).fields
      try {
        readPresetMenu(menu)
      } finally {
        writeConfig(ConfigSection.SETTINGS, original)
      }
    }

  private fun readPresetMenu(menu: Int): List<PresetEntry> {
    if (menu == 0) return emptyList()
    val n = midend.presetMenuNEntries(menu)
    return (0 until n).map { i ->
      val title = midend.presetMenuEntryTitle(menu, i)
      val submenu = midend.presetMenuEntrySubmenu(menu, i)
      if (submenu != 0) {
        PresetEntry.Submenu(title, readPresetMenu(submenu))
      } else {
        val id = midend.presetMenuEntryId(menu, i)
        midend.setParams(me, midend.presetMenuLookupById(menu, id))
        val settings = readConfigForm(ConfigSection.SETTINGS).fields
        PresetEntry.Preset(portraitTitle(title, settings), id)
      }
    }
  }

  /**
   * Presets are applied transposed (see [applyPreset]), so the mid-end's parameters no longer match
   * the preset the user picked and `midend_which_preset` reports -1. Transposing them back for the
   * duration of the lookup recovers it; `midend_set_config` only records parameters, so doing this
   * does not disturb the game in progress.
   */
  suspend fun currentPresetId(): Int =
    engineThread.run {
      val direct = midend.whichPreset(me)
      if (direct != -1) return@run direct
      val original = readConfigForm(ConfigSection.SETTINGS).fields
      val swapped = transposed(original, onlyIfLandscape = false) ?: return@run direct
      if (writeConfig(ConfigSection.SETTINGS, swapped) != null) return@run direct
      try {
        midend.whichPreset(me)
      } finally {
        writeConfig(ConfigSection.SETTINGS, original)
      }
    }

  suspend fun applyPreset(id: Int) =
    engineThread.run {
      setBusy(true)
      val menu = midend.getPresets(me)
      val params = midend.presetMenuLookupById(menu, id)
      midend.setParams(me, params)
      // Presented as portrait, so play as portrait: see [transposed].
      transposed(readConfigForm(ConfigSection.SETTINGS).fields)?.let {
        writeConfig(ConfigSection.SETTINGS, it)
      }
      startNewGame()
      applySize()
      applyPalette()
      midend.forceRedraw(me)
      redrawAndSync()
      setBusy(false)
    }

  suspend fun config(section: Int): ConfigForm = engineThread.run { readConfigForm(section) }

  /** Engine thread only. */
  private fun readConfigForm(section: Int): ConfigForm {
    val (cfg, title) = midend.getConfig(me, section)
    try {
      return ConfigForm(title, readConfigFields(cfg, readKeywords = section == ConfigSection.PREFS))
    } finally {
      midend.freeCfg(cfg)
    }
  }

  /**
   * @param readKeywords upstream only sets `config_item.kw` for preferences, which need it to be
   *   saved. Settings items are allocated with `snewn` and left uninitialised, so reading their
   *   keyword dereferences whatever the heap held (Dominosa's, for one, is junk that traps with an
   *   out-of-bounds read). The field is empty for those.
   */
  private fun readConfigFields(
    cfg: Int,
    readKeywords: Boolean,
  ): List<ConfigField> {
    val fields = mutableListOf<ConfigField>()
    var i = 0
    while (true) {
      val type = midend.configItemType(cfg, i)
      if (type == ConfigType.END) break
      val name = midend.configItemName(cfg, i)
      val keyword = if (readKeywords) midend.configItemKw(cfg, i) else ""
      fields +=
        when (type) {
          ConfigType.STRING -> {
            ConfigField.Text(name, keyword, midend.configItemStringValue(cfg, i))
          }

          ConfigType.BOOLEAN -> {
            ConfigField.Toggle(name, keyword, midend.configItemBoolValue(cfg, i))
          }

          ConfigType.CHOICES -> {
            val raw = midend.configItemChoices(cfg, i)
            val delimiter = raw.first()
            val options = raw.substring(1).split(delimiter)
            ConfigField.Choice(name, keyword, options, midend.configItemChoiceSelected(cfg, i))
          }

          else -> {
            error("unexpected config_item type $type at index $i")
          }
        }
      i++
    }
    return fields
  }

  /** @return an error message, or null on success. */
  suspend fun applyConfig(
    section: Int,
    fields: List<ConfigField>,
  ): String? =
    engineThread.run {
      val error = writeConfig(section, fields)
      if (error == null) {
        // midend_set_config only records the new settings; it never generates. Upstream's own
        // front ends call midend_new_game afterwards for every section except preferences (see
        // menu_config_event in gtk.c), and preferences are excluded because they change only how
        // an existing game is presented, not what it is.
        if (section != ConfigSection.PREFS) {
          setBusy(true)
          startNewGame()
          applySize()
          applyPalette()
          midend.forceRedraw(me)
          setBusy(false)
        }
        redrawAndSync()
      }
      error
    }

  /** Engine thread only. @return an error message, or null on success. */
  private fun writeConfig(
    section: Int,
    fields: List<ConfigField>,
  ): String? {
    val (cfg, _) = midend.getConfig(me, section)
    try {
      fields.forEachIndexed { i, field ->
        when (field) {
          is ConfigField.Text -> midend.configItemSetString(cfg, i, field.value)
          is ConfigField.Toggle -> midend.configItemSetBool(cfg, i, field.value)
          is ConfigField.Choice -> midend.configItemSetChoice(cfg, i, field.selected)
        }
      }
      return midend.setConfig(me, section, cfg)
    } finally {
      midend.freeCfg(cfg)
    }
  }

  suspend fun keyHints(): List<KeyHint> =
    engineThread.run {
      val (keys, n) = midend.requestKeys(me)
      try {
        val hints =
          (0 until n).map { i ->
            val button = midend.keyLabelButton(keys, i)
            val text = midend.keyLabelText(keys, i) ?: midend.currentKeyLabel(me, button)
            KeyHint(text, button)
          }
        hints.ifEmpty { numberHighlightHints() }
      } finally {
        midend.freeKeys(keys, n)
      }
    }

  /**
   * Dominosa lets you highlight up to two numbers by typing them, but its back end requests no
   * keys, so without this the only route is a long-press dead-centre on a digit — undiscoverable,
   * and the digits are the only way to highlight on a board where that is awkward to hit. The
   * highest number comes from the current settings rather than the game id because the id's
   * encoding is the back end's business. Digits are single keystrokes, so a board with numbers
   * above 9 gets chips for 0–9 only; the rest remain reachable by long-press.
   *
   * Engine thread only.
   */
  private fun numberHighlightHints(): List<KeyHint> {
    if (game.name != DOMINOSA) return emptyList()
    val max =
      readConfigForm(ConfigSection.SETTINGS)
        .fields
        .filterIsInstance<ConfigField.Text>()
        .firstOrNull()
        ?.value
        ?.toIntOrNull() ?: return emptyList()
    return (0..minOf(max, 9)).map { KeyHint(it.toString(), '0'.code + it) }
  }

  /** @return the current game's id, or an empty string if no game has been generated yet. */
  suspend fun gameId(): String = engineThread.run { if (hasGame) midend.getGameId(me) else "" }

  /** @return an error message, or null on success. */
  suspend fun setGameId(id: String): String? =
    engineThread.run {
      setBusy(true)
      // midend_game_id only parses and stores the id; it does not generate. Upstream always
      // follows a successful call with midend_new_game (see gtk.c), and without it the mid-end is
      // left with a description but no game, which asserts the moment anything tries to draw.
      val error = midend.gameId(me, id)
      if (error == null) {
        startNewGame()
        applySize()
        applyPalette()
        midend.forceRedraw(me)
        redrawAndSync()
      }
      setBusy(false)
      error
    }

  suspend fun textFormat(): String? =
    engineThread.run {
      if (!midend.canFormatAsTextNow(me)) null else midend.textFormat(me)
    }

  suspend fun serialise(): ByteArray = engineThread.run { midend.serialise(me) }

  /** @return an error message, or null on success. */
  suspend fun deserialise(bytes: ByteArray): String? =
    engineThread.run {
      setBusy(true)
      val error = midend.deserialise(me, bytes)
      if (error == null) {
        // A restored save *is* a game, so this is the other legitimate route to hasGame.
        hasGame = true
        applySize()
        applyPalette()
        midend.forceRedraw(me)
        redrawAndSync()
      }
      setBusy(false)
      error
    }

  suspend fun savePrefs(): ByteArray = engineThread.run { midend.savePrefs(me) }

  suspend fun loadPrefs(bytes: ByteArray) =
    engineThread.run {
      // wasmmid_load_prefs can report an error, but loadPrefs has no error channel of its own since
      // bad/foreign prefs are not something the UI needs to act on: log it so it isn't silent.
      val error = midend.loadPrefs(me, bytes)
      if (error != null) {
        Log.w(LOG_TAG, "loadPrefs: $error")
      }
    }

  /**
   * Applies this front end's preference defaults, for a game with no saved preferences.
   *
   * Upstream defaults `pencil-keep-highlight` off, which suits a mouse but not a finger: tapping a
   * square and then a digit in pencil mode drops the selection after every mark, so adding several
   * marks to one square means re-selecting it each time. Games without the preference are
   * unaffected.
   */
  suspend fun applyPrefDefaults() =
    engineThread.run {
      val (cfg, _) = midend.getConfig(me, ConfigSection.PREFS)
      try {
        var changed = false
        var i = 0
        while (midend.configItemType(cfg, i) != ConfigType.END) {
          if (midend.configItemKw(cfg, i) == KEEP_PENCIL_HIGHLIGHT_KW) {
            midend.configItemSetBool(cfg, i, true)
            changed = true
          }
          i++
        }
        if (changed) {
          val error = midend.setConfig(me, ConfigSection.PREFS, cfg)
          if (error != null) Log.w(LOG_TAG, "applyPrefDefaults: $error")
        }
      } finally {
        midend.freeCfg(cfg)
      }
    }

  /** Stops or resumes ticking as the activity comes and goes. */
  fun setPaused(paused: Boolean) {
    this.paused = paused
    updateAnimating()
  }

  private fun onActivateTimer() {
    timerWanted = true
    updateAnimating()
  }

  private fun onDeactivateTimer() {
    timerWanted = false
    updateAnimating()
  }

  private fun updateAnimating() {
    val next = timerWanted && !paused
    // Rebase the delta clock whenever ticking (re)starts, whether that's the mid-end asking for a
    // fresh animation or the activity coming back to the foreground — either way, time spent not
    // ticking must not show up as a single huge delta in the next tick() (see tick()'s doc).
    if (next && !_animating.value) {
      timerStartNanos = System.nanoTime()
    }
    _animating.value = next
  }

  /**
   * Ticks [WasmMidend.timer] once and redraws, for the UI's animation pacing loop to call
   * repeatedly while [animating] is true.
   *
   * This session only decides *whether* ticking should be happening ([animating]); how fast to
   * call this while it's true — vsync-paced during genuine animation, backed off to a slow poll
   * once nothing is visibly changing — is a UI-thread concern (`Choreographer`/frame timing), so
   * it lives in the caller rather than here. midend_timer takes the time since the *previous*
   * tick, not since the timer started: it does `me->anim_pos += tplus`. Passing cumulative time
   * would race every animation to its end and make in-game clocks (Mines, Inertia) run
   * quadratically fast — so the delta is measured fresh every call, however often that is.
   *
   * @return whether the mid-end painted anything, so the caller can tell an idle timer (a ticking
   *   game clock with nothing else moving) from a genuinely animating one.
   */
  suspend fun tick(): Boolean =
    engineThread.run {
      if (!hasGame || !timerWanted || paused) return@run false
      val now = System.nanoTime()
      val delta = (now - timerStartNanos) / 1_000_000_000f
      timerStartNanos = now
      midend.timer(me, delta)
      redrawAndSync()
      sink.consumeChanged()
    }

  private fun buttonCode(
    action: PointerAction,
    button: PointerButton,
    stylus: Boolean,
  ): Int {
    val base =
      when (button) {
        PointerButton.LEFT -> 0
        PointerButton.MIDDLE -> 1
        PointerButton.RIGHT -> 2
      }
    // LEFT_BUTTON, MIDDLE_BUTTON, RIGHT_BUTTON, then the same order for _DRAG, then _RELEASE: see
    // the Buttons object.
    val offset =
      when (action) {
        PointerAction.DOWN -> 0
        PointerAction.DRAG -> 3
        PointerAction.UP -> 6
      }
    val modifier = if (stylus) Buttons.MOD_STYLUS else 0
    return Buttons.LEFT_BUTTON + base + offset + modifier
  }

  /**
   * Frees the mid-end handle and detaches from [hooks]. Blocks the calling thread briefly, hopping
   * onto the engine thread to do it: [AutoCloseable.close] has no suspend variant, and a `midend *`
   * must only ever be freed from the thread that owns the instance.
   */
  override fun close() {
    _animating.value = false
    detachSurface()
    runBlocking(engineThread.dispatcher) {
      hasGame = false
      if (hooks.drawingSink === sink) hooks.drawingSink = null
      hooks.onActivateTimer = null
      hooks.onDeactivateTimer = null
      hooks.onIdsChanged = null
      midend.midendFree(me)
    }
  }
}
