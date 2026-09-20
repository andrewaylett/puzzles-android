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
package uk.aylett.puzzles.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors =
  lightColorScheme(
    primary =
      androidx.compose.ui.graphics
        .Color(0xFF3A5FA0),
    secondary =
      androidx.compose.ui.graphics
        .Color(0xFF5B6472),
  )

private val DarkColors =
  darkColorScheme(
    primary =
      androidx.compose.ui.graphics
        .Color(0xFFAAC7FF),
    secondary =
      androidx.compose.ui.graphics
        .Color(0xFFC3C9D9),
  )

/**
 * Dynamic colour (Android 12+) lets the app match the device's wallpaper-derived palette; older
 * devices fall back to a fixed brand palette rather than losing theming entirely.
 */
@Composable
fun PuzzlesTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val context = LocalContext.current
  val colorScheme =
    when {
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> {
        dynamicDarkColorScheme(context)
      }

      Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        dynamicLightColorScheme(context)
      }

      darkTheme -> {
        DarkColors
      }

      else -> {
        LightColors
      }
    }

  MaterialTheme(
    colorScheme = colorScheme,
    content = content,
  )
}
