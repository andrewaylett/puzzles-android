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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uk.aylett.puzzles.R
import uk.aylett.puzzles.engine.ConfigField
import uk.aylett.puzzles.engine.ConfigForm

/**
 * Renders a `config_item` form (custom settings, or mid-end preferences) as Compose widgets, and
 * applies it via [onApply]. Errors from `applyConfig` are shown inline rather than dismissing the
 * dialog, since the whole point is to let the user fix the offending field and retry.
 */
@Composable
fun ConfigDialog(
  form: ConfigForm,
  onApply: suspend (List<ConfigField>) -> String?,
  onDismiss: () -> Unit,
) {
  var fields by remember(form) { mutableStateOf(form.fields) }
  var error by remember { mutableStateOf<String?>(null) }
  val scope = rememberCoroutineScope()

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(form.title) },
    text = {
      Column {
        LazyColumn {
          items(fields.size) { index ->
            ConfigFieldRow(
              field = fields[index],
              onChange = { updated ->
                fields = fields.toMutableList().also { it[index] = updated }
              },
            )
          }
        }
        val message = error
        if (message != null) {
          Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
          )
        }
      }
    },
    confirmButton = {
      TextButton(onClick = {
        scope.launch {
          val result = onApply(fields)
          if (result == null) onDismiss() else error = result
        }
      }) { Text(stringResource(R.string.dialog_apply)) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
    },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfigFieldRow(
  field: ConfigField,
  onChange: (ConfigField) -> Unit,
) {
  when (field) {
    is ConfigField.Text -> {
      OutlinedTextField(
        value = field.value,
        onValueChange = { onChange(field.copy(value = it)) },
        label = { Text(field.name) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardTypeFor(field)),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
      )
    }

    is ConfigField.Toggle -> {
      Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(field.name, modifier = Modifier.weight(1f))
        Switch(checked = field.value, onCheckedChange = { onChange(field.copy(value = it)) })
      }
    }

    is ConfigField.Choice -> {
      var expanded by remember { mutableStateOf(false) }
      ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
      ) {
        OutlinedTextField(
          value = field.options.getOrElse(field.selected) { "" },
          onValueChange = {},
          readOnly = true,
          label = { Text(field.name) },
          trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
          modifier =
            Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
          expanded = expanded,
          onDismissRequest = { expanded = false },
        ) {
          field.options.forEachIndexed { index, option ->
            DropdownMenuItem(
              text = { Text(option) },
              onClick = {
                onChange(field.copy(selected = index))
                expanded = false
              },
            )
          }
        }
      }
    }
  }
}

private val INTEGER = Regex("-?\\d+")
private val DECIMAL = Regex("-?\\d*\\.\\d+")
private val NUMERIC_NAMES =
  Regex(
    "\\b(width|height|size|columns?|rows?|number|count|regions?|moves)\\b",
    RegexOption.IGNORE_CASE,
  )

/**
 * The mid-end gives every text field the same `C_STRING` type, so numeric ones are indistinguishable
 * by type. The current value is the better hint (a puzzle's defaults are always well-formed); the
 * field name is the fallback for a value that happens to be empty.
 */
internal fun keyboardTypeFor(field: ConfigField.Text): KeyboardType =
  when {
    DECIMAL.matches(field.value) -> KeyboardType.Decimal
    INTEGER.matches(field.value) || NUMERIC_NAMES.containsMatchIn(field.name) -> KeyboardType.Number
    else -> KeyboardType.Text
  }
