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
import java.util.concurrent.Executors

/**
 * Puts finished frames on a [Surface] owned by the UI, from the engine thread.
 *
 * The system's buffer queue is what makes this safe: every [present] locks a buffer nobody else is
 * reading, fills it, and posts it, so the compositor never sees pixels we are still writing. That is
 * the whole reason frames go out this way rather than as a [Bitmap] handed to Compose, whose render
 * thread reads the bitmap later, on its own schedule, with nothing to tell us when it has finished.
 *
 * The surface itself belongs to the UI and can vanish at any moment: `surfaceDestroyed` arrives on
 * the main thread and promises the surface is no longer used once it returns. [lock] covers the
 * lock-draw-post span of [present] and is never held across a call into WASM, so detaching waits at
 * most for one buffer copy rather than for a puzzle to generate.
 *
 * Attaching a surface must *not* take [lock]. `unlockCanvasAndPost` can block inside
 * `BLASTBufferQueue::onFrameAvailable` until earlier frames are acknowledged, and that
 * acknowledgement can depend on the main thread completing a layout pass. `surfaceChanged` is
 * called from inside that pass, so waiting for [lock] there deadlocks both sides until the system
 * reports an ANR (seen on resume). Only the final detach needs to wait for a frame in flight.
 */
internal class SurfacePresenter {
  private class Target(
    val surface: Surface,
    val background: Int,
  )

  private val lock = Any()

  // Whether a frame has gone out since the surface was attached. Only [fillBlank] reads it, to
  // avoid painting over a puzzle that arrived while it was waiting for the lock.
  @Volatile private var presented = false

  private val filler =
    Executors.newSingleThreadExecutor { r -> Thread(r, "surface-filler").apply { isDaemon = true } }

  // Replaced as a unit so that present never sees a new surface with an old background.
  @Volatile private var target: Target? = null

  /**
   * Paints the whole surface in its background colour, unless a frame has already been presented.
   * A new surface is black until something is posted, and the engine thread — the only thing that
   * can present a puzzle — may be busy generating one for seconds. Runs on its own thread for the
   * same reason: attaching must not wait on the buffer queue (see the class documentation).
   */
  fun fillBlank() {
    filler.execute {
      synchronized(lock) {
        if (presented) return@execute
        val current = target ?: return@execute
        val surface = current.surface.takeIf { it.isValid } ?: return@execute
        val canvas =
          try {
            surface.lockCanvas(null)
          } catch (_: IllegalArgumentException) {
            return@execute
          } catch (_: Surface.OutOfResourcesException) {
            return@execute
          }
        try {
          canvas.drawColor(current.background)
        } finally {
          surface.unlockCanvasAndPost(canvas)
        }
      }
    }
  }

  /** Changes the fill on the current surface, if any; the next frame uses it. */
  fun setBackground(background: Int) {
    target = target?.let { Target(it.surface, background) }
  }

  /**
   * Replaces the target surface, or detaches with null. Callable from any thread. [background]
   * fills whatever the puzzle doesn't cover, because a surface's untouched pixels show as black
   * rather than as the screen behind it.
   */
  fun attach(
    surface: Surface?,
    background: Int = 0,
  ) {
    if (surface != null) {
      // Lock-free: see the class documentation.
      presented = false
      target = Target(surface, background)
      return
    }
    target = null
    // Wait out any frame already being posted, so the surface is unused when this returns.
    synchronized(lock) {}
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
      presented = true
      val current = target ?: return
      val surface = current.surface.takeIf { it.isValid } ?: return
      val canvas =
        try {
          surface.lockCanvas(null)
        } catch (_: IllegalArgumentException) {
          // Released between the validity check and the lock, which a destroy racing us can do.
          return
        } catch (_: Surface.OutOfResourcesException) {
          return
        }
      try {
        canvas.drawColor(current.background)
        // Integer division, exactly as PuzzleCanvas's touch mapping does it: the two must agree to
        // the pixel or taps land a pixel away from what was drawn.
        val left = (canvas.width - bitmap.width) / 2
        val top = (canvas.height - bitmap.height) / 2
        canvas.drawBitmap(bitmap, left.toFloat(), top.toFloat(), null)
      } finally {
        surface.unlockCanvasAndPost(canvas)
      }
    }
  }
}
