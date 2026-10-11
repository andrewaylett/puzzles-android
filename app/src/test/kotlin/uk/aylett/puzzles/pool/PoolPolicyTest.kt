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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PoolPolicyTest {
  private fun power(
    charging: Boolean = false,
    saving: Boolean = false,
  ) = object : PowerState {
    override val isCharging = charging
    override val isPowerSaving = saving
  }

  @Test
  fun `favourites keep twenty and everything else ten`() {
    assertEquals(20, PoolPolicy.target(favourite = true))
    assertEquals(10, PoolPolicy.target(favourite = false))
  }

  @Test
  fun `on battery the used game is replaced`() {
    assertEquals(Refill.TOP_UP_ONE, PoolPolicy.refillAfterEmptied(power()))
  }

  @Test
  fun `power saving mode stops the replacement`() {
    assertEquals(Refill.NONE, PoolPolicy.refillAfterEmptied(power(saving = true)))
  }

  @Test
  fun `on charge everything is filled, whether or not power saving is reported`() {
    assertEquals(Refill.FILL_ALL, PoolPolicy.refillAfterEmptied(power(charging = true)))
    assertEquals(
      Refill.FILL_ALL,
      PoolPolicy.refillAfterEmptied(power(charging = true, saving = true)),
    )
  }
}
