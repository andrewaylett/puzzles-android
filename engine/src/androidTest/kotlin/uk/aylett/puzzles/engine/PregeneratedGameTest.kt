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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Starting a game from one generated earlier (see [NewGameSource]): the game that comes out must be
 * the one that went in, still be solvable, and be offered only where the player asked for a *new*
 * game. Needs the real engine, and a session to start the game in.
 */
@RunWith(AndroidJUnit4::class)
class PregeneratedGameTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  /** Hands out one save, once, and records what it was asked for. */
  private class OneShot(
    private var save: ByteArray?,
  ) : NewGameSource {
    val asked = mutableListOf<Pair<String, List<ConfigField>>>()

    override fun take(
      gameName: String,
      settings: List<ConfigField>,
    ): ByteArray? {
      asked += gameName to settings
      return save.also { save = null }
    }
  }

  // Netslide: its solution is lost by a bare game id, so Solve working proves the save kept it.
  private suspend fun <T> withNetslide(block: suspend (PuzzleEngine, GameInfo) -> T): T =
    PuzzleEngine.create(context).use { engine ->
      block(engine, engine.games.first { it.name == "Netslide" })
    }

  private suspend fun generate(
    engine: PuzzleEngine,
    game: GameInfo,
    presetIndex: Int,
  ): Triple<ByteArray, String, List<ConfigField>> {
    // Walked as the pool's worker does, and for the same reason: the parameters must be the ones
    // a player's preset ends up with, transposed for portrait included.
    engine.openGame(game.index).use { maker ->
      val preset = maker.presets().filterIsInstance<PresetEntry.Preset>()[presetIndex]
      maker.selectPresetParams(preset.id)
      val settings = maker.config(ConfigSection.SETTINGS).fields
      val save = maker.generateSave()
      return Triple(save, maker.gameId(), settings)
    }
  }

  @Test
  fun aNewGameStartsFromThePooledSaveAndCanStillBeSolved() =
    runBlocking {
      withNetslide { engine, game ->
        val (save, id, settings) = generate(engine, game, 0)
        val source = OneShot(save)
        engine.newGameSource = source

        engine.openGame(game.index).use { session ->
          session.selectSettings(settings)
          session.resize(600, 600)
          session.newGame()

          assertEquals(id, session.gameId())
          assertNull(session.solve())
          assertEquals(settings, source.asked.single().second)
        }
      }
    }

  @Test
  fun aPresetStartsFromThePool() =
    runBlocking {
      withNetslide { engine, game ->
        val (save, id, _) = generate(engine, game, 1)
        engine.newGameSource = OneShot(save)

        engine.openGame(game.index).use { session ->
          session.resize(600, 600)
          val preset = session.presets().filterIsInstance<PresetEntry.Preset>()[1]
          session.applyPreset(preset.id)

          assertEquals(id, session.gameId())
        }
      }
    }

  @Test
  fun aSavedGameIsOnlyHandedOutOnce() =
    runBlocking {
      withNetslide { engine, game ->
        val (save, id, settings) = generate(engine, game, 0)
        engine.newGameSource = OneShot(save)

        engine.openGame(game.index).use { session ->
          session.selectSettings(settings)
          session.resize(600, 600)
          session.newGame()
          session.newGame()

          assertNotEquals(id, session.gameId())
        }
      }
    }

  @Test
  fun aGameIdOrRestartNeverConsultsThePool() =
    runBlocking {
      withNetslide { engine, game ->
        val (save, _, settings) = generate(engine, game, 0)
        val source = OneShot(save)
        engine.newGameSource = source

        engine.openGame(game.index).use { session ->
          session.selectSettings(settings)
          session.resize(600, 600)
          session.newGame()
          val asked = source.asked.size
          val named = session.gameId()

          assertNull(session.setGameId(named))
          session.restart()

          assertEquals(asked, source.asked.size)
        }
      }
    }

  @Test
  fun aSaveTheMidEndRejectsFallsBackToGenerating() =
    runBlocking {
      withNetslide { engine, game ->
        engine.newGameSource = OneShot(byteArrayOf(1, 2, 3))

        engine.openGame(game.index).use { session ->
          session.resize(600, 600)
          session.newGame()

          assertTrue(session.gameId().isNotEmpty())
        }
      }
    }

  @Test
  fun generatingNeverConsultsTheSourceItIsFilling() =
    runBlocking {
      withNetslide { engine, game ->
        val source = OneShot(byteArrayOf(1))
        engine.newGameSource = source

        engine.openGame(game.index).use { it.generateSave() }

        assertTrue(source.asked.isEmpty())
      }
    }
}
