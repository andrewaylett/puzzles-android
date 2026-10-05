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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PortraitTest {
  @Test
  fun landscapeTitlesAreTransposed() {
    assertEquals("6x10 Easy", portraitTitle("10x6 Easy"))
    assertEquals("15x15", portraitTitle("15x15"))
    assertEquals("6x10", portraitTitle("6x10"))
  }

  private fun form(
    w: String,
    h: String,
  ) = listOf(
    ConfigField.Text("Width", "", w),
    ConfigField.Text("Height", "", h),
    ConfigField.Toggle("Wrap", "", false),
  )

  @Test
  fun landscapeSettingsAreTransposed() {
    assertEquals(form("6", "10"), transposed(form("10", "6")))
  }

  @Test
  fun portraitAndSquareSettingsAreLeftAlone() {
    assertNull(transposed(form("6", "10")))
    assertNull(transposed(form("7", "7")))
  }

  @Test
  fun unconditionalTransposeSwapsPortrait() {
    assertEquals(form("10", "6"), transposed(form("6", "10"), onlyIfLandscape = false))
  }

  @Test
  fun puzzlesWithoutTheFieldPairAreLeftAlone() {
    assertNull(transposed(listOf(ConfigField.Text("Size", "", "5"))))
  }
}
