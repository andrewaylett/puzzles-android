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
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import uk.aylett.puzzles.data.AppPreferences
import uk.aylett.puzzles.ui.game.GameScreen
import uk.aylett.puzzles.ui.list.GameListScreen
import uk.aylett.puzzles.ui.theme.PuzzlesTheme

private const val NO_GAME_SELECTED = -1

// How far the open game shrinks and slides during a predictive back gesture, per the platform's
// own convention for revealing the previous screen (see the Compose predictive-back guide).
private const val PREDICTIVE_BACK_MIN_SCALE = 0.9f
private val PREDICTIVE_BACK_MAX_TRANSLATION = 16.dp
private val PREDICTIVE_BACK_MAX_CORNER_RADIUS = 32.dp

/** The app's single activity: it just hosts a two-screen stack (list, then one game). */
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
 * Manual, two-screen navigation: `navigation-compose` isn't a dependency of this module, and a
 * single optional "which game is open" integer is all the state this app ever needs to restore.
 *
 * The last-played puzzle is remembered by name ([AppPreferences.lastPlayedName], since the
 * module's own index ordering could shift), so returning to the app after it was fully evicted
 * from memory reopens the same game rather than the launcher list. If that name is no longer
 * found among [uk.aylett.puzzles.engine.PuzzleEngine.games] the app falls back to the list.
 */
@Composable
private fun PuzzlesApp() {
  val context = LocalContext.current
  var restored by rememberSaveable { mutableIntStateOf(0) } // 0 = not yet checked, 1 = checked
  var selectedGame by rememberSaveable { mutableIntStateOf(NO_GAME_SELECTED) }

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

  Box(modifier = Modifier.fillMaxSize()) {
    // Kept underneath throughout the gesture so the list is what the shrinking game reveals.
    if (selectedGame == NO_GAME_SELECTED || backProgress.value > 0f) {
      GameListScreen(onGameSelected = { selectedGame = it })
    }
    if (selectedGame != NO_GAME_SELECTED) {
      val progress = backProgress.value
      val scale = 1f - (1f - PREDICTIVE_BACK_MIN_SCALE) * progress
      val edgeSign = if (swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
      Box(
        modifier =
          Modifier
            .graphicsLayer {
              scaleX = scale
              scaleY = scale
              translationX = edgeSign * PREDICTIVE_BACK_MAX_TRANSLATION.toPx() * progress
            }.clip(RoundedCornerShape(PREDICTIVE_BACK_MAX_CORNER_RADIUS * progress)),
      ) {
        GameScreen(gameIndex = selectedGame, onBack = { selectedGame = NO_GAME_SELECTED })
      }
    }
  }
}
