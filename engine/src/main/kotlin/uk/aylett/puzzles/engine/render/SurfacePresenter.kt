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
package uk.aylett.puzzles.engine.render

import android.graphics.Bitmap
import android.view.Surface

/**
 * Puts finished frames on a [Surface] owned by the UI, from the engine thread.
 *
 * The system's buffer queue is what makes this safe: every [present] locks a buffer nobody else is
 * reading, fills it, and posts it, so the compositor never sees pixels we are still writing. That is
 * the whole reason frames go out this way rather than as a [Bitmap] handed to Compose, whose render
 * thread reads the bitmap later, on its own schedule, with nothing to tell us when it has finished.
 *
 * The surface itself belongs to the UI and can vanish at any moment: `surfaceDestroyed` arrives on
 * the main thread and promises the surface is no longer used once it returns. [lock] guards only the
 * swap of [surface] and the lock-draw-post span of [present]; it is never held across a call into
 * WASM, so detaching waits at most for one buffer copy rather than for a puzzle to generate.
 */
internal class SurfacePresenter {
  private val lock = Any()
  private var surface: Surface? = null
  private var background = 0

  /**
   * Replaces the target surface, or detaches with null. Callable from any thread. [background]
   * fills whatever the puzzle doesn't cover, because a surface's untouched pixels show as black
   * rather than as the screen behind it.
   */
  fun attach(
    surface: Surface?,
    background: Int = 0,
  ) {
    synchronized(lock) {
      this.surface = surface
      this.background = background
    }
  }

  /**
   * Draws [bitmap] centred on the surface, replacing the whole of it. Does nothing without a valid
   * surface.
   *
   * The whole surface is repainted every time rather than only the region `drawUpdate` reports:
   * which buffer comes back from the queue is the system's choice, and its previous contents are
   * not necessarily the previous frame.
   */
  fun present(bitmap: Bitmap) {
    synchronized(lock) {
      val target = surface?.takeIf { it.isValid } ?: return
      val canvas =
        try {
          target.lockCanvas(null)
        } catch (_: IllegalArgumentException) {
          // Released between the validity check and the lock, which a destroy racing us can do.
          return
        } catch (_: Surface.OutOfResourcesException) {
          return
        }
      try {
        canvas.drawColor(background)
        // Integer division, exactly as PuzzleCanvas's touch mapping does it: the two must agree to
        // the pixel or taps land a pixel away from what was drawn.
        val left = (canvas.width - bitmap.width) / 2
        val top = (canvas.height - bitmap.height) / 2
        canvas.drawBitmap(bitmap, left.toFloat(), top.toFloat(), null)
      } finally {
        target.unlockCanvasAndPost(canvas)
      }
    }
  }
}
