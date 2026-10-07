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
package uk.aylett.puzzles.data

import uk.aylett.puzzles.engine.ConfigField

/**
 * Upstream gives every puzzle its own preferences blob, but several preferences are really the
 * same setting in different games ("keep highlight after changing a pencil mark" appears in five).
 * Users expect to set those once, so the app stores each by *identity* rather than per game, and
 * overlays the stored value onto every session that offers it.
 *
 * Identity is keyword + label + choice labels, not keyword alone: upstream reuses `flash-type`,
 * `appearance` and the mouse-button keywords for settings that mean different things in different
 * games, and merging those would apply one game's choice to another's unrelated options. Text
 * fields never occur in preferences, so they have no identity.
 */
fun ConfigField.settingId(): String? =
  when (this) {
    is ConfigField.Text -> null
    is ConfigField.Toggle -> "$keyword|$name"
    is ConfigField.Choice -> "$keyword|$name|${options.joinToString("/")}"
  }

/**
 * The value as stored: choices by their label rather than index, so a reordering upstream cannot
 * silently select a different option.
 */
fun ConfigField.settingValue(): String? =
  when (this) {
    is ConfigField.Text -> null
    is ConfigField.Toggle -> value.toString()
    is ConfigField.Choice -> options.getOrNull(selected)
  }

/** This field carrying [value], or unchanged if [value] does not fit it any more. */
fun ConfigField.withSettingValue(value: String): ConfigField =
  when (this) {
    is ConfigField.Text -> {
      this
    }

    is ConfigField.Toggle -> {
      value.toBooleanStrictOrNull()?.let { copy(value = it) } ?: this
    }

    is ConfigField.Choice -> {
      options.indexOf(value).takeIf { it >= 0 }?.let { copy(selected = it) }
        ?: this
    }
  }

/** Applies every stored setting that has a matching field; fields with no stored value keep theirs. */
fun List<ConfigField>.overlay(stored: Map<String, String>): List<ConfigField> =
  map { field -> field.settingId()?.let(stored::get)?.let(field::withSettingValue) ?: field }

/** The settings in this form, keyed by identity, ready to be handed to [AppPreferences]. */
fun List<ConfigField>.toSettings(): Map<String, String> =
  mapNotNull { field ->
    val id = field.settingId()
    val value = field.settingValue()
    if (id != null && value != null) id to value else null
  }.toMap()
