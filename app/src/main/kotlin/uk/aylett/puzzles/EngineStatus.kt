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
package uk.aylett.puzzles

/** Why the shared engine is no longer there. */
enum class EngineEnd {
  /** The app was hidden and the system asked for memory back; see `PuzzlesApplication.onTrimMemory`. */
  TRIMMED,

  /** It trapped, so its heap could not be trusted; see `uk.aylett.puzzles.engine.EngineFailure`. */
  BROKE,
}

/**
 * Where the shared engine is in its life, published so the status screen can *look* without
 * touching it: asking for the engine builds it if it is gone, which would change the very thing
 * being reported.
 */
sealed interface EngineStatus {
  /** [after] is null until one has been built in this process. */
  data class NotLoaded(
    val after: EngineEnd? = null,
  ) : EngineStatus

  data object Loading : EngineStatus

  data class Ready(
    val games: Int,
    val loadMillis: Long,
    val sinceMillis: Long,
  ) : EngineStatus

  /** Building it threw; the next request tries again. */
  data class Failed(
    val message: String,
  ) : EngineStatus
}
