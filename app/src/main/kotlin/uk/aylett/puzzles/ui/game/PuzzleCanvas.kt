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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
 * The mapping, matching how every other Simon Tatham front end behaves, is decided by
 * [GestureTranslator]; this only feeds it pointer events and the long-press timeout. Several
 * puzzles set `REQUIRE_RBUTTON` and are unplayable without the hold gesture.
 */
@Composable
fun PuzzleCanvas(
  frame: PuzzleFrame,
  onSizeChanged: (Int, Int, Float) -> Unit,
  onPointerEvent: (PointerAction, PointerButton, Int, Int) -> Unit,
  modifier: Modifier = Modifier,
  swapPressGesture: Boolean = false,
) {
  var canvasSize by remember { mutableStateOf(IntSize.Zero) }
  val bitmap = frame.bitmap
  val density = LocalDensity.current.density

  // The gesture handler below is keyed on Unit so it is not torn down and restarted on every
  // frame, which means it only ever sees the values from the first composition. Anything that can
  // change afterwards — the setting toggled from the toolbar, each new frame's bitmap, the
  // callback — has to be read through these instead of captured directly.
  val currentSwap = rememberUpdatedState(swapPressGesture)
  val currentBitmap = rememberUpdatedState(bitmap)
  val currentOnPointerEvent = rememberUpdatedState(onPointerEvent)

  // The engine recycles bitmap buffers between frames (an animated puzzle redraws up to display
  // refresh rate, and a fresh full-screen allocation each time would churn tens of megabytes a
  // second), so the same Bitmap instance comes back with different pixels. Rewrapping it is keyed
  // on frameSeq rather than the bitmap's identity, which would never change and so would never
  // invalidate.
  val image = remember(frame.frameSeq) { bitmap?.asImageBitmap() }

  Canvas(
    modifier =
      modifier
        .fillMaxSize()
        .onSizeChanged {
          canvasSize = it
          onSizeChanged(it.width, it.height, density)
        }.pointerInput(Unit) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val bmp = currentBitmap.value ?: return@awaitEachGesture
            val offset = bitmapOffset(canvasSize, bmp.width, bmp.height)
            val translator =
              GestureTranslator(
                touchSlop = viewConfiguration.touchSlop,
                swapPress = currentSwap.value,
              ) { action, button, x, y ->
                currentOnPointerEvent.value(
                  action,
                  button,
                  (x - offset.x).roundToInt(),
                  (y - offset.y).roundToInt(),
                )
              }
            translator.down(down.position.x, down.position.y)

            var released = false
            try {
              withTimeout(viewConfiguration.longPressTimeoutMillis) {
                while (translator.isUndecided) {
                  val change = nextChange(down.id) ?: continue
                  if (change.changedToUp()) {
                    translator.up(change.position.x, change.position.y)
                    released = true
                  } else {
                    translator.move(change.position.x, change.position.y)
                  }
                }
              }
            } catch (_: PointerEventTimeoutCancellationException) {
              // Outlasted the timeout while still down and within the slop: a long press.
              translator.longPressElapsed()
            }

            // Consumption is checked before it happens, not after: changedToUp is gated on
            // !isConsumed, so consuming first would hide the real release and wait forever.
            while (!released) {
              val change = nextChange(down.id) ?: continue
              released = change.changedToUp()
              change.consume()
              if (released) {
                translator.up(change.position.x, change.position.y)
              } else {
                translator.move(change.position.x, change.position.y)
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

/** The next change for [pointerId], skipping events that only concern other pointers. */
private suspend fun AwaitPointerEventScope.nextChange(pointerId: PointerId): PointerInputChange? =
  awaitPointerEvent().changes.firstOrNull { it.id == pointerId }
