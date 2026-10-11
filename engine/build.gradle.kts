plugins {
  alias(libs.plugins.android.library)
  id("uk.aylett.puzzles.chicory")
}

android {
  namespace = "uk.aylett.puzzles.engine"
  compileSdk = 37

  testOptions {
    // `android.util.Log` is only called when the module writes to stdout or stderr.
    unitTests.isReturnDefaultValues = true
  }

  defaultConfig {
    minSdk = 26
    consumerProguardFiles("consumer-rules.pro")
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

// The loader class Chicory generates alongside the compiled bytecode is plain source, so it
// compiles here rather than arriving in the jar with the rest.
androidComponents {
  onVariants { variant ->
    variant.sources.java?.addGeneratedSourceDirectory(
      tasks.named("chicoryCompile", uk.aylett.puzzles.build.ChicoryCompileTask::class.java),
      uk.aylett.puzzles.build.ChicoryCompileTask::sourcesDirectory,
    )
  }
}

chicory {
  wasmFile.set(layout.projectDirectory.file("wasm/puzzles-midend.wasm"))
  moduleName.set("uk.aylett.puzzles.engine.wasm.PuzzlesModule")
  moduleInterface.set("uk.aylett.puzzles.engine.wasm.Puzzles")
}

dependencies {
  api(libs.chicory.runtime)
  api(libs.chicory.wasm)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.androidx.core.ktx)

  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)

  // The engine can only really be exercised on a device: it needs a dexed build of the translated
  // WASM, and the drawing backend needs a real android.graphics.Canvas.
  androidTestImplementation(libs.androidx.test.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
}
