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

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import uk.aylett.puzzles.engine.ConfigField
import java.io.File

class PuzzlePoolTest {
  @TempDir
  lateinit var dir: File

  private val emptied = mutableListOf<PoolKey>()
  private val missed = mutableListOf<PoolKey>()
  private val pool by lazy { PuzzlePool(dir, { emptied += it }, { missed += it }) }

  private fun size(value: String) = listOf(ConfigField.Text("Width", "", value))

  private val key get() = PoolKey.of("Solo", size("9"))

  @Test
  fun `hands out the oldest game first and only once`() {
    pool.put(key, byteArrayOf(1))
    Thread.sleep(2)
    pool.put(key, byteArrayOf(2))

    assertArrayEquals(byteArrayOf(1), pool.take("Solo", size("9")))
    assertArrayEquals(byteArrayOf(2), pool.take("Solo", size("9")))
    assertNull(pool.take("Solo", size("9")))
    assertEquals(0, pool.count(key))
  }

  @Test
  fun `games are kept apart by settings and by puzzle`() {
    pool.put(key, byteArrayOf(1))

    assertNull(pool.take("Solo", size("6")))
    assertNull(pool.take("Keen", size("9")))
    assertEquals(1, pool.count(key))
  }

  @Test
  fun `says so when the last game is taken, and not before`() {
    pool.put(key, byteArrayOf(1))
    pool.put(key, byteArrayOf(2))

    pool.take("Solo", size("9"))
    assertEquals(emptyList<PoolKey>(), emptied)

    pool.take("Solo", size("9"))
    assertEquals(listOf(key), emptied)

    // Nothing was used, so nothing to replace.
    pool.take("Solo", size("9"))
    assertEquals(1, emptied.size)
  }

  @Test
  fun `a key does not depend on how the settings were reached`() {
    val choice = ConfigField.Choice("Difficulty", "", listOf("Easy", "Hard"), 1)

    assertEquals(
      PoolKey.of("Solo", listOf(choice)),
      PoolKey.of("Solo", listOf(choice.copy(keyword = "k"))),
    )
    assertNotEquals(
      PoolKey.of("Solo", listOf(choice)).id,
      PoolKey.of("Solo", listOf(choice.copy(selected = 0))).id,
    )
  }

  @Test
  fun `retain drops kinds no longer wanted and trims the oldest from the rest`() {
    val other = PoolKey.of("Solo", size("6"))
    repeat(3) {
      pool.put(key, byteArrayOf(it.toByte()))
      Thread.sleep(2)
    }
    pool.put(other, byteArrayOf(9))

    pool.retain("Solo", mapOf(key.id to 2))

    assertEquals(0, pool.count(other))
    assertArrayEquals(byteArrayOf(1), pool.take("Solo", size("9")))
    assertArrayEquals(byteArrayOf(2), pool.take("Solo", size("9")))
  }

  @Test
  fun `an unfinished write is never handed out`() {
    pool.put(key, byteArrayOf(1))
    File(dir, "Solo/${key.id}/partial.tmp").writeBytes(byteArrayOf(7))

    assertEquals(1, pool.count(key))
  }

  @Test
  fun `a take that finds nothing asks for a fill, and does not claim to have emptied anything`() {
    assertNull(pool.take("Solo", size("9")))

    assertEquals(listOf(key), missed)
    assertEquals(emptyList<PoolKey>(), emptied)
  }

  @Test
  fun `discard removes one kind and leaves the others`() {
    val other = PoolKey.of("Solo", size("6"))
    pool.put(key, byteArrayOf(1))
    pool.put(other, byteArrayOf(2))

    pool.discard(key)

    assertEquals(0, pool.count(key))
    assertEquals(1, pool.count(other))
  }

  @Test
  fun `the summary says what each pool is for, and does not count its key as a game`() {
    pool.put(key, byteArrayOf(1, 2, 3))
    pool.put(key, byteArrayOf(4))
    pool.put(PoolKey.of("Train Tracks", size("6")), byteArrayOf(5))

    val summary = pool.summary()

    assertEquals(setOf("Solo", "Train_Tracks"), summary.keys)
    assertEquals(listOf(PoolKindSummary(key, 2, 4)), summary.getValue("Solo"))
    assertEquals("Train_Tracks", pool.directoryName("Train Tracks"))
    // Handing games out is unaffected by the key beside them.
    assertEquals(2, pool.count(key))
    assertArrayEquals(byteArrayOf(1, 2, 3), pool.take("Solo", size("9")))
  }

  @Test
  fun `a pool from before keys were recorded is still counted, as of unknown kind`() {
    pool.put(key, byteArrayOf(1))
    File(dir, "Solo/${key.id}/key.json").delete()

    assertEquals(listOf(PoolKindSummary(null, 1, 1)), pool.summary().getValue("Solo"))
  }

  @Test
  fun `an empty pool summarises as nothing`() {
    assertEquals(emptyMap<String, List<PoolKindSummary>>(), pool.summary())
  }
}
