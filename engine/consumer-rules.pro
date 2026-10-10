# The generated module loads PuzzlesModule.meta with Class.getResourceAsStream, a path relative to
# the class's own package. R8 can't see that dependency, so renaming or repackaging the class would
# leave the lookup pointing at a resource that is no longer there. Everything else in the engine is
# linked statically and R8 can trace it.
-keepnames class uk.aylett.puzzles.engine.wasm.PuzzlesModule
