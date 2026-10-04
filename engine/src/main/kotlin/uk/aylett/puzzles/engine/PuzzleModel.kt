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
)

/**
 * Everything about the puzzle the UI shows other than its pixels.
 *
 * The pixels go straight to the UI's surface from the engine thread (see
 * [PuzzleSession.attachSurface]) and are deliberately not part of this value: it is compared by
 * equality, so a redraw that changes nothing here — most animation ticks — publishes nothing and
 * costs the UI no recomposition. [width] and [height] are the physical-pixel size of what was
 * drawn, which the UI needs to map touches back into the puzzle; both are zero until the first
 * frame.
 */
data class PuzzleFrame(
  val width: Int = 0,
  val height: Int = 0,
  val statusText: String = "",
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
