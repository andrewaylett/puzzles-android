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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import uk.aylett.puzzles.R
import uk.aylett.puzzles.data.settingValue
import uk.aylett.puzzles.data.withSettingValue
import uk.aylett.puzzles.ui.game.dialogs.ConfigFieldRow

/**
 * Every setting in the app, grouped by how widely it applies. A puzzle's own Preferences dialog
 * shows the subset that affects it.
 */
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
    val catalogue = uiState.catalogue
    LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
      item { SectionHeader(stringResource(R.string.settings_section_touch)) }
      item {
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
      }
      item {
        ListItem(
          headlineContent = { Text(stringResource(R.string.action_stylus_taps)) },
          supportingContent = { Text(stringResource(R.string.setting_stylus_taps_detail)) },
          trailingContent = {
            Switch(checked = uiState.stylusTaps, onCheckedChange = viewModel::setStylusTaps)
          },
        )
      }

      if (catalogue == null) {
        item {
          Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
          }
        }
        return@LazyColumn
      }

      settingsGroup(
        catalogue.controls,
        uiState.stored,
        viewModel::setSetting,
        detail = R.string.settings_controls_note,
      )
      if (catalogue.general.isNotEmpty()) {
        item { SectionHeader(stringResource(R.string.settings_section_all)) }
        settingsGroup(catalogue.general, uiState.stored, viewModel::setSetting, showGames = false)
      }
      if (catalogue.shared.isNotEmpty()) {
        item { SectionHeader(stringResource(R.string.settings_section_shared)) }
        settingsGroup(catalogue.shared, uiState.stored, viewModel::setSetting)
      }
      catalogue.perGame.forEach { (game, entries) ->
        item(key = "game-$game") { SectionHeader(game) }
        settingsGroup(entries, uiState.stored, viewModel::setSetting, showGames = false)
      }
    }
  }
}

/**
 * @param showGames whether to say which puzzles a setting affects; pointless for a setting every
 *   puzzle has, or one already under a puzzle's own heading.
 * @param detail extra explanation appended to each entry, for groups whose behaviour needs it.
 */
private fun LazyListScope.settingsGroup(
  entries: List<CatalogueEntry>,
  stored: Map<String, String>,
  onChange: (String, String) -> Unit,
  showGames: Boolean = true,
  detail: Int? = null,
) {
  items(entries, key = { it.id }) { entry ->
    // A stored value wins; otherwise the catalogue's own resolved value is what games are using.
    val field = stored[entry.id]?.let(entry.field::withSettingValue) ?: entry.field
    Column(Modifier.padding(horizontal = 16.dp)) {
      ConfigFieldRow(
        field = field,
        onChange = { updated -> updated.settingValue()?.let { onChange(entry.id, it) } },
      )
      val notes =
        listOfNotNull(
          if (showGames) {
            stringResource(
              R.string.settings_affects,
              entry.games.joinToString(),
            )
          } else {
            null
          },
          detail?.let { stringResource(it) },
        )
      if (notes.isNotEmpty()) {
        Text(
          text = notes.joinToString(" "),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(bottom = 8.dp),
        )
      }
    }
  }
}

@Composable
private fun SectionHeader(title: String) {
  Text(
    text = title,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
  )
}
