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

import android.content.Context
import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import uk.aylett.puzzles.R
import uk.aylett.puzzles.data.FailureReport
import java.io.File

/**
 * Tells the player that the engine failed and what was done about it, and offers to send the
 * report on. Sharing uses the system chooser, so the report goes only where the player sends it:
 * the app has no network access to report anything itself.
 */
@Composable
fun FailureDialog(
  report: FailureReport,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.failure_title, report.gameName)) },
    text = {
      Text(
        stringResource(
          if (report.startedFresh) R.string.failure_started_fresh else R.string.failure_reloaded,
        ),
      )
    },
    confirmButton = {
      TextButton(onClick = {
        shareReport(context, report.summary, report.file, report.text)
        onDismiss()
      }) { Text(stringResource(R.string.failure_share)) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_ok)) }
    },
  )
}

private fun reportUri(
  context: Context,
  file: File,
) = FileProvider.getUriForFile(context, "${context.packageName}.reports", file)

/**
 * Offers a report to the system chooser. Used for a fresh failure and, from the status screen, for
 * any kept since, so that not sharing it at the time does not lose it.
 */
fun shareReport(
  context: Context,
  summary: String,
  file: File?,
  text: String,
) {
  val send =
    Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_SUBJECT, summary)
      if (file != null) {
        // A file, not EXTRA_TEXT: the report is long, and share targets such as Tailscale
        // only take files. The provider grants the receiver read access to this one file.
        putExtra(Intent.EXTRA_STREAM, reportUri(context, file))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      } else {
        putExtra(Intent.EXTRA_TEXT, text)
      }
    }
  context.startActivity(Intent.createChooser(send, null))
}
