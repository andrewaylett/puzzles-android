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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uk.aylett.puzzles.engine.render.BitmapDrawingSink
import kotlin.concurrent.thread

/** Regressions found in review; each needs the real engine or a real `Bitmap`. */
@RunWith(AndroidJUnit4::class)
class SessionRegressionTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  private val red = 0xFFFF0000.toInt()
  private val blue = 0xFF0000FF.toInt()

  /**
   * Back ends such as Map, Pegs and Inertia allocate their blitters inside `set_size`, so the sink
   * has to know the scale *before* the mid-end is sized. A blitter created first and resized after
   * still has to cover the whole rectangle at the new scale.
   */
  @Test
  fun blitterAllocatedBeforeResizeCoversTheScaledRectangle() {
    val sink = BitmapDrawingSink(onFrame = { _, _ -> }, onStatus = {})
    sink.setPalette(intArrayOf(red, blue))
    sink.useScale(2)
    val blitter = sink.blitterNew(10, 10)
    sink.resize(200, 200, 2)

    sink.startDraw()
    sink.drawRect(5, 5, 10, 10, 0)
    sink.blitterSave(blitter, 5, 5)
    sink.drawRect(5, 5, 10, 10, 1)
    sink.blitterLoad(blitter, 5, 5)
    sink.endDraw()

    val bitmap = requireNotNull(sink.snapshot())
    // Physical (10..30): the far corner is only restored if the blitter was allocated at 2x.
    assertEquals(red, bitmap.getPixel(11, 11))
    assertEquals(red, bitmap.getPixel(29, 29))
  }

  @Test
  fun changingOnlyTheScaleStillRebuildsTheCanvas() {
    val sink = BitmapDrawingSink(onFrame = { _, _ -> }, onStatus = {})
    sink.setPalette(intArrayOf(red))
    sink.resize(100, 100, 1)
    sink.useScale(2)
    sink.resize(100, 100, 2)

    sink.startDraw()
    sink.drawRect(0, 0, 10, 10, 0)
    sink.endDraw()

    val bitmap = requireNotNull(sink.snapshot())
    assertEquals(red, bitmap.getPixel(19, 19))
  }

  /**
   * The Settings screen reads every puzzle's preferences without generating a game, which only
   * works if each back end copes with a gameless mid-end (upstream builds a temporary `game_ui`).
   * Several keywords are shared between puzzles, and `one-key-shortcuts` comes from the mid-end
   * itself, so every puzzle must report it.
   */
  @Test
  fun everyPuzzleReportsPreferencesWithoutAGame() =
    runBlocking {
      PuzzleEngine.create(context).use { engine ->
        engine.games.forEach { game ->
          engine.openGame(game.index).use { session ->
            val keywords = session.config(ConfigSection.PREFS).fields.map { it.keyword }
            assertTrue("${game.name}: $keywords", "one-key-shortcuts" in keywords)
          }
        }
      }
    }

  @Test
  fun saveStateMatchesSerialiseAndSavePrefs() =
    runBlocking {
      PuzzleEngine.create(context).use { engine ->
        val game = engine.games.first { it.name == "Fifteen" }
        engine.openGame(game.index).use { session ->
          session.resize(600, 600)
          session.newGame()

          val state = session.saveState()
          assertArrayEquals(session.serialise(), state.game)
          assertArrayEquals(session.savePrefs(), state.prefs)
        }
      }
    }

  @Test
  fun sessionReportsItsEngineClosing() =
    runBlocking {
      val engine = PuzzleEngine.create(context)
      val game = engine.games.first { it.name == "Fifteen" }
      val session = engine.openGame(game.index)
      session.resize(600, 600)
      session.newGame()

      assertFalse(session.engineClosed)
      engine.close()
      assertTrue(session.engineClosed)
    }

  /** Fifteen animates a slide, which starts the timer; finds a movable tile and moves it. */
  private suspend fun startTimer(session: PuzzleSession) {
    val step = session.frames.value.width / 4
    outer@ for (row in 0 until 4) {
      for (col in 0 until 4) {
        val x = col * step + step / 2
        val y = row * step + step / 2
        session.pointer(PointerAction.DOWN, PointerButton.LEFT, x, y)
        session.pointer(PointerAction.UP, PointerButton.LEFT, x, y)
        if (session.frames.value.canUndo) break@outer
      }
    }
    assertTrue("no tile could be moved", session.animating.value)
  }

  @Test
  fun pausingStopsTickingAndResumingRestartsIt() =
    runBlocking {
      PuzzleEngine.create(context).use { engine ->
        val game = engine.games.first { it.name == "Fifteen" }
        engine.openGame(game.index).use { session ->
          session.resize(600, 600)
          session.newGame()
          startTimer(session)

          session.setPaused(true)
          assertFalse(session.animating.value)
          assertFalse("tick() must be inert while paused", session.tick())

          session.setPaused(false)
          assertTrue(session.animating.value)
        }
      }
    }

  /**
   * The UI thread toggles pause while the engine thread flips the timer; whatever the interleaving,
   * once the dust settles [PuzzleSession.animating] must agree with the inputs.
   */
  @Test
  fun animatingAgreesWithItsInputsAfterConcurrentPausing() =
    runBlocking {
      PuzzleEngine.create(context).use { engine ->
        val game = engine.games.first { it.name == "Fifteen" }
        engine.openGame(game.index).use { session ->
          session.resize(600, 600)
          session.newGame()
          startTimer(session)

          val togglers =
            List(4) {
              thread {
                repeat(2_000) { i -> session.setPaused(i % 2 == 0) }
              }
            }
          repeat(50) { session.tick() }
          togglers.forEach { it.join() }

          session.setPaused(true)
          assertFalse(session.animating.value)
          session.setPaused(false)
          assertTrue(session.animating.value)
        }
      }
    }
}
