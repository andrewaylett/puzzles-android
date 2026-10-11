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

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uk.aylett.puzzles.engine.ConfigField
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** One field of a custom preset: [name] is the label the mid-end gave it, [value] what was chosen. */
@Serializable
data class StoredField(
  val name: String,
  val value: String,
)

/**
 * A set of custom parameters the user has entered, kept so that picking the same type again is one
 * tap rather than a trip through the Custom dialog.
 *
 * Fields are matched back to the mid-end's by label, and choices are held by their label rather
 * than index, for the reason given on [settingValue]. [title] is generated when the preset is
 * first stored (see [customTitle]) and kept as written: it is display text, and rendering it needs
 * no engine.
 */
@Serializable
data class CustomPreset(
  val title: String,
  val fields: List<StoredField>,
) {
  /** Whether [current] is exactly this preset, for ticking the one in force. */
  fun matches(current: List<ConfigField>): Boolean = current.map { it.storedField() } == fields

  /** [current] with this preset's values written over it; fields it has no value for keep theirs. */
  fun applyTo(current: List<ConfigField>): List<ConfigField> {
    val values = fields.associate { it.name to it.value }
    return current.map { field ->
      val value = values[field.name] ?: return@map field
      if (field is ConfigField.Text) field.copy(value = value) else field.withSettingValue(value)
    }
  }
}

@Serializable
data class CustomPresets(
  /** Most recently used first, keyed by [uk.aylett.puzzles.engine.GameInfo.name]. */
  val byGame: Map<String, List<CustomPreset>> = emptyMap(),
)

private fun ConfigField.storedField() =
  StoredField(name, if (this is ConfigField.Text) value else settingValue().orEmpty())

/**
 * A short name describing [this] settings form, e.g. `9x9, Mines 10, Ensure solubility`.
 *
 * The mid-end only names its own presets, and only for the shapes it ships, so this reads the
 * values instead: width and height as `WxH` when the game has exactly that pair (matching how
 * upstream and [uk.aylett.puzzles.engine.portraitTitle] write them), other text fields as
 * `label value`, choices as the selected label and switches by name when on.
 */
fun List<ConfigField>.customTitle(): String {
  fun dimension(name: String) =
    filterIsInstance<ConfigField.Text>()
      .singleOrNull { it.name.equals(name, ignoreCase = true) }
      ?.takeIf { it.value.isNotBlank() }
  val width = dimension("width")
  val height = dimension("height")
  val size =
    if (width != null &&
      height != null
    ) {
      "${width.value.trim()}x${height.value.trim()}"
    } else {
      null
    }
  val parts =
    listOfNotNull(size) +
      mapNotNull { field ->
        when {
          size != null && (field === width || field === height) -> {
            null
          }

          field is ConfigField.Text -> {
            field.value
              .trim()
              .takeIf {
                it.isNotEmpty()
              }?.let { "${field.name} $it" }
          }

          field is ConfigField.Toggle -> {
            field.name.takeIf { field.value }
          }

          field is ConfigField.Choice -> {
            field.options.getOrNull(field.selected)
          }

          else -> {
            null
          }
        }
      }
  return parts.joinToString(", ").ifEmpty { "Custom" }
}

private object CustomPresetsSerializer : Serializer<CustomPresets> {
  private val json = Json { ignoreUnknownKeys = true }

  override val defaultValue = CustomPresets()

  // A corrupt file reads as empty rather than throwing: these are conveniences, and losing three
  // shortcuts is better than a game screen that cannot open.
  override suspend fun readFrom(input: InputStream): CustomPresets =
    runCatching { json.decodeFromString<CustomPresets>(input.readBytes().decodeToString()) }
      .getOrDefault(defaultValue)

  override suspend fun writeTo(
    t: CustomPresets,
    output: OutputStream,
  ) {
    output.write(json.encodeToString(CustomPresets.serializer(), t).encodeToByteArray())
  }
}

/**
 * The last few custom parameter sets the user chose, per puzzle, most recent first.
 *
 * This is a typed DataStore of its own rather than more string keys in [AppPreferences]: the value
 * is a nested structure that has to be read and replaced as a whole (moving one entry to the front
 * and dropping another), which a flat key/value store can only do by encoding it in a string
 * anyway. DataStore still gives atomic, serialised read-modify-write.
 */
class CustomPresetStore(
  private val store: DataStore<CustomPresets>,
) {
  constructor(
    file: File,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
  ) :
    this(DataStoreFactory.create(CustomPresetsSerializer, scope = scope) { file })

  fun presets(gameName: String): Flow<List<CustomPreset>> =
    store.data.map { it.byGame[gameName].orEmpty() }

  /** Records [fields] as the most recently used custom preset, adding it or moving it to the front. */
  suspend fun use(
    gameName: String,
    fields: List<ConfigField>,
  ) {
    val stored = fields.map { it.storedField() }
    store.updateData { all ->
      val existing = all.byGame[gameName].orEmpty()
      val preset =
        existing.firstOrNull { it.fields == stored } ?: CustomPreset(fields.customTitle(), stored)
      val updated = (listOf(preset) + existing.filter { it !== preset }).take(MAX_PER_GAME)
      all.copy(byGame = all.byGame + (gameName to updated))
    }
  }

  companion object {
    const val MAX_PER_GAME = 3
  }
}
