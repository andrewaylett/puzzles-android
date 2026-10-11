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
@file:OptIn(FlowPreview::class)

package uk.aylett.puzzles.ui.game

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uk.aylett.puzzles.data.AppPreferences
import uk.aylett.puzzles.data.SavedGameStore
import uk.aylett.puzzles.engine.EngineFailure
import uk.aylett.puzzles.engine.PuzzleSession
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private const val LOG_TAG = "GamePersister"

/** How long the game must sit unchanged before it is written down. */
val AUTOSAVE_DEBOUNCE_INTERVAL = 300L.milliseconds

/**
 * Keeps a game's save on disk within [debounceInterval] of the player stopping, whatever the lifecycle
 * of the screen is doing.
 *
 * Saving only at lifecycle edges lost moves: swiping the app away from recents gives `onPause`, then
 * `onDestroy` a few milliseconds later, then SIGKILL, and `viewModelScope` is cancelled with the
 * save part way through. So saving is driven by [PuzzleSession.revision] instead, and runs in
 * [scope], which must outlive any screen (the application's), with the write itself shielded from
 * cancellation. Worst case on an instant kill is one debounce interval of moves.
 *
 * Nothing is saved until [attach]: the caller does that once any restore has settled, because a
 * session that has not yet restored holds no game, or a different one, and saving it would replace
 * the real save with that.
 */
class GamePersister(
  private val scope: CoroutineScope,
  private val saves: SavedGameStore,
  private val prefs: AppPreferences,
  /** An engine failure while saving; the owner decides what to do about the session. */
  private val onFailure: (PuzzleSession, EngineFailure) -> Unit,
  /** A save has been serialised, proving the game in the session sound enough to keep. */
  private val onSerialised: suspend () -> Unit,
  private val debounceInterval: Duration = AUTOSAVE_DEBOUNCE_INTERVAL,
) {
  private class Target(
    val session: PuzzleSession,
    val name: String,
  )

  // Main thread only, as is everything that calls us.
  private var target: Target? = null
  private var watcher: Job? = null

  // Every save that has not finished, so that [drain] can wait for them.
  private val inFlight = ConcurrentHashMap.newKeySet<Job>()

  // Hands out places in line, in the order saves were *requested*. The engine thread runs snapshots
  // in that order, but what runs after it need not, so a snapshot that has been overtaken is
  // dropped rather than written over a newer one.
  private val tickets = AtomicLong()
  private val writeLock = Mutex()
  private var lastWritten = 0L
  private var lastGame: ByteArray? = null
  private var lastPrefs: ByteArray? = null

  /**
   * Starts saving [session] as it changes. [savedGame] and [savedPrefs] are what is already on
   * disk for it, if known, so that restoring a game does not immediately write it straight back.
   */
  fun attach(
    session: PuzzleSession,
    name: String,
    savedGame: ByteArray?,
    savedPrefs: ByteArray?,
  ) {
    detach()
    target = Target(session, name)
    lastGame = savedGame
    lastPrefs = savedPrefs
    // Starts with the session's current revision, so a restore that happened before this was
    // attached is saved too, if it turns out to differ from what was on disk.
    watcher =
      scope.launch {
        session.revision.debounce(debounceInterval).collect { launchSave(session, name) }
      }
  }

  /**
   * Stops watching, for a session that is going away or has failed. A save already under way is
   * left to finish: the game it holds is the newest there is.
   */
  fun detach() {
    watcher?.cancel()
    watcher = null
    target = null
  }

  /**
   * Saves the current game now rather than waiting out the debounce, for when it may not get the
   * chance: the screen is pausing or being destroyed.
   *
   * The snapshot is requested of the engine thread before this returns, so work queued on that
   * thread afterwards, closing the session in particular, runs after it.
   */
  fun flush(): Job {
    val (session, name) = target?.let { it.session to it.name } ?: return Job().apply { complete() }
    return launchSave(session, name)
  }

  /** Waits for every save under way, so that nothing is written after the caller moves the file. */
  suspend fun drain() {
    inFlight.toList().joinAll()
  }

  private fun launchSave(
    session: PuzzleSession,
    name: String,
  ): Job {
    val ticket = tickets.incrementAndGet()
    // Undispatched, so the snapshot is on the engine thread's queue before the caller carries on;
    // see [flush].
    val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { save(session, name, ticket) }
    inFlight += job
    job.invokeOnCompletion { inFlight -= job }
    return job
  }

  /** @return null if there is nothing to write: no game yet, a closed session, or a failure. */
  private suspend fun snapshot(
    session: PuzzleSession,
    name: String,
  ): PuzzleSession.SavedState? =
    try {
      session.saveState()
    } catch (e: EngineFailure) {
      onFailure(session, e)
      null
    } catch (e: CancellationException) {
      // A closed engine cancels rather than fails. We are shielded from our own cancellation, so
      // this is the engine going away, and the moves since the last save went with it.
      Log.w(LOG_TAG, "Save of $name was cancelled by the engine closing; recent moves are lost", e)
      null
    }

  private suspend fun save(
    session: PuzzleSession,
    name: String,
    ticket: Long,
  ) = withContext(NonCancellable) {
    val state = snapshot(session, name) ?: return@withContext

    writeLock.withLock {
      if (ticket < lastWritten) return@withLock
      lastWritten = ticket
      if (state.game.contentEquals(lastGame) && state.prefs.contentEquals(lastPrefs)) {
        Log.d(LOG_TAG, "Save of $name unchanged; not writing")
      } else {
        // Both stores write beside and rename into place, so a kill part way through leaves the
        // previous save. The lock is process-wide for the file's sake: a screen's persister can
        // outlive it, and two for the same puzzle must not share its temporary file.
        fileLock.withLock {
          saves.save(name, state.game)
          prefs.savePrefs(name, state.prefs)
        }
        lastGame = state.game
        lastPrefs = state.prefs
        Log.d(LOG_TAG, "Saved $name (${state.game.size} bytes)")
      }
    }
    onSerialised()
  }

  private companion object {
    val fileLock = Mutex()
  }
}
