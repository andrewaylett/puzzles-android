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
 * Persists one in-progress game per puzzle type to the app's private files directory, so leaving
 * the app or rotating the device does not lose progress. No permission is needed: this is
 * app-private storage, distinct from the Storage Access Framework used for user-visible
 * import/export.
 *
 * Keyed by [uk.aylett.puzzles.engine.GameInfo.name] rather than its `index`, which is just
 * upstream's list ordering and could shift under us.
 */
class SavedGameStore(
  private val context: Context,
) {
  private fun file(gameName: String): File {
    val dir = File(context.filesDir, "saved_games")
    dir.mkdirs()
    return File(dir, "${sanitise(gameName)}.sav")
  }

  private fun sanitise(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

  suspend fun load(gameName: String): ByteArray? =
    withContext(Dispatchers.IO) {
      val f = file(gameName)
      if (f.exists()) f.readBytes() else null
    }

  suspend fun save(
    gameName: String,
    bytes: ByteArray,
  ) {
    withContext(Dispatchers.IO) {
      file(gameName).writeBytes(bytes)
    }
  }
}
