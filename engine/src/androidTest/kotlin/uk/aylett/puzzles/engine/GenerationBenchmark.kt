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
package uk.aylett.puzzles.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureNanoTime

/**
 * Times new-game generation for the slowest presets, to compare build configurations (WASM
 * optimisation level, ART compiler filter, Chicory version) on a real device.
 *
 * Generation is where the time goes: a redraw is milliseconds. Each run is a fresh random game, so
 * individual timings vary; compare the median and minimum across configurations, not a single run.
 * The first runs include JIT warm-up, which is deliberately reported separately: how quickly a cold
 * install gets fast is as much a part of the experience as steady state.
 *
 * It only runs when asked (`-e benchmark true`) so that it stays out of the ordinary test suite.
 * Results go to logcat under the tag `PuzzleBench`.
 */
@RunWith(AndroidJUnit4::class)
class GenerationBenchmark {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val arguments = InstrumentationRegistry.getArguments()

  private val cases =
    listOf(
      "Solo" to "3x3 Unreasonable",
      "Galaxies" to "15x15 Unreasonable",
      "Loopy" to "Hard",
    )

  @Test
  fun generation() {
    assumeTrue("pass -e benchmark true to run", arguments.getString("benchmark") == "true")
    val runs = arguments.getString("runs")?.toIntOrNull() ?: 5
    val only = arguments.getString("only")

    runBlocking {
      PuzzleEngine.create(context).use { engine ->
        for ((name, presetFragment) in cases) {
          if (only != null && only != name) continue
          val game = engine.games.first { it.name == name }
          engine.openGame(game.index).use { session ->
            session.resize(1080, 1800)
            val leaves = session.presets().leaves()
            val preset = leaves.firstOrNull { it.title.contains(presetFragment) }
            if (preset == null) {
              Log.w(TAG, "$name: no preset matching '$presetFragment' in ${leaves.map { it.title }}")
              return@use
            }
            // Seeds are fixed so every configuration generates the very same games; otherwise
            // the variation between random games swamps any difference being looked for.
            session.applyPreset(preset.id)
            // The plain game id leaves out parameters such as difficulty; the seed form keeps them all.
            val seedField =
              session.config(ConfigSection.SEED).fields.filterIsInstance<ConfigField.Text>().first()
            val params = seedField.value.substringBefore('#')
            val seeds = (1..runs).map { "$params#bench$it" }
            val passes =
              (1..2).map {
                seeds.map { id ->
                  measureNanoTime { session.setGameId(id) } / 1_000_000
                }
              }
            val (first, second) = passes
            Log.i(
              TAG,
              "RESULT $name '${preset.title}' [$params] first=$first (sum ${first.sum()}) " +
                "second=$second (sum ${second.sum()})",
            )
            assertTrue(second.isNotEmpty())
          }
        }
      }
    }
  }

  private companion object {
    const val TAG = "PuzzleBench"
  }
}

private fun List<PresetEntry>.leaves(): List<PresetEntry.Preset> =
  flatMap { entry ->
    when (entry) {
      is PresetEntry.Preset -> listOf(entry)
      is PresetEntry.Submenu -> entry.entries.leaves()
    }
  }
