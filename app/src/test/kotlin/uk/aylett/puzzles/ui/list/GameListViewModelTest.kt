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

import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uk.aylett.puzzles.EngineProvider
import uk.aylett.puzzles.data.AppPreferences
import uk.aylett.puzzles.engine.GameInfo
import uk.aylett.puzzles.engine.PuzzleEngine

@OptIn(ExperimentalCoroutinesApi::class)
class GameListViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val engineReady = CompletableDeferred<PuzzleEngine>()
  private val games =
    listOf("Net", "Netslide", "Solo").mapIndexed { i, n ->
      GameInfo(i, n, canSolve = true, canConfigure = true, canFormatAsText = false)
    }

  private val engines =
    object : EngineProvider {
      override val engineInvalidated = MutableSharedFlow<Unit>()

      override suspend fun engine(): PuzzleEngine = engineReady.await()

      override suspend fun discardEngine(failed: PuzzleEngine) = Unit

      override val applicationScope = CoroutineScope(SupervisorJob())
    }

  private val favourites = MutableStateFlow(emptySet<String>())
  private val prefs =
    mockk<AppPreferences>(relaxUnitFun = true) {
      every { this@mockk.favourites } returns this@GameListViewModelTest.favourites
    }

  @BeforeEach
  fun setUp() = Dispatchers.setMain(dispatcher)

  @AfterEach
  fun tearDown() = Dispatchers.resetMain()

  @Test
  fun loadingUntilTheEngineIsReady() =
    runTest(dispatcher) {
      val vm = GameListViewModel(engines, prefs)
      advanceUntilIdle()
      assertTrue(vm.uiState.value.loading)
      assertTrue(
        vm.uiState.value.games
          .isEmpty(),
      )
    }

  @Test
  fun listsTheEnginesGamesOnceReady() =
    runTest(dispatcher) {
      val vm = GameListViewModel(engines, prefs)
      engineReady.complete(
        mockk {
          every { this@mockk.games } returns
            this@GameListViewModelTest.games
        },
      )
      advanceUntilIdle()
      assertFalse(vm.uiState.value.loading)
      assertEquals(games, vm.uiState.value.games)
    }

  @Test
  fun filterSurvivesLoadingAndNarrowsTheList() =
    runTest(dispatcher) {
      val vm = GameListViewModel(engines, prefs)
      vm.onFilterChange("net")
      engineReady.complete(
        mockk {
          every { this@mockk.games } returns
            this@GameListViewModelTest.games
        },
      )
      advanceUntilIdle()
      assertEquals(
        listOf("Net", "Netslide"),
        vm.uiState.value.visibleGames
          .map { it.name },
      )
    }

  @Test
  fun favouritesSortFirstAndKeepUpstreamOrderOtherwise() =
    runTest(dispatcher) {
      val vm = GameListViewModel(engines, prefs)
      engineReady.complete(
        mockk {
          every { this@mockk.games } returns
            this@GameListViewModelTest.games
        },
      )
      favourites.value = setOf("Solo", "Netslide")
      advanceUntilIdle()
      assertEquals(
        listOf("Netslide", "Solo", "Net"),
        vm.uiState.value.visibleGames
          .map { it.name },
      )
    }

  @Test
  fun longPressStarsAnUnstarredGameAndUnstarsAStarredOne() =
    runTest(dispatcher) {
      val vm = GameListViewModel(engines, prefs)
      advanceUntilIdle()
      vm.onToggleFavourite(games[0])
      advanceUntilIdle()
      coVerify { prefs.setFavourite("Net", true) }

      favourites.value = setOf("Net")
      advanceUntilIdle()
      vm.onToggleFavourite(games[0])
      advanceUntilIdle()
      coVerify { prefs.setFavourite("Net", false) }
    }
}
