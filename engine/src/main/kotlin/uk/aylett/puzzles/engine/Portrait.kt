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
 * a string it made up, and the parameters live behind `config_item`s. The title is only touched
 * where it demonstrably describes the parameters (see [portraitTitle]).
 */
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

/**
 * Rewrites the `WxH` in a preset's title to match its transposed parameters.
 *
 * The title is free text, so it is only changed if it contains exactly the width and height the
 * preset really applies — a `10x6` that means something else, or a title that doesn't mention the
 * size at all, is left alone.
 *
 * @param settings the form the preset's parameters produce.
 */
fun portraitTitle(
  title: String,
  settings: List<ConfigField>,
): String {
  val (w, h) = settings.dimensions() ?: return title
  val (newW, newH) = (transposed(settings) ?: return title).dimensions() ?: return title
  return Regex("\\b${w}x$h\\b").replaceFirst(title, "${newW}x$newH")
}

private fun List<ConfigField>.dimensions(): Pair<Int, Int>? {
  fun valueOf(name: String) =
    filterIsInstance<ConfigField.Text>()
      .singleOrNull { it.name.equals(name, ignoreCase = true) }
      ?.value
      ?.toIntOrNull()
  return Pair(valueOf("width") ?: return null, valueOf("height") ?: return null)
}
