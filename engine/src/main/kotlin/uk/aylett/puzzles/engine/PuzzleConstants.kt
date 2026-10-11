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
 * Button codes the mid-end understands, transcribed from `puzzles.h`.
 *
 * These are a contract with the C code, not our choice, so they are spelled out as literals
 * matching the header rather than derived from an enum ordinal that could drift.
 */
object Buttons {
  const val LEFT_BUTTON = 0x0200
  const val MIDDLE_BUTTON = 0x0201
  const val RIGHT_BUTTON = 0x0202
  const val LEFT_DRAG = 0x0203
  const val MIDDLE_DRAG = 0x0204
  const val RIGHT_DRAG = 0x0205
  const val LEFT_RELEASE = 0x0206
  const val MIDDLE_RELEASE = 0x0207
  const val RIGHT_RELEASE = 0x0208
  const val CURSOR_UP = 0x0209
  const val CURSOR_DOWN = 0x020A
  const val CURSOR_LEFT = 0x020B
  const val CURSOR_RIGHT = 0x020C
  const val CURSOR_SELECT = 0x020D
  const val CURSOR_SELECT2 = 0x020E

  // The UI_* codes are synthetic keystrokes a front end sends for menu actions. They continue the
  // same enum, after a UI_LOWER_BOUND marker at 0x020F, so their values are easy to misplace by
  // one or two — and the mid-end exposes no midend_undo(), so undo is *only* reachable by sending
  // UI_UNDO to midend_process_key. Getting these wrong silently starts a new game or solves the
  // puzzle instead of undoing.
  const val UI_QUIT = 0x0210
  const val UI_NEWGAME = 0x0211
  const val UI_SOLVE = 0x0212
  const val UI_UNDO = 0x0213
  const val UI_REDO = 0x0214

  const val MOD_STYLUS = 0x0800
  const val MOD_CTRL = 0x1000
  const val MOD_SHFT = 0x2000
  const val MOD_NUM_KEYPAD = 0x4000
  const val MOD_MASK = 0x7800
}

/** `fonttype` values passed to [DrawingSink.drawText]. */
object FontType {
  const val FIXED = 0
  const val VARIABLE = 1
}

/**
 * Text alignment flags, combined as a bitmask. The vertical and horizontal parts occupy different
 * bits, so a single `align` argument carries both.
 */
@Suppress("unused")
object Align {
  const val V_NORMAL = 0x000
  const val V_CENTRE = 0x100
  const val H_LEFT = 0x000
  const val H_CENTRE = 0x001
  const val H_RIGHT = 0x002
}

/** `config_item` discriminants, from the anonymous enum in `puzzles.h`. */
object ConfigType {
  const val STRING = 0
  const val CHOICES = 1
  const val BOOLEAN = 2
  const val END = 3
}

/** Which set of settings `midend_get_config`/`midend_set_config` should operate on. */
object ConfigSection {
  const val SETTINGS = 0
  const val SEED = 1
  const val DESC = 2
  const val PREFS = 3
}

/** Result of `midend_status`. */
object GameStatus {
  const val IN_PROGRESS = 0
  const val WON = 1
  const val LOST = -1
}
