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
package uk.aylett.puzzles.ui.game.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import uk.aylett.puzzles.R
import uk.aylett.puzzles.data.CustomPreset
import uk.aylett.puzzles.engine.PresetEntry

private data class FlatEntry(
  val title: String,
  val depth: Int,
  val preset: PresetEntry.Preset?,
)

private fun flatten(
  entries: List<PresetEntry>,
  depth: Int = 0,
): List<FlatEntry> =
  entries.flatMap { entry ->
    when (entry) {
      is PresetEntry.Preset -> {
        listOf(FlatEntry(entry.title, depth, entry))
      }

      is PresetEntry.Submenu -> {
        listOf(FlatEntry(entry.title, depth, null)) + flatten(entry.entries, depth + 1)
      }
    }
  }

/**
 * The "Type" menu: a tree of presets (several games nest a difficulty under a size, or similar),
 * flattened here into an indented list since a tree control would be overkill for at most two
 * levels. "Custom…" lives at the end of the list because it is just another way of choosing a
 * type, and is null when the game has no configurable parameters. The [customPresets] the user
 * chose most recently sit just above it, so that repeating one does not mean retyping it.
 */
@Composable
fun PresetsDialog(
  presets: List<PresetEntry>,
  currentPresetId: Int,
  customPresets: List<CustomPreset>,
  currentCustom: CustomPreset?,
  onSelect: (Int) -> Unit,
  onSelectCustom: (CustomPreset) -> Unit,
  onCustom: (() -> Unit)?,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.dialog_type_title)) },
    text = {
      LazyColumn {
        items(flatten(presets)) { entry ->
          Row(
            modifier =
              Modifier
                .fillMaxWidth()
                .then(
                  if (entry.preset != null) {
                    Modifier.clickable {
                      onSelect(entry.preset.id)
                      onDismiss()
                    }
                  } else {
                    Modifier
                  },
                ).padding(
                  start = (16 * (entry.depth + 1)).dp,
                  top = 12.dp,
                  bottom = 12.dp,
                  end = 16.dp,
                ),
          ) {
            if (entry.preset != null && entry.preset.id == currentPresetId) {
              Icon(Icons.Filled.Check, contentDescription = null)
              Spacer(modifier = Modifier.width(8.dp))
            } else if (entry.preset != null) {
              Spacer(modifier = Modifier.width(32.dp))
            }
            Text(entry.title)
          }
        }
        items(customPresets) { preset ->
          Row(
            modifier =
              Modifier
                .fillMaxWidth()
                .clickable {
                  onSelectCustom(preset)
                  onDismiss()
                }.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 16.dp),
          ) {
            if (preset == currentCustom) {
              Icon(Icons.Filled.Check, contentDescription = null)
              Spacer(modifier = Modifier.width(8.dp))
            } else {
              Spacer(modifier = Modifier.width(32.dp))
            }
            Text(preset.title)
          }
        }
        if (onCustom != null) {
          item {
            Text(
              text = stringResource(R.string.action_custom),
              modifier =
                Modifier
                  .fillMaxWidth()
                  .clickable {
                    onDismiss()
                    onCustom()
                  }.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 16.dp),
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
    },
  )
}
