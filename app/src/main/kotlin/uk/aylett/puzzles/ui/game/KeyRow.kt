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

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import uk.aylett.puzzles.R
import uk.aylett.puzzles.engine.KeyHint

/** Backspace, in the mid-end's own button encoding (see puzzles.h). */
private const val BACKSPACE_BUTTON = '\b'.code

/**
 * Undead's three placeable pieces get the collection's own artwork instead of a text label — the
 * on-screen letters ("Ghost", "Vampire", "Zombie") are otherwise the least discoverable buttons in
 * the app, since the mid-end supplies no icon of its own (see keyHints in PuzzleSession.kt).
 */
@DrawableRes
private fun undeadIconFor(label: String): Int? =
  when (label) {
    "Ghost" -> R.drawable.ic_key_ghost
    "Vampire" -> R.drawable.ic_key_vampire
    "Zombie" -> R.drawable.ic_key_zombie
    else -> null
  }

/**
 * On-screen digit/letter buttons for the current game, from `session.keyHints()`. Several games
 * (Solo, Keen, Towers, Unequal) require entering numbers and are otherwise unplayable on a device
 * with no physical keyboard.
 */
@Composable
fun KeyRow(
  hints: List<KeyHint>,
  onKey: (Int) -> Unit,
  modifier: Modifier = Modifier,
  gameName: String? = null,
) {
  if (hints.isEmpty()) return

  FlowRow(
    modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    for (hint in hints) {
      val icon = if (gameName == "Undead") undeadIconFor(hint.label) else null
      Button(onClick = { onKey(hint.button) }) {
        when {
          icon != null -> {
            Icon(painterResource(icon), contentDescription = hint.label)
          }

          hint.button == BACKSPACE_BUTTON -> {
            Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = hint.label)
          }

          else -> {
            Text(hint.label)
          }
        }
      }
    }
  }
}
