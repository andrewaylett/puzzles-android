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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import uk.aylett.puzzles.engine.ConfigField

class SettingsCatalogueTest {
  private val shortcuts = ConfigField.Toggle("Shortcuts", "one-key-shortcuts", false)

  private fun keep(value: Boolean) = ConfigField.Toggle("Keep", "pencil-keep-highlight", value)

  private val mouse = ConfigField.Choice("Mouse button order", "left-button", listOf("A", "B"), 0)
  private val grid = ConfigField.Toggle("Grid", "show-grid", false)

  private val catalogue =
    buildCatalogue(
      listOf(
        "Keen" to listOf(shortcuts, keep(true)),
        "Solo" to listOf(shortcuts, keep(false)),
        "Slant" to listOf(shortcuts, mouse),
        "Map" to listOf(shortcuts, grid),
      ),
    )

  @Test
  fun `groups settings by how many puzzles have them`() {
    assertEquals(listOf("Shortcuts"), catalogue.general.map { it.field.name })
    assertEquals(listOf("Keen", "Solo"), catalogue.shared.single().games)
    assertEquals(listOf("Map"), catalogue.perGame.map { it.first })
    assertEquals(listOf("Mouse button order"), catalogue.controls.map { it.field.name })
  }

  @Test
  fun `puzzles that disagree resolve to the first one's value`() {
    assertEquals(keep(true), catalogue.shared.single().field)
  }
}
