# Puzzles — an Android front end for Simon Tatham's Portable Puzzle Collection

This app is a **front end only**. All puzzle logic — generation, move validation, undo/redo,
serialisation, even the decision about what size to draw at — lives in the upstream C code,
compiled to WebAssembly and executed on-device by a pure-Java WASM runtime.

## Why this shape

Upstream splits every puzzle into a *back end* (the game rules) and a *mid-end* (the generic
machinery around them), leaving the front end to supply a window, input events and a drawing
surface. `../../native/puzzles/wasmmidend.c` exposes that mid-end API directly as WASM exports, so
we can consume it without writing any C, without the NDK, and without Emscripten's JS glue. See
`../../native/puzzles/wasm-midend.md` for the module's own documentation and
<https://www.chiark.greenend.org.uk/~sgtatham/puzzles/devel/midend.html> for the mid-end proper.

Consequences worth internalising:

- **We do not reimplement any game.** If a puzzle behaves oddly, the bug is nearly always in how we
  translate input, size the canvas, or service a drawing callback — not in the puzzle.
- **One WASM module covers all 40 puzzles** (`wasmmid_game_count`/`wasmmid_game_at`), so there is a
  single blob and a single engine, not forty.
- **No runtime permissions, ever** (and no `INTERNET`; the only install-time ones are WorkManager's,
  see *Pre-generated games*). Everything happens in-process; file import/export uses the
  Storage Access Framework, which grants access per-document without a permission.

## Build pipeline

`engine/wasm/puzzles-midend.wasm` is a checked-in build artefact from the CMake `PUZZLES_WASM_MIDEND`
configuration. At build time `build-logic`'s `uk.aylett.puzzles.chicory` plugin translates it into
JVM bytecode via Chicory's build-time compiler, producing three things:

| Output | Fate |
| --- | --- |
| `build/chicory/classes` | Already bytecode — jarred and put on the compile classpath, then dexed |
| `build/chicory/sources` | A generated loader class; compiled as ordinary Java source |
| `build/chicory/resources` | `PuzzlesModule.meta`, a stripped module carrying types, memory layout and data segments |

**Build-time compilation is not an optimisation, it is the only option.** Android cannot load JVM
bytecode it did not dex, so Chicory's runtime compiler is unusable here; and interpreting is far too
slow. Measured figures for generating a Solo "3x3 Unreasonable", the worst case in the collection:

| Configuration | Time |
| --- | --- |
| Desktop JVM, interpreted | ~16.6s |
| Desktop JVM, compiled | ~2.1s |
| Emulated ART (x86_64, software GPU), compiled | ~20s |

The emulator is far slower than real hardware, but the shape of the problem is clear: **generation
can take many seconds and must never block the UI thread**, and the user needs to see progress
while it happens. Most puzzles are far quicker — Galaxies "15x15 Unreasonable" generates in ~2.7s
on the same emulator, and a redraw is a few milliseconds.

This path is verified end to end: the dexed module instantiates and runs under ART, producing game
IDs identical to the desktop JVM.

Note that the original ~0.9MB (`-Oz`) `.wasm` is *not* shipped; the 154KB `.meta` plus the
dexed bytecode replace it.

## Threading — the single most important rule

**The WASM instance is not reentrant, and one call can block for seconds.** Therefore:

- Exactly **one dedicated engine thread** owns the `Instance`. Every call into WASM — input events,
  the timer tick, redraws, new-game generation — is serialised onto it through a queue.
- Nothing touching the engine may run on the UI thread.
- Linear memory can grow (hence the `emscripten_notify_memory_growth` import). **Never cache a
  view, ByteBuffer or raw array derived from memory across a call into WASM.** The `Memory` object
  itself is stable for the life of the instance (growth happens inside it), so hold that, and read
  and write through it each time. Getting this wrong produces sporadic corruption that only shows up on large puzzles.

## Drawing — push, not pull

The mid-end *calls us* while `midend_redraw`/`midend_force_redraw` runs; we cannot ask it to paint
into a Compose draw scope. Additionally `blitter_save` must **read back** pixels already drawn.

So: the engine thread draws into a retained off-screen `Bitmap` via an `android.graphics.Canvas`,
and at the end of each batch (`endDraw`) copies it onto a `SurfaceView`'s `Surface` itself
(`SurfacePresenter`: `lockCanvas`, draw, `unlockCanvasAndPost`). A blitter is just a smaller
`Bitmap` holding a copied-out rectangle.

**The bitmap never leaves the engine thread, and frames do not go through Compose.** An earlier
design published recycled bitmap copies through a `StateFlow` and drew them with `drawImage`. That
raced: Compose records the bitmap and its render thread reads it some time later, with nothing to
tell us when it had finished, so the engine could overwrite a buffer still waiting to be uploaded.
The symptom was intermittent — a change from user input missing or half-drawn until the next tap.
Presenting through the surface's buffer queue gives real double buffering from the system, and
removes the sharing rather than trying to schedule around it. `PuzzleFrame` therefore carries only
metadata (status, undo state, the drawn size for touch mapping); equal frames are dropped by
`StateFlow`, so a tick that changes none of that costs no recomposition.

Surface lifetime is the delicate part. `surfaceDestroyed` runs on the main thread and promises the
surface is unused once it returns, so `SurfacePresenter` guards just the attach/detach swap and the
lock-draw-post span with its own small lock — never `engineThread.run`, which could be mid-way
through a multi-second generation. Attaching a surface (`surfaceChanged`) takes no lock at all:
`unlockCanvasAndPost` can block in `BLASTBufferQueue` until the main thread finishes a layout pass, and
`surfaceChanged` runs *inside* that pass, so waiting for the lock there deadlocked into an ANR on
resume. `GameViewModel` holds the surface and re-attaches it to a
replacement session after the engine is recycled. A new or resized surface starts empty, so
attaching asks the engine thread to re-present the retained bitmap (asynchronously, since it owns
it); until it is free the surface is blank. The surface is always repainted whole, with the theme
background around the puzzle, because which buffer the queue returns is not ours to choose and a
surface's untouched pixels show as black. The `SurfaceView` must never be given a fixed size: its
buffer then equals the view size, which is what lets the centring in `SurfacePresenter` and the
touch mapping in `PuzzleCanvas` agree to the pixel.

Coordinates are physical pixels *divided by an integer scale*, not raw physical pixels. Several
back ends draw fixed-pixel-sized UI elements that don't scale with tile size — untangle's
grab-handle `CIRCLE_RADIUS`/`DRAG_THRESHOLD`, for instance, is a compile-time constant, not derived
from tile size — so sizing the mid-end directly in physical pixels leaves those elements a fixed
*physical* size that shrinks as display density rises, to the point of being unreliable to tap on
a high-density phone. Instead `PuzzleSession` sizes the mid-end in a `floor(density)`-reduced
space (`scale = max(1, floor(density))`), and `BitmapDrawingSink` scales every draw call back up by
that factor via a `Canvas.scale` transform, so those fixed-pixel elements grow with density the way
the rest of the puzzle already does. The scale must be an integer: back ends tile adjacent
rectangles edge-to-edge in integer coordinates, and a fractional scale lands those edges mid-pixel,
seaming every cell boundary and disagreeing on blitter save/load roundtrips (see the four games
that keep blitters, below) — so the fractional remainder of `density` is simply dropped rather than
reconciled. `device_pixel_ratio` is still passed through to `midend_size` for the record, but only
matters on the `userSize = false` path (`convert_tilesize` in `midend.c`), which this front end
never takes.

Blitters store and replay pixels 1:1 in physical pixels: their own bitmaps are allocated at
`scale`× the size the mid-end asks for, and save/load convert the mid-end's coordinates to physical
pixels explicitly rather than relying on the scaled `Canvas` — resampling a saved rectangle twice
(once implicitly on save, again on load) would visibly soften it.

The rendered surface always fits the viewport the UI offered, by construction: the mid-end is asked
to fit `viewport / scale`, and what it returns times `scale` is at most `viewport`. It can render a
few pixels smaller than the viewport it was given — the reduced space quantises tile sizes `scale`×
more coarsely, leaving up to `scale - 1` px unused per axis — which the centring in
`SurfacePresenter` absorbs. Puzzles are never scaled to fit and never exceed the viewport, so there is
no zoom or pan gesture to support today.

### Drag coalescing

Pointer *drags* are position reports, not commands, so `PuzzleSession.pointer` keeps only the
newest unprocessed one rather than queueing each onto the engine thread. Queueing them all meant
that whenever a redraw or tick ran long, a backlog of stale positions built up and each was then
replayed with its own redraw, so dragged pieces trailed the finger by a growing margin. Presses and
releases are never dropped, and any pending drag is taken at call time and replayed in front of
them, so a later drag cannot overtake a press that was queued first. Animation timing needs no
equivalent: `tick()` measures a fresh delta from the clock each call and the pacing loop awaits
each tick, so a missed frame just yields a larger delta rather than a queue.

### Animation pacing

`PuzzleSession.tick()` calls `midend_timer` and redraws once; how often to call it while
`PuzzleSession.animating` is true is decided by `GameViewModel`'s pacing loop, not the engine —
that decision needs `Choreographer`/vsync timing, which only makes sense on the UI thread. The loop
calls `tick()` once per display frame (`awaitFrame()`) as long as `BitmapDrawingSink` reports
something actually painted (`drawUpdate` having fired since the last check), and backs off to a
slow poll once a run of ticks hasn't, sized in time rather than frame count so the threshold means
the same thing on a 60Hz and a 120Hz display. That same dirty flag is sticky — set on any
`drawUpdate`, cleared only when read — so a redraw triggered by user input outside the loop (a tap,
handled inline via `PuzzleSession.pointer`) is picked up on the loop's next iteration and snaps it
back to full rate, without a separate wake-up path.

## The WASM interface

28 imports must all be supplied or the module will not instantiate. `wasm-midend.md` documents 22 of
them; the disassembly is authoritative and adds six more:

- `env.emscripten_notify_memory_growth(i32)` — undocumented but **required**; a no-op body is
  correct.
- Five `wasi_snapshot_preview1` shims: `environ_get`, `environ_sizes_get`, `fd_close`, `fd_seek`,
  `fd_write`. Hand-write them rather than pulling in a WASI implementation. `fd_write` should
  forward to logcat so diagnostics are not silently swallowed.

The build generates `Puzzles_ModuleExports` and `Puzzles_ModuleImports` (plus `Puzzles_Env` and
`Puzzles_WasiSnapshotPreview1`) from the module, so `WasmMidend` and `WasmImports` implement or wrap
those rather than hand-writing signatures, and a change to the module's interface breaks the build
instead of failing at instantiation. The generated imports are not given the `Instance`, which
cannot exist before them, so `WasmImports.memory` is bound straight after instantiation, before
`_initialize` is run (the builder does not run it, and its WASI calls touch memory).

`wasmmid_debug` is **not** imported — this blob was built without `DEBUGGING`. Do not supply it.

### Marshalling

All values are the 32-bit C ABI: pointers, `int`, `bool` and `float` pass directly; strings are
NUL-terminated bytes in linear memory. Anything the module allocates (`midend *`, `char *`,
`config_item *`) is **owned by the module** and must be released through the matching export
(`free`, `free_cfg`, `free_keys`) — never a host-side allocator.

Several exports return values through `int *` out-parameters (`midend_size`, `midend_colours`,
`wasmmid_serialise`, `midend_get_cursor_location`). Allocate a small scratch buffer inside WASM with
the `malloc` export, pass pointers into it, read the results back, and `free` it.

Chicory passes everything as `long[]`; a `void` export returns `null`, not an empty array. `f64`
arguments are raw bits (`java.lang.Double.doubleToRawLongBits`), `f32` likewise via
`Float.floatToRawIntBits`.

### Constants

Take these from `../../native/puzzles/puzzles.h` — never from memory, as a transposed value produces
puzzles that respond to the wrong gestures and is miserable to debug.

Buttons start at `LEFT_BUTTON = 0x0200` and run consecutively: `LEFT_BUTTON`, `MIDDLE_BUTTON`,
`RIGHT_BUTTON`, `LEFT_DRAG`, `MIDDLE_DRAG`, `RIGHT_DRAG`, `LEFT_RELEASE`, `MIDDLE_RELEASE`,
`RIGHT_RELEASE`, `CURSOR_UP`, `CURSOR_DOWN`, `CURSOR_LEFT`, `CURSOR_RIGHT`, `CURSOR_SELECT`,
`CURSOR_SELECT2`. Modifiers: `MOD_STYLUS = 0x0800`, `MOD_CTRL = 0x1000`, `MOD_SHFT = 0x2000`,
`MOD_NUM_KEYPAD = 0x4000`, `MOD_MASK = 0x7800`.

Text: `FONT_FIXED = 0`, `FONT_VARIABLE = 1`; `ALIGN_VNORMAL = 0x000`, `ALIGN_VCENTRE = 0x100`,
`ALIGN_HLEFT = 0x000`, `ALIGN_HCENTRE = 0x001`, `ALIGN_HRIGHT = 0x002`.

Config: `C_STRING = 0`, `C_CHOICES = 1`, `C_BOOLEAN = 2`, `C_END = 3`. Config sections:
`CFG_SETTINGS = 0`, `CFG_SEED = 1`, `CFG_DESC = 2`, `CFG_PREFS = 3`.

`midend_status` returns 0 while play continues, +1 on a win, −1 on a loss.

### Touch input

The mid-end expects a mouse. The established mapping is: tap → `LEFT_BUTTON` then `LEFT_RELEASE`;
long-press → `RIGHT_BUTTON` then `RIGHT_RELEASE`; drag → `LEFT_DRAG` between them. Games that set
`REQUIRE_RBUTTON` need the long-press path to be discoverable.

Pointer events also carry `MOD_STYLUS` by default (a user preference, "Taps cycle all states").
Six games set `STYLUS_SUPPORT` — Loopy, Net, Light Up, Palisade, Pattern and Tents —
and with the modifier a single button cycles every cell state (Loopy: blank → line → crossed → blank) where without it
left-click only toggles two and the third needs a right-click. A finger has no second button, so
the modifier is what makes those states reachable by tapping. `midend.c` strips it for every other
game, so it is safe to send unconditionally. It is *not* derived from touch-versus-stylus input
type: `STYLUS_BASED` is a compile-time flag in the C, so the mid-end will never add it for us.

## Traps the mid-end sets for a new front end

Every one of these cost real debugging time; they are recorded so they only cost it once.

**A mid-end with no game asserts rather than erroring.** `midend_new` does not generate anything,
and until `midend_new_game` has run, `me->desc` is NULL: asking for the game id, the status, or a
redraw trips an `assert` inside the C. `PuzzleSession` therefore tracks whether a game exists and
refuses to draw or report before then. The session is deliberately left gameless at open so a saved
game can be restored without first paying to generate a throwaway — but that means *something* must
generate one, and on a first run that is the UI's job.

**An assert inside WASM surfaces as `TrapException: Trapped on unreachable instruction`**, with a
Kotlin stack that stops at the export boundary and tells you nothing about the cause. The assertion
text goes to stderr, which is why the `fd_write` shim forwards to logcat — `Assertion failed:
me->desc (midend.c: midend_get_game_id: 1981)` is the diagnosis, and without that forwarding there
is nothing to go on.

**`midend_game_id` and `midend_set_config` only record; they do not generate.** Both must be
followed by `midend_new_game` (see `menu_config_event` in upstream's `gtk.c`), except for
`CFG_PREFS`, which changes only presentation. Skipping it leaves a description with no game — the
first trap, arrived at by a different route.

**Ownership of returned strings is signalled by constness, and getting it wrong corrupts the heap.**
`char *` returns (`midend_get_game_id`, `midend_get_random_seed`, `midend_text_format`,
`midend_rewrite_statusbar`, `identify_game`'s out-param) are freshly allocated and must be freed.
`const char *` returns — which is *every error message*, from `midend_solve`, `midend_game_id`,
`midend_deserialise`, `midend_set_config` and `midend_load_prefs` — are static literals in the data segment. Passing one
to `free` hands the allocator a pointer it never issued.

**Generate before sizing, and re-size whenever the parameters change.** `midend_size` finishes by
calling the back end's `set_size` on the current draw state, which `midend_new` leaves NULL until a
game exists — so sizing an empty mid-end hands the back end a NULL draw state. Upstream orders it
the other way round (`emcc.c` creates the game, *then* sets the canvas size). Only the four games
that keep blitters — **Inertia, Map, Pegs and Signpost** — assert loudly about it; the other 36 do
not notice, which is exactly why this needs a test that sweeps all forty rather than a few by hand.

Equally, sizing must be re-run when the *parameters* change, not only when the viewport does. A new
preset means a new grid and a new tile size, but it does not trigger a layout pass, so nothing
external will prompt it. Upstream calls `resize_fe()` immediately after `midend_new_game()` in
`menu_config_event`. The affected paths are preset selection, custom configuration, entering a game
ID, and restoring a save — each of which can change the dimensions.

**Do not draw before sizing.** Until `midend_size` has run, `tilesize` is 0, and back ends divide it
up without checking: Solo's pencil-mark layout asserts `pbest > 0` (`draw_number`), where most
others quietly draw nothing. A restore runs before the UI has reported a viewport, so
`applySize()` is a no-op there and an unconditional `midend_force_redraw` trapped — and the failure
handler then blamed the save and quarantined it. `PuzzleSession` therefore only redraws once
`midend_tilesize > 0`; `resize` does the first real draw.

**`midend_timer` takes the delta since the previous tick**, not the time since the timer started:
it does `me->anim_pos += tplus`, and upstream resets its timestamp after every call. Passing
cumulative time races every animation to its end and makes in-game clocks run quadratically fast.

**Size with `user_size = true`.** With it false, `midend_size` caps at the game's preferred tile
size, which is tuned for a desktop window and leaves a 5x5 grid as a postage stamp on a phone. True
makes it search for the largest tile size that fits. It returns the size it will actually draw,
which is usually a little smaller than the space offered, so allocate the surface to the returned
size and centre that — don't allocate the full viewport.

**Config text fields carry no type.** Every `C_STRING` item looks alike, so numeric inputs (width,
height…) are recognised in `ConfigDialog` from the current value, falling back to the field name.

## Portrait presets

Upstream's presets are tuned for landscape desktop windows (`10x6`), which wastes a phone held
upright. `PuzzleSession` therefore transposes them: titles via `portraitTitle`, and the actual
parameters by swapping the `Width`/`Height` settings after `applyPreset` (see `Portrait.kt`). This
is only done where exactly one integer field of each name exists, so other puzzles are untouched.
The transposed parameters no longer match any preset, so `currentPresetId` transposes them back
temporarily to recover the tick mark. Custom sizes are the user's own and are never swapped.

## On-screen keys

`KeyRow` shows whatever `midend_request_keys` returns, which is nothing for games whose back end
sets no `request_keys`. Dominosa is one: it accepts digit keys to highlight up to two numbers (its
only other route is a right-click dead-centre on a digit, which a long-press hits unreliably), so
`PuzzleSession.keyHints` synthesises `0..n` chips for it from the current settings. The highlight
state lives in the back end's `game_ui` and is not exposed, so the chips cannot show which numbers
are currently lit. Hints are re-fetched when the game id changes, because presets and custom sizes
alter the parameters they depend on.

## Persistence

Saved games are opaque byte strings from `wasmmid_serialise`; do not try to interpret them. They
carry their own game identity, which `wasmmid_identify_game` can sniff without deserialising.

**Persist which puzzle was last played by name, never by index.** `GameInfo.index` is an artefact of
upstream's `list.c` ordering and will shift the moment upstream adds a puzzle, silently reopening
the wrong game from a stale preference.

**Saves are driven by change, not by lifecycle.** Saving only in `onPause` lost moves: swiping the
app from recents gives `onPause`, `onDestroy` ~70ms later (cancelling `viewModelScope`, and the save
with it) and SIGKILL ~140ms after the pause. So `PuzzleSession.revision` counts every change that
would alter a save (a `StateFlow` of frames cannot: it deduplicates), and `GamePersister` writes
after 300ms of quiet. It runs in the *application's* scope with the write shielded from
cancellation, so a ViewModel going away cannot cancel it; `onPause` and `onCleared` flush at once,
and the flush's engine task is queued before `close()`'s, which the FIFO engine thread then honours.
What is accepted as lost on an instant kill is one debounce interval. Details worth knowing:

- Nothing is saved until any restore has settled, as an unrestored session holds no game and
  saving it would overwrite the real save.
- Identical bytes are not rewritten; a restore seeds the comparison with what it read.
- Writes are ordered by the request they came from and share a process-wide lock, as a persister
  can outlive its ViewModel and two must not share the temporary file.
- A restore marker is cleared by a save only once the game has been laid out, or the player is
  leaving: an autosave just after restoring proves serialising works, not that drawing does.
- Timed puzzles serialise their elapsed time, so "ticks never change a save" is approximately true;
  the pause flush covers it.

## Pre-generated games

Generation can take many seconds, so games are made ahead of time and kept in `PuzzlePool`
(`noBackupFilesDir/pregenerated`), and a new game is started from one when it can be.

- **Stored as saves, not game ids.** A save keeps the seed and the solver's auxiliary data; a game id
  loses the latter, and Netslide and Untangle then answer Solve with "Solution not known"
  (`WasmInstantiationTest` pins this down). Mines generates lazily on the first click, so there is
  nothing to keep ahead of time for it; it is harmless there.
- **Keyed by the settings actually in force**, as `PoolKey`: the settings section as `StoredField`s,
  by label, which is how custom types are identified too. Not by preset id (an index, and presets
  are transposed for portrait) and not by game id (varies with every game).
- **Consumed by `PuzzleSession.startNewGame(fromSource = true)`**, from New game, a preset and the
  settings section of the config dialog. Not for a game id, a seed or a description, which name a
  particular game, and not for Restart. The entry is deleted *before* it is used, so a save that traps
  the engine cannot be offered again; one the mid-end merely rejects falls through to generating.
- **Filled by `PoolWorker`, on an engine of its own.** The shared engine thread runs one thing at a
  time and a generation can take many seconds, so filling it there would queue the player's next tap
  behind a puzzle nobody asked for. That engine has no `newGameSource`, or it would take from what
  it is filling. An `EngineFailure` abandons that game for the pass and carries on on a fresh engine.
- **Targets**: every preset (all submenus), the game's default parameters and the stored custom
  types; 20 per kind for favourites, 10 for the rest (`PoolPolicy`). Favourites are done first.
  `PuzzlePool.retain` removes kinds that are no longer wanted and trims over-full ones, per game and
  only once that game was enumerated in full.
- **When**: WorkManager, constrained to charging (periodically, and whenever favourites or custom
  types change). When a pool is emptied by a game being taken, `PoolPolicy.refillAfterEmptied`
  decides then: charging fills everything, on battery makes exactly one replacement, power saving
  mode does nothing. A take that finds the pool empty (a fresh install) generates synchronously as
  before and also requests a fill, which still waits for the charger. WorkManager was chosen over `JobScheduler` to survive reboots, at the price of
  its merged-manifest permissions: `WAKE_LOCK` and `RECEIVE_BOOT_COMPLETED` remain, while
  `ACCESS_NETWORK_STATE` and `FOREGROUND_SERVICE` are removed with `tools:node="remove"`, because the
  goal is a permission list that cannot be read as tracking or reporting back.
- **Custom types clean up after themselves**: `CustomPresetStore` reports each preset that falls off
  the three-entry list and `PuzzlePool.discard` removes its games at once; `retain` is only the
  backstop.
- Excluded from backup: it is bulk, regenerable and says nothing about the player.

## Custom types

Applying the Custom dialog's settings stores them as a `CustomPreset` (`CustomPresetStore`), newest
first, three per game, shown in the Type dialog above "Custom…". Picking one moves it to the front.

- **It is a typed DataStore of its own**, JSON via kotlinx.serialization, not more keys in
  `AppPreferences`: the value is a nested list that is replaced as a whole, which flat keys can
  only do by hiding JSON in a string. A DataStore must be the only one on its file, so the store is
  a process-wide singleton on `PuzzlesApplication`, not built per ViewModel.
- **Fields are matched by label, choices stored by label**, as for shared settings. Settings items
  have no keyword (see `readConfigFields`), so the label is the only identity there is.
- **The title is generated from the values** (`customTitle`: `9x9, Mines 10, Ensure solubility`)
  and stored as written; the mid-end only names its own presets.
- **Parameters a built-in type already describes are not stored** (`currentPresetId() != -1` after
  applying): they would only duplicate a row that is already in the list.
- A corrupt file reads as empty rather than throwing; losing three shortcuts must not stop a game
  screen opening.

## App status screen

"App status" (list menu) shows what the app holds and is doing: the engine, the generation jobs,
pool and save sizes, recovery leftovers and, per puzzle, its save, pool and custom types. It exists
because most of that state is invisible by design (a pool fills overnight, a restore marker only
matters at launch), so "why was that slow?" had no answer short of the file system.

- **It must only look.** `engines.engine()` builds the WASM instance if it was trimmed, so asking it
  for the game list would change what the screen reports. `PuzzlesApplication.engineStatus` is
  published for this, and `knownGameNames` outlives the engine so the screen can name puzzles
  while it is gone. Before any engine exists in the process, names come from whatever the stores
  mention.
- **The pool records its key beside its games** (`key.json` in each kind's directory). Directory
  names are a hash and a sanitised title, which cannot be turned back into a game and settings. A
  pool from before this has no file and shows as "unknown kind"; it is regenerable, so there is no
  migration.
- **Disk counts are polled** (every few seconds while the screen shows) as they have no flow;
  WorkManager's state is live. `PoolWorker` reports its game and count as progress purely for this
  screen. The periodic job is found by unique name, not tag, as it is enqueued with `KEEP` and an
  existing install never sees a changed request.

## Dark mode and game colours

The mid-end hands us each game's palette as anonymous indices (`midend_colours`); nothing says which
entry is ink and which is paper. Upstream palettes assume a light background, so in a dark theme
`PuzzleSession.applyPalette` asks for the palette twice — once against the old light default colour
(`wasmmid_default_colour`, now `EngineHooks.defaultColour`) and once against the theme's — and
`PaletteTransform` uses the difference to decide, per entry, what to do:

- **Unchanged between the runs** is a fixed colour the game picked for a light background: adapt it.
- **Bevels** (highlight/lowlight from `game_mkhighlight`, recognisable as sitting `sqrt(3)/6` from
  the background or clamped to pure black/white) and the background itself are taken as themed.
- **Anything else that changed** is the game scaling or mixing the background ("60% of paper" for a
  text colour). On a dark background that ratio goes to black and the text vanishes (Solo's
  user-entered digits, Unequal's pencil marks), so these are adapted like fixed colours.

"Adapt" flips OKLab lightness for neutral colours and keeps the lightness of saturated ones, which
would otherwise turn palettes that differ mainly in hue (Flood, Map, Same Game) into murky darks.

The theme's colour reaches the engine as a `ColourScheme` set through `GameViewModel`, which keeps
the latest so that a replacement session opens already themed. The default colour is the theme's
`surfaceContainerHigh`, not `surface`: games derive bevels from it, and on a near-black base the
lowlight has nowhere to go, leaving Mines' and Flip's tiles indistinguishable from their grid.

Checked by eye on all 40 games on a Pixel 6 Pro; the hand-written index heuristics in
`PaletteTransform` are the part most likely to need a per-game override if a puzzle is added.

## Settings

Every setting lives in one place, the Settings screen; a puzzle's own Preferences dialog is the
subset of those that it has. They are two views of the same stored values.

The mid-end's preferences are per game (an opaque blob each), but many are really the same setting
in several games — "keep highlight after a pencil mark" is in five — and users expect to set those
once. So preferences are stored by *identity* in `AppPreferences.sharedSettings` and overlaid onto
every session when it opens (`data/SharedSettings.kt`). The per-game blob remains, but a stored
value always wins over it.

- **Identity is keyword + label + choice labels, never keyword alone.** Upstream reuses
  `flash-type`, `appearance` and the mouse-button keywords for options that mean different things in
  different games; merging by keyword would apply one game's choice to another's unrelated list.
  Choices are stored by label, not index, so upstream reordering cannot select a different option.
- **A `GameViewModel` outlives a trip to Settings** (it is activity-scoped, and navigation here is
  hand-rolled), and so does its session. Without a live overlay the session keeps stale values and
  `persist()` writes them straight back over what Settings just changed. Hence the
  `sharedSettings` collector in `GameViewModel`, and `applyConfig` writing PREFS back to the store.
- **The catalogue is discovered, not hard-coded:** `SettingsViewModel` opens every puzzle without
  generating a game and reads its PREFS. Anything not yet stored is then stored with the value just
  resolved — puzzles that disagreed (they were configured separately before pooling) take the first
  puzzle's value, so Settings shows one value and every puzzle then agrees with it.
- Range's and Slant's "mouse button order" sit under touch controls, not their puzzle: they compose
  with the app's tap/hold swap, so enabling both swaps the buttons twice.
- "Taps cycle all states" is only offered in a puzzle that sets `STYLUS_SUPPORT`
  (`GameInfo.supportsStylus`, an allowlist for the same reason as `canFillPencilMarks`).

## Several sessions, one set of hooks

A `GameViewModel` (and its session) is kept when the player goes back to the list, so many sessions
can be alive on the one engine, while the WASM imports call into a single `EngineHooks`. Binding
the hooks once at session creation sent every session's drawing and timer callbacks to the newest:
a Mines clock ticked into the next puzzle's status bar and a stale Netslide frame was painted over
whatever opened afterwards. Each call therefore binds the hooks to its own session first, and a
game's screen pauses its ViewModel when it leaves composition so that hidden sessions stop ticking.

## Engine failures

**A trap is an exception, not an error return, and it condemns the whole engine.** A failed
`assert` in the C ends as a `TrapException` thrown out of Chicory; left alone it kills the process
from whichever coroutine was calling. Worse, if the failure was in restoring a save, the app
reopens the last-played game on launch and restores it again — an unrecoverable crash loop that
only clearing storage ended. So:

- `EngineThread.run` is the one choke point and wraps anything thrown as `EngineFailure`, carrying
  the tail of the module's stderr (the assertion text; the trap itself says nothing).
- After a failure the instance's heap and the mid-end that trapped are suspect, so the engine is
  discarded (`EngineProvider.discardEngine`) and a new session opened on a fresh one. Retrying on
  the same engine is not safe. This deliberately does *not* go through `engineInvalidated`, which
  would race the replacement session being opened.
- Every route from `GameViewModel` into the engine is wrapped in `guarded`. On failure the game
  reloads from its last save — unless a restore was still unconfirmed, in which case the save is
  blamed, moved aside (never deleted: it is the only reproduction) and a fresh game generated.
  `RecoveryStore` keeps a few reports and quarantined saves, and the player is offered the report
  to share. There is no network reporting; the app has no network access.
- A restore marker is written before restoring and cleared once the restored game has been laid out
  or saved. If it is still there at launch, the last attempt died in a way no handler could catch
  (out of memory, process killed), and the save is quarantined without being tried.
- Failures that are not an `EngineFailure` (a bug in our Kotlin) are deliberately not swallowed.

## Engine lifecycle

The shared `PuzzleEngine` (WASM instance plus its dedicated thread) is discarded and lazily rebuilt
across the app's lifetime, not just held for the process's — `PuzzlesApplication.onTrimMemory`
closes it on `TRIM_MEMORY_UI_HIDDEN`, the callback the system fires once every activity has
stopped and nothing of ours is visible. This is safe only because every `GameViewModel` flushes
its save in `onPause`, which always runs before `UI_HIDDEN`, and saves every change before then as
it happens (see Persistence): there is nothing left to save by the time the engine goes.

A `GameViewModel` cannot just keep its `PuzzleSession` across this — the engine thread it was
serialised onto no longer exists, and re-entering it (`PuzzleSession.close()` included, which
blocks on that thread) would throw. Instead `PuzzlesApplication.engineInvalidated` tells every
open `GameViewModel` to drop its session reference outright (nothing to free — the whole `Instance`
is garbage once dereferenced) rather than close it, and `onResume` reopens a fresh session against
the current engine on demand, restoring from the save `onPause` just flushed. Reopening happens
lazily in `onResume`, not eagerly in the `engineInvalidated` collector, so a screen that never comes
back doesn't pay to rebuild the WASM instance just to immediately discard it again.

`PuzzleSession.close()` is therefore a no-op on a session whose engine is already closed, because
`GameViewModel.onCleared` can run after `UI_HIDDEN` and before any `engineInvalidated` is handled;
hopping onto the dead thread threw from `onDestroy` and killed the process. For the same reason a
save that reaches a closed engine is *cancelled*, not failed, and `GamePersister` logs it, since it
means moves since the last save are gone. Moves made since the last save are still lost if the engine itself fails mid-play.

## Vendored icon artwork

`app/src/main/res/drawable-nodpi/ic_game_*.png` and `puzzles_icon_montage.png` are the upstream
collection's own artwork — per-puzzle icons from its `icons/*-96d24@2x.png` and the app montage
from its `Icon@2x.png`/`iTunesArtwork.png` — copied unmodified from
<https://github.com/x-sheep/puzzles> (an actively-synced mirror of the `git.tartarus.org` source;
GitHub has no first-party mirror). Both are covered by the project's own MIT-style `LICENCE`, same
as `engine/wasm/puzzles-midend.wasm`.

They map from `GameInfo.name` (the mid-end's display name) via `GameIcons.kt`, keyed by name
because the mid-end exposes no internal slug — most names differ from their icon filename only in
case and spacing, except `rect`, whose icon keeps the historical short name despite the game's
display name being "Rectangles". Two games added after that icon set was last regenerated upstream — Mosaic and Palisade — have no
upstream PNG, and the one prior Android port does have art for them but under the ambiguous licence
described below, so we render them ourselves. Upstream's recipe (`icons/icons.cmake`) is: load
`icons/<name>.sav` at the game's preferred size, crop a hand-picked rectangle, then `square.pl`
trims the background, pads to a square and resamples. We ran that with our own engine (the GTK front
end and ImageMagick it needs are not available), using the `.sav` files and crop rectangles
(Mosaic `97x97+142+78` of 288x288, Palisade `192x192+0+0` of 292x292) from the upstream tree. The
screenshot sizes matched upstream's exactly, which is the check its `crop.sh` makes. They differ
visibly from the vendored icons in two ways: those carry a per-game tinted background and bolder
text, ours the upstream neutral grey and regular text. Regenerate them if upstream ships its own. The generator is `IconRenderer` in
`engine/src/androidTest` (run instructions in its KDoc); it also renders Singles and Range, which
have vendored icons, as a calibration against the real thing.
Beware names that differ from the
game's own: tracks.c is displayed as "Train Tracks", and a mismatched key fails silently as "no icon".

The launcher icon uses the montage directly as the adaptive foreground, full-bleed: it is a square
with its own tile edges, so shrinking it into the safe zone left it floating in the grey background.
Launchers mask away the outer third, so only the middle of the montage is ever visible.

The three Undead key icons (`ic_key_ghost.xml`, `ic_key_vampire.xml`, `ic_key_zombie.xml`) are
original vector artwork, not vendored — they're redrawn from the shapes and colours visible in the
upstream montage rather than copied from any Android port, to sidestep the ambiguous licensing note
on the one prior Android port's own icon set ("does not apply to the additional graphics
distributed in the Google Play Store version").

## Navigation and the puzzle list

Navigation is hand-rolled in `MainActivity` (list, with a game or the Settings/About screens on
top). `GameListScreen` leaves composition while a game is open, so its `LazyListState` is hoisted
into `PuzzlesApp`; remembered inside the screen, the user would return to the top every time.
Favourites (long-press a row) are stored by game name, like the last-played game, and sort to the
top. "Settings" holds the two app-wide switches that also appear inline in the game screen; it is
named so to keep it distinct from the per-puzzle "Preferences" dialog, which is the mid-end's.

## Licences screen

The About screen's licence list comes from AboutLibraries, whose Gradle plugin collects Maven
dependencies at build time. The puzzle collection is compiled into the WASM blob, where no
dependency graph can see it, so it is declared by hand in `config/aboutlibraries` (the MIT text
there is copied from `NOTICE`; keep the two in step). The acknowledgement above the list is ours.

## Testing

JVM unit tests live in `app/src/test` (JUnit 5, MockK, `kotlinx-coroutines-test`); run them with
`./gradlew :app:testDebugUnitTest`. ViewModels take their collaborators by constructor
(`EngineProvider`, the stores) precisely so they can be tested without the WASM module or an
Android `Context`; sessions and the engine are final classes, so tests mock them with MockK rather
than hiding them behind interfaces that would only exist for the tests. Anything that needs the
real engine belongs in `engine/src/androidTest`.

## Conventions

- Kotlin throughout, `ktlint_official` style, 2-space indent, 100-column lines, no wildcard imports.
- Java 17 bytecode, toolchain pinned to Temurin 21 via `mise.toml` (AGP dislikes newer).
- Jetpack Compose with Material 3.
- `minSdk = 26`, `compileSdk`/`targetSdk` 37.
- Apache 2.0; headers enforced by Spotless.
- Gradle needs network access, which the agent sandbox denies — run it with the sandbox disabled.

## Documentation

Explain **why**, not how. If you don't know why, ask rather than guess.
