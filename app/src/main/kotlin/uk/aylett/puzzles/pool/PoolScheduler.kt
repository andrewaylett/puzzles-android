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
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/** Which of the scheduler's requests a [PoolJob] came from. */
enum class PoolJobKind {
  /** The regular pass, whether or not anything has asked for one. */
  PERIODIC,

  /** A pass asked for because something changed or a game was missed. */
  FILL,

  /** One replacement game, made on battery after a pool was emptied. */
  TOP_UP,
}

/** What WorkManager currently says about one request, reduced to what a person would want to read. */
data class PoolJob(
  val kind: PoolJobKind,
  val state: WorkInfo.State,
  /** Null unless it is waiting for a time rather than for its constraints. */
  val nextRunMillis: Long?,
  val attempts: Int,
  /** Why the system last stopped it, if it did. */
  val stopReason: String?,
  /** What a running pass is making and how far it has got, from [PoolWorker]'s progress reports. */
  val progress: PoolProgress?,
)

data class PoolProgress(
  val game: String,
  val have: Int,
  val want: Int,
)

/**
 * Asks the system to run [PoolWorker] when it suits the device, which is what lets games be made
 * overnight with the app closed.
 *
 * Filling is constrained to charging: it can run for a long time and is pure battery cost. Topping
 * up a single game after the last one was used is not, as its whole point is to happen on battery.
 * Every request is unique by name, so a flurry of changes or a restart does not queue a pile of
 * identical passes; a pass already running is left alone, as it reads current state as it goes.
 */
class PoolScheduler(
  context: Context,
) {
  private val work = WorkManager.getInstance(context)

  /**
   * A fill every so often, as well as whenever something asks for one, because the pools only
   * ever shrink otherwise and the app may not have been opened to ask. Cheap when nothing is
   * missing: it is a few seconds to instantiate the engine and list the presets.
   */
  fun schedulePeriodicFill() {
    work.enqueueUniquePeriodicWork(
      PERIODIC_NAME,
      ExistingPeriodicWorkPolicy.KEEP,
      PeriodicWorkRequestBuilder<PoolWorker>(PERIOD_HOURS, TimeUnit.HOURS)
        .setConstraints(charging)
        .setInputData(Data.Builder().putString(PoolWorker.MODE, PoolWorker.FILL).build())
        .build(),
    )
  }

  /**
   * What the system is doing about each kind of request, live.
   *
   * The periodic and fill requests are found by their unique names and the top-ups, which have one
   * name per key, by a tag. The periodic one cannot be tagged retroactively: it is enqueued with
   * `KEEP`, so a device that already has it scheduled never sees a changed request.
   */
  fun jobs(): Flow<List<PoolJob>> =
    combine(
      work.getWorkInfosForUniqueWorkFlow(PERIODIC_NAME),
      work.getWorkInfosForUniqueWorkFlow(FILL_NAME),
      work.getWorkInfosByTagFlow(TOP_UP_TAG),
    ) { periodic, fill, topUps ->
      periodic.map { it.toJob(PoolJobKind.PERIODIC) } +
        fill.map { it.toJob(PoolJobKind.FILL) } +
        topUps.map { it.toJob(PoolJobKind.TOP_UP) }
    }

  private fun WorkInfo.toJob(kind: PoolJobKind) =
    PoolJob(
      kind = kind,
      state = state,
      nextRunMillis = nextScheduleTimeMillis.takeIf { it != Long.MAX_VALUE },
      attempts = runAttemptCount,
      stopReason = stopReasonText(stopReason),
      progress =
        progress.getString(PoolWorker.PROGRESS_GAME)?.let {
          PoolProgress(
            it,
            progress.getInt(PoolWorker.PROGRESS_HAVE, 0),
            progress.getInt(PoolWorker.PROGRESS_WANT, 0),
          )
        },
    )

  private fun stopReasonText(reason: Int): String? =
    when (reason) {
      WorkInfo.STOP_REASON_CONSTRAINT_CHARGING -> "stopped when charging ended"
      WorkInfo.STOP_REASON_TIMEOUT -> "stopped for running too long"
      WorkInfo.STOP_REASON_PREEMPT -> "stopped to make way for other work"
      WorkInfo.STOP_REASON_DEVICE_STATE -> "stopped by the device's state"
      WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "cancelled by the app"
      WorkInfo.STOP_REASON_NOT_STOPPED, WorkInfo.STOP_REASON_UNKNOWN -> null
      else -> "stopped (reason $reason)"
    }

  /** Top every pool up as soon as the device is charging. */
  fun requestFill() {
    work.enqueueUniqueWork(
      FILL_NAME,
      ExistingWorkPolicy.KEEP,
      OneTimeWorkRequestBuilder<PoolWorker>()
        .setConstraints(charging)
        .setInputData(Data.Builder().putString(PoolWorker.MODE, PoolWorker.FILL).build())
        .build(),
    )
  }

  /** Make one game for [key] now, whatever the charging state; see [PoolWorker]. */
  fun requestTopUp(key: PoolKey) {
    work.enqueueUniqueWork(
      "$TOP_UP_PREFIX${key.id}",
      ExistingWorkPolicy.KEEP,
      OneTimeWorkRequestBuilder<PoolWorker>()
        .setInputData(
          Data
            .Builder()
            .putString(PoolWorker.MODE, PoolWorker.TOP_UP)
            .putString(PoolWorker.KEY, Json.encodeToString(PoolKey.serializer(), key))
            .build(),
        ).addTag(TOP_UP_TAG)
        .build(),
    )
  }

  private companion object {
    const val PERIODIC_NAME = "pool-periodic"
    const val FILL_NAME = "pool-fill"
    const val TOP_UP_PREFIX = "pool-top-up-"
    const val TOP_UP_TAG = "pool-top-up"
    const val PERIOD_HOURS = 12L
    val charging: Constraints = Constraints.Builder().setRequiresCharging(true).build()
  }
}
