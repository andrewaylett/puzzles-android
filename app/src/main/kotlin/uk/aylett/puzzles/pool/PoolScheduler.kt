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
import androidx.work.WorkManager
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

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
        ).build(),
    )
  }

  private companion object {
    const val PERIODIC_NAME = "pool-periodic"
    const val FILL_NAME = "pool-fill"
    const val TOP_UP_PREFIX = "pool-top-up-"
    const val PERIOD_HOURS = 12L
    val charging: Constraints = Constraints.Builder().setRequiresCharging(true).build()
  }
}
