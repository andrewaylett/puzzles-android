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
 * Upstream's presets are tuned for a landscape desktop window, so many are wider than tall. A phone
 * is normally held upright, where a landscape grid wastes most of the screen, so presets are
 * presented — and applied — transposed. Every puzzle with separate width and height settings is
 * symmetric under transposition, so this changes the shape of the board but not the puzzle.
 *
 * Titles and parameters are transposed separately because the mid-end owns both: the title is just
 * a string it made up, and the parameters live behind `config_item`s.
 */
private val DIMENSIONS = Regex("""\b(\d+)x(\d+)\b""")

/** Rewrites each `WxH` in a preset title to put the larger dimension vertically. */
fun portraitTitle(title: String): String =
  DIMENSIONS.replace(title) { match ->
    val (w, h) = match.destructured
    if (w.toInt() > h.toInt()) "${h}x$w" else match.value
  }

private fun ConfigField.isNamed(name: String) =
  this is ConfigField.Text && this.name.equals(name, ignoreCase = true)

/**
 * Transposes the width and height fields of a settings form.
 *
 * Only done when there is exactly one of each, with integer values, so a puzzle that lacks the pair
 * (or names it something else) is left alone rather than half-swapped.
 *
 * @param onlyIfLandscape leave the form untouched unless it is wider than tall.
 * @return the transposed fields, or null if there was nothing to do.
 */
fun transposed(
  fields: List<ConfigField>,
  onlyIfLandscape: Boolean = true,
): List<ConfigField>? {
  val wi = fields.indices.singleOrNull { fields[it].isNamed("width") } ?: return null
  val hi = fields.indices.singleOrNull { fields[it].isNamed("height") } ?: return null
  val w = fields[wi] as ConfigField.Text
  val h = fields[hi] as ConfigField.Text
  val wn = w.value.toIntOrNull() ?: return null
  val hn = h.value.toIntOrNull() ?: return null
  if (wn == hn || (onlyIfLandscape && wn < hn)) return null
  return fields.toMutableList().also {
    it[wi] = w.copy(value = h.value)
    it[hi] = h.copy(value = w.value)
  }
}

/** Applies [portraitTitle] throughout a preset tree. */
fun List<PresetEntry>.portrait(): List<PresetEntry> =
  map {
    when (it) {
      is PresetEntry.Preset -> {
        it.copy(title = portraitTitle(it.title))
      }

      is PresetEntry.Submenu -> {
        it.copy(
          title = portraitTitle(it.title),
          entries = it.entries.portrait(),
        )
      }
    }
  }
