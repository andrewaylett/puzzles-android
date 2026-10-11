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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import uk.aylett.puzzles.data.AppPreferences
import uk.aylett.puzzles.data.SavedGameStore
import uk.aylett.puzzles.engine.EngineFailure
import uk.aylett.puzzles.engine.PuzzleSession

/** On virtual time: the debounce is what is being tested, and it must not take real time. */
@OptIn(ExperimentalCoroutinesApi::class)
class GamePersisterTest {
  private val revision = MutableStateFlow(0L)
  private val saves =
    mockk<SavedGameStore>(relaxUnitFun = true) { coEvery { save(any(), any()) } returns Unit }
  private val prefs = mockk<AppPreferences>(relaxUnitFun = true)
  private val failures = mutableListOf<EngineFailure>()
  private var serialised = 0
  private var next = 0

  // Every snapshot differs from the last, unless a test says otherwise.
  private val session =
    mockk<PuzzleSession> {
      every { revision } returns this@GamePersisterTest.revision
      coEvery { saveState() } answers
        { PuzzleSession.SavedState(byteArrayOf((++next).toByte()), byteArrayOf()) }
    }

  private fun TestScope.persister() =
    GamePersister(
      scope = this,
      saves = saves,
      prefs = prefs,
      onFailure = { _, e -> failures += e },
      onSerialised = { serialised++ },
    )

  @Test
  fun `a burst of changes is saved once, after it stops`() =
    runTest {
      val persister = persister()
      persister.attach(session, "Test", null, null)
      runCurrent()
      val before = next

      repeat(10) {
        revision.value++
        advanceTimeBy(AUTOSAVE_DEBOUNCE_INTERVAL / 2)
      }
      advanceUntilIdle()

      assertEquals(1, next - before)
      persister.detach()
    }

  @Test
  fun `flush saves at once instead of waiting out the debounce`() =
    runTest {
      val persister = persister()
      persister.attach(session, "Test", null, null)
      runCurrent()
      val before = next

      persister.flush().join()

      assertEquals(1, next - before)
      coVerify(exactly = before + 1) { saves.save("Test", any()) }
      persister.detach()
    }

  @Test
  fun `a snapshot identical to what is on disk is not written again`() =
    runTest {
      coEvery { session.saveState() } returns
        PuzzleSession.SavedState(byteArrayOf(1), byteArrayOf(2))
      val persister = persister()
      persister.attach(session, "Test", byteArrayOf(1), byteArrayOf(2))
      advanceUntilIdle()

      persister.flush().join()

      coVerify(exactly = 0) { saves.save(any(), any()) }
      // Serialising it still proved the game sound.
      assertEquals(2, serialised)
      persister.detach()
    }

  @Test
  fun `nothing is saved before a session is attached`() =
    runTest {
      val persister = persister()
      revision.value++
      advanceUntilIdle()
      persister.flush().join()

      coVerify(exactly = 0) { saves.save(any(), any()) }
    }

  @Test
  fun `a session with no game saves nothing`() =
    runTest {
      coEvery { session.saveState() } returns null
      val persister = persister()
      persister.attach(session, "Test", null, null)
      advanceUntilIdle()
      persister.flush().join()

      coVerify(exactly = 0) { saves.save(any(), any()) }
      assertEquals(0, serialised)
      persister.detach()
    }

  @Test
  fun `a detached persister stops watching, and a save in flight still lands`() =
    runTest {
      val persister = persister()
      persister.attach(session, "Test", null, null)
      advanceUntilIdle()
      val job = persister.flush()
      persister.detach()
      revision.value++
      advanceUntilIdle()
      job.join()

      // Once for the initial revision and once for the flush; the later change was not watched.
      coVerify(exactly = 2) { saves.save("Test", any()) }
    }

  @Test
  fun `an engine failure is reported rather than thrown`() =
    runTest {
      val failure = EngineFailure(IllegalStateException("trap"), "stderr")
      coEvery { session.saveState() } throws failure
      val persister = persister()
      persister.attach(session, "Test", null, null)

      advanceUntilIdle()

      assertEquals(listOf(failure), failures)
      coVerify(exactly = 0) { saves.save(any(), any()) }
      persister.detach()
    }
}
