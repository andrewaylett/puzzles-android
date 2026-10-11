plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.aboutlibraries)
}

android {
  namespace = "uk.aylett.puzzles"
  compileSdk = 37

  defaultConfig {
    applicationId = "uk.aylett.puzzles"
    minSdk = 26
    targetSdk = 37
    versionCode = 1
    versionName = "0.1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }

  buildFeatures {
    compose = true
  }

  testOptions {
    // So that logging from code under test (failure recovery logs what it recovered from) does
    // not throw "not mocked" from the android.jar stubs.
    unitTests.isReturnDefaultValues = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

dependencies {
  implementation(project(":engine"))

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.datastore)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.work.runtime)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.android)

  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.ui.graphics)
  implementation(libs.compose.material3)
  implementation(libs.compose.material.icons.extended)
  implementation(libs.aboutlibraries.compose.m3)
  implementation(libs.compose.ui.tooling.preview)
  debugImplementation(libs.compose.ui.tooling)

  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.mockk)
  testRuntimeOnly(libs.junit.platform.launcher)

  androidTestImplementation(platform(libs.compose.bom))
  androidTestImplementation(libs.androidx.test.junit)
  androidTestImplementation(libs.androidx.test.espresso.core)
}

aboutLibraries {
  collect {
    // The puzzle collection is compiled into the WASM blob, so no dependency graph can see it;
    // it is declared by hand here, with its MIT text, rather than only mentioned in prose.
    configPath = rootProject.layout.projectDirectory.dir("config/aboutlibraries")
  }
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
}
