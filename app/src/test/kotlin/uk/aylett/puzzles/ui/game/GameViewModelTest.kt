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
import kotlinx.coroutines.test.TestScope
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
import uk.aylett.puzzles.data.FailureReport
import uk.aylett.puzzles.data.RecoveryStore
import uk.aylett.puzzles.data.SavedGameStore
import uk.aylett.puzzles.data.settingId
import uk.aylett.puzzles.engine.ConfigField
import uk.aylett.puzzles.engine.ConfigForm
import uk.aylett.puzzles.engine.ConfigSection
import uk.aylett.puzzles.engine.EngineFailure
import uk.aylett.puzzles.engine.GameInfo
import uk.aylett.puzzles.engine.GameStatus
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
  private val discarded = mutableListOf<PuzzleEngine>()

  // Consumed by the next session's deserialise, to make a restore fail the way a trap does.
  private var deserialiseFailure: Throwable? = null

  // What every mock session reports for engineClosed; flipped to simulate the engine being
  // recycled without the invalidation notification having arrived yet.
  private var engineClosedFlag = false

  private val engines =
    object : EngineProvider {
      override val engineInvalidated = invalidated

      override suspend fun engine(): PuzzleEngine = engineReady.await()

      override suspend fun discardEngine(failed: PuzzleEngine) {
        discarded += failed
      }
    }

  private val swapSetting = MutableStateFlow(false)

  private val stylusSetting = MutableStateFlow(true)

  private val prefs =
    mockk<AppPreferences>(relaxUnitFun = true) {
      every { swapPressGesture } returns swapSetting
      coEvery { setSwapPressGesture(any()) } coAnswers { swapSetting.value = firstArg() }
      every { stylusTaps } returns stylusSetting
      coEvery { setStylusTaps(any()) } coAnswers { stylusSetting.value = firstArg() }
      coEvery { loadPrefs(any()) } returns null
      every { sharedSettings } returns MutableStateFlow(emptyMap())
    }

  private val saves =
    mockk<SavedGameStore>(relaxUnitFun = true) {
      coEvery { load(any()) } returns null
      coEvery { save(any(), any()) } returns Unit
    }

  private val recovery =
    mockk<RecoveryStore>(relaxUnitFun = true) {
      coEvery { wasRestoring(any()) } returns false
      coEvery { record(any(), any(), any(), any(), any(), any()) } answers
        {
          FailureReport(
            firstArg(),
            secondArg(),
            thirdArg(),
            savedGameKept = false,
            startedFresh = true,
          )
        }
    }

  @BeforeEach
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    every { engine.games } returns listOf(GameInfo(0, "Test", false, false, false))
    coEvery { engine.openGame(any()) } coAnswers
      {
        mockk<PuzzleSession>(relaxUnitFun = true) {
          every { game } returns GameInfo(0, "Test", false, false, false)
          every { frames } returns MutableStateFlow(PuzzleFrame())
          every { animating } returns MutableStateFlow(false)
          every { engineClosed } answers { engineClosedFlag }
          coEvery { keyHints() } returns emptyList()
          coEvery { config(any()) } returns ConfigForm("Preferences", emptyList())
          coEvery { saveState() } returns PuzzleSession.SavedState(byteArrayOf(), byteArrayOf())
          coEvery { deserialise(any()) } coAnswers
            {
              deserialiseFailure?.let {
                deserialiseFailure = null
                throw it
              }
              null
            }
        }.also { openedSessions += it }
      }
  }

  @AfterEach
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun viewModel() = GameViewModel(engines, 0, prefs, saves, recovery)

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
  fun `a session whose engine closed is replaced on resume even if no notification arrived`() =
    runTest(dispatcher) {
      engineReady.complete(engine)
      val vm = viewModel()
      advanceUntilIdle()

      engineClosedFlag = true // only the first session is consulted, before it is replaced
      vm.onResume()
      advanceUntilIdle()

      coVerify(exactly = 2) { engine.openGame(any()) }
      // The dead session is dropped, not closed or resumed.
      verify(exactly = 0) { openedSessions.first().close() }
      verify(exactly = 0) { openedSessions.first().setPaused(false) }
      verify(exactly = 1) { openedSessions.last().setPaused(false) }
    }

  @Test
  fun `a save that breaks the engine is quarantined and replaced by a fresh game`() =
    runTest(dispatcher) {
      engineReady.complete(engine)
      coEvery { saves.load("Test") } returns byteArrayOf(1, 2, 3)
      coEvery { saves.take("Test") } returns byteArrayOf(1, 2, 3)
      deserialiseFailure = EngineFailure(IllegalStateException("unreachable"), "Assertion failed")

      val vm = viewModel()
      advanceUntilIdle()

      assertEquals(listOf(engine), discarded)
      coVerify { saves.take("Test") }
      coVerify { recovery.record("Test", any(), any(), byteArrayOf(1, 2, 3), true, any()) }
      assertTrue(vm.state.value.failure != null)
      // The replacement session did not try the save again.
      assertEquals(2, openedSessions.size)
      coVerify(exactly = 0) { openedSessions.last().deserialise(any()) }
      assertFalse(vm.state.value.loading)
    }

  @Test
  fun `a restore marker left by an earlier run quarantines the save without restoring it`() =
    runTest(dispatcher) {
      engineReady.complete(engine)
      coEvery { recovery.wasRestoring("Test") } returns true
      coEvery { saves.take("Test") } returns byteArrayOf(9)

      val vm = viewModel()
      advanceUntilIdle()

      coVerify { saves.take("Test") }
      coVerify(exactly = 0) { saves.load(any()) }
      coVerify(exactly = 0) { openedSessions.single().deserialise(any()) }
      assertTrue(vm.state.value.failure != null)
    }

  @Test
  fun `a restore is marked while in flight and unmarked once saved`() =
    runTest(dispatcher) {
      engineReady.complete(engine)
      coEvery { saves.load("Test") } returns byteArrayOf(1)

      val vm = viewModel()
      advanceUntilIdle()
      coVerify { recovery.markRestoring("Test") }
      coVerify(exactly = 0) { recovery.clearRestoring("Test") }

      vm.onPause()
      advanceUntilIdle()
      coVerify { recovery.clearRestoring("Test") }
    }

  @Test
  fun `onPause persists the game and prefs from a single engine call`() =
    runTest(dispatcher) {
      engineReady.complete(engine)
      val vm = viewModel()
      advanceUntilIdle()

      vm.onPause()
      advanceUntilIdle()

      val session = openedSessions.single()
      coVerify(exactly = 1) { session.saveState() }
      coVerify(exactly = 0) { session.serialise() }
      coVerify(exactly = 0) { session.savePrefs() }
      coVerify { saves.save("Test", any()) }
      coVerify { prefs.savePrefs("Test", any()) }
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
  fun `stylus taps are on by default and the toggle flips them`() =
    runTest(dispatcher) {
      engineReady.complete(engine)
      val vm = viewModel()
      advanceUntilIdle()
      assertTrue(vm.state.value.stylusTaps)

      vm.toggleStylusTaps()
      advanceUntilIdle()
      assertFalse(vm.state.value.stylusTaps)
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
          coEvery { config(any()) } returns ConfigForm("Preferences", emptyList())
          coEvery { saveState() } returns PuzzleSession.SavedState(byteArrayOf(), byteArrayOf())
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

  private fun TestScope.openWithSave(status: Int): PuzzleSession {
    val session =
      mockk<PuzzleSession>(relaxUnitFun = true) {
        every { game } returns GameInfo(0, "Test", false, false, false)
        every { frames } returns MutableStateFlow(PuzzleFrame(status = status))
        every { animating } returns MutableStateFlow(false)
        coEvery { keyHints() } returns emptyList()
        coEvery { config(any()) } returns ConfigForm("Preferences", emptyList())
        coEvery { deserialise(any()) } returns null
        coEvery { saveState() } returns PuzzleSession.SavedState(byteArrayOf(), byteArrayOf())
      }
    coEvery { engine.openGame(any()) } returns session
    coEvery { saves.load(any()) } returns byteArrayOf(1)
    engineReady.complete(engine)
    val vm = viewModel()
    advanceUntilIdle()
    vm.onSizeChanged(100, 100, 1f)
    advanceUntilIdle()
    return session
  }

  @Test
  fun `a restored game that was already won is replaced by a new one`() =
    runTest(dispatcher) {
      val session = openWithSave(GameStatus.WON)

      coVerify(exactly = 1) { session.newGame() }
    }

  @Test
  fun `a restored game still in progress is kept`() =
    runTest(dispatcher) {
      val session = openWithSave(GameStatus.IN_PROGRESS)

      coVerify(exactly = 0) { session.newGame() }
    }

  @Test
  fun `a changed shared setting is pushed into the live session`() =
    runTest(dispatcher) {
      val toggle = ConfigField.Toggle("Keep", "pencil-keep-highlight", false)
      val stored = MutableStateFlow(emptyMap<String, String>())
      every { prefs.sharedSettings } returns stored
      engineReady.complete(engine)
      viewModel()
      advanceUntilIdle()
      val session = openedSessions.single()
      coEvery { session.config(any()) } returns ConfigForm("Preferences", listOf(toggle))
      coEvery { session.applyConfig(any(), any()) } returns null

      stored.value = mapOf(toggle.settingId()!! to "true")
      advanceUntilIdle()

      coVerify { session.applyConfig(ConfigSection.PREFS, listOf(toggle.copy(value = true))) }
    }

  @Test
  fun `applying preferences records them as shared settings`() =
    runTest(dispatcher) {
      val toggle = ConfigField.Toggle("Keep", "pencil-keep-highlight", true)
      engineReady.complete(engine)
      val vm = viewModel()
      advanceUntilIdle()
      coEvery { openedSessions.single().applyConfig(any(), any()) } returns null

      vm.applyConfig(ConfigSection.PREFS, listOf(toggle))

      coVerify { prefs.setSharedSettings(mapOf(toggle.settingId()!! to "true")) }
    }
}
