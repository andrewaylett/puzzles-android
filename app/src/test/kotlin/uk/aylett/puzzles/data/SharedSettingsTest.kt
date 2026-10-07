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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import uk.aylett.puzzles.engine.ConfigField

class SharedSettingsTest {
  private val keep = ConfigField.Toggle("Keep highlight", "pencil-keep-highlight", false)

  private fun flash(vararg options: String) =
    ConfigField.Choice("Flash", "flash-type", options.toList(), 0)

  @Test
  fun `same keyword with different options is a different setting`() {
    assertNotEquals(
      flash("Cyclic", "All").settingId(),
      flash("Unidirectional", "Gears").settingId(),
    )
  }

  @Test
  fun `text fields are not settings`() {
    assertNull(ConfigField.Text("Width", "", "5").settingId())
  }

  @Test
  fun `overlay applies stored values by label and leaves others alone`() {
    val choice = flash("Cyclic", "All")
    val updated =
      listOf(keep, choice).overlay(
        mapOf(keep.settingId()!! to "true", choice.settingId()!! to "All"),
      )
    assertEquals(listOf(keep.copy(value = true), choice.copy(selected = 1)), updated)
    assertEquals(listOf(keep, choice), listOf(keep, choice).overlay(emptyMap()))
  }

  @Test
  fun `a stored choice that no longer exists is ignored`() {
    val choice = flash("Cyclic", "All")
    assertEquals(listOf(choice), listOf(choice).overlay(mapOf(choice.settingId()!! to "Gone")))
  }

  @Test
  fun `toSettings round trips through overlay`() {
    val fields = listOf(keep.copy(value = true), flash("Cyclic", "All").copy(selected = 1))
    assertEquals(fields, listOf(keep, flash("Cyclic", "All")).overlay(fields.toSettings()))
  }
}
