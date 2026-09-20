plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.spotless)
}

spotless {
  kotlin {
    target("**/src/**/*.kt")
    ktlint()
      // Compose names composable functions in PascalCase, which the standard naming rule would
      // otherwise flag on every screen and dialog. Set here rather than in .editorconfig because
      // Spotless does not pick the project's .editorconfig up for this step.
      .editorConfigOverride(
        mapOf("ktlint_function_naming_ignore_when_annotated_with" to "Composable"),
      )
    licenseHeaderFile(rootProject.file("config/license-header.txt"))
  }
  kotlinGradle {
    target("**/*.gradle.kts")
    ktlint()
  }
}
