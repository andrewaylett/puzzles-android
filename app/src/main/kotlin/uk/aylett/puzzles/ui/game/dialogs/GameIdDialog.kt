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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uk.aylett.puzzles.R

/**
 * Lets the user type in a game ID or seed (e.g. shared by another player) and load it via
 * `setGameId`. Errors are shown inline: a malformed ID should not silently do nothing.
 */
@Composable
fun GameIdDialog(
  onSubmit: suspend (String) -> String?,
  onDismiss: () -> Unit,
) {
  var text by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  val scope = rememberCoroutineScope()

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.dialog_game_id_title)) },
    text = {
      Column {
        OutlinedTextField(
          value = text,
          onValueChange = { text = it },
          label = { Text(stringResource(R.string.dialog_game_id_hint)) },
          singleLine = true,
        )
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
          val result = onSubmit(text)
          if (result == null) onDismiss() else error = result
        }
      }) { Text(stringResource(R.string.dialog_ok)) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
    },
  )
}
