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
package uk.aylett.puzzles.pool

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uk.aylett.puzzles.data.StoredField
import uk.aylett.puzzles.data.storedField
import uk.aylett.puzzles.engine.ConfigField
import uk.aylett.puzzles.engine.NewGameSource
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * A kind of game: a puzzle and the settings it was generated with.
 *
 * The settings are identified as a custom type is (see [StoredField]), by label, so the same
 * parameters reached through a preset, the Custom dialog or a stored custom type are one key. The
 * preset id is no use for this: it is an index into a menu, and presets are transposed for portrait
 * (see [uk.aylett.puzzles.engine.transposed]) so the parameters actually played differ from it.
 * The game id is no use either, as it varies with the game generated.
 */
@Serializable
data class PoolKey(
  val game: String,
  val settings: List<StoredField>,
) {
  /** Stable and filename-safe, which the label text itself is not. */
  val id: String by lazy {
    val text = (listOf(game) + settings.map { "${it.name}=${it.value}" }).joinToString("\n")
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.toByteArray())
      .take(ID_BYTES)
      .joinToString("") { "%02x".format(it) }
  }

  companion object {
    private const val ID_BYTES = 12

    fun of(
      game: String,
      settings: List<ConfigField>,
    ) = PoolKey(game, settings.map { it.storedField() })
  }
}

/** What [PuzzlePool.summary] found for one kind of game. */
data class PoolKindSummary(
  /** Null for a pool written before the key was recorded beside it; the games are still good. */
  val key: PoolKey?,
  val games: Int,
  val bytes: Long,
)

/**
 * Games generated ahead of time and kept on disk, one save each, so that starting a game need not
 * wait for the generator.
 *
 * A save rather than a game id because it keeps what a game id loses: the seed, and the solver's
 * auxiliary data, without which Solve fails for any back end that cannot re-derive the answer.
 *
 * It lives in [dir], which should be excluded from backup: it is bulk, regenerable, and says
 * nothing about the player. All access is serialised by one lock, since [take] runs on the engine
 * thread while the generating worker calls [put] from its own. Each game is its own file, written
 * beside and renamed into place as [uk.aylett.puzzles.data.SavedGameStore] does, so being killed
 * mid-write cannot leave a truncated game for the next [take].
 */
class PuzzlePool(
  private val dir: File,
  /** Called with the key when a [take] leaves its pool empty, outside the lock. */
  private val onEmptied: (PoolKey) -> Unit = {},
  /**
   * Called with the key when a [take] found nothing, so the caller is about to generate
   * synchronously. Outside the lock.
   */
  private val onMissed: (PoolKey) -> Unit = {},
) : NewGameSource {
  private val lock = Any()

  private fun keyDir(key: PoolKey) = File(File(dir, directoryName(key.game)), key.id)

  private fun games(keyDir: File): List<File> =
    keyDir.listFiles { f -> f.name.endsWith(SUFFIX) }?.sortedBy { it.name }.orEmpty()

  fun count(key: PoolKey): Int = synchronized(lock) { games(keyDir(key)).size }

  fun put(
    key: PoolKey,
    bytes: ByteArray,
  ) {
    synchronized(lock) {
      val target = keyDir(key)
      target.mkdirs()
      // The directory names are a hash and a sanitised title, neither of which can be turned back
      // into a key, so the status screen could not say what a pool is for without this.
      val keyFile = File(target, KEY_FILE)
      if (!keyFile.exists()) {
        keyFile.writeText(Json.encodeToString(PoolKey.serializer(), key))
      }
      // Oldest first when taking, so a game does not sit for ever behind newer ones.
      val name = "${System.currentTimeMillis()}-${UUID.randomUUID()}"
      val temp = File(target, "$name.tmp")
      temp.writeBytes(bytes)
      Files.move(
        temp.toPath(),
        File(target, name + SUFFIX).toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
      )
    }
  }

  override fun take(
    gameName: String,
    settings: List<ConfigField>,
  ): ByteArray? {
    val key = PoolKey.of(gameName, settings)
    val taken =
      synchronized(lock) {
        val available = games(keyDir(key))
        val next = available.firstOrNull()
        if (next == null) {
          null
        } else {
          // Deleted before it is returned, so a game that then fails cannot be handed out again.
          val bytes = next.readBytes()
          next.delete()
          bytes to (available.size == 1)
        }
      }
    if (taken == null) {
      onMissed(key)
      return null
    }
    if (taken.second) onEmptied(key)
    return taken.first
  }

  /**
   * What is kept, by the directory name each puzzle's pools are filed under (see [directoryName]).
   * For diagnostics: it reads the disk under the lock, so the engine thread's [take] waits for it.
   */
  fun summary(): Map<String, List<PoolKindSummary>> =
    synchronized(lock) {
      dir
        .listFiles { f -> f.isDirectory }
        .orEmpty()
        .associate { gameDir ->
          gameDir.name to
            gameDir.listFiles { f -> f.isDirectory }.orEmpty().map { keyDir ->
              val saves = games(keyDir)
              PoolKindSummary(
                key =
                  runCatching {
                    Json.decodeFromString(
                      PoolKey.serializer(),
                      File(keyDir, KEY_FILE).readText(),
                    )
                  }.getOrNull(),
                games = saves.size,
                bytes = saves.sumOf { it.length() },
              )
            }
        }
    }

  /** The name [summary] files [game]'s pools under, which is not [game] itself. */
  fun directoryName(game: String): String = game.replace(UNSAFE, "_")

  /** Throws away every game kept for [key], for a kind that is no longer wanted. */
  fun discard(key: PoolKey) {
    synchronized(lock) { keyDir(key).deleteRecursively() }
  }

  /**
   * Makes the pools for [game] exactly [wanted] (key id to the most to keep): kinds no longer wanted
   * are removed, and pools over their allowance lose their oldest games. This is how a custom type
   * that has fallen out of the last three, or a game that is no longer a favourite, stops costing
   * space.
   */
  fun retain(
    game: String,
    wanted: Map<String, Int>,
  ) {
    synchronized(lock) {
      File(dir, directoryName(game)).listFiles()?.forEach { keyDir ->
        val limit = wanted[keyDir.name]
        if (limit == null) {
          keyDir.deleteRecursively()
        } else {
          games(keyDir).dropLast(limit).forEach { it.delete() }
        }
      }
    }
  }

  private companion object {
    const val SUFFIX = ".sav"
    const val KEY_FILE = "key.json"
    val UNSAFE = Regex("[^A-Za-z0-9._-]")
  }
}
