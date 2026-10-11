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

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import uk.aylett.puzzles.PuzzlesApplication
import uk.aylett.puzzles.data.AppPreferences
import uk.aylett.puzzles.data.CustomPreset
import uk.aylett.puzzles.engine.ConfigSection
import uk.aylett.puzzles.engine.EngineFailure
import uk.aylett.puzzles.engine.GameInfo
import uk.aylett.puzzles.engine.PresetEntry
import uk.aylett.puzzles.engine.PuzzleEngine
import uk.aylett.puzzles.engine.PuzzleSession

private const val LOG_TAG = "PoolWorker"

/**
 * Generates the games [PuzzlePool] holds.
 *
 * It runs on an engine of its own rather than the one the player is using. The engine thread runs
 * one thing at a time and a single generation can take many seconds, so doing this on the shared
 * one would queue the player's next tap behind it. The cost is a second WASM instance for the
 * length of a pass, which is closed afterwards; it deliberately has no [PuzzleEngine.newGameSource],
 * or it would be taking from the pool it is filling.
 *
 * A failure inside the engine (see [EngineFailure]) abandons that game for this pass and carries on
 * with the others on a fresh engine. It is only logged: unlike a player's save there is nothing to
 * preserve, and the next pass tries again.
 */
class PoolWorker(
  context: Context,
  params: WorkerParameters,
) : CoroutineWorker(context, params) {
  private val app = applicationContext as PuzzlesApplication

  override suspend fun doWork(): Result = passes.withLock { run() }

  private suspend fun run(): Result {
    val pool = app.puzzlePool
    when (inputData.getString(MODE)) {
      TOP_UP -> {
        // Checked again here: the work may have waited, and power saving may have begun since.
        if (AndroidPowerState(app).isPowerSaving) return Result.success()
        val key =
          Json.decodeFromString(
            PoolKey.serializer(),
            inputData.getString(KEY) ?: return Result.failure(),
          )
        withEngine { engine -> topUp(engine, pool, key) }
      }

      else -> {
        withEngine { engine -> fill(engine, pool) }
      }
    }
    return Result.success()
  }

  /** Runs [block] on a private engine, replacing it if [block] reports that it broke. */
  private suspend fun withEngine(block: suspend (EngineSupplier) -> Unit) {
    val supplier = EngineSupplier(app)
    try {
      block(supplier)
    } finally {
      supplier.close()
    }
  }

  private class EngineSupplier(
    private val app: PuzzlesApplication,
  ) {
    private var engine: PuzzleEngine? = null

    suspend fun get(): PuzzleEngine = engine ?: PuzzleEngine.create(app).also { engine = it }

    /** After an [EngineFailure] the heap is suspect: drop the engine and build a new one on demand. */
    fun discard() {
      runCatching { engine?.close() }
      engine = null
    }

    fun close() = discard()
  }

  private suspend fun topUp(
    engines: EngineSupplier,
    pool: PuzzlePool,
    key: PoolKey,
  ) {
    val game = engines.get().games.firstOrNull { it.name == key.game } ?: return
    val session = engines.get().openGame(game.index)
    try {
      val target =
        Target(key) { s ->
          val current = s.config(ConfigSection.SETTINGS).fields
          s.selectSettings(CustomPreset("", key.settings).applyTo(current))
        }
      target.select(session)
      // Labels can change between versions; a key that no longer lands on itself would put the game
      // where nothing will ever look for it.
      if (PoolKey.of(game.name, session.config(ConfigSection.SETTINGS).fields) != key) return
      generate(session, pool, target, pool.count(key) + 1)
    } finally {
      closeQuietly(session)
    }
  }

  private suspend fun fill(
    engines: EngineSupplier,
    pool: PuzzlePool,
  ) {
    val favourites = AppPreferences(app).favourites.first()
    // Favourites first: they have the bigger allowance and are the ones most likely to be wanted
    // if this pass is cut short.
    val games = engines.get().games.sortedBy { it.name !in favourites }
    for (game in games) {
      currentCoroutineContext().ensureActive()
      val custom = app.customPresetStore.presets(game.name).first()
      val target = PoolPolicy.target(game.name in favourites)
      try {
        fillGame(engines.get(), pool, game, custom, target)
      } catch (e: EngineFailure) {
        Log.w(LOG_TAG, "Giving up on ${game.name} for this pass", e)
        engines.discard()
      }
    }
  }

  private suspend fun fillGame(
    engine: PuzzleEngine,
    pool: PuzzlePool,
    game: GameInfo,
    custom: List<CustomPreset>,
    perKind: Int,
  ) {
    if (!game.canConfigure) return
    val session = engine.openGame(game.index)
    try {
      val targets = targets(session, game, custom)
      for (target in targets) {
        generate(session, pool, target, perKind)
      }
      // Only once the whole game has been enumerated: a game that failed part way would otherwise
      // look as if its other kinds were no longer wanted, and have their pools deleted.
      pool.retain(game.name, targets.associate { it.key.id to perKind })
    } finally {
      closeQuietly(session)
    }
  }

  /** How to put a session's parameters on one kind of game. */
  private class Target(
    val key: PoolKey,
    val select: suspend (PuzzleSession) -> Unit,
  )

  /**
   * Every kind of game worth keeping: the game's default, each of its presets (in every submenu),
   * and the player's stored custom types, each de-duplicated by [PoolKey] because a custom type can
   * coincide with a preset's parameters.
   */
  private suspend fun targets(
    session: PuzzleSession,
    game: GameInfo,
    custom: List<CustomPreset>,
  ): List<Target> {
    val found = LinkedHashMap<String, Target>()

    suspend fun add(select: suspend (PuzzleSession) -> Unit) {
      select(session)
      val key = PoolKey.of(game.name, session.config(ConfigSection.SETTINGS).fields)
      found.putIfAbsent(key.id, Target(key, select))
    }

    suspend fun walk(entries: List<PresetEntry>) {
      for (entry in entries) {
        when (entry) {
          is PresetEntry.Submenu -> walk(entry.entries)
          is PresetEntry.Preset -> add { it.selectPresetParams(entry.id) }
        }
      }
    }
    // What a new session starts with, which is what a first game is generated for.
    val defaults = session.config(ConfigSection.SETTINGS).fields
    add { it.selectSettings(defaults) }
    walk(session.presets())
    for (preset in custom) {
      add { s -> s.selectSettings(preset.applyTo(s.config(ConfigSection.SETTINGS).fields)) }
    }
    return found.values.toList()
  }

  private suspend fun generate(
    session: PuzzleSession,
    pool: PuzzlePool,
    target: Target,
    upTo: Int,
  ) {
    if (pool.count(target.key) >= upTo) return
    target.select(session)
    // The count is read afresh each time rather than worked out once: games are also being taken
    // meanwhile, and a pass that overlapped another would otherwise make twice as many.
    while (pool.count(target.key) < upTo) {
      // One game per engine task, and the check between them, so that a pass the system stops
      // (the charger was unplugged) ends after at most the game in hand.
      currentCoroutineContext().ensureActive()
      pool.put(target.key, session.generateSave())
      // Only for the status screen. Names the game, as that is what a person would recognise.
      setProgress(
        workDataOf(
          PROGRESS_GAME to target.key.game,
          PROGRESS_HAVE to pool.count(target.key),
          PROGRESS_WANT to upTo,
        ),
      )
    }
  }

  private fun closeQuietly(session: PuzzleSession) {
    runCatching { session.close() }
      .onFailure { Log.w(LOG_TAG, "Closing a session failed", it) }
  }

  companion object {
    // The periodic and on-demand fills have different names, so WorkManager happily runs them at
    // once. Each needs an engine of its own and they would only duplicate each other's work.
    private val passes = Mutex()

    const val MODE = "mode"
    const val KEY = "key"
    const val FILL = "fill"
    const val TOP_UP = "top-up"
    const val PROGRESS_GAME = "game"
    const val PROGRESS_HAVE = "have"
    const val PROGRESS_WANT = "want"
  }
}
