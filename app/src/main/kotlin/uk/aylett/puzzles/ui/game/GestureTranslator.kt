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

import uk.aylett.puzzles.engine.PointerAction
import uk.aylett.puzzles.engine.PointerButton
import kotlin.math.hypot

/**
 * Turns one touch gesture into the mouse-button events the mid-end understands. Pure, so every
 * decision about *which* events to send is testable on the JVM; [PuzzleCanvas] only feeds it
 * pointer events and the long-press timeout, which need a real Compose pointer scope.
 *
 * Tap, hold and drag are mutually exclusive outcomes of one gesture, decided by whichever happens
 * first: release (tap), movement past [touchSlop] (drag) or [longPressElapsed] (hold).
 *
 * - **Tap**: [tapButton] down then up, both at the touch-down position, so a finger that wobbles
 *   a little on lift still clicks where it landed.
 * - **Drag**: a tap that moved, so it is [tapButton] too, pressed at the touch-down position and
 *   then following the finger.
 * - **Hold**: [holdButton] goes down at the touch-down position, and *keeps tracking*: moving
 *   afterwards drags with that same button, because that is how a mouse behaves and the mid-end
 *   passes right-drags through to the back end.
 *
 * [swapPress] reverses which of left/right a tap (and so a drag) and a hold present as. It is fixed for the
 * lifetime of a gesture, so toggling it mid-gesture cannot send a down on one button and its up
 * on the other, which would leave the mid-end believing a button was still held.
 */
internal class GestureTranslator(
  private val touchSlop: Float,
  swapPress: Boolean,
  private val send: (PointerAction, PointerButton, Float, Float) -> Unit,
) {
  private val tapButton = if (swapPress) PointerButton.RIGHT else PointerButton.LEFT
  private val holdButton = if (swapPress) PointerButton.LEFT else PointerButton.RIGHT

  private var downX = 0f
  private var downY = 0f

  /** Non-null once a button is down in the mid-end, so moves are drags and release is an up. */
  private var activeButton: PointerButton? = null

  private var finished = false

  /** True until the gesture has resolved as a tap, a drag or a hold. */
  val isUndecided: Boolean get() = activeButton == null && !finished

  fun down(
    x: Float,
    y: Float,
  ) {
    downX = x
    downY = y
  }

  fun move(
    x: Float,
    y: Float,
  ) {
    val button = activeButton
    when {
      finished -> {}

      button != null -> {
        send(PointerAction.DRAG, button, x, y)
      }

      hypot(x - downX, y - downY) > touchSlop -> {
        activeButton = tapButton
        send(PointerAction.DOWN, tapButton, downX, downY)
        send(PointerAction.DRAG, tapButton, x, y)
      }
    }
  }

  /** The press outlasted the system's long-press timeout without moving past the slop. */
  fun longPressElapsed() {
    if (!isUndecided) return
    activeButton = holdButton
    send(PointerAction.DOWN, holdButton, downX, downY)
  }

  fun up(
    x: Float,
    y: Float,
  ) {
    val button = activeButton
    when {
      finished -> {}

      button != null -> {
        send(PointerAction.UP, button, x, y)
      }

      else -> {
        send(PointerAction.DOWN, tapButton, downX, downY)
        send(PointerAction.UP, tapButton, downX, downY)
      }
    }
    finished = true
  }
}
