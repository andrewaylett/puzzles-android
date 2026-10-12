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

import android.os.Bundle
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import uk.aylett.puzzles.data.AppPreferences
import uk.aylett.puzzles.ui.about.AboutScreen
import uk.aylett.puzzles.ui.game.GameScreen
import uk.aylett.puzzles.ui.list.GameListScreen
import uk.aylett.puzzles.ui.settings.SettingsScreen
import uk.aylett.puzzles.ui.status.AppStatusScreen
import uk.aylett.puzzles.ui.theme.PuzzlesTheme

private const val NO_GAME_SELECTED = -1

/** Screens reached from the list's menu; they sit on top of it, one level deep. */
private const val SCREEN_LIST = 0
private const val SCREEN_SETTINGS = 1
private const val SCREEN_ABOUT = 2
private const val SCREEN_STATUS = 3

// How far the open game shrinks and slides during a predictive back gesture, per the platform's
// own convention for revealing the previous screen (see the Compose predictive-back guide).
private const val PREDICTIVE_BACK_MIN_SCALE = 0.9f
private val PREDICTIVE_BACK_MAX_TRANSLATION = 16.dp
private val PREDICTIVE_BACK_MAX_CORNER_RADIUS = 32.dp

// How much of the screen's width the game must have been dragged in for a release to commit to it.
private const val REVEAL_GAME_COMMIT_FRACTION = 0.3f

/** The app's single activity: it hosts the list, with one game or a menu screen on top. */
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      PuzzlesTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
          PuzzlesApp()
        }
      }
    }
  }
}

/**
 * Manual navigation: `navigation-compose` isn't a dependency of this module, and a
 * single optional "which game is open" integer is all the state this app ever needs to restore.
 *
 * The last-played puzzle is remembered by name ([AppPreferences.lastPlayedName], since the
 * module's own index ordering could shift), so returning to the app after it was fully evicted
 * from memory reopens the same game rather than the launcher list. If that name is no longer
 * found among [uk.aylett.puzzles.engine.PuzzleEngine.games] the app falls back to the list.
 *
 * The open game is logically to the right of the list, as the last channel is in Slack: a leftward
 * swipe on the list brings back the game most recently left. The reverse is deliberately absent —
 * swiping within a game is puzzle input, and only back (or the system gesture) returns to the list.
 */
@Composable
private fun PuzzlesApp() {
  val context = LocalContext.current
  var restored by rememberSaveable { mutableIntStateOf(0) } // 0 = not yet checked, 1 = checked
  var selectedGame by rememberSaveable { mutableIntStateOf(NO_GAME_SELECTED) }
  var menuScreen by rememberSaveable { mutableIntStateOf(SCREEN_LIST) }
  // Hoisted here, not in GameListScreen: that leaves composition while a game is open, and with it
  // anything it remembered. This is saveable, so it also survives process death.
  val listState = rememberLazyListState()

  if (restored == 0) {
    LaunchedEffect(Unit) {
      val lastPlayedName = AppPreferences(context).lastPlayedName.first()
      selectedGame =
        if (lastPlayedName == null) {
          NO_GAME_SELECTED
        } else {
          val engine = (context.applicationContext as PuzzlesApplication).engine()
          engine.games.firstOrNull { it.name == lastPlayedName }?.index ?: NO_GAME_SELECTED
        }
      restored = 1
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator()
    }
    return
  }

  // The game to bring back with a leftward swipe on the list: whichever was last open.
  var lastGame by rememberSaveable { mutableIntStateOf(NO_GAME_SELECTED) }
  if (selectedGame != NO_GAME_SELECTED) lastGame = selectedGame
  // How far the last game has been dragged in from the right, 0 (off screen) to 1 (in place).
  var reveal by remember { mutableFloatStateOf(0f) }
  val revealing by remember { derivedStateOf { reveal > 0f } }

  val backProgress = remember { Animatable(0f) }
  var swipeEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
  val coroutineScope = rememberCoroutineScope()

  // handleOnBackCancelled cancels this handler's own job alongside the progress channel, so a
  // suspending snap-back here would throw immediately at its first suspension point rather than
  // running. Animating the cancelled gesture back to rest needs a scope that outlives the job.
  PredictiveBackHandler(
    enabled = selectedGame != NO_GAME_SELECTED,
  ) { events: Flow<BackEventCompat> ->
    try {
      events.collect { event ->
        swipeEdge = event.swipeEdge
        backProgress.snapTo(event.progress)
      }
      backProgress.snapTo(0f)
      selectedGame = NO_GAME_SELECTED
    } catch (_: CancellationException) {
      coroutineScope.launch { backProgress.animateTo(0f) }
    }
  }

  BackHandler(enabled = menuScreen != SCREEN_LIST) { menuScreen = SCREEN_LIST }

  when (menuScreen) {
    SCREEN_SETTINGS -> {
      SettingsScreen(onBack = { menuScreen = SCREEN_LIST })
      return
    }

    SCREEN_ABOUT -> {
      AboutScreen(onBack = { menuScreen = SCREEN_LIST })
      return
    }

    SCREEN_STATUS -> {
      AppStatusScreen(onBack = { menuScreen = SCREEN_LIST })
      return
    }
  }

  val canRevealGame = selectedGame == NO_GAME_SELECTED && lastGame != NO_GAME_SELECTED
  Box(
    modifier =
      Modifier.fillMaxSize().pointerInput(canRevealGame, lastGame) {
        if (!canRevealGame) return@pointerInput
        val width = size.width.toFloat()

        // Settling runs in the composition's scope: the gesture's own scope ends with the release.
        fun settle(target: Float) {
          coroutineScope.launch {
            animate(reveal, target) { value, _ -> reveal = value }
            if (target == 1f) {
              selectedGame = lastGame
              reveal = 0f
            }
          }
        }
        detectHorizontalDragGestures(
          onDragCancel = { settle(0f) },
          onDragEnd = { settle(if (reveal >= REVEAL_GAME_COMMIT_FRACTION) 1f else 0f) },
        ) { change, delta ->
          change.consume()
          reveal = (reveal - delta / width).coerceIn(0f, 1f)
        }
      },
  ) {
    // Kept underneath throughout the gesture so the list is what the shrinking game reveals.
    if (selectedGame == NO_GAME_SELECTED || backProgress.value > 0f) {
      GameListScreen(
        onGameSelected = { selectedGame = it },
        onOpenSettings = { menuScreen = SCREEN_SETTINGS },
        onOpenAbout = { menuScreen = SCREEN_ABOUT },
        onOpenStatus = { menuScreen = SCREEN_STATUS },
        listState = listState,
      )
    }
    if (selectedGame != NO_GAME_SELECTED || revealing) {
      val progress = backProgress.value
      val scale = 1f - (1f - PREDICTIVE_BACK_MIN_SCALE) * progress
      val edgeSign = if (swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
      Box(
        modifier =
          Modifier
            .graphicsLayer {
              scaleX = scale
              scaleY = scale
              translationX =
                if (selectedGame == NO_GAME_SELECTED) {
                  size.width * (1f - reveal)
                } else {
                  edgeSign * PREDICTIVE_BACK_MAX_TRANSLATION.toPx() * progress
                }
            }.clip(RoundedCornerShape(PREDICTIVE_BACK_MAX_CORNER_RADIUS * progress)),
      ) {
        GameScreen(
          gameIndex = if (selectedGame == NO_GAME_SELECTED) lastGame else selectedGame,
          onBack = { selectedGame = NO_GAME_SELECTED },
        )
      }
    }
  }
}
