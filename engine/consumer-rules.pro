# The WASM engine is reached reflectively in places and consists of machine-generated classes that
# R8 has no way to reason about, so keep it whole. Shrinking it would save little in any case: the
# bulk is one large generated dispatch class that is entirely live whenever any puzzle is played.
-keep class uk.aylett.puzzles.engine.wasm.** { *; }
-keep class com.dylibso.chicory.** { *; }
-keepclassmembers class com.dylibso.chicory.** { *; }

# PuzzlesModule.meta is loaded as a classpath resource by the generated loader.
-keepattributes *Annotation*
