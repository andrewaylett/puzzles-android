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
package uk.aylett.puzzles.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Instantiates the real module on the JVM. The translated bytecode runs as well here as under ART;
 * only drawing needs a device, and with no [DrawingSink] bound those callbacks are no-ops. This
 * catches the failures that matter most to a change in how the module is wired up: a missing or
 * mis-typed import, an export that has gone, or start-up run before its memory is available.
 */
class WasmInstantiationTest {
  private val hooks = EngineHooks()
  private val midend = PuzzleEngine.instantiate(hooks)

  @Test
  fun `lists every puzzle`() {
    assertEquals(40, midend.gameCount())
    val names = (0 until midend.gameCount()).map { midend.gameName(midend.gameAt(it)) }
    assertEquals(names.size, names.distinct().size)
    assertTrue("Net" in names)
  }

  @Test
  fun `generates a game without a drawing surface`() {
    val net = (0 until midend.gameCount()).first { midend.gameName(midend.gameAt(it)) == "Net" }
    val me = midend.midendNew(net)
    try {
      midend.newGame(me)
      assertFalse(midend.getGameId(me).isEmpty())
      assertEquals(0, midend.status(me))
    } finally {
      midend.midendFree(me)
    }
  }
}
