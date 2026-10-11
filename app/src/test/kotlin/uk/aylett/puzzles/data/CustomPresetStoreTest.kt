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
package uk.aylett.puzzles.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import uk.aylett.puzzles.engine.ConfigField
import java.io.File

class CustomPresetStoreTest {
  @TempDir lateinit var dir: File

  private fun mines(
    width: Int,
    height: Int,
    count: Int = 10,
    solvable: Boolean = true,
  ) = listOf(
    ConfigField.Text("Width", "", "$width"),
    ConfigField.Text("Height", "", "$height"),
    ConfigField.Text("Mines", "", "$count"),
    ConfigField.Toggle("Ensure solubility", "", solvable),
  )

  private fun TestScope.store() = CustomPresetStore(File(dir, "presets.json"), backgroundScope)

  @Test
  fun `title reads size then other parameters`() {
    assertEquals("9x9, Mines 10, Ensure solubility", mines(9, 9).customTitle())
    assertEquals("9x9, Mines 10", mines(9, 9, solvable = false).customTitle())
  }

  @Test
  fun `title uses choice labels and falls back when empty`() {
    val fields = listOf(ConfigField.Choice("Difficulty", "", listOf("Easy", "Hard"), 1))
    assertEquals("Hard", fields.customTitle())
    assertEquals("Custom", emptyList<ConfigField>().customTitle())
  }

  @Test
  fun `a used preset is stored for its own game only`() =
    runTest {
      val s = store()
      s.use("Mines", mines(9, 9))
      assertEquals(
        listOf("9x9, Mines 10, Ensure solubility"),
        s.presets("Mines").first().map {
          it.title
        },
      )
      assertTrue(s.presets("Solo").first().isEmpty())
    }

  @Test
  fun `only the three most recent are kept, newest first`() =
    runTest {
      val s = store()
      (5..9).forEach { s.use("Mines", mines(it, it)) }
      assertEquals(
        listOf("9x9", "8x8", "7x7"),
        s.presets("Mines").first().map { it.title.substringBefore(",") },
      )
    }

  @Test
  fun `reusing a preset moves it to the front without duplicating it`() =
    runTest {
      val s = store()
      listOf(5, 6, 7).forEach { s.use("Mines", mines(it, it)) }
      s.use("Mines", mines(5, 5))
      assertEquals(
        listOf("5x5", "7x7", "6x6"),
        s.presets("Mines").first().map { it.title.substringBefore(",") },
      )
    }

  @Test
  fun `a corrupt file reads as empty`() =
    runTest {
      File(dir, "presets.json").writeText("{not json")
      assertTrue(store().presets("Mines").first().isEmpty())
    }

  @Test
  fun `applying writes values over the form and matches recognises the result`() =
    runTest {
      val s = store()
      s.use("Mines", mines(12, 7, count = 20, solvable = false))
      val preset = s.presets("Mines").first().single()
      val applied = preset.applyTo(mines(9, 9))
      assertEquals(mines(12, 7, count = 20, solvable = false), applied)
      assertTrue(preset.matches(applied))
      assertFalse(preset.matches(mines(9, 9)))
    }

  @Test
  fun `choices are applied by label`() {
    val before = ConfigField.Choice("Difficulty", "", listOf("Easy", "Hard"), 1)
    val preset = CustomPreset("Easy", listOf(StoredField("Difficulty", "Easy")))
    assertEquals(listOf(before.copy(selected = 0)), preset.applyTo(listOf(before)))
  }

  @Test
  fun `a preset pushed off the list is reported, and reuse is not an eviction`() =
    runTest {
      val evicted = mutableListOf<Pair<String, CustomPreset>>()
      val store =
        CustomPresetStore(File(dir, "evict.json"), backgroundScope) { g, p -> evicted += g to p }
      (1..3).forEach { store.use("Mines", mines(it + 5, 9)) }
      store.use("Mines", mines(6, 9))
      assertEquals(emptyList<Any>(), evicted)

      store.use("Mines", mines(20, 9))

      assertEquals(listOf("Mines"), evicted.map { it.first })
      assertEquals("7x9, Mines 10, Ensure solubility", evicted.single().second.title)
    }
}
