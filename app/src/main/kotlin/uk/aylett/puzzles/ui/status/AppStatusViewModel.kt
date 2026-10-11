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
package uk.aylett.puzzles.ui.status

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import uk.aylett.puzzles.EngineStatus
import uk.aylett.puzzles.PuzzlesApplication
import uk.aylett.puzzles.data.AppPreferences
import uk.aylett.puzzles.data.CustomPresetStore
import uk.aylett.puzzles.data.CustomPresets
import uk.aylett.puzzles.data.RecoveryStore
import uk.aylett.puzzles.data.RecoverySummary
import uk.aylett.puzzles.data.SavedGameInfo
import uk.aylett.puzzles.data.SavedGameStore
import uk.aylett.puzzles.pool.AndroidPowerState
import uk.aylett.puzzles.pool.PoolJob
import uk.aylett.puzzles.pool.PoolKindSummary
import uk.aylett.puzzles.pool.PowerState
import uk.aylett.puzzles.pool.PuzzlePool
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Everything the app holds for one puzzle. */
data class GameStatus(
  val name: String,
  val favourite: Boolean,
  val save: SavedGameInfo?,
  /** Pre-generated games ready to start, and the number of kinds of game they are spread over. */
  val pooledGames: Int,
  val pooledKinds: Int,
  /** The titles of the custom types remembered for it. */
  val customTypes: List<String>,
) {
  val hasAnything: Boolean
    get() = favourite || save != null || pooledGames > 0 || customTypes.isNotEmpty()
}

data class AppStatusUiState(
  val loaded: Boolean = false,
  val engine: EngineStatus = EngineStatus.NotLoaded(),
  /** Games with anything to report, by name. */
  val games: List<GameStatus> = emptyList(),
  /** How many puzzles there are in all, if the engine has ever said; zero if not. */
  val totalGames: Int = 0,
  val jobs: List<PoolJob> = emptyList(),
  val charging: Boolean = false,
  val powerSaving: Boolean = false,
  val poolGames: Int = 0,
  val poolBytes: Long = 0,
  /** Pre-generated games filed under no known kind, left by a version that did not record one. */
  val poolGamesOfUnknownKind: Int = 0,
  val saveBytes: Long = 0,
  val recovery: RecoverySummary = RecoverySummary(0, 0, null, emptyList()),
)

/** What [buildAppStatus] turns into a [AppStatusUiState]; separate so that it can be tested alone. */
data class AppStatusInputs(
  val gameNames: List<String>,
  val favourites: Set<String>,
  val custom: CustomPresets,
  val saves: Map<String, SavedGameInfo>,
  /** By the name the pool files a puzzle under, as [PuzzlePool.summary] reports it. */
  val pools: Map<String, List<PoolKindSummary>>,
  val directoryName: (String) -> String,
  val engine: EngineStatus,
  val jobs: List<PoolJob>,
  val charging: Boolean,
  val powerSaving: Boolean,
  val recovery: RecoverySummary,
)

/**
 * Puts what the app knows about itself into one picture.
 *
 * The puzzle names come from [AppStatusInputs.gameNames], which is empty until an engine has been
 * built in this process. Rather than say nothing then, the names any of the stores mention are used
 * too, so a game that has a save or a pool is never missing from the screen for want of a list.
 */
fun buildAppStatus(input: AppStatusInputs): AppStatusUiState {
  val keyed =
    input.pools.values
      .flatten()
      .mapNotNull { it.key?.game }
  val names =
    (input.gameNames + input.favourites + input.custom.byGame.keys + input.saves.keys + keyed)
      .toSortedSet()
  val games =
    names
      .map { name ->
        val kinds = input.pools[input.directoryName(name)].orEmpty()
        GameStatus(
          name = name,
          favourite = name in input.favourites,
          save = input.saves[name],
          pooledGames = kinds.sumOf { it.games },
          pooledKinds = kinds.count { it.games > 0 },
          customTypes =
            input.custom.byGame[name]
              .orEmpty()
              .map { it.title },
        )
      }.filter { it.hasAnything }
  val allKinds = input.pools.values.flatten()
  return AppStatusUiState(
    loaded = true,
    engine = input.engine,
    games = games,
    totalGames = input.gameNames.size,
    jobs = input.jobs,
    charging = input.charging,
    powerSaving = input.powerSaving,
    poolGames = allKinds.sumOf { it.games },
    poolBytes = allKinds.sumOf { it.bytes },
    poolGamesOfUnknownKind = allKinds.filter { it.key == null }.sumOf { it.games },
    saveBytes = input.saves.values.sumOf { it.bytes },
    recovery = input.recovery,
  )
}

/**
 * Backs the "App status" screen, which only ever *looks*: nothing here may start the engine, build
 * a pool or schedule work, or opening the screen would change what it reports.
 *
 * Counts on disk cannot be observed, so they are re-read every [pollInterval] while the screen is
 * showing, which also keeps a running fill's numbers moving. What has a flow of its own (the
 * engine, the jobs, favourites, custom types) is combined in as it changes.
 */
class AppStatusViewModel(
  private val prefs: AppPreferences,
  private val customTypes: CustomPresetStore,
  private val saves: SavedGameStore,
  private val pool: PuzzlePool,
  private val recovery: RecoveryStore,
  private val power: PowerState,
  private val engineStatus: Flow<EngineStatus>,
  private val jobs: Flow<List<PoolJob>>,
  private val gameNames: () -> List<String>,
  pollInterval: Duration = POLL_INTERVAL,
) : ViewModel() {
  private val ticks =
    flow {
      while (true) {
        emit(Unit)
        delay(pollInterval)
      }
    }

  val uiState: StateFlow<AppStatusUiState> =
    combine(prefs.favourites, customTypes.all, engineStatus, jobs, ticks) {
      favourites,
      custom,
      engine,
      jobs,
      _,
      ->
      withContext(Dispatchers.IO) {
        val names = gameNames()
        buildAppStatus(
          AppStatusInputs(
            gameNames = names,
            favourites = favourites,
            custom = custom,
            saves = saves.info(names + favourites + custom.byGame.keys),
            pools = pool.summary(),
            directoryName = pool::directoryName,
            engine = engine,
            jobs = jobs,
            charging = power.isCharging,
            powerSaving = power.isPowerSaving,
            recovery = recovery.summary(),
          ),
        )
      }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppStatusUiState())

  companion object {
    private val POLL_INTERVAL = 3_000L.milliseconds

    fun factory(application: Application): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(
          modelClass: Class<T>,
          extras: CreationExtras,
        ): T {
          val app = application as PuzzlesApplication
          @Suppress("UNCHECKED_CAST")
          return AppStatusViewModel(
            prefs = AppPreferences(application),
            customTypes = app.customPresetStore,
            saves = SavedGameStore(application),
            pool = app.puzzlePool,
            recovery = RecoveryStore(application),
            power = AndroidPowerState(application),
            engineStatus = app.engineStatus,
            jobs = app.poolScheduler.jobs(),
            gameNames = { app.knownGameNames },
          ) as T
        }
      }
  }
}
