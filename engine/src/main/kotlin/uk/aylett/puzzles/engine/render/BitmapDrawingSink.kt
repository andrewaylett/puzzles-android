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
  private var canvas: Canvas? = null

  // Two buffers we publish into alternately, rather than one we allocate per frame. The mid-end
  // runs its animation timer at ~20Hz, and a fresh full-screen ARGB_8888 bitmap each time would be
  // on the order of 8MB per frame — enough allocation churn to be felt. Alternating is what makes
  // reuse safe: the UI thread may still be reading the frame we published last, so we must never
  // write into that same buffer, only into the other one.
  private var publishBuffers: Array<Bitmap>? = null
  private var publishIndex = 0

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
   * (Re)allocates the backing bitmap. Called from the engine thread whenever `midend_size` picks a
   * new physical-pixel size; a no-op if the size is unchanged, since the mid-end may call this more
   * often than the size actually changes.
   */
  fun resize(
    width: Int,
    height: Int,
  ) {
    if (width <= 0 || height <= 0) return
    val current = bitmap
    if (current != null && current.width == width && current.height == height) return
    val next = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap = next
    canvas = Canvas(next)
    clipMarker = null
    publishBuffers =
      Array(2) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
    publishIndex = 0
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
    // Copy rather than hand over `current` directly: the mid-end draws incrementally, relying on
    // what it painted last time still being there, so the surface it owns can never be given away.
    // We copy it whole; `drawUpdate` tells us which parts changed, but tracking dirty regions per
    // buffer would have to reconcile two buffers that fell out of step on different frames, and a
    // full-surface copy is a memcpy of a few milliseconds at the sizes involved.
    publishIndex = (publishIndex + 1) % buffers.size
    val target = buffers[publishIndex]
    Canvas(target).drawBitmap(current, 0f, 0f, null)
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
    // Deliberately ignored. This tells us which rectangle changed, which would let endDraw copy
    // less, but Compose redraws the whole Image either way, so the only saving would be in the
    // surface-to-buffer copy — and that copy is already cheap next to the drawing that produced it.
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

  override fun blitterNew(
    w: Int,
    h: Int,
  ): Int {
    val handle = nextBlitterHandle++
    val safeWidth = w.coerceAtLeast(1)
    val safeHeight = h.coerceAtLeast(1)
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
    // Games such as Mines legitimately save rectangles that hang off the surface edge during drag
    // animations, so clamp to the surface bounds rather than letting Canvas throw.
    val srcLeft = x.coerceIn(0, source.width)
    val srcTop = y.coerceIn(0, source.height)
    val srcRight = (x + target.width).coerceIn(0, source.width)
    val srcBottom = (y + target.height).coerceIn(0, source.height)
    if (srcRight <= srcLeft || srcBottom <= srcTop) return
    val blitterCanvas = Canvas(target)
    // Clear first: a save whose rectangle is partly off-surface only copies the in-bounds part, and
    // a stale previous save's pixels must not leak into the uncovered remainder.
    blitterCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
    val src = Rect(srcLeft, srcTop, srcRight, srcBottom)
    val dst =
      RectF(
        (srcLeft - x).toFloat(),
        (srcTop - y).toFloat(),
        (srcRight - x).toFloat(),
        (srcBottom - y).toFloat(),
      )
    blitterCanvas.drawBitmap(source, src, dst, null)
  }

  override fun blitterLoad(
    handle: Int,
    x: Int,
    y: Int,
  ) {
    val c = canvas ?: return
    val source = blitters[handle] ?: return
    c.drawBitmap(source, x.toFloat(), y.toFloat(), null)
  }
}
