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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch

class EngineThreadTest {
  @Test
  fun `is open until closed`() {
    val thread = EngineThread()
    assertFalse(thread.isClosed)
    thread.close()
    assertTrue(thread.isClosed)
  }

  @Test
  fun `work queued before close still completes`() {
    val thread = EngineThread()
    val gate = CountDownLatch(1)
    val result = CompletableDeferred<String>()
    // Queued behind a blocked task, as a save is behind a generation, then the thread is closed
    // before either has run. Persisting from onPause relies on that work still being honoured.
    CoroutineScope(thread.dispatcher).launch {
      gate.await()
      result.complete("saved")
    }
    thread.close()
    gate.countDown()
    assertEquals("saved", runBlocking { withTimeout(5_000) { result.await() } })
  }
}
