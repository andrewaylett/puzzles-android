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
package uk.aylett.puzzles.ui.game

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import uk.aylett.puzzles.R
import uk.aylett.puzzles.engine.ConfigForm
import uk.aylett.puzzles.engine.ConfigSection
import uk.aylett.puzzles.engine.FILL_PENCIL_MARKS_BUTTON
import uk.aylett.puzzles.engine.GameStatus
import uk.aylett.puzzles.engine.PresetEntry
import uk.aylett.puzzles.ui.game.dialogs.ConfigDialog
import uk.aylett.puzzles.ui.game.dialogs.GameIdDialog
import uk.aylett.puzzles.ui.game.dialogs.PresetsDialog

private enum class ActiveDialog { NONE, PRESETS, SETTINGS, PREFS, GAME_ID }

/**
 * The main event: shows the puzzle bitmap, handles touch, and hosts every menu/dialog reachable
 * from play. One [GameViewModel] per instance of this screen owns the puzzle session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(
  gameIndex: Int,
  onBack: () -> Unit,
  viewModel: GameViewModel =
    viewModel(
      key = "game-$gameIndex",
      factory =
        GameViewModel.factory(
          LocalContext.current.applicationContext as android.app.Application,
          gameIndex,
        ),
    ),
) {
  val uiState by viewModel.state.collectAsState()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var activeDialog by remember { mutableStateOf(ActiveDialog.NONE) }
  var menuExpanded by remember { mutableStateOf(false) }
  val snackbarHostState = remember { SnackbarHostState() }

  // The session must not keep animating (and burning battery / WASM cycles) once the user can't
  // see it, and the in-progress game must not be lost if the process dies while backgrounded.
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner, viewModel) {
    val observer =
      LifecycleEventObserver { _, event ->
        when (event) {
          Lifecycle.Event.ON_RESUME -> viewModel.onResume()
          Lifecycle.Event.ON_PAUSE -> viewModel.onPause()
          else -> Unit
        }
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  LaunchedEffect(uiState.toastMessage) {
    val message = uiState.toastMessage
    if (message != null) {
      snackbarHostState.showSnackbar(message)
      viewModel.clearToast()
    }
  }

  val exportLauncher =
    rememberLauncherForActivityResult(
      ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
      if (uri != null) {
        scope.launch {
          val bytes = viewModel.exportBytes()
          if (bytes != null) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
          }
        }
      }
    }
  val importLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        scope.launch {
          val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
          if (bytes != null) {
            val error = viewModel.importBytes(bytes)
            if (error != null) snackbarHostState.showSnackbar(error)
          }
        }
      }
    }

  Scaffold(
    snackbarHost = {
      SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data) }
    },
    topBar = {
      TopAppBar(
        title = { Text(uiState.game?.name ?: stringResource(R.string.loading)) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
          }
        },
        actions = {
          IconButton(onClick = viewModel::newGame) {
            Icon(
              Icons.Filled.Refresh,
              contentDescription = stringResource(R.string.action_new_game),
            )
          }
          IconButton(onClick = viewModel::undo, enabled = uiState.frame.canUndo) {
            Icon(
              Icons.AutoMirrored.Filled.Undo,
              contentDescription = stringResource(R.string.action_undo),
            )
          }
          IconButton(onClick = viewModel::redo, enabled = uiState.frame.canRedo) {
            Icon(
              Icons.AutoMirrored.Filled.Redo,
              contentDescription = stringResource(R.string.action_redo),
            )
          }
          IconButton(onClick = { activeDialog = ActiveDialog.PRESETS }) {
            Icon(
              Icons.Filled.GridView,
              contentDescription = stringResource(R.string.action_type),
            )
          }
          FilledIconToggleButton(
            checked = uiState.swapPressGesture,
            onCheckedChange = { viewModel.toggleSwapPressGesture() },
            colors =
              IconButtonDefaults.filledIconToggleButtonColors(
                containerColor = Color.Transparent,
                contentColor = LocalContentColor.current,
                checkedContainerColor = MaterialTheme.colorScheme.primary,
                checkedContentColor = MaterialTheme.colorScheme.onPrimary,
              ),
          ) {
            Icon(
              Icons.Filled.SwapVert,
              contentDescription = stringResource(R.string.action_swap_press_gesture),
            )
          }
          IconButton(onClick = { menuExpanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = null)
          }
          GameOverflowMenu(
            expanded = menuExpanded,
            canSolve = uiState.game?.canSolve == true,
            canFillPencilMarks = uiState.game?.canFillPencilMarks == true,
            showStylusTaps = uiState.game?.supportsStylus == true,
            stylusTaps = uiState.stylusTaps,
            onToggleStylusTaps = viewModel::toggleStylusTaps,
            onDismiss = { menuExpanded = false },
            onRestart = {
              menuExpanded = false
              viewModel.restart()
            },
            onSolve = {
              menuExpanded = false
              viewModel.solve()
            },
            onFillPencilMarks = {
              menuExpanded = false
              viewModel.key(FILL_PENCIL_MARKS_BUTTON)
            },
            onPreferences = {
              menuExpanded = false
              activeDialog = ActiveDialog.PREFS
            },
            onEnterGameId = {
              menuExpanded = false
              activeDialog = ActiveDialog.GAME_ID
            },
            onShareGameId = {
              menuExpanded = false
              scope.launch {
                val id = viewModel.gameId()
                val send =
                  Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, id)
                  }
                context.startActivity(Intent.createChooser(send, null))
              }
            },
            onImport = {
              menuExpanded = false
              importLauncher.launch(arrayOf("*/*"))
            },
            onExport = {
              menuExpanded = false
              exportLauncher.launch("${uiState.game?.name ?: "puzzle"}.sav")
            },
          )
        },
      )
    },
  ) { padding ->
    Column(modifier = Modifier.padding(padding).fillMaxSize()) {
      if (uiState.frame.busy) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      }

      Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        if (uiState.loading) {
          CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
          PuzzleCanvas(
            frame = uiState.frame,
            onSurfaceChanged = viewModel::attachSurface,
            onSurfaceDestroyed = viewModel::detachSurface,
            onSizeChanged = viewModel::onSizeChanged,
            onPointerEvent = viewModel::pointer,
            modifier = Modifier.fillMaxSize(),
            swapPressGesture = uiState.swapPressGesture,
          )
        }

        if (uiState.frame.status != GameStatus.IN_PROGRESS) {
          val label =
            if (uiState.frame.status == GameStatus.WON) {
              stringResource(R.string.status_won)
            } else {
              stringResource(R.string.status_lost)
            }
          // Tapping the banner starts the next game: once the puzzle is over it is the obvious
          // thing to reach for, and it saves a trip into the menu.
          Surface(
            onClick = viewModel::newGame,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium,
          ) {
            Text(
              text = label,
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
              color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          }
        }
      }

      if (uiState.frame.statusText.isNotEmpty()) {
        Text(
          text = uiState.frame.statusText,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
          textAlign = TextAlign.Start,
          style = MaterialTheme.typography.bodySmall,
        )
      }

      KeyRow(hints = uiState.keyHints, onKey = viewModel::key, gameName = uiState.game?.name)
    }
  }

  when (activeDialog) {
    ActiveDialog.PRESETS -> {
      var presets by remember { mutableStateOf<List<PresetEntry>>(emptyList()) }
      var currentId by remember { mutableStateOf(-1) }
      LaunchedEffect(Unit) {
        presets = viewModel.presets()
        currentId = viewModel.currentPresetId()
      }
      PresetsDialog(
        presets = presets,
        currentPresetId = currentId,
        onSelect = { viewModel.applyPreset(it) },
        onCustom =
          if (uiState.game?.canConfigure == true) {
            { activeDialog = ActiveDialog.SETTINGS }
          } else {
            null
          },
        onDismiss = { activeDialog = ActiveDialog.NONE },
      )
    }

    ActiveDialog.SETTINGS, ActiveDialog.PREFS -> {
      val section =
        if (activeDialog == ActiveDialog.SETTINGS) ConfigSection.SETTINGS else ConfigSection.PREFS
      var form by remember(activeDialog) { mutableStateOf<ConfigForm?>(null) }
      LaunchedEffect(activeDialog) { form = viewModel.config(section) }
      val currentForm = form
      if (currentForm != null) {
        ConfigDialog(
          form = currentForm,
          onApply = { fields -> viewModel.applyConfig(section, fields) },
          onDismiss = { activeDialog = ActiveDialog.NONE },
        )
      }
    }

    ActiveDialog.GAME_ID -> {
      GameIdDialog(
        onSubmit = { id -> viewModel.setGameId(id) },
        onDismiss = { activeDialog = ActiveDialog.NONE },
      )
    }

    ActiveDialog.NONE -> {
      Unit
    }
  }
}

@Composable
private fun GameOverflowMenu(
  expanded: Boolean,
  canSolve: Boolean,
  canFillPencilMarks: Boolean,
  showStylusTaps: Boolean,
  stylusTaps: Boolean,
  onToggleStylusTaps: () -> Unit,
  onDismiss: () -> Unit,
  onRestart: () -> Unit,
  onSolve: () -> Unit,
  onFillPencilMarks: () -> Unit,
  onPreferences: () -> Unit,
  onEnterGameId: () -> Unit,
  onShareGameId: () -> Unit,
  onImport: () -> Unit,
  onExport: () -> Unit,
) {
  DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
    DropdownMenuItem(
      text = { Text(stringResource(R.string.action_restart)) },
      onClick = onRestart,
    )
    if (canSolve) {
      DropdownMenuItem(text = { Text(stringResource(R.string.action_solve)) }, onClick = onSolve)
    }
    if (canFillPencilMarks) {
      DropdownMenuItem(
        text = { Text(stringResource(R.string.action_fill_pencil_marks)) },
        onClick = onFillPencilMarks,
      )
    }
    if (showStylusTaps) {
      DropdownMenuItem(
        text = { Text(stringResource(R.string.action_stylus_taps)) },
        trailingIcon = { Checkbox(checked = stylusTaps, onCheckedChange = null) },
        onClick = onToggleStylusTaps,
      )
    }
    DropdownMenuItem(
      text = { Text(stringResource(R.string.action_preferences)) },
      onClick = onPreferences,
    )
    DropdownMenuItem(
      text = { Text(stringResource(R.string.action_enter_game_id)) },
      onClick = onEnterGameId,
    )
    DropdownMenuItem(
      text = { Text(stringResource(R.string.action_share_game_id)) },
      onClick = onShareGameId,
    )
    DropdownMenuItem(text = { Text(stringResource(R.string.action_import)) }, onClick = onImport)
    DropdownMenuItem(text = { Text(stringResource(R.string.action_export)) }, onClick = onExport)
  }
}
