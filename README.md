# Puzzles

An Android front end for [Simon Tatham's Portable Puzzle
Collection](https://www.chiark.greenend.org.uk/~sgtatham/puzzles/) — the
collection of 40-odd small logic puzzles (Solo, Mines, Net, Loopy, and so
on) that Simon Tatham has maintained since 2004. All credit for the puzzles
themselves, their generation algorithms and their rules belongs to him and
the collection's other contributors; see [NOTICE](NOTICE) for the full
attribution and licence text.

## What this is

This repository is a **front end only**. It contains no puzzle logic —
no generation, no move validation, no undo/redo, no serialisation. All of
that lives in the upstream C code, compiled to WebAssembly and executed
on-device. This app's job is to give that engine a window, forward input
events to it, and paint what it asks us to paint.

### How it works

Upstream splits every puzzle into a *back end* (the individual game's
rules) and a *mid-end* (the generic machinery shared by all of them —
game selection, undo/redo, serialisation, timers). The mid-end exposes a
WASM-friendly API (`wasmmidend.c` in the upstream tree) that we consume
directly, without the NDK and without Emscripten's JavaScript glue.

The compiled result — the mid-end and all forty puzzle back ends in one
module — is `engine/wasm/puzzles-midend.wasm`, checked into
this repository as a build artefact (~1.3MB). It is **not** executed as
WASM at runtime, though: Android cannot load JVM bytecode it did not dex,
so at build time the `:engine` module uses
[Chicory](https://chicory.dev/)'s build-time compiler to translate the
WASM module into ordinary JVM bytecode ahead of time, which is then dexed
like any other class. Chicory's runtime interpreter is available too, but
measured on a desktop it takes roughly 8x longer than the compiled path
for a representative puzzle — too slow for a phone, so it isn't used here.

See [AGENTS.md](AGENTS.md) for the details that matter to anyone changing
this code: the threading model (the WASM instance is not reentrant), how
drawing works (the mid-end pushes paint calls to us, rather than us
pulling from it), and how values are marshalled across the WASM boundary.

## Building the app

Requirements:

- Java 21 (Temurin), managed via [mise](https://mise.jdx.dev/) — see
  `mise.toml`.
- An Android SDK with `compileSdk`/`targetSdk` 37 installed.

```sh
./gradlew :app:assembleDebug
```

`./gradlew build` runs the full check, including Spotless formatting
checks and tests.

## Regenerating the WASM blob

`engine/wasm/puzzles-midend.wasm` is built from the upstream Puzzles
source tree, not from anything in this repository. To regenerate it:

1. Check out the upstream Puzzles source.
2. Configure with CMake using an Emscripten toolchain, passing `PUZZLES_WASM_MIDEND=ON`.
3. Copy the resulting `puzzles-midend.wasm` output into `engine/wasm/puzzles-midend.wasm`.

For example:

```sh
emcmake cmake -B build -DPUZZLES_WASM_MIDEND=ON
cmake --build build
```

See `wasm-midend.md` in the upstream tree for what the module exports, and
[AGENTS.md](AGENTS.md) for how this repository consumes it.

## Licence

This app's own code is licensed under the Apache License, Version 2.0 —
see [LICENSE](LICENSE). It ships a compiled build of the MIT-licensed
Portable Puzzle Collection; see [NOTICE](NOTICE) for that licence's full
text and attribution.
