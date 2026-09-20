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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import uk.aylett.puzzles.engine.PointerAction
import uk.aylett.puzzles.engine.PointerButton
import uk.aylett.puzzles.engine.PuzzleFrame
import kotlin.math.roundToInt

/**
 * Displays [PuzzleFrame.bitmap] pixel-for-pixel (never scaled: the mid-end already rendered it at
 * exactly the size we asked for, so any resampling here would only blur it) and translates touch
 * gestures into the mid-end's mouse model.
 *
 * The mapping, matching how every other Simon Tatham front end behaves: a quick tap is a left
 * click, a press-and-hold is a right click (several puzzles are unplayable without it, since they
 * set `REQUIRE_RBUTTON`), and moving before either resolves is a left-button drag. [swapPressGesture]
 * reverses which button a tap versus a hold presents as, for players who find one or the other
 * gesture awkward to perform reliably; a drag is always the left button regardless, since it is
 * already a distinct gesture from either.
 */
@Composable
fun PuzzleCanvas(
  frame: PuzzleFrame,
  onSizeChanged: (Int, Int) -> Unit,
  onPointerEvent: (PointerAction, PointerButton, Int, Int) -> Unit,
  modifier: Modifier = Modifier,
  swapPressGesture: Boolean = false,
) {
  var canvasSize by remember { mutableStateOf(IntSize.Zero) }
  val bitmap = frame.bitmap

  // The engine recycles bitmap buffers between frames (an animated puzzle redraws at ~20Hz, and
  // a fresh full-screen allocation each time would churn tens of megabytes a second), so the same
  // Bitmap instance comes back with different pixels. Rewrapping it is keyed on frameSeq rather
  // than the bitmap's identity, which would never change and so would never invalidate.
  val image = remember(frame.frameSeq) { bitmap?.asImageBitmap() }

  Canvas(
    modifier =
      modifier
        .fillMaxSize()
        .onSizeChanged {
          canvasSize = it
          onSizeChanged(it.width, it.height)
        }.pointerInput(Unit) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val bmp = bitmap ?: return@awaitEachGesture
            val offset = bitmapOffset(canvasSize, bmp.width, bmp.height)

            fun send(
              action: PointerAction,
              button: PointerButton,
              position: Offset,
            ) {
              val x = (position.x - offset.x).roundToInt()
              val y = (position.y - offset.y).roundToInt()
              onPointerEvent(action, button, x, y)
            }

            val tapButton = if (swapPressGesture) PointerButton.RIGHT else PointerButton.LEFT
            val holdButton = if (swapPressGesture) PointerButton.LEFT else PointerButton.RIGHT

            when (val resolution = resolveGesture(down)) {
              is GestureResolution.Tap -> {
                send(PointerAction.DOWN, tapButton, down.position)
                send(PointerAction.UP, tapButton, down.position)
              }

              is GestureResolution.LongPress -> {
                send(PointerAction.DOWN, holdButton, down.position)
                trackUntilRelease(down.id) { change ->
                  send(PointerAction.UP, holdButton, change.position)
                }
              }

              is GestureResolution.Drag -> {
                send(PointerAction.DOWN, PointerButton.LEFT, down.position)
                send(PointerAction.DRAG, PointerButton.LEFT, resolution.change.position)
                trackUntilRelease(down.id) { change ->
                  send(PointerAction.DRAG, PointerButton.LEFT, change.position)
                }
              }
            }
          }
        },
  ) {
    if (bitmap != null && image != null) {
      val offset = bitmapOffset(canvasSize, bitmap.width, bitmap.height)
      drawImage(image, topLeft = offset)
    }
  }
}

private fun bitmapOffset(
  canvasSize: IntSize,
  bitmapWidth: Int,
  bitmapHeight: Int,
): Offset =
  Offset(
    ((canvasSize.width - bitmapWidth) / 2).toFloat(),
    ((canvasSize.height - bitmapHeight) / 2).toFloat(),
  )

private sealed interface GestureResolution {
  data object Tap : GestureResolution

  data object LongPress : GestureResolution

  data class Drag(
    val change: PointerInputChange,
  ) : GestureResolution
}

/**
 * Waits up to the system's long-press timeout for the gesture to declare itself: released
 * without moving is a tap, moved beyond the touch slop is a drag, and simply outlasting the
 * timer is a long press.
 *
 * Deliberately [AwaitPointerEventScope.withTimeoutOrNull], the member on this receiver, rather
 * than `kotlinx.coroutines.withTimeout`: the member shadows the top-level function inside this
 * scope, so a `catch` written against `kotlinx.coroutines.TimeoutCancellationException` silently
 * never fires — the member throws `PointerEventTimeoutCancellationException`, which extends
 * `CancellationException` directly, not that class. `awaitEachGesture` then swallows it as an
 * ordinary cancellation and restarts the gesture loop, so a long press appeared to do nothing at
 * all. Using `withTimeoutOrNull` here sidesteps the exception entirely.
 */
private suspend fun AwaitPointerEventScope.resolveGesture(
  down: PointerInputChange,
): GestureResolution {
  val downPosition = down.position
  return withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
    while (true) {
      val event = awaitPointerEvent()
      val change = event.changes.firstOrNull { it.id == down.id } ?: continue
      if (change.changedToUp()) {
        change.consume()
        return@withTimeoutOrNull GestureResolution.Tap
      }
      val distance = (change.position - downPosition).getDistance()
      if (distance > viewConfiguration.touchSlop) {
        change.consume()
        return@withTimeoutOrNull GestureResolution.Drag(change)
      }
      change.consume()
    }
    // Unreachable: the loop above only ever exits via an explicit return@withTimeoutOrNull.
    // Kotlin can't prove that statically, so this satisfies the block's declared return type.
    error("unreachable")
  } ?: GestureResolution.LongPress
}

/** Follows a pointer already down, invoking [onChange] on move and once more on release. */
private suspend fun AwaitPointerEventScope.trackUntilRelease(
  pointerId: PointerId,
  onChange: (PointerInputChange) -> Unit,
) {
  while (true) {
    val event = awaitPointerEvent()
    val change = event.changes.firstOrNull { it.id == pointerId } ?: continue
    change.consume()
    if (change.changedToUp()) {
      onChange(change)
      return
    }
    onChange(change)
  }
}
