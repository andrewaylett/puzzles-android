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
package uk.aylett.puzzles.ui.game

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uk.aylett.puzzles.EngineProvider
import uk.aylett.puzzles.data.AppPreferences
import uk.aylett.puzzles.data.SavedGameStore
import uk.aylett.puzzles.engine.GameInfo
import uk.aylett.puzzles.engine.PuzzleEngine
import uk.aylett.puzzles.engine.PuzzleFrame
import uk.aylett.puzzles.engine.PuzzleSession

/**
 * Covers the session lifecycle, which is where the races live: `init` and `onResume` both want a
 * session, and the shared engine can be recycled underneath a backgrounded screen. The engine,
 * sessions and stores are mocked because the real ones need the WASM module and an Android
 * context; none of the behaviour under test depends on what a session actually does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val invalidated = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  private val engineReady = CompletableDeferred<PuzzleEngine>()
  private val engine = mockk<PuzzleEngine>()
  private val openedSessions = mutableListOf<PuzzleSession>()

  private val engines =
    object : EngineProvider {
      override val engineInvalidated = invalidated

      override suspend fun engine(): PuzzleEngine = engineReady.await()
    }

  private val prefs =
    mockk<AppPreferences>(relaxUnitFun = true) {
      every { swapPressGesture } returns flowOf(false)
      coEvery { loadPrefs(any()) } returns null
    }

  private val saves =
    mockk<SavedGameStore>(relaxUnitFun = true) {
      coEvery { load(any()) } returns null
      coEvery { save(any(), any()) } returns Unit
    }

  @BeforeEach
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    coEvery { engine.openGame(any()) } coAnswers
      {
        mockk<PuzzleSession>(relaxUnitFun = true) {
          every { game } returns GameInfo(0, "Test", false, false, false)
          every { frames } returns MutableStateFlow(PuzzleFrame(bitmap = null))
          every { animating } returns MutableStateFlow(false)
          coEvery { keyHints() } returns emptyList()
          coEvery { serialise() } returns byteArrayOf()
          coEvery { savePrefs() } returns byteArrayOf()
        }.also { openedSessions += it }
      }
  }

  @AfterEach
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun viewModel() = GameViewModel(engines, 0, prefs, saves)

  @Test
  fun `onResume while the first open is still waiting for the engine opens only one session`() =
    runTest(dispatcher) {
      val vm = viewModel()
      advanceUntilIdle() // init's open is now suspended on the engine
      vm.onResume()
      advanceUntilIdle() // ...and so is onResume's
      engineReady.complete(engine)
      advanceUntilIdle()

      coVerify(exactly = 1) { engine.openGame(any()) }
    }

  @Test
  fun `onResume with a live session does not reopen it`() =
    runTest(dispatcher) {
      engineReady.complete(engine)
      val vm = viewModel()
      advanceUntilIdle()
      vm.onResume()
      advanceUntilIdle()

      coVerify(exactly = 1) { engine.openGame(any()) }
    }

  @Test
  fun `a recycled engine is replaced on the next resume, not before`() =
    runTest(dispatcher) {
      engineReady.complete(engine)
      val vm = viewModel()
      advanceUntilIdle()

      invalidated.emit(Unit)
      advanceUntilIdle()
      coVerify(exactly = 1) { engine.openGame(any()) }

      vm.onResume()
      advanceUntilIdle()
      coVerify(exactly = 2) { engine.openGame(any()) }
    }

  @Test
  fun `a recycled engine's session is dropped rather than closed`() =
    runTest(dispatcher) {
      engineReady.complete(engine)
      viewModel()
      advanceUntilIdle()

      invalidated.emit(Unit)
      advanceUntilIdle()

      // Closing would re-enter the engine thread that no longer exists.
      assertFalse(openedSessions.isEmpty())
      openedSessions.forEach { verify(exactly = 0) { it.close() } }
    }
}
