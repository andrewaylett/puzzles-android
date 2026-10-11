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
package uk.aylett.puzzles.ui.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import uk.aylett.puzzles.EngineStatus
import uk.aylett.puzzles.data.CustomPreset
import uk.aylett.puzzles.data.CustomPresets
import uk.aylett.puzzles.data.RecoverySummary
import uk.aylett.puzzles.data.SavedGameInfo
import uk.aylett.puzzles.pool.PoolKey
import uk.aylett.puzzles.pool.PoolKindSummary

class AppStatusTest {
  private val solo = PoolKey("Solo", emptyList())

  private fun input(
    gameNames: List<String> = listOf("Net", "Solo", "Train Tracks"),
    favourites: Set<String> = emptySet(),
    custom: CustomPresets = CustomPresets(),
    saves: Map<String, SavedGameInfo> = emptyMap(),
    pools: Map<String, List<PoolKindSummary>> = emptyMap(),
  ) = AppStatusInputs(
    gameNames = gameNames,
    favourites = favourites,
    custom = custom,
    saves = saves,
    pools = pools,
    directoryName = { it.replace(' ', '_') },
    engine = EngineStatus.NotLoaded(),
    jobs = emptyList(),
    charging = false,
    powerSaving = false,
    recovery = RecoverySummary(0, 0, null, emptyList()),
  )

  @Test
  fun `a puzzle with nothing stored is left out, and counted as the rest`() {
    val status = buildAppStatus(input(favourites = setOf("Net")))

    assertEquals(listOf("Net"), status.games.map { it.name })
    assertEquals(3, status.totalGames)
  }

  @Test
  fun `a puzzle's pools are found through the name the pool files them under`() {
    val tracks = PoolKey("Train Tracks", emptyList())
    val status =
      buildAppStatus(
        input(
          pools =
            mapOf(
              "Train_Tracks" to
                listOf(PoolKindSummary(tracks, 3, 30), PoolKindSummary(tracks, 0, 0)),
            ),
        ),
      )

    val game = status.games.single()
    assertEquals("Train Tracks", game.name)
    assertEquals(3, game.pooledGames)
    assertEquals(1, game.pooledKinds)
    assertEquals(3, status.poolGames)
    assertEquals(30L, status.poolBytes)
  }

  @Test
  fun `pools of unknown kind are counted and flagged`() {
    val status =
      buildAppStatus(
        input(
          pools =
            mapOf("Solo" to listOf(PoolKindSummary(null, 2, 8), PoolKindSummary(solo, 1, 4))),
        ),
      )

    assertEquals(3, status.poolGames)
    assertEquals(2, status.poolGamesOfUnknownKind)
  }

  @Test
  fun `saves and custom types show on their puzzle and saves are totalled`() {
    val status =
      buildAppStatus(
        input(
          saves = mapOf("Solo" to SavedGameInfo(100, 5), "Net" to SavedGameInfo(20, 6)),
          custom =
            CustomPresets(mapOf("Solo" to listOf(CustomPreset("9x9, Mines 10", emptyList())))),
        ),
      )

    val soloStatus = status.games.single { it.name == "Solo" }
    assertEquals(SavedGameInfo(100, 5), soloStatus.save)
    assertEquals(listOf("9x9, Mines 10"), soloStatus.customTypes)
    assertNull(
      status.games
        .single { it.name == "Net" }
        .customTypes
        .firstOrNull(),
    )
    assertEquals(120L, status.saveBytes)
  }

  @Test
  fun `before any engine has named the puzzles, what the stores mention still appears`() {
    val status =
      buildAppStatus(
        input(
          gameNames = emptyList(),
          saves = mapOf("Net" to SavedGameInfo(1, 1)),
          pools = mapOf("Solo" to listOf(PoolKindSummary(solo, 1, 1))),
        ),
      )

    assertEquals(listOf("Net", "Solo"), status.games.map { it.name })
    assertTrue(status.loaded)
    assertEquals(0, status.totalGames)
  }
}
