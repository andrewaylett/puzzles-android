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

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import uk.aylett.puzzles.engine.PointerAction
import uk.aylett.puzzles.engine.PointerButton
import uk.aylett.puzzles.engine.PuzzleFrame
import kotlin.math.roundToInt

/**
 * Hosts the [SurfaceView] the engine presents the puzzle on (pixel-for-pixel, never scaled: the
 * mid-end already rendered it at exactly the size we asked for, so any resampling would only blur
 * it) and translates touch gestures into the mid-end's mouse model.
 *
 * Nothing is drawn from here. The pixels reach the surface from the engine thread, through the
 * system's own buffer queue, so there is no bitmap shared with Compose's render thread to race on.
 * [frame] supplies only the drawn size, which touch mapping needs.
 *
 * The mapping, matching how every other Simon Tatham front end behaves, is decided by
 * [GestureTranslator]; this only feeds it pointer events and the long-press timeout. Several
 * puzzles set `REQUIRE_RBUTTON` and are unplayable without the hold gesture.
 */
@Composable
fun PuzzleCanvas(
  frame: PuzzleFrame,
  onSurfaceChanged: (Surface, Int) -> Unit,
  onSurfaceDestroyed: () -> Unit,
  onSizeChanged: (Int, Int, Float) -> Unit,
  onPointerEvent: (PointerAction, PointerButton, Int, Int) -> Unit,
  modifier: Modifier = Modifier,
  swapPressGesture: Boolean = false,
) {
  var canvasSize by remember { mutableStateOf(IntSize.Zero) }
  val density = LocalDensity.current.density

  // Fills what the puzzle doesn't cover, so the surface matches the screen behind it; a surface's
  // untouched pixels would otherwise show as black.
  val background = MaterialTheme.colorScheme.background.toArgb()

  // The gesture handler below is keyed on Unit so it is not torn down and restarted on every
  // frame, which means it only ever sees the values from the first composition. Anything that can
  // change afterwards — the setting toggled from the toolbar, each new frame's size, the
  // callbacks — has to be read through these instead of captured directly.
  val currentSwap = rememberUpdatedState(swapPressGesture)
  val currentFrame = rememberUpdatedState(frame)
  val currentOnPointerEvent = rememberUpdatedState(onPointerEvent)
  val currentBackground = rememberUpdatedState(background)
  val currentOnSurfaceChanged = rememberUpdatedState(onSurfaceChanged)
  val currentOnSurfaceDestroyed = rememberUpdatedState(onSurfaceDestroyed)

  // Gestures and sizing sit on this Box rather than on the AndroidView: a SurfaceView doesn't
  // consume touches, so they reach the parent, and the Box's size is the SurfaceView's size — the
  // surface's buffer is that size unless a fixed size is set, which is deliberately never done, so
  // the centring here and in SurfacePresenter agree exactly.
  Box(
    modifier =
      modifier
        .fillMaxSize()
        .onSizeChanged {
          canvasSize = it
          onSizeChanged(it.width, it.height, density)
        }.pointerInput(Unit) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val drawn = currentFrame.value
            if (drawn.width <= 0 || drawn.height <= 0) return@awaitEachGesture
            val offset = bitmapOffset(canvasSize, drawn.width, drawn.height)
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
            var lastX = down.position.x
            var lastY = down.position.y
            try {
              try {
                withTimeout(viewConfiguration.longPressTimeoutMillis) {
                  while (translator.isUndecided) {
                    // No change for our pointer, or one no longer pressed, means the gesture is
                    // over. A cancelled gesture (the system back gesture claiming the touch) can
                    // arrive as either, and waiting for a clean release that never comes would
                    // leave this handler stuck and every later touch ignored.
                    val change = nextChange(down.id)
                    if (change == null || !change.pressed) {
                      if (change != null) translator.up(change.position.x, change.position.y)
                      released = true
                      break
                    }
                    lastX = change.position.x
                    lastY = change.position.y
                    translator.move(lastX, lastY)
                  }
                }
              } catch (_: PointerEventTimeoutCancellationException) {
                // Outlasted the timeout while still down and within the slop: a long press.
                translator.longPressElapsed()
              }

              while (!released) {
                val change = nextChange(down.id)
                if (change == null || !change.pressed) {
                  if (change != null) translator.up(change.position.x, change.position.y)
                  released = true
                  break
                }
                change.consume()
                lastX = change.position.x
                lastY = change.position.y
                translator.move(lastX, lastY)
              }
            } finally {
              // Also covers this coroutine being cancelled outright. A no-op after a normal release.
              translator.cancel(lastX, lastY)
            }
          }
        },
  ) {
    AndroidView(
      modifier = Modifier.fillMaxSize(),
      factory = { context ->
        SurfaceView(context).apply {
          holder.addCallback(
            object : SurfaceHolder.Callback {
              // Nothing to do on creation: surfaceChanged always follows it, and is the first point
              // at which the surface has a size to present into.
              override fun surfaceCreated(holder: SurfaceHolder) = Unit

              override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
              ) = currentOnSurfaceChanged.value(holder.surface, currentBackground.value)

              override fun surfaceDestroyed(holder: SurfaceHolder) =
                currentOnSurfaceDestroyed.value()
            },
          )
        }
      },
    )
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

/** The next event's change for [pointerId], or null if that pointer is no longer in it. */
private suspend fun AwaitPointerEventScope.nextChange(pointerId: PointerId): PointerInputChange? =
  awaitPointerEvent().changes.firstOrNull { it.id == pointerId }
