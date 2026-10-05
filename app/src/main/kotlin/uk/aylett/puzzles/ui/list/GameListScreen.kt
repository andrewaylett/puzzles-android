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
package uk.aylett.puzzles.ui.list

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import uk.aylett.puzzles.R
import uk.aylett.puzzles.engine.GameInfo

/** The launcher screen: every puzzle the module carries, filterable, tap to play. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameListScreen(
  onGameSelected: (Int) -> Unit,
  viewModel: GameListViewModel =
    viewModel(
      factory =
        GameListViewModel.factory(
          LocalContext.current.applicationContext as android.app.Application,
        ),
    ),
) {
  val uiState by viewModel.uiState.collectAsState()

  Scaffold(
    topBar = {
      TopAppBar(title = { Text(stringResource(R.string.game_list_title)) })
    },
  ) { padding ->
    Column(modifier = Modifier.padding(padding).fillMaxSize()) {
      OutlinedTextField(
        value = uiState.filter,
        onValueChange = viewModel::onFilterChange,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        placeholder = { Text(stringResource(R.string.game_list_search_hint)) },
      )

      if (uiState.loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(bottom = 16.dp),
        ) {
          items(uiState.visibleGames, key = GameInfo::index) { game ->
            val icon = iconFor(game.name)
            ListItem(
              headlineContent = { Text(game.name) },
              leadingContent = {
                if (icon != null) {
                  Image(
                    painter = painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                  )
                } else {
                  Icon(Icons.Filled.Extension, contentDescription = null)
                }
              },
              modifier =
                Modifier
                  .fillMaxWidth()
                  .clickable { onGameSelected(game.index) },
            )
          }
        }
      }
    }
  }
}
