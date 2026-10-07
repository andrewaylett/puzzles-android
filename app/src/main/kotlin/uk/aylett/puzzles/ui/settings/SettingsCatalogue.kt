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
package uk.aylett.puzzles.ui.settings

import uk.aylett.puzzles.data.settingId
import uk.aylett.puzzles.engine.ConfigField

/** One preference as the Settings screen shows it, and the games it affects. */
data class CatalogueEntry(
  val id: String,
  /** Carries the effective value when nothing has been stored yet: see [buildCatalogue]. */
  val field: ConfigField,
  val games: List<String>,
)

/**
 * Every puzzle preference, grouped by how widely it applies, so the Settings screen can label each
 * group honestly: [general] ones exist in every puzzle, [controls] change what a touch does,
 * [shared] ones exist in some puzzles, and [perGame] ones in exactly one.
 */
data class SettingsCatalogue(
  val general: List<CatalogueEntry>,
  val controls: List<CatalogueEntry>,
  val shared: List<CatalogueEntry>,
  val perGame: List<Pair<String, List<CatalogueEntry>>>,
) {
  val all: List<CatalogueEntry> get() = general + controls + shared + perGame.flatMap { it.second }
}

/**
 * Upstream's keywords for "which mouse button does what" (Range and Slant). They are kept with the
 * touch controls rather than their puzzle because they compose with the app's tap/hold swap: both
 * on means the buttons are swapped twice.
 */
private val CONTROL_KEYWORDS = setOf("left-button", "left-mouse-button")

/**
 * @param perGame each puzzle's name with the preference fields it reports, in puzzle order, with
 *   each field holding the value that puzzle currently uses.
 *
 * Where puzzles disagree about a shared setting (they were configured separately before settings
 * were pooled) the first puzzle's value is taken, so that Settings shows one value and every puzzle
 * then agrees with it.
 */
fun buildCatalogue(perGame: List<Pair<String, List<ConfigField>>>): SettingsCatalogue {
  val order = mutableListOf<String>()
  val fields = mutableMapOf<String, ConfigField>()
  val games = mutableMapOf<String, MutableList<String>>()
  for ((name, form) in perGame) {
    for (field in form) {
      val id = field.settingId() ?: continue
      if (id !in fields) {
        order += id
        fields[id] = field
      }
      games.getOrPut(id) { mutableListOf() } += name
    }
  }
  val entries = order.map { CatalogueEntry(it, fields.getValue(it), games.getValue(it)) }
  val total = perGame.size

  val (controls, rest) = entries.partition { it.field.keyword in CONTROL_KEYWORDS }
  return SettingsCatalogue(
    general = rest.filter { it.games.size == total },
    controls = controls,
    shared = rest.filter { it.games.size in 2 until total },
    perGame =
      rest
        .filter { it.games.size == 1 }
        .groupBy { it.games.single() }
        .toList()
        .sortedBy { it.first },
  )
}
