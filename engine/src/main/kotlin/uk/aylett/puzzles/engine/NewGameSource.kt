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

/**
 * Somewhere a session can get a ready-made game from rather than waiting for the generator, which
 * can take many seconds on a phone.
 *
 * Called on the engine thread in the middle of starting a game, so an implementation may do a small
 * local read but must not wait on anything slow.
 */
fun interface NewGameSource {
  /**
   * Removes and returns a game for [gameName] generated with exactly [settings] (the settings
   * section of its configuration, as the mid-end would show it), as written by
   * [PuzzleSession.serialise]; or null if there is none.
   *
   * Removal is part of the contract: a game is handed out once, and a save that goes on to fail must
   * not be offered a second time.
   */
  fun take(
    gameName: String,
    settings: List<ConfigField>,
  ): ByteArray?
}
