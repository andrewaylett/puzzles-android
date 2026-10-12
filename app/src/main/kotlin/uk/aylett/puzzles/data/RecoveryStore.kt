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
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * What the player is told, and can send on, after something went wrong with a game.
 *
 * [text] is complete and self-contained (including the app's recent log output and the
 * unrestorable save, when there was one) so that it can be read without any other file. [file]
 * holds the same text on disk, because many share targets accept a file but not a long piece of
 * text.
 */
data class FailureReport(
  val gameName: String,
  val summary: String,
  val text: String,
  val savedGameKept: Boolean,
  val startedFresh: Boolean,
  val file: File? = null,
)

/** One failure report kept on disk, as listed on the status screen. */
data class StoredReport(
  val file: File,
  val gameName: String,
  val summary: String,
  /** When it was recorded, from the file name. */
  val millis: Long,
  /** Whether the unrestorable save was set aside with it. */
  val savedGameKept: Boolean,
)

/** What [RecoveryStore] is holding, for diagnostics. */
data class RecoverySummary(
  /** Failure reports kept, at most the store's limit. */
  val reports: Int,
  /** Saves moved aside because they could not be restored. */
  val quarantinedSaves: Int,
  /** When the newest report was written, if there is one. */
  val newestReportMillis: Long?,
  /** Games whose restore started and never finished: the last launch died part way through one. */
  val restoreMarkers: List<String>,
  /** The reports themselves, newest first. */
  val history: List<StoredReport> = emptyList(),
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
  private val readLog: () -> String = ::readOwnLog,
) {
  private val dir: File
    get() = File(context.filesDir, "recovery").also { it.mkdirs() }

  private fun marker(gameName: String) = File(dir, "$MARKER_PREFIX${sanitise(gameName)}")

  private fun sanitise(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

  suspend fun markRestoring(gameName: String) {
    withContext(Dispatchers.IO) { marker(gameName).writeText(gameName) }
  }

  suspend fun clearRestoring(gameName: String) {
    withContext(Dispatchers.IO) { marker(gameName).delete() }
  }

  suspend fun wasRestoring(gameName: String): Boolean =
    withContext(Dispatchers.IO) { marker(gameName).exists() }

  suspend fun summary(): RecoverySummary =
    withContext(Dispatchers.IO) {
      val files = dir.listFiles().orEmpty()
      val reports = files.filter { it.name.endsWith(".txt") }
      RecoverySummary(
        reports = reports.size,
        quarantinedSaves = files.count { it.name.endsWith(".sav") },
        // The file name starts with the time it was recorded, see [record].
        newestReportMillis =
          reports.maxOfOrNull { it.name }?.substringBefore('-')?.toLongOrNull(),
        restoreMarkers =
          files
            .filter { it.name.startsWith(MARKER_PREFIX) }
            .map { it.readText() }
            .sorted(),
        history = reports.sortedByDescending { it.name }.mapNotNull(::readReport),
      )
    }

  /** Reads just the header [record] wrote; the rest of a report can be large. */
  private fun readReport(file: File): StoredReport? {
    val millis = file.name.substringBefore('-').toLongOrNull() ?: return null
    val header =
      try {
        file.useLines { lines -> lines.take(HEADER_LINES).toList() }
      } catch (_: IOException) {
        return null
      }

    fun field(name: String) = header.firstOrNull { it.startsWith("$name: ") }?.substringAfter(": ")
    return StoredReport(
      file = file,
      gameName = field("Game") ?: "?",
      summary = field("Summary").orEmpty(),
      millis = millis,
      savedGameKept = File(file.parentFile, file.name.removeSuffix(".txt") + ".sav").exists(),
    )
  }

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
          appendLine()
          appendLine("Log output:")
          appendLine(readLog().trim())
          if (save != null) {
            appendLine()
            appendLine("Saved game (${save.size} bytes):")
            append(String(save.copyOf(minOf(save.size, MAX_EMBEDDED_SAVE)), Charsets.UTF_8))
          }
        }
      val stem = "$nowMillis-${sanitise(gameName)}"
      val file = File(dir, "$stem.txt").also { it.writeText(text) }
      if (save != null) File(dir, "$stem.sav").writeBytes(save)
      prune()
      FailureReport(
        gameName,
        summary,
        text,
        savedGameKept = save != null,
        startedFresh = startedFresh,
        file = file,
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
    const val MARKER_PREFIX = "restoring-"
    const val HEADER_LINES = 4
    const val MAX_REPORTS = 5
    const val MAX_EMBEDDED_SAVE = 64 * 1024
  }
}

/**
 * The tail of this process's own logcat. An app may always read its own log without a permission,
 * which is what keeps the "no permissions" rule intact. It is the log of *this* process only: a
 * report written after a previous run died (the restore marker) has none of that run's output.
 */
private fun readOwnLog(): String =
  try {
    val process =
      ProcessBuilder(
        "logcat",
        "-d",
        "-t",
        LOG_LINES.toString(),
        "-v",
        "threadtime",
        "--pid=${Process.myPid()}",
      ).redirectErrorStream(true)
        .start()
    process.inputStream
      .bufferedReader()
      .use { it.readText() }
      .also { process.waitFor() }
  } catch (e: IOException) {
    "(log unavailable: $e)"
  }

private const val LOG_LINES = 500
