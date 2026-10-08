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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What the player is told, and can send on, after something went wrong with a game.
 *
 * [text] is complete and self-contained (including the unrestorable save, when there was one) so
 * that it can be pasted into a bug report without any other file.
 */
data class FailureReport(
  val gameName: String,
  val summary: String,
  val text: String,
  val savedGameKept: Boolean,
  val startedFresh: Boolean,
)

/**
 * Remembers failures, and whether a restore is in flight.
 *
 * A save that cannot be restored must not simply be deleted — it is the only reproduction of the
 * bug — nor left in place, where it would fail again on every launch. So it is moved aside here,
 * with a text report, and only the most recent few are kept to bound the storage used.
 *
 * The restore marker covers failures that cannot be caught: if the process is killed part way
 * through restoring (out of memory, a watchdog), no handler runs, and without the marker the next
 * launch would try the same restore and die in the same place indefinitely.
 */
class RecoveryStore(
  private val context: Context,
) {
  private val dir: File
    get() = File(context.filesDir, "recovery").also { it.mkdirs() }

  private fun marker(gameName: String) = File(dir, "restoring-${sanitise(gameName)}")

  private fun sanitise(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

  suspend fun markRestoring(gameName: String) {
    withContext(Dispatchers.IO) { marker(gameName).writeText(gameName) }
  }

  suspend fun clearRestoring(gameName: String) {
    withContext(Dispatchers.IO) { marker(gameName).delete() }
  }

  suspend fun wasRestoring(gameName: String): Boolean =
    withContext(Dispatchers.IO) { marker(gameName).exists() }

  /**
   * Writes the report (and [save], if any) to storage and returns it for display.
   *
   * @param detail technical detail such as a stack trace and the engine's stderr
   */
  suspend fun record(
    gameName: String,
    summary: String,
    detail: String,
    save: ByteArray?,
    startedFresh: Boolean,
    nowMillis: Long = System.currentTimeMillis(),
  ): FailureReport =
    withContext(Dispatchers.IO) {
      val text =
        buildString {
          appendLine("Puzzles failure report")
          appendLine("Game: $gameName")
          appendLine("Time: $nowMillis")
          appendLine("Summary: $summary")
          appendLine()
          appendLine(detail.trim())
          if (save != null) {
            appendLine()
            appendLine("Saved game (${save.size} bytes):")
            append(String(save.copyOf(minOf(save.size, MAX_EMBEDDED_SAVE)), Charsets.UTF_8))
          }
        }
      val stem = "$nowMillis-${sanitise(gameName)}"
      File(dir, "$stem.txt").writeText(text)
      if (save != null) File(dir, "$stem.sav").writeBytes(save)
      prune()
      FailureReport(
        gameName,
        summary,
        text,
        savedGameKept = save != null,
        startedFresh = startedFresh,
      )
    }

  private fun prune() {
    // Names start with the timestamp, so lexical order is age order.
    val reports = dir.listFiles { f -> f.name.endsWith(".txt") }.orEmpty().sortedBy { it.name }
    for (old in reports.dropLast(MAX_REPORTS)) {
      old.delete()
      File(old.parentFile, old.name.removeSuffix(".txt") + ".sav").delete()
    }
  }

  private companion object {
    const val MAX_REPORTS = 5
    const val MAX_EMBEDDED_SAVE = 64 * 1024
  }
}
