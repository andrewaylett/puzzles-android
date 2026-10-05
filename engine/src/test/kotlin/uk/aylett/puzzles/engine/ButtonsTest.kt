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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ButtonsTest {
  private val mouseAndCursor =
    listOf(
      Buttons.LEFT_BUTTON,
      Buttons.MIDDLE_BUTTON,
      Buttons.RIGHT_BUTTON,
      Buttons.LEFT_DRAG,
      Buttons.MIDDLE_DRAG,
      Buttons.RIGHT_DRAG,
      Buttons.LEFT_RELEASE,
      Buttons.MIDDLE_RELEASE,
      Buttons.RIGHT_RELEASE,
      Buttons.CURSOR_UP,
      Buttons.CURSOR_DOWN,
      Buttons.CURSOR_LEFT,
      Buttons.CURSOR_RIGHT,
      Buttons.CURSOR_SELECT,
      Buttons.CURSOR_SELECT2,
    )

  @Test
  fun buttonsRunConsecutivelyFromLeftButton() {
    // puzzles.h declares these as one anonymous enum; a transposed value is miserable to debug.
    assertEquals((0x0200..0x020E).toList(), mouseAndCursor)
  }

  @Test
  fun uiKeysFollowTheLowerBoundMarker() {
    // UI_LOWER_BOUND occupies 0x020F, so the first UI key is 0x0210.
    val ui =
      listOf(
        Buttons.UI_QUIT,
        Buttons.UI_NEWGAME,
        Buttons.UI_SOLVE,
        Buttons.UI_UNDO,
        Buttons.UI_REDO,
      )
    assertEquals((0x0210..0x0214).toList(), ui)
  }

  @Test
  fun modifiersAreDisjointBitsWithinTheMask() {
    val mods =
      listOf(Buttons.MOD_STYLUS, Buttons.MOD_CTRL, Buttons.MOD_SHFT, Buttons.MOD_NUM_KEYPAD)
    mods.forEach { assertEquals(1, Integer.bitCount(it)) }
    assertEquals(Buttons.MOD_MASK, mods.fold(0) { acc, m -> acc or m })
  }

  @Test
  fun modifiersDoNotOverlapButtonCodes() {
    (mouseAndCursor + Buttons.UI_REDO).forEach { assertEquals(0, it and Buttons.MOD_MASK) }
  }

  @Test
  fun alignmentFlagsCombineWithoutInterfering() {
    val combined = Align.V_CENTRE or Align.H_RIGHT
    assertEquals(Align.V_CENTRE, combined and 0x100)
    assertEquals(Align.H_RIGHT, combined and 0x003)
  }
}

class GameInfoTest {
  private fun game(name: String) =
    GameInfo(0, name, canSolve = true, canConfigure = true, canFormatAsText = true)

  @Test
  fun pencilMarkFillIsOfferedToThePencilMarkGames() {
    listOf("Solo", "Keen", "Towers", "Unequal", "Undead").forEach {
      assertTrue(game(it).canFillPencilMarks, it)
    }
  }

  @Test
  fun mMeansSomethingElseInTheOtherGames() {
    listOf("Bridges", "Flip", "Galaxies", "Flood", "Same Game", "Pearl", "Unknown").forEach {
      assertFalse(game(it).canFillPencilMarks, it)
    }
  }
}
