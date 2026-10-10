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
package uk.aylett.puzzles.ui.list

import androidx.annotation.DrawableRes
import uk.aylett.puzzles.R

/**
 * Maps [uk.aylett.puzzles.engine.GameInfo.name] (the mid-end's display name, e.g. "Same Game") to
 * the upstream project's own icon for that puzzle, vendored from its `icons` directory (files
 * named `<slug>-96d24@2x.png`).
 *
 * Keyed by display name rather than an internal slug because the mid-end exposes no slug — see
 * `wasmmid_game_at`/`gameName` in `WasmMidend.kt`. Two games — Mosaic and Palisade — were added to
 * the collection after that icon set was last regenerated upstream and so have no entry here;
 * [iconFor] returns null for them and the list falls back to no icon, per "icons where available".
 */
private val gameIconsByName: Map<String, Int> =
  mapOf(
    "Black Box" to R.drawable.ic_game_blackbox,
    "Bridges" to R.drawable.ic_game_bridges,
    "Cube" to R.drawable.ic_game_cube,
    "Dominosa" to R.drawable.ic_game_dominosa,
    "Fifteen" to R.drawable.ic_game_fifteen,
    "Filling" to R.drawable.ic_game_filling,
    "Flip" to R.drawable.ic_game_flip,
    "Flood" to R.drawable.ic_game_flood,
    "Galaxies" to R.drawable.ic_game_galaxies,
    "Guess" to R.drawable.ic_game_guess,
    "Inertia" to R.drawable.ic_game_inertia,
    "Keen" to R.drawable.ic_game_keen,
    "Light Up" to R.drawable.ic_game_lightup,
    "Loopy" to R.drawable.ic_game_loopy,
    "Magnets" to R.drawable.ic_game_magnets,
    "Map" to R.drawable.ic_game_map,
    "Mines" to R.drawable.ic_game_mines,
    "Net" to R.drawable.ic_game_net,
    "Netslide" to R.drawable.ic_game_netslide,
    "Pattern" to R.drawable.ic_game_pattern,
    "Pearl" to R.drawable.ic_game_pearl,
    "Pegs" to R.drawable.ic_game_pegs,
    "Range" to R.drawable.ic_game_range,
    // rect.c's internal id is "rectangles", but its icon file is still named "rect-96d24.png".
    "Rectangles" to R.drawable.ic_game_rect,
    "Same Game" to R.drawable.ic_game_samegame,
    "Signpost" to R.drawable.ic_game_signpost,
    "Singles" to R.drawable.ic_game_singles,
    "Sixteen" to R.drawable.ic_game_sixteen,
    "Slant" to R.drawable.ic_game_slant,
    "Solo" to R.drawable.ic_game_solo,
    "Tents" to R.drawable.ic_game_tents,
    "Towers" to R.drawable.ic_game_towers,
    // tracks.c is displayed as "Train Tracks" but its icon file is plain "tracks-96d24.png".
    "Train Tracks" to R.drawable.ic_game_tracks,
    "Twiddle" to R.drawable.ic_game_twiddle,
    "Undead" to R.drawable.ic_game_undead,
    "Unequal" to R.drawable.ic_game_unequal,
    "Unruly" to R.drawable.ic_game_unruly,
    "Untangle" to R.drawable.ic_game_untangle,
  )

@DrawableRes
fun iconFor(gameName: String): Int? = gameIconsByName[gameName]
