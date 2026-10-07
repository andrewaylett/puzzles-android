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
package uk.aylett.puzzles.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import uk.aylett.puzzles.R

private const val UPSTREAM_URL = "https://www.chiark.greenend.org.uk/~sgtatham/puzzles/"

/**
 * Credits and licensing. The puzzles themselves are Simon Tatham's work and are MIT licensed;
 * this app is only the front end, so the credit has to say so plainly. The full licence texts
 * ship in the repository's `LICENSE` and `NOTICE` files.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
  val uriHandler = LocalUriHandler.current
  // Read from the package rather than BuildConfig so there is one place to bump it: the Gradle file.
  val context = LocalContext.current
  val version =
    remember(context) {
      context.packageManager
        .getPackageInfo(context.packageName, 0)
        .versionName
        .orEmpty()
    }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.action_about)) },
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
    Column(
      modifier =
        Modifier
          .padding(padding)
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(16.dp),
    ) {
      Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
      Text(
        stringResource(R.string.about_version, version),
        style = MaterialTheme.typography.bodyMedium,
      )
      Text(
        stringResource(R.string.about_description),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 16.dp),
      )
      Text(
        stringResource(R.string.about_credits_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp),
      )
      Text(
        stringResource(R.string.about_credits),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp),
      )
      TextButton(onClick = { uriHandler.openUri(UPSTREAM_URL) }) {
        Text(stringResource(R.string.about_upstream_link))
      }
      Text(
        stringResource(R.string.about_licence_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp),
      )
      Text(
        stringResource(R.string.about_licence),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp),
      )
    }
  }
}
