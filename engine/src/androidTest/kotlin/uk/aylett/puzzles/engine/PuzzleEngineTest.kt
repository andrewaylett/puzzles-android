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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the whole engine stack on a device.
 *
 * These cannot be JVM unit tests: the WASM is only executable once dexed, and the drawing backend
 * needs a real `android.graphics.Canvas`. Everything here is therefore an integration check of the
 * combination that actually ships.
 */
@RunWith(AndroidJUnit4::class)
class PuzzleEngineTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun exposesEveryPuzzle() =
    runBlocking {
      PuzzleEngine.create(context).use { engine ->
        // Built from upstream's COMBINED list. If this number moves, upstream has added or
        // removed a puzzle, and our expectations should be revisited deliberately.
        assertEquals(40, engine.games.size)
        assertTrue(engine.games.any { it.name == "Solo" })
        assertTrue(engine.games.any { it.name == "Net" })
        // Names must be unique, because saved state is keyed by name rather than by index.
        assertEquals(
          engine.games.size,
          engine.games
            .map { it.name }
            .distinct()
            .size,
        )
      }
    }

  @Test
  fun generatesAndRendersAGame() =
    runBlocking {
      PuzzleEngine.create(context).use { engine ->
        val net = engine.games.first { it.name == "Net" }
        engine.openGame(net.index).use { session ->
          session.resize(600, 600)
          session.newGame()

          val frame = session.frames.value
          val bitmap = requireNotNull(session.snapshot()) { "expected a rendered frame" }

          assertTrue(bitmap.width > 0 && bitmap.height > 0)
          // A blank surface would mean the drawing callbacks never reached us, which is the
          // failure this check exists to catch: a real puzzle uses more than one colour.
          val pixels = IntArray(bitmap.width * bitmap.height)
          bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
          assertTrue("surface is a single flat colour", pixels.distinct().size > 1)

          assertTrue("a fresh game should have an id", frame.gameId.isNotEmpty())
          assertEquals(GameStatus.IN_PROGRESS, frame.status)
        }
      }
    }

  @Test
  fun pencilHighlightDefaultsOnForGamesThatHaveIt() =
    runBlocking {
      PuzzleEngine.create(context).use { engine ->
        suspend fun keepsHighlight(name: String): Boolean? =
          engine.openGame(engine.games.first { it.name == name }.index).use { session ->
            session.resize(600, 600)
            session.newGame()
            session.applyPrefDefaults()
            session
              .config(ConfigSection.PREFS)
              .fields
              .filterIsInstance<ConfigField.Toggle>()
              .firstOrNull { it.keyword == "pencil-keep-highlight" }
              ?.value
          }

        assertEquals(true, keepsHighlight("Towers"))
        // Net has no pencil marks, so there is no such preference to set and nothing may break.
        assertEquals(null, keepsHighlight("Net"))
      }
    }

  @Test
  fun everyGameThatFillsPencilMarksHandlesTheKey() =
    runBlocking {
      PuzzleEngine.create(context).use { engine ->
        // The allowlist is by display name, so a rename upstream would silently drop the item.
        val names = engine.games.filter { it.canFillPencilMarks }.map { it.name }
        assertEquals(listOf("Keen", "Solo", "Towers", "Undead", "Unequal"), names.sorted())
      }
    }

  @Test
  fun dominosaOffersNumberKeysUpToItsHighestNumber() =
    runBlocking {
      PuzzleEngine.create(context).use { engine ->
        val dominosa = engine.games.first { it.name == "Dominosa" }
        engine.openGame(dominosa.index).use { session ->
          session.resize(600, 600)
          session.newGame()
          val labels = session.keyHints().map { it.label }
          // The default preset's highest number is at least 3 and never more than 9.
          assertTrue("expected digit chips, got $labels", labels.size in 4..10)
          assertEquals(labels.indices.map { it.toString() }, labels)
        }
      }
    }

  @Test
  fun everyGamesSettingsAndPreferencesCanBeRead() =
    runBlocking {
      PuzzleEngine.create(context).use { engine ->
        // Settings items carry no initialised keyword in several back ends (Dominosa's trapped on
        // an out-of-bounds read), so this sweeps all forty rather than trusting a sample.
        for (game in engine.games) {
          engine.openGame(game.index).use { session ->
            session.resize(600, 600)
            session.newGame()
            session.config(ConfigSection.SETTINGS)
            session.config(ConfigSection.PREFS)
          }
        }
      }
    }

  @Test
  fun undoRedoRoundTrips() =
    runBlocking {
      PuzzleEngine.create(context).use { engine ->
        val net = engine.games.first { it.name == "Net" }
        engine.openGame(net.index).use { session ->
          session.resize(600, 600)
          session.newGame()
          assertFalse("a new game has nothing to undo", session.frames.value.canUndo)

          // Click somewhere inside the grid; Net rotates a tile, which is an undoable move.
          session.pointer(PointerAction.DOWN, PointerButton.LEFT, 100, 100)
          session.pointer(PointerAction.UP, PointerButton.LEFT, 100, 100)
          assertTrue("clicking should have made a move", session.frames.value.canUndo)

          session.undo()
          // The crucial check: UI_UNDO must actually undo. Getting the constant wrong sends
          // UI_NEWGAME instead, which silently discards the game rather than reverting one move.
          assertTrue("undo should offer a redo", session.frames.value.canRedo)

          session.redo()
          assertTrue("redo should restore the move", session.frames.value.canUndo)
        }
      }
    }

  @Test
  fun serialisationRoundTripsTheSameGame() =
    runBlocking {
      PuzzleEngine.create(context).use { engine ->
        val game = engine.games.first { it.name == "Net" }
        engine.openGame(game.index).use { session ->
          session.resize(600, 600)
          session.newGame()
          val original = session.gameId()
          val saved = session.serialise()
          assertTrue(saved.isNotEmpty())

          session.newGame()
          session.deserialise(saved).let { assertEquals(null, it) }
          assertEquals("a restored save is the same puzzle", original, session.gameId())
        }
      }
    }

  @Test
  fun presetsAndConfigAreReadable() =
    runBlocking {
      PuzzleEngine.create(context).use { engine ->
        val solo = engine.games.first { it.name == "Solo" }
        engine.openGame(solo.index).use { session ->
          session.resize(600, 600)
          session.newGame()

          // Solo's presets nest, so this also covers the submenu walk.
          val presets = session.presets()
          assertTrue("expected presets", presets.isNotEmpty())

          val form = session.config(ConfigSection.SETTINGS)
          assertTrue("expected configurable fields", form.fields.isNotEmpty())
        }
      }
    }

  /**
   * Changing the parameters must re-size the surface, not just regenerate.
   *
   * A preset change doesn't alter the Compose layout, so nothing external triggers a resize; if
   * the session doesn't re-run `midend_size` itself, the new grid is drawn at the old tile size
   * into a surface sized for the old one, and the Canvas quietly clips it.
   */
  @Test
  fun changingPresetResizesTheSurface() =
    runBlocking {
      PuzzleEngine.create(context).use { engine ->
        val solo = engine.games.first { it.name == "Solo" }
        engine.openGame(solo.index).use { session ->
          session.resize(900, 900)
          session.newGame()

          val leaves = session.presets().flatten()
          assertTrue("expected leaf presets", leaves.size > 1)

          // Pick a preset that is not the current one, so the dimensions genuinely change.
          val current = session.currentPresetId()
          val target = leaves.firstOrNull { it.id != current } ?: leaves.first()
          session.applyPreset(target.id)

          val bitmap = requireNotNull(session.snapshot())
          val pixels = IntArray(bitmap.width * bitmap.height)
          bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
          assertTrue(
            "board is blank after changing preset to '${target.title}'",
            pixels.distinct().size > 1,
          )
        }
      }
    }

  /**
   * Every puzzle must generate and draw something.
   *
   * The forty back ends exercise quite different parts of the drawing API — blitters, polygons
   * with no fill, thick lines, fixed-pitch text — so a sweep at default settings is the only way
   * to know the surface is complete rather than merely sufficient for the few games tried by hand.
   */
  @Test
  fun everyPuzzleGeneratesAndRenders() =
    runBlocking {
      PuzzleEngine.create(context).use { engine ->
        val failures = mutableListOf<String>()
        for (game in engine.games) {
          try {
            engine.openGame(game.index).use { session ->
              session.resize(600, 600)
              session.newGame()
              val bitmap = session.snapshot()
              if (bitmap == null) {
                failures += "${game.name}: no frame"
              } else {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                if (pixels.distinct().size <= 1) failures += "${game.name}: blank surface"
              }
            }
          } catch (e: Exception) {
            failures += "${game.name}: ${e::class.simpleName} ${e.message}"
          }
        }
        assertTrue("puzzles failed to render: $failures", failures.isEmpty())
      }
    }

  /**
   * The animation timer must actually tick.
   *
   * Nothing else covers the `activate_timer` → `midend_timer` → redraw → present path, and its
   * failure mode is quiet: static puzzles look perfect while animated ones freeze. Ticking the
   * session with no further input exercises the timer, the redraw and the frame counter together. The pacing of those ticks belongs to GameViewModel, so it is not tested here.
   */
  @Test
  fun animationTimerProducesFrames() =
    runBlocking {
      PuzzleEngine.create(context).use { engine ->
        val game = engine.games.first { it.name == "Fifteen" }
        engine.openGame(game.index).use { session ->
          session.resize(600, 600)
          session.newGame()

          // Sliding a tile in Fifteen animates, which is what starts the timer — but only tiles
          // next to the gap will move, and where the gap is depends on the shuffle. Try a grid of
          // points until one takes, rather than assuming a fixed spot is movable.
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
          assertTrue("no tile could be moved, so nothing animated", session.frames.value.canUndo)

          // The session only decides *whether* ticking should happen; GameViewModel's pacing loop
          // is what calls tick(), so stand in for it here.
          assertTrue("a sliding tile should start the timer", session.animating.value)

          val afterMove = session.framesPresented.get()
          var painted = false
          repeat(20) {
            painted = session.tick() || painted
            delay(16)
          }
          val afterTicks = session.framesPresented.get()
          assertTrue("tick() never reported painting", painted)
          assertTrue(
            "no frames presented while animating ($afterMove -> $afterTicks)",
            afterTicks > afterMove,
          )
        }
      }
    }
}

/** Flattens a preset tree to its leaves, so a test can pick any concrete preset. */
private fun List<PresetEntry>.flatten(): List<PresetEntry.Preset> =
  flatMap { entry ->
    when (entry) {
      is PresetEntry.Preset -> listOf(entry)
      is PresetEntry.Submenu -> entry.entries.flatten()
    }
  }
