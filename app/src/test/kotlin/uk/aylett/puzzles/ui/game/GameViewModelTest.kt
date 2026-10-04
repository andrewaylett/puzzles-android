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

import android.view.Surface
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
import uk.aylett.puzzles.data.SavedGameStore
import uk.aylett.puzzles.engine.GameInfo
import uk.aylett.puzzles.engine.KeyHint
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

  private val swapSetting = MutableStateFlow(false)

  private val prefs =
    mockk<AppPreferences>(relaxUnitFun = true) {
      every { swapPressGesture } returns swapSetting
      coEvery { setSwapPressGesture(any()) } coAnswers { swapSetting.value = firstArg() }
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
          every { frames } returns MutableStateFlow(PuzzleFrame())
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

  @Test
  fun `a live surface is handed to the replacement session after the engine is recycled`() =
    runTest(dispatcher) {
      engineReady.complete(engine)
      val vm = viewModel()
      advanceUntilIdle()
      val surface = mockk<Surface>(relaxed = true)
      vm.attachSurface(surface, 0x123456)
      verify(exactly = 1) { openedSessions.single().attachSurface(surface, 0x123456) }

      invalidated.emit(Unit)
      vm.onResume()
      advanceUntilIdle()

      // Without this the new session has nowhere to present, and the screen stays blank.
      assertEquals(2, openedSessions.size)
      verify(exactly = 1) { openedSessions.last().attachSurface(surface, 0x123456) }
    }

  @Test
  fun `a detached surface is not handed to a later session`() =
    runTest(dispatcher) {
      engineReady.complete(engine)
      val vm = viewModel()
      advanceUntilIdle()
      vm.attachSurface(mockk<Surface>(relaxed = true), 0)
      vm.detachSurface()
      verify(exactly = 1) { openedSessions.single().detachSurface() }

      invalidated.emit(Unit)
      vm.onResume()
      advanceUntilIdle()

      verify(exactly = 0) { openedSessions.last().attachSurface(any(), any()) }
    }

  @Test
  fun `the invert control flips the swap-press setting and the state follows it`() =
    runTest(dispatcher) {
      engineReady.complete(engine)
      val vm = viewModel()
      advanceUntilIdle()
      assertFalse(vm.state.value.swapPressGesture)

      vm.toggleSwapPressGesture()
      advanceUntilIdle()
      assertTrue(vm.state.value.swapPressGesture)

      vm.toggleSwapPressGesture()
      advanceUntilIdle()
      assertFalse(vm.state.value.swapPressGesture)
    }

  @Test
  fun `a game with no saved prefs gets the front end's defaults`() =
    runTest(dispatcher) {
      engineReady.complete(engine)
      viewModel()
      advanceUntilIdle()

      coVerify(exactly = 1) { openedSessions.single().applyPrefDefaults() }
      coVerify(exactly = 0) { openedSessions.single().loadPrefs(any()) }
    }

  @Test
  fun `saved prefs are loaded and the defaults do not override them`() =
    runTest(dispatcher) {
      val saved = byteArrayOf(1)
      coEvery { prefs.loadPrefs(any()) } returns saved
      engineReady.complete(engine)
      viewModel()
      advanceUntilIdle()

      coVerify(exactly = 1) { openedSessions.single().loadPrefs(saved) }
      coVerify(exactly = 0) { openedSessions.single().applyPrefDefaults() }
    }

  @Test
  fun `key hints are refreshed when the game id changes`() =
    runTest(dispatcher) {
      val frames = MutableStateFlow(PuzzleFrame())
      val session =
        mockk<PuzzleSession>(relaxUnitFun = true) {
          every { game } returns GameInfo(0, "Test", false, false, false)
          every { this@mockk.frames } returns frames
          every { animating } returns MutableStateFlow(false)
          coEvery { keyHints() } returnsMany
            listOf(
              listOf(KeyHint("1", '1'.code)),
              listOf(KeyHint("1", '1'.code), KeyHint("2", '2'.code)),
            )
          coEvery { serialise() } returns byteArrayOf()
          coEvery { savePrefs() } returns byteArrayOf()
        }
      coEvery { engine.openGame(any()) } returns session
      engineReady.complete(engine)
      val vm = viewModel()
      advanceUntilIdle()
      assertEquals(1, vm.state.value.keyHints.size)

      frames.value = PuzzleFrame(gameId = "3:abc")
      advanceUntilIdle()
      assertEquals(2, vm.state.value.keyHints.size)

      // The same params and a different seed still trigger a refresh, which is harmless; what
      // matters is that an unchanged id does not.
      frames.value = PuzzleFrame(gameId = "3:abc", canUndo = true)
      advanceUntilIdle()
      coVerify(exactly = 2) { session.keyHints() }
    }
}
