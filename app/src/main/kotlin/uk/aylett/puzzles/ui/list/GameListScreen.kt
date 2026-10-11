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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import uk.aylett.puzzles.R
import uk.aylett.puzzles.engine.GameInfo

/**
 * The launcher screen: every puzzle the module carries, filterable, tap to play, long-press to
 * star.
 *
 * [listState] is owned by the caller because this screen leaves composition while a game is open,
 * and state remembered here would go with it: the user would come back to the top of the list
 * every time.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GameListScreen(
  onGameSelected: (Int) -> Unit,
  onOpenSettings: () -> Unit,
  onOpenAbout: () -> Unit,
  onOpenStatus: () -> Unit,
  listState: LazyListState = rememberLazyListState(),
  viewModel: GameListViewModel =
    viewModel(
      factory =
        GameListViewModel.factory(
          LocalContext.current.applicationContext as android.app.Application,
        ),
    ),
) {
  val uiState by viewModel.uiState.collectAsState()
  val haptics = LocalHapticFeedback.current
  var menuExpanded by remember { mutableStateOf(false) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.game_list_title)) },
        actions = {
          IconButton(onClick = { menuExpanded = true }) {
            Icon(
              Icons.Filled.MoreVert,
              contentDescription = stringResource(R.string.action_more_options),
            )
          }
          DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.action_settings)) },
              onClick = {
                menuExpanded = false
                onOpenSettings()
              },
            )
            DropdownMenuItem(
              text = { Text(stringResource(R.string.action_app_status)) },
              onClick = {
                menuExpanded = false
                onOpenStatus()
              },
            )
            DropdownMenuItem(
              text = { Text(stringResource(R.string.action_about)) },
              onClick = {
                menuExpanded = false
                onOpenAbout()
              },
            )
          }
        },
      )
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
          state = listState,
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
              trailingContent = {
                if (game.name in uiState.favourites) {
                  Icon(
                    Icons.Filled.Star,
                    contentDescription = stringResource(R.string.favourite),
                    tint = MaterialTheme.colorScheme.primary,
                  )
                }
              },
              modifier =
                Modifier
                  .fillMaxWidth()
                  .combinedClickable(
                    onClick = { onGameSelected(game.index) },
                    onLongClick = {
                      haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                      viewModel.onToggleFavourite(game)
                    },
                  ),
            )
          }
        }
      }
    }
  }
}
