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
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import uk.aylett.puzzles.engine.Align
import uk.aylett.puzzles.engine.DrawingSink
import uk.aylett.puzzles.engine.FontType

/**
 * Renders mid-end drawing calls into a retained off-screen [Bitmap], handing the UI thread an
 * immutable snapshot at the end of each batch.
 *
 * We cannot paint into a Compose draw scope because [blitterSave] must read pixels already drawn
 * back off the surface, and Compose draw scopes are write-only. Keeping our own [Canvas] also lets
 * us serve reads and writes from the single engine thread that owns every other call into the
 * mid-end, so there is no extra synchronisation to get wrong.
 */
class BitmapDrawingSink(
  private val onFrame: (Bitmap) -> Unit,
  private val onStatus: (String) -> Unit,
) : DrawingSink {
  private var bitmap: Bitmap? = null

  // The mid-end draws in a density-reduced coordinate space (see PuzzleSession.scale); this is the
  // main surface's Canvas with that scale baked in as a transform, so every drawXxx call below
  // lands at the right physical-pixel position without having to multiply each coordinate by hand.
  private var canvas: Canvas? = null

  // The same surface, but without the scale transform: blitters need pixel-exact placement using
  // physical-pixel coordinates (a save/load roundtrip must not resample), so they compute their own
  // scaled coordinates explicitly and draw through this instead of through the scaled [canvas].
  private var rawCanvas: Canvas? = null
  private var scale = 1

  // Two buffers we publish into alternately, rather than one we allocate per frame. The animation
  // loop now paces itself to the display's own refresh rate while something is actually moving —
  // up to 120Hz on current hardware — and a fresh full-screen ARGB_8888 bitmap each time would be
  // on the order of 8MB per frame, so allocation churn matters even more here than it did at the
  // old fixed 20Hz. Alternating is what makes reuse safe: the UI thread may still be reading the
  // frame we published last, so we must never write into that same buffer, only into the other
  // one. The two `Canvas`es wrapping them are cached alongside for the same reason: a fresh
  // `Canvas(target)` every tick is one more allocation on a path now run far more often.
  private var publishBuffers: Array<Bitmap>? = null
  private var publishCanvases: Array<Canvas>? = null
  private var publishIndex = 0

  // Set by drawUpdate() whenever the mid-end paints something, cleared only by consumeChanged().
  // Sticky across ticks (not reset in startDraw()) so that a change made between two ticks of the
  // pacing loop — a tap handled inline via pointer(), say — survives to be seen by the next tick,
  // rather than being wiped by that tick's own startDraw() before anyone reads it. This is also
  // what lets the pacing loop in GameViewModel notice "something happened" and snap back to full
  // refresh rate after user input, without any separate wake-up signal.
  private var frameChanged = false

  private var palette: IntArray = IntArray(0)

  // Non-zero handles, per the DrawingSink contract that zero means "allocation failed".
  private val blitters = mutableMapOf<Int, Bitmap>()
  private var nextBlitterHandle = 1

  // save() returns a value we need to restore() the matching state; the mid-end's clip/unclip
  // pairs don't nest, but we track the marker defensively rather than trusting that.
  private var clipMarker: Int? = null

  private val fillPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
    }
  private val strokePaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = 1f
    }
  private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val textBounds = Rect()

  /**
   * (Re)allocates the backing bitmap at [width]x[height] physical pixels, and records [scale] (the
   * mid-end's coordinate space is [width]/[scale] x [height]/[scale]) for every draw call to come
   * until the next resize. A no-op if neither has changed, since the mid-end may call this more
   * often than the size actually changes.
   */
  fun resize(
    width: Int,
    height: Int,
    scale: Int = 1,
  ) {
    if (width <= 0 || height <= 0) return
    val current = bitmap
    if (current != null && current.width == width && current.height == height &&
      this.scale == scale
    ) {
      return
    }
    this.scale = scale
    val next = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap = next
    canvas =
      Canvas(next).apply {
        scale(scale.toFloat(), scale.toFloat())
      }
    rawCanvas = Canvas(next)
    clipMarker = null
    val buffers = Array(2) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
    publishBuffers = buffers
    publishCanvases = Array(buffers.size) { Canvas(buffers[it]) }
    publishIndex = 0
  }

  /**
   * Returns whether the mid-end has painted anything since the last call, and clears the flag.
   *
   * Used by the animation pacing loop to tell "still animating" from "the timer fired but nothing
   * actually moved", so it can back off to a slow poll once a run of ticks produces no visible
   * change, and snap back to full rate the moment something — a tick's own animation frame, or a
   * redraw triggered by user input — sets the flag again.
   */
  fun consumeChanged(): Boolean {
    val changed = frameChanged
    frameChanged = false
    return changed
  }

  /**
   * Replaces the colour palette. `midend_colours` hands us a flat packed-ARGB array indexed by the
   * colour indices every draw call uses, so we just keep it and look values up by index.
   */
  fun setPalette(colours: IntArray) {
    palette = colours
  }

  /** Returns the ARGB colour for [index], or null if it is negative or out of range. */
  private fun colourOrNull(index: Int): Int? {
    if (index < 0 || index >= palette.size) return null
    return palette[index]
  }

  override fun startDraw() {
    // Nothing to do: we draw straight into the retained bitmap, so there is no separate batch
    // buffer to prepare.
  }

  override fun endDraw() {
    val current = bitmap ?: return
    val buffers = publishBuffers ?: return
    val canvases = publishCanvases ?: return
    // Copy rather than hand over `current` directly: the mid-end draws incrementally, relying on
    // what it painted last time still being there, so the surface it owns can never be given away.
    // We copy it whole; `drawUpdate` tells us which parts changed, but tracking dirty regions per
    // buffer would have to reconcile two buffers that fell out of step on different frames, and a
    // full-surface copy is a memcpy of a few milliseconds at the sizes involved.
    publishIndex = (publishIndex + 1) % buffers.size
    val target = buffers[publishIndex]
    canvases[publishIndex].drawBitmap(current, 0f, 0f, null)
    onFrame(target)
  }

  override fun drawText(
    x: Int,
    y: Int,
    fontType: Int,
    fontSize: Int,
    align: Int,
    colour: Int,
    text: String,
  ) {
    val c = canvas ?: return
    val argb = colourOrNull(colour) ?: return
    textPaint.color = argb
    textPaint.typeface =
      if (fontType == FontType.FIXED) Typeface.MONOSPACE else Typeface.SANS_SERIF
    // Upstream front ends pass the font height and treat it as roughly the em size; there is no
    // finer-grained size hint in the mid-end API, so textSize is the direct, if approximate, match.
    textPaint.textSize = fontSize.toFloat()
    textPaint.textAlign =
      when (align and (Align.H_CENTRE or Align.H_RIGHT)) {
        Align.H_CENTRE -> Paint.Align.CENTER
        Align.H_RIGHT -> Paint.Align.RIGHT
        else -> Paint.Align.LEFT
      }

    var baselineY = y.toFloat()
    if (align and Align.V_CENTRE != 0) {
      // y is the vertical centre of the text, not the baseline. Measure the actual glyph bounds
      // rather than assuming a fixed ascent/descent split, because puzzles centre digits in cells
      // and font metrics (which include leading and descenders for characters not present) put the
      // baseline in a visibly different place than the drawn glyphs' true centre.
      textPaint.getTextBounds(text, 0, text.length, textBounds)
      baselineY = y - (textBounds.top + textBounds.bottom) / 2f
    }
    c.drawText(text, x.toFloat(), baselineY, textPaint)
  }

  override fun drawRect(
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    colour: Int,
  ) {
    val c = canvas ?: return
    val argb = colourOrNull(colour) ?: return
    fillPaint.color = argb
    c.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), fillPaint)
  }

  override fun drawLine(
    x1: Int,
    y1: Int,
    x2: Int,
    y2: Int,
    colour: Int,
  ) {
    val c = canvas ?: return
    val argb = colourOrNull(colour) ?: return
    strokePaint.color = argb
    strokePaint.strokeWidth = 1f
    strokePaint.strokeCap = Paint.Cap.BUTT
    c.drawLine(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), strokePaint)
  }

  override fun drawPolygon(
    coords: IntArray,
    fillColour: Int,
    outlineColour: Int,
  ) {
    val c = canvas ?: return
    if (coords.size < 4) return
    val path =
      Path().apply {
        moveTo(coords[0].toFloat(), coords[1].toFloat())
        var i = 2
        while (i + 1 < coords.size) {
          lineTo(coords[i].toFloat(), coords[i + 1].toFloat())
          i += 2
        }
        close()
      }
    // Fill before stroking the outline, so the outline sits exactly on the fill's edge rather than
    // being partly overwritten by it.
    colourOrNull(fillColour)?.let { argb ->
      fillPaint.color = argb
      c.drawPath(path, fillPaint)
    }
    colourOrNull(outlineColour)?.let { argb ->
      strokePaint.color = argb
      strokePaint.strokeWidth = 1f
      strokePaint.strokeCap = Paint.Cap.BUTT
      c.drawPath(path, strokePaint)
    }
  }

  override fun drawCircle(
    cx: Int,
    cy: Int,
    radius: Int,
    fillColour: Int,
    outlineColour: Int,
  ) {
    val c = canvas ?: return
    val radiusF = radius.toFloat()
    // Fill before stroking, matching drawPolygon, so the two line up exactly.
    colourOrNull(fillColour)?.let { argb ->
      fillPaint.color = argb
      c.drawCircle(cx.toFloat(), cy.toFloat(), radiusF, fillPaint)
    }
    colourOrNull(outlineColour)?.let { argb ->
      strokePaint.color = argb
      strokePaint.strokeWidth = 1f
      strokePaint.strokeCap = Paint.Cap.BUTT
      c.drawCircle(cx.toFloat(), cy.toFloat(), radiusF, strokePaint)
    }
  }

  override fun drawThickLine(
    thickness: Float,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    colour: Int,
  ) {
    val c = canvas ?: return
    val argb = colourOrNull(colour) ?: return
    strokePaint.color = argb
    strokePaint.strokeWidth = thickness
    // Round caps match upstream's cairo backend, which draws thick lines as capsules rather than
    // stopping flush at the endpoints.
    strokePaint.strokeCap = Paint.Cap.ROUND
    c.drawLine(x1, y1, x2, y2, strokePaint)
  }

  override fun drawUpdate(
    x: Int,
    y: Int,
    w: Int,
    h: Int,
  ) {
    // The rectangle itself is ignored: it would let endDraw copy less, but Compose redraws the
    // whole Image either way, so the only saving would be in the surface-to-buffer copy — already
    // cheap next to the drawing that produced it. That the call happened at all is what we keep;
    // see frameChanged.
    frameChanged = true
  }

  override fun clip(
    x: Int,
    y: Int,
    w: Int,
    h: Int,
  ) {
    val c = canvas ?: return
    // The mid-end's clip/unclip pairs don't nest, but guard against a stray extra clip call
    // clobbering the marker for a pending unclip by only ever tracking the outermost save.
    if (clipMarker != null) return
    clipMarker = c.save()
    c.clipRect(x, y, x + w, y + h)
  }

  override fun unclip() {
    val c = canvas ?: return
    val marker = clipMarker ?: return
    c.restoreToCount(marker)
    clipMarker = null
  }

  override fun statusBar(text: String) {
    onStatus(text)
  }

  // Blitters store and replay physical pixels 1:1: the mid-end hands us w/h/x/y in its own
  // reduced coordinate space, same as every other draw call, so they're scaled up explicitly here
  // rather than via the [canvas] transform. Sizing the buffer itself in physical pixels (rather
  // than relying on a scaled Canvas to stretch a small one) is what keeps a save/load roundtrip
  // pixel-exact instead of resampling it twice.
  override fun blitterNew(
    w: Int,
    h: Int,
  ): Int {
    val handle = nextBlitterHandle++
    val safeWidth = (w * scale).coerceAtLeast(1)
    val safeHeight = (h * scale).coerceAtLeast(1)
    blitters[handle] = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
    return handle
  }

  override fun blitterFree(handle: Int) {
    blitters.remove(handle)
  }

  override fun blitterSave(
    handle: Int,
    x: Int,
    y: Int,
  ) {
    val source = bitmap ?: return
    val target = blitters[handle] ?: return
    val px = x * scale
    val py = y * scale
    // Games such as Mines legitimately save rectangles that hang off the surface edge during drag
    // animations, so clamp to the surface bounds rather than letting Canvas throw.
    val srcLeft = px.coerceIn(0, source.width)
    val srcTop = py.coerceIn(0, source.height)
    val srcRight = (px + target.width).coerceIn(0, source.width)
    val srcBottom = (py + target.height).coerceIn(0, source.height)
    if (srcRight <= srcLeft || srcBottom <= srcTop) return
    val blitterCanvas = Canvas(target)
    // Clear first: a save whose rectangle is partly off-surface only copies the in-bounds part, and
    // a stale previous save's pixels must not leak into the uncovered remainder.
    blitterCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
    val src = Rect(srcLeft, srcTop, srcRight, srcBottom)
    val dst =
      RectF(
        (srcLeft - px).toFloat(),
        (srcTop - py).toFloat(),
        (srcRight - px).toFloat(),
        (srcBottom - py).toFloat(),
      )
    blitterCanvas.drawBitmap(source, src, dst, null)
  }

  override fun blitterLoad(
    handle: Int,
    x: Int,
    y: Int,
  ) {
    val c = rawCanvas ?: return
    val source = blitters[handle] ?: return
    c.drawBitmap(source, (x * scale).toFloat(), (y * scale).toFloat(), null)
  }
}
