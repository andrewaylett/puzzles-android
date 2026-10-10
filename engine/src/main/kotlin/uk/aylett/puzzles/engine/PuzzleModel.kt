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

/**
 * One of the 40 puzzles the combined module carries.
 *
 * @param index the module's own ordering, used with `wasmmid_midend_new`.
 */
data class GameInfo(
  val index: Int,
  val name: String,
  val canSolve: Boolean,
  val canConfigure: Boolean,
  val canFormatAsText: Boolean,
) {
  /**
   * Whether the back end accepts `M` to fill every pencil mark in every empty square, for players
   * who start from all candidates and eliminate. It is a key press with no mouse equivalent and
   * none of these back ends list it in `request_keys`, so the UI must send it itself. `M` means
   * something else in other games (Bridges, Flip, Galaxies, Flood, Samegame, Pearl), so this must
   * stay an allowlist.
   */
  val canFillPencilMarks: Boolean get() = name in PENCIL_FILL_GAMES

  /**
   * Whether the back end sets `STYLUS_SUPPORT`, the only case in which the mid-end acts on
   * `MOD_STYLUS`. `STYLUS_SUPPORT` is a compile-time flag the mid-end does not expose, so this is
   * an allowlist; offering the "taps cycle all states" switch anywhere else would do nothing.
   */
  val supportsStylus: Boolean get() = name in STYLUS_GAMES
}

private val STYLUS_GAMES = setOf("Loopy", "Net", "Light Up", "Palisade", "Pattern", "Tents")

private val PENCIL_FILL_GAMES = setOf("Solo", "Keen", "Towers", "Unequal", "Undead")

/** The mid-end's button code for "fill all pencil marks" (see [GameInfo.canFillPencilMarks]). */
const val FILL_PENCIL_MARKS_BUTTON = 'M'.code

/**
 * Everything about the puzzle the UI shows other than its pixels.
 *
 * The pixels go straight to the UI's surface from the engine thread (see
 * [PuzzleSession.attachSurface]) and are deliberately not part of this value: it is compared by
 * equality, so a redraw that changes nothing here — most animation ticks — publishes nothing and
 * costs the UI no recomposition. [width] and [height] are the physical-pixel size of what was
 * drawn, which the UI needs to map touches back into the puzzle; both are zero until the first
 * frame. [hasStatusBar] lets the UI reserve the status line even while [statusText] is empty, so
 * the puzzle does not move when the text appears (Rectangles only has any while dragging).
 */
data class PuzzleFrame(
  val width: Int = 0,
  val height: Int = 0,
  val statusText: String = "",
  val hasStatusBar: Boolean = false,
  val canUndo: Boolean = false,
  val canRedo: Boolean = false,
  val status: Int = GameStatus.IN_PROGRESS,
  val gameId: String = "",
  val busy: Boolean = false,
)

/**
 * An entry in a puzzle's preset menu. Upstream models this as a tree, and several games use the
 * second level, so it is kept as one rather than flattened.
 */
sealed interface PresetEntry {
  val title: String

  /** @param id passed to `preset_menu_lookup_by_id` to recover the parameters. */
  data class Preset(
    override val title: String,
    val id: Int,
  ) : PresetEntry

  data class Submenu(
    override val title: String,
    val entries: List<PresetEntry>,
  ) : PresetEntry
}

/** One row of a `config_item` array, already decoded into something a dialog can render. */
sealed interface ConfigField {
  val name: String
  val keyword: String

  data class Text(
    override val name: String,
    override val keyword: String,
    val value: String,
  ) : ConfigField

  data class Toggle(
    override val name: String,
    override val keyword: String,
    val value: Boolean,
  ) : ConfigField

  data class Choice(
    override val name: String,
    override val keyword: String,
    val options: List<String>,
    val selected: Int,
  ) : ConfigField
}

/** A whole configuration dialog: its title and the fields to show. */
data class ConfigForm(
  val title: String,
  val fields: List<ConfigField>,
)

/** A key the current game would like offered as an on-screen button. */
data class KeyHint(
  val label: String,
  val button: Int,
)

/** Which gesture the user made, before translation into mid-end button codes. */
enum class PointerAction { DOWN, DRAG, UP }

/** Which mid-end mouse button a gesture should present as. */
enum class PointerButton { LEFT, MIDDLE, RIGHT }
