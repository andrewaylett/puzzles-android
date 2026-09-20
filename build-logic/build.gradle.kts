plugins {
  `kotlin-dsl`
}

kotlin {
  jvmToolchain(21)
}

dependencies {
  implementation(libs.chicory.build.time.compiler)
  implementation(libs.chicory.wasm)
  implementation(libs.javaparser.core)
}

gradlePlugin {
  plugins {
    create("chicoryCompile") {
      id = "uk.aylett.puzzles.chicory"
      implementationClass = "uk.aylett.puzzles.build.ChicoryCompilePlugin"
    }
  }
}
