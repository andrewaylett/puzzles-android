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
package uk.aylett.puzzles.ui.game.dialogs

import androidx.compose.ui.text.input.KeyboardType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import uk.aylett.puzzles.engine.ConfigField

class KeyboardTypeTest {
  private fun text(
    name: String,
    value: String,
  ) = ConfigField.Text(name, "", value)

  @Test
  fun integerValuesGetANumberKeyboard() {
    assertEquals(KeyboardType.Number, keyboardTypeFor(text("Anything", "12")))
    assertEquals(KeyboardType.Number, keyboardTypeFor(text("Anything", "-3")))
  }

  @Test
  fun decimalValuesGetADecimalKeyboard() {
    assertEquals(KeyboardType.Decimal, keyboardTypeFor(text("Anything", "1.5")))
    assertEquals(KeyboardType.Decimal, keyboardTypeFor(text("Anything", ".5")))
  }

  @Test
  fun emptyValueFallsBackToTheFieldName() {
    assertEquals(KeyboardType.Number, keyboardTypeFor(text("Grid Width", "")))
    assertEquals(KeyboardType.Number, keyboardTypeFor(text("Rows", "")))
  }

  @Test
  fun nameMatchIsWholeWordOnly() {
    assertEquals(KeyboardType.Text, keyboardTypeFor(text("Resizeable", "")))
  }

  @Test
  fun otherTextStaysText() {
    assertEquals(KeyboardType.Text, keyboardTypeFor(text("Seed", "abc")))
  }
}
