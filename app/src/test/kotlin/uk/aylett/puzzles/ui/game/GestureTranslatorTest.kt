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
package uk.aylett.puzzles.ui.game

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import uk.aylett.puzzles.engine.PointerAction
import uk.aylett.puzzles.engine.PointerAction.DOWN
import uk.aylett.puzzles.engine.PointerAction.DRAG
import uk.aylett.puzzles.engine.PointerAction.UP
import uk.aylett.puzzles.engine.PointerButton
import uk.aylett.puzzles.engine.PointerButton.LEFT
import uk.aylett.puzzles.engine.PointerButton.RIGHT

class GestureTranslatorTest {
  private data class Sent(
    val action: PointerAction,
    val button: PointerButton,
    val x: Float,
    val y: Float,
  )

  private val sent = mutableListOf<Sent>()

  private fun translator(swap: Boolean = false) =
    GestureTranslator(touchSlop = SLOP, swapPress = swap) { action, button, x, y ->
      sent += Sent(action, button, x, y)
    }

  @Test
  fun `tap is a left click at the touch-down position`() {
    val t = translator()
    t.down(10f, 20f)
    t.up(11f, 21f) // a little wobble on lift must not move the click

    assertEquals(listOf(Sent(DOWN, LEFT, 10f, 20f), Sent(UP, LEFT, 10f, 20f)), sent)
  }

  @Test
  fun `movement within the slop is still a tap`() {
    val t = translator()
    t.down(10f, 10f)
    t.move(10f + SLOP, 10f)
    assertTrue(t.isUndecided)
    t.up(10f + SLOP, 10f)

    assertEquals(listOf(DOWN, UP), sent.map { it.action })
    assertEquals(setOf(LEFT), sent.map { it.button }.toSet())
  }

  @Test
  fun `drag presses at the start, then follows the finger, then releases where it lifted`() {
    val t = translator()
    t.down(10f, 10f)
    t.move(10f + SLOP + 1, 10f)
    assertFalse(t.isUndecided)
    t.move(30f, 40f)
    t.up(50f, 60f)

    assertEquals(
      listOf(
        Sent(DOWN, LEFT, 10f, 10f),
        Sent(DRAG, LEFT, 10f + SLOP + 1, 10f),
        Sent(DRAG, LEFT, 30f, 40f),
        Sent(UP, LEFT, 50f, 60f),
      ),
      sent,
    )
  }

  @Test
  fun `slop is measured as distance, not per axis`() {
    val t = translator()
    t.down(0f, 0f)
    t.move(SLOP * 0.8f, SLOP * 0.8f) // each axis inside the slop, together outside it

    assertEquals(listOf(DOWN, DRAG), sent.map { it.action })
  }

  @Test
  fun `long press is a right press at the touch-down position, released where it lifted`() {
    val t = translator()
    t.down(10f, 20f)
    t.longPressElapsed()
    assertFalse(t.isUndecided)
    t.up(12f, 22f)

    assertEquals(listOf(Sent(DOWN, RIGHT, 10f, 20f), Sent(UP, RIGHT, 12f, 22f)), sent)
  }

  @Test
  fun `long press then drag follows the finger with the right button`() {
    val t = translator()
    t.down(10f, 20f)
    t.longPressElapsed()
    t.move(15f, 25f)
    t.move(40f, 50f)
    t.up(40f, 50f)

    assertEquals(
      listOf(
        Sent(DOWN, RIGHT, 10f, 20f),
        Sent(DRAG, RIGHT, 15f, 25f),
        Sent(DRAG, RIGHT, 40f, 50f),
        Sent(UP, RIGHT, 40f, 50f),
      ),
      sent,
    )
  }

  @Test
  fun `a drag cannot become a long press`() {
    val t = translator()
    t.down(0f, 0f)
    t.move(SLOP + 1, 0f)
    sent.clear()
    t.longPressElapsed()

    assertEquals(emptyList<Sent>(), sent)
  }

  @Test
  fun `swapped, tap is a right click and long press is a left press`() {
    val tap = translator(swap = true)
    tap.down(1f, 2f)
    tap.up(1f, 2f)
    assertEquals(setOf(RIGHT), sent.map { it.button }.toSet())

    sent.clear()
    val hold = translator(swap = true)
    hold.down(1f, 2f)
    hold.longPressElapsed()
    hold.move(5f, 6f)
    hold.up(5f, 6f)
    assertEquals(listOf(DOWN, DRAG, UP), sent.map { it.action })
    assertEquals(setOf(LEFT), sent.map { it.button }.toSet())
  }

  @Test
  fun `swapped, a plain drag is a right-button drag`() {
    val t = translator(swap = true)
    t.down(0f, 0f)
    t.move(SLOP + 1, 0f)
    t.up(SLOP + 1, 0f)

    assertEquals(listOf(DOWN, DRAG, UP), sent.map { it.action })
    assertEquals(setOf(RIGHT), sent.map { it.button }.toSet())
  }

  @Test
  fun `events after the gesture has finished are ignored`() {
    val t = translator()
    t.down(0f, 0f)
    t.up(0f, 0f)
    sent.clear()
    t.move(100f, 100f)
    t.up(100f, 100f)
    t.longPressElapsed()

    assertEquals(emptyList<Sent>(), sent)
  }

  private companion object {
    const val SLOP = 8f
  }
}
