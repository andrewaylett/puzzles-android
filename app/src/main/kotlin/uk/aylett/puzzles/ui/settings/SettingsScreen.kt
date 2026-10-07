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
package uk.aylett.puzzles.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import uk.aylett.puzzles.R

/** App-wide settings reachable from the puzzle list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  onBack: () -> Unit,
  viewModel: SettingsViewModel =
    viewModel(
      factory =
        SettingsViewModel.factory(
          LocalContext.current.applicationContext as android.app.Application,
        ),
    ),
) {
  val uiState by viewModel.uiState.collectAsState()

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.action_settings)) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(
              Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.action_back),
            )
          }
        },
      )
    },
  ) { padding ->
    Column(modifier = Modifier.padding(padding).fillMaxSize()) {
      ListItem(
        headlineContent = { Text(stringResource(R.string.action_swap_press_gesture)) },
        supportingContent = { Text(stringResource(R.string.setting_swap_press_gesture_detail)) },
        trailingContent = {
          Switch(
            checked = uiState.swapPressGesture,
            onCheckedChange = viewModel::setSwapPressGesture,
          )
        },
      )
      ListItem(
        headlineContent = { Text(stringResource(R.string.action_stylus_taps)) },
        supportingContent = { Text(stringResource(R.string.setting_stylus_taps_detail)) },
        trailingContent = {
          Switch(checked = uiState.stylusTaps, onCheckedChange = viewModel::setStylusTaps)
        },
      )
    }
  }
}
