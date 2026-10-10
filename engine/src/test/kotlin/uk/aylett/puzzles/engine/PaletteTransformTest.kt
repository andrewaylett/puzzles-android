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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaletteTransformTest {
  private fun lum(argb: Int) =
    (((argb shr 16) and 0xFF) + ((argb shr 8) and 0xFF) + (argb and 0xFF)) / 3

  private val paper = floatArrayOf(0.9f, 0.9f, 0.9f)
  private val darkPaper = floatArrayOf(0.1f, 0.1f, 0.15f)

  @Test
  fun `a fixed ink colour becomes light and fixed paper becomes dark`() {
    val ref = paper + floatArrayOf(0f, 0f, 0f) + floatArrayOf(1f, 1f, 1f)
    val out =
      PaletteTransform.darken(
        ref,
        darkPaper + floatArrayOf(0f, 0f, 0f) + floatArrayOf(1f, 1f, 1f),
      )
    assertTrue(lum(out[1]) > 230, "ink should be light")
    assertTrue(lum(out[2]) < 25, "white should be dark")
  }

  @Test
  fun `the background entry takes the themed colour`() {
    val out = PaletteTransform.darken(paper + paper, darkPaper + darkPaper)
    assertEquals(PaletteTransform.pack(darkPaper), out[0])
  }

  @Test
  fun `a colour scaled from the background is adapted rather than kept near black`() {
    // 0.6 * paper is "darker than paper" text in light; scaled the same way on a dark background
    // it is invisible, so it must not be taken as themed.
    val ref = paper + floatArrayOf(0f, 0.54f, 0f)
    val themed = darkPaper + floatArrayOf(0f, 0.09f, 0f)
    assertTrue(lum(PaletteTransform.darken(ref, themed)[1]) > 40)
  }

  @Test
  fun `saturated colours keep their lightness so hues stay distinct`() {
    val red = floatArrayOf(1f, 0f, 0f)
    val out = PaletteTransform.adapt(red)
    assertTrue(out[0] > 0.6f && out[1] < 0.35f && out[2] < 0.35f, out.joinToString())
  }
}
