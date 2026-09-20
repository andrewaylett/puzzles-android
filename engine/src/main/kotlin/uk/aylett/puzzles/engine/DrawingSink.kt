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

/**
 * The drawing operations the mid-end invokes on us, mirroring `drawing_api` in `puzzles.h`.
 *
 * This is a *push* interface: the mid-end calls these while `redraw` runs, so an implementation
 * must paint somewhere it owns rather than expecting to be asked for a picture. In particular
 * [blitterSave] has to read pixels back, which rules out a write-only target.
 *
 * All coordinates are physical pixels in the surface's own space. Colours are indices into the
 * palette from `midend_colours`, except where noted: a negative colour index means "omit this
 * part", used by [drawPolygon] and [drawCircle] to draw unfilled or unoutlined shapes.
 *
 * Every method is called on the engine thread.
 */
interface DrawingSink {
  /** Begins a batch of drawing operations. Nothing need be visible until [endDraw]. */
  fun startDraw()

  /**
   * Ends a batch. The implementation should make the result visible; the mid-end will have told us
   * which parts changed via [drawUpdate].
   */
  fun endDraw()

  fun drawText(
    x: Int,
    y: Int,
    fontType: Int,
    fontSize: Int,
    align: Int,
    colour: Int,
    text: String,
  )

  fun drawRect(
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    colour: Int,
  )

  fun drawLine(
    x1: Int,
    y1: Int,
    x2: Int,
    y2: Int,
    colour: Int,
  )

  /**
   * @param coords x/y pairs, so `2 * nPoints` entries long.
   * @param fillColour negative to leave the interior unpainted.
   * @param outlineColour negative to omit the outline.
   */
  fun drawPolygon(
    coords: IntArray,
    fillColour: Int,
    outlineColour: Int,
  )

  /** @param fillColour negative to leave the interior unpainted. */
  fun drawCircle(
    cx: Int,
    cy: Int,
    radius: Int,
    fillColour: Int,
    outlineColour: Int,
  )

  /** Like [drawLine] but with an explicit width, and taking sub-pixel coordinates. */
  fun drawThickLine(
    thickness: Float,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    colour: Int,
  )

  /** Declares that this rectangle changed and should be repainted on screen. */
  fun drawUpdate(
    x: Int,
    y: Int,
    w: Int,
    h: Int,
  )

  /** Restricts subsequent drawing to this rectangle, until [unclip]. Does not nest. */
  fun clip(
    x: Int,
    y: Int,
    w: Int,
    h: Int,
  )

  fun unclip()

  /** Text for the status bar, for games where `midend_wants_statusbar` is true. */
  fun statusBar(text: String)

  /**
   * Creates an off-screen buffer of this size.
   *
   * @return a non-zero handle; zero is reserved and will be treated as failure by the mid-end.
   */
  fun blitterNew(
    w: Int,
    h: Int,
  ): Int

  fun blitterFree(handle: Int)

  /** Copies the `w`×`h` rectangle at (`x`, `y`) out of the surface and into the blitter. */
  fun blitterSave(
    handle: Int,
    x: Int,
    y: Int,
  )

  /** Paints a previously saved blitter back onto the surface at (`x`, `y`). */
  fun blitterLoad(
    handle: Int,
    x: Int,
    y: Int,
  )
}
