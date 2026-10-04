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
- **No runtime permissions, ever.** Everything happens in-process; file import/export uses the
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

Note that the original 1.3MB `.wasm` is *not* shipped; the 154KB `.meta` plus the dexed bytecode
replace it.

## Threading — the single most important rule

**The WASM instance is not reentrant, and one call can block for seconds.** Therefore:

- Exactly **one dedicated engine thread** owns the `Instance`. Every call into WASM — input events,
  the timer tick, redraws, new-game generation — is serialised onto it through a queue.
- Nothing touching the engine may run on the UI thread.
- Linear memory can grow (hence the `emscripten_notify_memory_growth` import). **Never cache a
  memory view, ByteBuffer or pointer across a call into WASM.** Re-read `instance.memory()` each
  time. Getting this wrong produces sporadic corruption that only shows up on large puzzles.

## Drawing — push, not pull

The mid-end *calls us* while `midend_redraw`/`midend_force_redraw` runs; we cannot ask it to paint
into a Compose draw scope. Additionally `blitter_save` must **read back** pixels already drawn.

So: the engine thread draws into a retained off-screen `Bitmap` via an `android.graphics.Canvas`,
and the UI thread is handed a snapshot to display. A blitter is just a smaller `Bitmap` holding a
copied-out rectangle.

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
more coarsely, leaving up to `scale - 1` px unused per axis — which the existing centring in
`PuzzleCanvas` absorbs. Puzzles are never scaled to fit and never exceed the viewport, so there is
no zoom or pan gesture to support today.

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

## Persistence

Saved games are opaque byte strings from `wasmmid_serialise`; do not try to interpret them. They
carry their own game identity, which `wasmmid_identify_game` can sniff without deserialising.

**Persist which puzzle was last played by name, never by index.** `GameInfo.index` is an artefact of
upstream's `list.c` ordering and will shift the moment upstream adds a puzzle, silently reopening
the wrong game from a stale preference.

## Engine lifecycle

The shared `PuzzleEngine` (WASM instance plus its dedicated thread) is discarded and lazily rebuilt
across the app's lifetime, not just held for the process's — `PuzzlesApplication.onTrimMemory`
closes it on `TRIM_MEMORY_UI_HIDDEN`, the callback the system fires once every activity has
stopped and nothing of ours is visible. This is safe only because every `GameViewModel` persists
its session in `onPause`, which always runs before `UI_HIDDEN`: there is nothing left to save by
the time the engine goes.

A `GameViewModel` cannot just keep its `PuzzleSession` across this — the engine thread it was
serialised onto no longer exists, and re-entering it (`PuzzleSession.close()` included, which
blocks on that thread) would throw. Instead `PuzzlesApplication.engineInvalidated` tells every
open `GameViewModel` to drop its session reference outright (nothing to free — the whole `Instance`
is garbage once dereferenced) rather than close it, and `onResume` reopens a fresh session against
the current engine on demand, restoring from the save `onPause` just wrote. Reopening happens
lazily in `onResume`, not eagerly in the `engineInvalidated` collector, so a screen that never comes
back doesn't pay to rebuild the WASM instance just to immediately discard it again.

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
display name being "Rectangles". Two games added after that icon set was last regenerated upstream
— Mosaic and Palisade — have no matching file and fall back to a generic icon.

The three Undead key icons (`ic_key_ghost.xml`, `ic_key_vampire.xml`, `ic_key_zombie.xml`) are
original vector artwork, not vendored — they're redrawn from the shapes and colours visible in the
upstream montage rather than copied from any Android port, to sidestep the ambiguous licensing note
on the one prior Android port's own icon set ("does not apply to the additional graphics
distributed in the Google Play Store version").

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
