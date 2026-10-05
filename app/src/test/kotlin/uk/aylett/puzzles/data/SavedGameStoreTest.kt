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
package uk.aylett.puzzles.data

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SavedGameStoreTest {
  @TempDir lateinit var dir: File

  private fun store(): SavedGameStore {
    val context = mockk<Context>()
    every { context.filesDir } returns dir
    return SavedGameStore(context)
  }

  @Test
  fun loadingAnUnsavedGameGivesNull() =
    runTest {
      assertNull(store().load("Solo"))
    }

  @Test
  fun savedBytesRoundTrip() =
    runTest {
      val bytes = byteArrayOf(0, 1, 2, -1, 127)
      store().save("Solo", bytes)
      assertArrayEquals(bytes, store().load("Solo"))
    }

  @Test
  fun savingAgainOverwrites() =
    runTest {
      val s = store()
      s.save("Solo", byteArrayOf(1, 2, 3))
      s.save("Solo", byteArrayOf(9))
      assertArrayEquals(byteArrayOf(9), s.load("Solo"))
    }

  @Test
  fun gamesAreKeptSeparate() =
    runTest {
      val s = store()
      s.save("Solo", byteArrayOf(1))
      s.save("Net", byteArrayOf(2))
      assertArrayEquals(byteArrayOf(1), s.load("Solo"))
      assertArrayEquals(byteArrayOf(2), s.load("Net"))
    }

  @Test
  fun awkwardNamesStayInsideTheSaveDirectory() =
    runTest {
      val s = store()
      s.save("../Same Game/x", byteArrayOf(5))
      assertArrayEquals(byteArrayOf(5), s.load("../Same Game/x"))
      val saved = File(dir, "saved_games").listFiles().orEmpty().map { it.name }
      assertEquals(1, saved.size)
      assertFalse(saved.single().contains('/'))
      assertEquals(0, dir.listFiles().orEmpty().count { it.isFile })
    }
}
