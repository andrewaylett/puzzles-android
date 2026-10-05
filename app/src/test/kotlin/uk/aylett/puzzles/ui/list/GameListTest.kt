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
package uk.aylett.puzzles.ui.list

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import uk.aylett.puzzles.engine.GameInfo

class GameListTest {
  private val games =
    listOf("Same Game", "Solo", "Sixteen", "Net", "Netslide").mapIndexed { i, n ->
      GameInfo(i, n, canSolve = true, canConfigure = true, canFormatAsText = false)
    }

  @Test
  fun blankFilterShowsEverything() {
    assertEquals(games, GameListUiState(games = games, filter = "").visibleGames)
    assertEquals(games, GameListUiState(games = games, filter = "   ").visibleGames)
  }

  @Test
  fun filterMatchesSubstringsIgnoringCase() {
    val names = GameListUiState(games = games, filter = "NET").visibleGames.map { it.name }
    assertEquals(listOf("Net", "Netslide"), names)
  }

  @Test
  fun filterMatchingNothingShowsNothing() {
    assertTrue(GameListUiState(games = games, filter = "zzz").visibleGames.isEmpty())
  }

  @Test
  fun rectanglesUsesItsHistoricalRectIcon() {
    assertNotNull(iconFor("Rectangles"))
  }

  @Test
  fun gamesAddedAfterTheIconSetHaveNoIcon() {
    assertNull(iconFor("Mosaic"))
    assertNull(iconFor("Palisade"))
  }
}
