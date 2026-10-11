/*
 * Copyright 2026 Andrew Aylett
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:Suppress("UnstableApiUsage")

package uk.aylett.puzzles.build

import com.dylibso.chicory.build.time.compiler.Config
import com.dylibso.chicory.build.time.compiler.Generator
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.problems.ProblemGroup
import org.gradle.api.problems.Problems
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Jar
import javax.inject.Inject

/**
 * Translates a WASM module into JVM bytecode at build time.
 *
 * Chicory can do this at runtime, but Android cannot load JVM bytecode it did not dex, so the
 * translation has to happen here where the result can go through D8 with everything else.
 * Interpreting instead is not a real option: a hard Solo generates in ~2s compiled and ~17s
 * interpreted, and that is on a desktop.
 */
@CacheableTask
abstract class ChicoryCompileTask : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val wasmFile: RegularFileProperty

  /** Fully-qualified name of the generated loader class. */
  @get:Input
  abstract val moduleName: Property<String>

  /** Fully-qualified name of the generated module interface. */
  @get:Input
  abstract val moduleInterface: Property<String>

  /** Translated WASM bodies, already bytecode; jarred rather than compiled. */
  @get:OutputDirectory
  abstract val classesDirectory: DirectoryProperty

  /** The loader class, which is plain Java and still needs compiling. */
  @get:OutputDirectory
  abstract val sourcesDirectory: DirectoryProperty

  /** The stripped module the loader parses for types, memory layout and data segments. */
  @get:OutputDirectory
  abstract val resourcesDirectory: DirectoryProperty

  @get:Inject
  abstract val problems: Problems

  @Suppress("NewApi")
  @TaskAction
  fun generate() {
    listOf(classesDirectory, sourcesDirectory, resourcesDirectory)
      .forEach { it.get().asFile.deleteRecursively() }

    val config =
      Config
        .builder()
        .withWasmFile(wasmFile.get().asFile.toPath())
        .withName(moduleName.get())
        .withModuleInterface(moduleInterface.get())
        .withTargetClassFolder(classesDirectory.get().asFile.toPath())
        .withTargetSourceFolder(sourcesDirectory.get().asFile.toPath())
        .withTargetWasmFolder(resourcesDirectory.get().asFile.toPath())
        .build()

    val generator = Generator(config)
    // Functions the compiler could not translate are listed here and left for the interpreter;
    // the meta-WASM must then still carry their bodies, hence passing the set on.
    val interpreted = generator.generateResources()
    generator.generateSources()
    generator.generateModuleInterface(moduleInterface.get())
    generator.generateMetaWasm(interpreted)

    if (interpreted.isNotEmpty()) {
      val reporter = problems.reporter

      val problems =
        interpreted
          .stream()
          .map {
            reporter.create(interpretedProblem) {
              details("Function $it left to be interpreted")
            }
          }.toList()
      throw reporter.throwing(
        RuntimeException("Chicory left ${interpreted.size} function(s) to the interpreter"),
        problems,
      )
    }
  }

  companion object {
    val interpretedProblem =
      org.gradle.api.problems.ProblemId.create(
        "interpreted",
        "Function left to be interpreted",
        ProblemGroup.create("chicory", "Chicory Problems"),
      )
  }
}

/**
 * Wires [ChicoryCompileTask] into a module: the generated `.class` files are packaged as a jar and
 * added as a dependency, because they are already-compiled bytecode rather than source.
 */
abstract class ChicoryCompilePlugin : Plugin<Project> {
  override fun apply(project: Project): Unit =
    with(project) {
      val extension = extensions.create("chicory", ChicoryExtension::class.java)

      val compile =
        tasks.register("chicoryCompile", ChicoryCompileTask::class.java) {
          wasmFile.set(extension.wasmFile)
          moduleName.set(extension.moduleName)
          moduleInterface.set(extension.moduleInterface)
          classesDirectory.set(layout.buildDirectory.dir("chicory/classes"))
          sourcesDirectory.set(layout.buildDirectory.dir("chicory/sources"))
          resourcesDirectory.set(layout.buildDirectory.dir("chicory/resources"))
        }

      val jar =
        tasks.register("chicoryJar", Jar::class.java) {
          from(compile.flatMap { it.classesDirectory })
          from(compile.flatMap { it.resourcesDirectory })
          archiveBaseName.set("chicory-compiled")
          destinationDirectory.set(layout.buildDirectory.dir("chicory-jar"))
        }

      dependencies.add("implementation", files(jar))
    }
}

abstract class ChicoryExtension {
  abstract val wasmFile: RegularFileProperty
  abstract val moduleName: Property<String>
  abstract val moduleInterface: Property<String>
}
