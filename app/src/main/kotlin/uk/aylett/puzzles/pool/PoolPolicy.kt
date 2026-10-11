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

/** What the device's power situation means for background generation. */
interface PowerState {
  val isCharging: Boolean
  val isPowerSaving: Boolean
}

/** What to do about a pool that has just handed out its last game. */
enum class Refill {
  /** Nothing: the device is in power saving mode, so generating would only cost battery. */
  NONE,

  /** On charge, so top every pool up, this one included; the charging constraint is already met. */
  FILL_ALL,

  /** On battery: replace just the game that was used, so the pool never stays empty. */
  TOP_UP_ONE,
}

/**
 * How many games to keep ready, and when it is worth spending battery making more.
 *
 * Generation is the slow part of starting a game (several seconds on a phone for the harder
 * presets), but it is also the part that burns battery, so it is done freely while the device is
 * charging and otherwise only to replace what was just used.
 */
object PoolPolicy {
  /** Games kept per type for favourites, which the player is most likely to start. */
  const val FAVOURITE_TARGET = 20

  /** Games kept per type for everything else. */
  const val OTHER_TARGET = 10

  fun target(favourite: Boolean) = if (favourite) FAVOURITE_TARGET else OTHER_TARGET

  /**
   * Decided when a game is taken and the pool for its type is left empty, using the power state
   * *then* rather than when the app started: the player may well have unplugged since.
   */
  fun refillAfterEmptied(power: PowerState): Refill =
    when {
      power.isCharging -> Refill.FILL_ALL
      power.isPowerSaving -> Refill.NONE
      else -> Refill.TOP_UP_ONE
    }
}
