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

The puzzles' compiled form is `engine/wasm/puzzles-midend.wasm`, a build
artefact checked into this repository. At build time the `:engine` module
translates it into JVM bytecode with [Chicory](https://chicory.dev/).

[AGENTS.md](AGENTS.md) explains why it is built this way, and the details
that matter to anyone changing the code: the threading model, how drawing
works, and how values cross the WASM boundary.

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

## Testing

| Command | Runs |
| --- | --- |
| `./gradlew :app:testDebugUnitTest` | JVM unit tests for the app (JUnit 5, MockK, `kotlinx-coroutines-test`) in `app/src/test` |
| `./gradlew :engine:connectedDebugAndroidTest` | Tests that need the real engine under ART, in `engine/src/androidTest`; needs a device or emulator |

## Conventions

- Kotlin throughout, `ktlint_official` style, 2-space indent, 100-column lines, no wildcard imports.
- Java 17 bytecode, toolchain pinned to Temurin 21 via `mise.toml`.
- Jetpack Compose with Material 3.
- `minSdk = 26`, `compileSdk`/`targetSdk` 37.
- Apache 2.0; licence headers are enforced by Spotless.

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
