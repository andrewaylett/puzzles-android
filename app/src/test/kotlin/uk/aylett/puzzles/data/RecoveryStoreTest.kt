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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RecoveryStoreTest {
  @TempDir lateinit var dir: File

  private fun store(): RecoveryStore {
    val context = mockk<Context>()
    every { context.filesDir } returns dir
    return RecoveryStore(context, readLog = { "LOGLINE" })
  }

  private fun files(extension: String) =
    File(dir, "recovery").listFiles().orEmpty().filter { it.name.endsWith(extension) }

  @Test
  fun `the restore marker is per game and can be cleared`() =
    runTest {
      val s = store()
      assertFalse(s.wasRestoring("Solo"))
      s.markRestoring("Solo")
      assertTrue(s.wasRestoring("Solo"))
      assertFalse(s.wasRestoring("Net"))
      s.clearRestoring("Solo")
      assertFalse(s.wasRestoring("Solo"))
    }

  @Test
  fun `a report carries the save so it stands alone, and the save is kept`() =
    runTest {
      val report = store().record("Solo", "broke", "trace", "SAVEFILE:x".toByteArray(), true)
      assertTrue(report.text.contains("trace"))
      assertTrue(report.text.contains("SAVEFILE:x"))
      assertTrue(report.savedGameKept)
      assertEquals(1, files(".sav").size)
    }

  @Test
  fun `a report includes log output and is also written as a file with the same text`() =
    runTest {
      val report = store().record("Solo", "broke", "trace", null, false)
      assertTrue(report.text.contains("LOGLINE"))
      assertEquals(report.text, report.file?.readText())
    }

  @Test
  fun `only the most recent reports are kept`() =
    runTest {
      val s = store()
      for (i in 1..8) {
        s.record(
          "Solo",
          "broke",
          "trace",
          byteArrayOf(1),
          true,
          nowMillis = i * 1000L,
        )
      }
      assertEquals(5, files(".txt").size)
      assertEquals(5, files(".sav").size)
      assertTrue(files(".txt").none { it.name.startsWith("1000-") })
    }

  @Test
  fun `the summary counts reports and saves, and names a restore that never finished`() =
    runTest {
      val s = store()
      assertEquals(RecoverySummary(0, 0, null, emptyList(), emptyList()), s.summary())

      s.record("Solo", "broke", "trace", byteArrayOf(1), true, nowMillis = 1_000)
      s.record("Net", "broke", "trace", null, false, nowMillis = 2_000)
      s.markRestoring("Train Tracks")

      val summary = s.summary()
      assertEquals(2, summary.reports)
      assertEquals(1, summary.quarantinedSaves)
      assertEquals(2_000L, summary.newestReportMillis)
      assertEquals(listOf("Train Tracks"), summary.restoreMarkers)
    }

  @Test
  fun `the history lists each kept report newest first, with what it says about itself`() =
    runTest {
      val s = store()
      s.record("Solo", "first", "trace", byteArrayOf(1), true, nowMillis = 1_000)
      s.record("Train Tracks", "second", "trace", null, false, nowMillis = 2_000)

      val history = s.summary().history
      assertEquals(listOf("Train Tracks", "Solo"), history.map { it.gameName })
      assertEquals(listOf("second", "first"), history.map { it.summary })
      assertEquals(listOf(2_000L, 1_000L), history.map { it.millis })
      assertEquals(listOf(false, true), history.map { it.savedGameKept })
      assertTrue(history.all { it.file.readText().contains("trace") })
    }
}
