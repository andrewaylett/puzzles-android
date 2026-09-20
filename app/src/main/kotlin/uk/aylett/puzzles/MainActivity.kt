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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.first
import uk.aylett.puzzles.data.AppPreferences
import uk.aylett.puzzles.ui.game.GameScreen
import uk.aylett.puzzles.ui.list.GameListScreen
import uk.aylett.puzzles.ui.theme.PuzzlesTheme

private const val NO_GAME_SELECTED = -1

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

  if (selectedGame == NO_GAME_SELECTED) {
    GameListScreen(onGameSelected = { selectedGame = it })
  } else {
    GameScreen(gameIndex = selectedGame, onBack = { selectedGame = NO_GAME_SELECTED })
  }
}
