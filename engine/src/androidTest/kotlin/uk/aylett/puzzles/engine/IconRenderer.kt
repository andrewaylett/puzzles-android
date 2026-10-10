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

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Renders a puzzle's list icon the way upstream's `icons/icons.cmake` does, for puzzles whose icon
 * the upstream artwork collection lacks.
 *
 * Upstream screenshots the puzzle loaded from `icons/<name>.sav` at its preferred tile size, crops
 * a hand-picked rectangle (`crop.sh`, which first insists the screenshot is the size the crop was
 * chosen against), then `square.pl` trims the background, squares it up and resamples it. This
 * repeats that with our own engine, since the GTK front end and ImageMagick it needs are not
 * available.
 *
 * It is a generator, not a check, so it does nothing unless asked:
 * `./gradlew :engine:connectedDebugAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.renderIcons=true`. The test package is uninstalled
 * afterwards, taking its storage with it, so the PNGs are also written to logcat as base64 between
 * `BEGIN <file>` and `END <file>` lines (tag `IconRenderer`) for collection with `adb logcat -d`.
 */
@RunWith(AndroidJUnit4::class)
class IconRenderer {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  private class Spec(
    val name: String,
    val game: String,
    val fullSize: Int,
    val crop: IntArray,
  )

  // From upstream's icons.cmake. Singles and Range already have vendored icons: they are here to
  // check that this pipeline reproduces the real thing before it is trusted with the others.
  private val specs =
    listOf(
      Spec("singles", "Singles", 224, intArrayOf(98, 98, 15, 15)),
      Spec("range", "Range", 256, intArrayOf(98, 98, 111, 15)),
      Spec("mosaic", "Mosaic", 288, intArrayOf(97, 97, 142, 78)),
      Spec("palisade", "Palisade", 292, intArrayOf(192, 192, 0, 0)),
    )

  @Test
  fun render() =
    runBlocking {
      assumeTrue(
        InstrumentationRegistry.getArguments().getString("renderIcons") == "true",
      )
      val out = File(requireNotNull(context.getExternalFilesDir(null)), "icons").apply { mkdirs() }
      PuzzleEngine.create(context).use { engine ->
        for (spec in specs) {
          val info = engine.games.first { it.name == spec.game }
          engine.openGame(info.index).use { session ->
            val bytes =
              InstrumentationRegistry
                .getInstrumentation()
                .context.assets
                .open("icons/${spec.name}.sav")
                .readBytes()
            session.resize(spec.fullSize, spec.fullSize)
            val error = session.deserialise(bytes)
            assertEquals("${spec.name}: $error", null, error)
            session.resize(spec.fullSize, spec.fullSize)
            val shot = requireNotNull(session.snapshot())
            File(out, "${spec.name}-base.png").writePng(shot)
            // crop.sh's safety check: if the size moved, the crop no longer means what it did.
            assertEquals("${spec.name} width", spec.fullSize, shot.width)
            assertEquals("${spec.name} height", spec.fullSize, shot.height)
            val (w, h, x, y) = spec.crop
            val cropped = Bitmap.createBitmap(shot, x, y, w, h)
            File(out, "${spec.name}-ibase.png").writePng(cropped)
            File(out, "${spec.name}.png").writePng(square(cropped, 192, 8))
          }
        }
      }
      // The test package is uninstalled after the run, taking its own storage with it, so the
      // results are also emitted to logcat as base64 for `adb logcat` to collect.
      out.listFiles()!!.forEach { f ->
        android.util.Log.i("IconRenderer", "BEGIN ${f.name}")
        android.util.Base64
          .encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
          .chunked(2000)
          .forEach { android.util.Log.i("IconRenderer", it) }
        android.util.Log.i("IconRenderer", "END ${f.name}")
      }
    }

  private operator fun IntArray.component4() = this[3]

  /** `square.pl`: trim to non-background, pad to a square, add the border, resample. */
  private fun square(
    src: Bitmap,
    outSize: Int,
    border: Int,
  ): Bitmap {
    val w = src.width
    val h = src.height
    val px = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
    val counts = HashMap<Int, Int>()

    fun count(i: Int) = counts.merge(px[i], 1, Int::plus)
    for (i in 0 until w) {
      count(i)
      count((h - 1) * w + i)
    }
    for (i in 1 until h - 1) {
      count(i * w)
      count(i * w + w - 1)
    }
    val back = counts.maxByOrNull { it.value }!!.key

    fun rowBlank(r: Int) = (0 until w).all { px[r * w + it] == back }

    fun colBlank(c: Int) = (0 until h).all { px[it * w + c] == back }
    var ys = 0
    while (ys < h - 1 && rowBlank(ys)) ys++
    var ye = h - 1
    while (ye > ys && rowBlank(ye)) ye--
    var xs = 0
    while (xs < w - 1 && colBlank(xs)) xs++
    var xe = w - 1
    while (xe > xs && colBlank(xe)) xe--

    val hexpand = (ye - ys) - (xe - xs)
    if (hexpand > 0) {
      val left = hexpand / 2
      xs -= left
      xe += hexpand - left
    } else if (hexpand < 0) {
      val vexpand = -hexpand
      val top = vexpand / 2
      ys -= top
      ye += vexpand - top
    }
    var ow = xe - xs + 1
    val realow = (ow * (outSize.toDouble() / (outSize - 2 * border))).toInt()
    val extra = realow - ow
    val left = extra / 2
    xs -= left
    xe += extra - left
    ys -= extra / 2
    ye += extra - extra / 2
    ow = xe - xs + 1
    val oh = ye - ys + 1
    val sq = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
    for (yy in 0 until oh) {
      for (xx in 0 until ow) {
        val sx = xs + xx
        val sy = ys + yy
        sq.setPixel(xx, yy, if (sx in 0 until w && sy in 0 until h) px[sy * w + sx] else back)
      }
    }
    return Bitmap.createScaledBitmap(sq, outSize, outSize, true)
  }

  private fun File.writePng(bitmap: Bitmap) {
    FileOutputStream(this).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }
}
