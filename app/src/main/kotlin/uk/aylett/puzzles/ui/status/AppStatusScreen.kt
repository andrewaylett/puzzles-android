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
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import uk.aylett.puzzles.EngineEnd
import uk.aylett.puzzles.EngineStatus
import uk.aylett.puzzles.R
import uk.aylett.puzzles.pool.PoolJob
import uk.aylett.puzzles.pool.PoolJobKind
import uk.aylett.puzzles.ui.list.iconFor

/**
 * What the app is holding and doing, for working out why a game started slowly or what a fill is up
 * to. Read-only by design: see [AppStatusViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppStatusScreen(
  onBack: () -> Unit,
  viewModel: AppStatusViewModel =
    viewModel(
      factory = AppStatusViewModel.factory(LocalContext.current.applicationContext as Application),
    ),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.action_app_status)) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(
              Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.action_back),
            )
          }
        },
      )
    },
  ) { padding ->
    if (!state.loaded) {
      Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
      return@Scaffold
    }
    val size = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }
    val ago = { millis: Long ->
      DateUtils
        .getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        .toString()
    }

    LazyColumn(
      modifier = Modifier.padding(padding).fillMaxSize(),
      contentPadding = PaddingValues(bottom = 16.dp),
    ) {
      item { Header(stringResource(R.string.app_status_section_engine)) }
      item { Line(engineText(state.engine)) }

      item { Header(stringResource(R.string.app_status_section_generation)) }
      item {
        val power =
          when {
            state.charging -> R.string.app_status_power_charging
            state.powerSaving -> R.string.app_status_power_saving
            else -> R.string.app_status_power_battery
          }
        Line(stringResource(R.string.app_status_power, stringResource(power)))
      }
      if (state.jobs.isEmpty()) {
        item { Line(stringResource(R.string.app_status_no_jobs)) }
      }
      items(state.jobs) { job -> Line(jobText(job, ago)) }

      item { Header(stringResource(R.string.app_status_section_storage)) }
      item {
        Line(stringResource(R.string.app_status_pool, state.poolGames, size(state.poolBytes)))
      }
      if (state.poolGamesOfUnknownKind > 0) {
        item {
          Line(
            stringResource(R.string.app_status_pool_unknown, state.poolGamesOfUnknownKind),
          )
        }
      }
      item { Line(stringResource(R.string.app_status_saves, size(state.saveBytes))) }
      item {
        val recovery = state.recovery
        Line(
          stringResource(
            R.string.app_status_recovery,
            recovery.reports,
            recovery.quarantinedSaves,
          ),
        )
        recovery.newestReportMillis?.let {
          Line(stringResource(R.string.app_status_recovery_newest, ago(it)))
        }
      }
      items(state.recovery.restoreMarkers) { game ->
        Line(
          stringResource(R.string.app_status_restore_markers, game),
          MaterialTheme.colorScheme.error,
        )
      }

      item { Header(stringResource(R.string.app_status_section_puzzles)) }
      if (state.games.isEmpty()) {
        item { Line(stringResource(R.string.app_status_puzzles_empty)) }
      }
      items(state.games, key = GameStatus::name) { game ->
        val icon = iconFor(game.name)
        ListItem(
          headlineContent = { Text(game.name) },
          supportingContent = { Text(gameDetails(game, size, ago)) },
          leadingContent = {
            if (icon != null) {
              Image(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
              )
            } else {
              Icon(Icons.Filled.Extension, contentDescription = null)
            }
          },
        )
      }
      if (state.totalGames > state.games.size) {
        item {
          Line(
            stringResource(R.string.app_status_puzzles_rest, state.totalGames - state.games.size),
          )
        }
      }
    }
  }
}

@Composable
private fun Header(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.titleMedium,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
  )
}

@Composable
private fun Line(
  text: String,
  colour: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
  Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = colour)
  }
}

@Composable
private fun engineText(engine: EngineStatus): String =
  when (engine) {
    is EngineStatus.NotLoaded -> {
      stringResource(
        when (engine.after) {
          null -> R.string.app_status_engine_not_loaded
          EngineEnd.TRIMMED -> R.string.app_status_engine_not_loaded_trimmed
          EngineEnd.BROKE -> R.string.app_status_engine_not_loaded_broke
        },
      )
    }

    EngineStatus.Loading -> {
      stringResource(R.string.app_status_engine_loading)
    }

    is EngineStatus.Ready -> {
      stringResource(R.string.app_status_engine_ready, engine.games, engine.loadMillis)
    }

    is EngineStatus.Failed -> {
      stringResource(R.string.app_status_engine_failed, engine.message)
    }
  }

@Composable
private fun jobText(
  job: PoolJob,
  ago: (Long) -> String,
): String {
  val name =
    stringResource(
      when (job.kind) {
        PoolJobKind.PERIODIC -> R.string.app_status_job_periodic
        PoolJobKind.FILL -> R.string.app_status_job_fill
        PoolJobKind.TOP_UP -> R.string.app_status_job_top_up
      },
    )
  val progress = job.progress
  val state =
    when (job.state) {
      WorkInfo.State.ENQUEUED -> {
        stringResource(R.string.app_status_job_enqueued)
      }

      WorkInfo.State.RUNNING -> {
        if (progress == null) {
          stringResource(R.string.app_status_job_running)
        } else {
          stringResource(
            R.string.app_status_job_running_progress,
            progress.game,
            progress.have,
            progress.want,
          )
        }
      }

      WorkInfo.State.SUCCEEDED -> {
        stringResource(R.string.app_status_job_succeeded)
      }

      WorkInfo.State.FAILED -> {
        stringResource(R.string.app_status_job_failed)
      }

      WorkInfo.State.BLOCKED -> {
        stringResource(R.string.app_status_job_blocked)
      }

      WorkInfo.State.CANCELLED -> {
        stringResource(R.string.app_status_job_cancelled)
      }
    }
  val extras =
    listOfNotNull(
      job.nextRunMillis?.takeIf { job.state == WorkInfo.State.ENQUEUED }?.let {
        stringResource(R.string.app_status_job_next, ago(it))
      },
      job.attempts.takeIf { it > 0 }?.let { stringResource(R.string.app_status_job_attempts, it) },
      job.stopReason,
    )
  return (listOf("$name: $state") + extras).joinToString(", ")
}

@Composable
private fun gameDetails(
  game: GameStatus,
  size: (Long) -> String,
  ago: (Long) -> String,
): String =
  listOfNotNull(
    if (game.favourite) stringResource(R.string.app_status_game_favourite) else null,
    game.save?.let {
      stringResource(R.string.app_status_game_save, size(it.bytes), ago(it.lastModifiedMillis))
    },
    if (game.pooledGames > 0) {
      stringResource(R.string.app_status_game_pool, game.pooledGames, game.pooledKinds)
    } else {
      null
    },
    if (game.customTypes.isNotEmpty()) {
      stringResource(R.string.app_status_game_custom, game.customTypes.joinToString("; "))
    } else {
      null
    },
  ).joinToString("\n")
