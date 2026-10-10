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

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.sqrt

/**
 * How a puzzle's palette should be adapted to the app's theme.
 *
 * The mid-end gives us each game's palette as anonymous indices; nothing says which is "ink" and
 * which is "paper". What we do know is which entries a game *derives from the front end's default
 * colour* (via `game_mkhighlight`): they change if we change that colour. Everything else is a
 * fixed colour the game chose for a light background, and is what [PaletteTransform] has to flip.
 *
 * @property dark whether to adapt the palette at all; the light scheme leaves games as designed.
 * @property background the colour games should treat as their default background, packed ARGB.
 */
data class ColourScheme(
  val dark: Boolean = false,
  val background: Int = LIGHT_BACKGROUND,
) {
  companion object {
    /** What the front end has always reported as the default colour: a light neutral grey. */
    const val LIGHT_BACKGROUND = 0xFFE6E6E6.toInt()
  }
}

/** Derives a dark palette from two runs of the same game's `colours` callback. */
internal object PaletteTransform {
  /**
   * @param reference the palette as the game makes it against [ColourScheme.LIGHT_BACKGROUND].
   * @param themed the palette as the game makes it against the dark scheme's background.
   * @return packed ARGB: entries that moved with the background are taken as themed, the rest
   *   (fixed colours) have their lightness flipped in OKLab, which keeps hue and chroma so that
   *   distinct colours stay distinct.
   */
  fun darken(
    reference: FloatArray,
    themed: FloatArray,
  ): IntArray {
    val n = reference.size / 3

    fun rgb(
      from: FloatArray,
      i: Int,
    ) = from.copyOfRange(i * 3, i * 3 + 3)
    val base = rgb(themed, 0)
    return IntArray(n) { i ->
      val ref = rgb(reference, i)
      val now = rgb(themed, i)
      val derived = (0..2).any { abs(ref[it] - now[it]) > EPSILON }
      // Entries that moved with the default colour are of two kinds. Bevels, made by
      // `game_mkhighlight`, sit a fixed distance from the background (or clamp to pure white or
      // black) and are right as themed. Others are the game scaling or mixing the background to
      // make, say, a text colour "40% darker than paper": the ratio is meaningless on a dark
      // background (it goes to black), so those are treated like any fixed colour.
      val keep = i == 0 || (derived && isBevel(now, base))
      pack(if (keep) now else adapt(ref))
    }
  }

  private fun isBevel(
    c: FloatArray,
    base: FloatArray,
  ): Boolean {
    val pure = c.all { it == 0f } || c.all { it == 1f }
    var d = 0f
    for (k in 0..2) d += (c[k] - base[k]) * (c[k] - base[k])
    return pure || abs(sqrt(d) - BEVEL_DISTANCE) < 0.02f
  }

  fun pack(rgb: FloatArray): Int {
    fun c(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (c(rgb[0]) shl 16) or (c(rgb[1]) shl 8) or c(rgb[2])
  }

  /**
   * Lightness is flipped for neutral colours (ink becomes paper). Saturated colours keep their
   * lightness, nudged into a band that reads on a dark background: flipping them would turn a
   * palette whose entries differ mostly in hue (Flood, Map) into a row of murky darks.
   */
  fun adapt(rgb: FloatArray): FloatArray {
    val (l, a, b) = toOklab(rgb)
    val weight = (sqrt(a * a + b * b) / SATURATED_CHROMA).coerceIn(0f, 1f)
    val flipped = 1f - l
    val kept = l.coerceIn(0.55f, 0.9f)
    return fromOklab(flipped * (1f - weight) + kept * weight, a, b)
  }

  private fun toLinear(c: Float) =
    if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

  private fun toGamma(c: Float): Float {
    val v = c.coerceIn(0f, 1f)
    return if (v <=
      0.0031308f
    ) {
      v * 12.92f
    } else {
      (1.055 * Math.pow(v.toDouble(), 1 / 2.4) - 0.055).toFloat()
    }
  }

  private fun toOklab(rgb: FloatArray): Triple<Float, Float, Float> {
    val r = toLinear(rgb[0])
    val g = toLinear(rgb[1])
    val b = toLinear(rgb[2])
    val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
    val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
    val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
    return Triple(
      0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
      1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
      0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
    )
  }

  private fun fromOklab(
    ll: Float,
    a: Float,
    b: Float,
  ): FloatArray {
    val l = Math.pow((ll + 0.3963377774f * a + 0.2158037573f * b).toDouble(), 3.0).toFloat()
    val m = Math.pow((ll - 0.1055613458f * a - 0.0638541728f * b).toDouble(), 3.0).toFloat()
    val s = Math.pow((ll - 0.0894841775f * a - 1.2914855480f * b).toDouble(), 3.0).toFloat()
    return floatArrayOf(
      toGamma(4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s),
      toGamma(-1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s),
      toGamma(-0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s),
    )
  }

  private const val EPSILON = 0.004f

  // sqrt(3)/6, the distance `game_mkhighlight` puts its highlight and lowlight from the background.
  private const val BEVEL_DISTANCE = 0.2887f
  private const val SATURATED_CHROMA = 0.12f
}
