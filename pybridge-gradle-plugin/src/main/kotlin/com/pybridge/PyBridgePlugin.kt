package com.pybridge

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider

// ============================================================
// PyBridgePlugin
// ============================================================
// Gradle plugin (id: `com.pybridge.gradle`) that integrates
// cross-compiled Python packages into an Android build.
//
// It registers four tasks (all in the "pybridge" group):
//
//   configurePyBridge      Validates the `pybridge {}` configuration and
//                          checks that every declared package exists under
//                          prebuilt-packages/{abi}/{pkg}/.
//
//   copyPyBridgeNatives    Copies prebuilt-packages/{abi}/*.so into
//                          src/main/jniLibs/{abi}/  (depends on configurePyBridge)
//
//   copyPyBridgePackages   Copies prebuilt-packages/{abi}/{pkg}/ Python sources
//                          into src/main/assets/python-packages/{pkg}/
//                          (depends on configurePyBridge)
//
//   copyPyBridgeSkills     Copies .bsk files from skillsAssetsDir into
//                          src/main/assets/python-skills/
//
// The three copy tasks are wired as dependencies of the Android `preBuild`
// task so the artifacts are staged before the APK is assembled.
//
// The extension is read inside `Project.afterEvaluate { }` so the final
// values from the user's `pybridge {}` block are available.
// ============================================================

class PyBridgePlugin : Plugin<Project> {

    companion object {
        private const val TASK_GROUP = "pybridge"
        private const val JNI_LIBS_DIR = "src/main/jniLibs"
        private const val ASSETS_PACKAGES_DIR = "src/main/assets/python-packages"
        private const val ASSETS_SKILLS_DIR = "src/main/assets/python-skills"
    }

    override fun apply(project: Project) {
        // Register the `pybridge { ... }` extension.
        val extension = project.extensions.create("pybridge", PyBridgeExtension::class.java)

        // Register tasks eagerly so they show up in `gradle tasks`. Their
        // real configuration (actions, dependencies) is attached in
        // afterEvaluate, once the extension has been fully evaluated.
        val configureTask: TaskProvider<Task> =
            project.tasks.register("configurePyBridge") {
                group = TASK_GROUP
                description = "Reads and validates the PyBridge extension configuration."
            }
        val copyNativesTask: TaskProvider<Task> =
            project.tasks.register("copyPyBridgeNatives") {
                group = TASK_GROUP
                description = "Copies cross-compiled .so files into src/main/jniLibs."
            }
        val copyPackagesTask: TaskProvider<Task> =
            project.tasks.register("copyPyBridgePackages") {
                group = TASK_GROUP
                description = "Copies Python source packages into src/main/assets/python-packages."
            }
        val copySkillsTask: TaskProvider<Task> =
            project.tasks.register("copyPyBridgeSkills") {
                group = TASK_GROUP
                description = "Copies .bsk skill files into src/main/assets/python-skills."
            }

        project.afterEvaluate {
            // Snapshot the final configuration values from the `pybridge {}` block.
            val pythonVersion = extension.pythonVersion
            val abiFilters = extension.abiFilters
            val packages = extension.packages
            val usePrebuilt = extension.usePrebuiltPackages
            val prebuiltDir = extension.prebuiltPackagesDir
            val skillsDir = extension.skillsAssetsDir

            // ----------------------------------------------------------
            // configurePyBridge: print configuration + validate artifacts
            // ----------------------------------------------------------
            configureTask.configure {
                doLast {
                    project.logger.lifecycle("[PyBridge] === Configuration ===")
                    project.logger.lifecycle("[PyBridge]   Python version       : $pythonVersion")
                    project.logger.lifecycle("[PyBridge]   ABI filters           : $abiFilters")
                    project.logger.lifecycle("[PyBridge]   Packages (${packages.size})       : $packages")
                    project.logger.lifecycle("[PyBridge]   Use prebuilt packages : $usePrebuilt")
                    project.logger.lifecycle("[PyBridge]   Prebuilt packages dir : ${project.file(prebuiltDir).absolutePath}")
                    project.logger.lifecycle("[PyBridge]   Skills assets dir     : ${project.file(skillsDir).absolutePath}")

                    if (!usePrebuilt) {
                        project.logger.lifecycle("[PyBridge] usePrebuiltPackages=false, skipping validation.")
                        return@doLast
                    }

                    val prebuiltRoot = project.file(prebuiltDir)
                    if (!prebuiltRoot.exists()) {
                        throw GradleException(
                            "[PyBridge] prebuilt-packages directory not found: " +
                                "${prebuiltRoot.absolutePath}. " +
                                "Run pybridge-build-packages.sh first, or set usePrebuiltPackages=false."
                        )
                    }

                    for (abi in abiFilters) {
                        val abiDir = project.file("$prebuiltDir/$abi")
                        if (!abiDir.exists()) {
                            project.logger.lifecycle(
                                "[PyBridge] WARNING: ABI directory not found: ${abiDir.absolutePath}"
                            )
                        }
                    }

                    // Verify every declared package exists under at least one ABI.
                    val missing = packages.filter { pkg ->
                        abiFilters.none { abi -> project.file("$prebuiltDir/$abi/$pkg").exists() }
                    }
                    if (missing.isNotEmpty()) {
                        throw GradleException(
                            "[PyBridge] Missing declared packages in prebuilt-packages: $missing. " +
                                "Checked ABIs: $abiFilters. " +
                                "Cross-compile them or remove them from packages()."
                        )
                    }

                    project.logger.lifecycle(
                        "[PyBridge] Validation OK: ${packages.size} package(s), ${abiFilters.size} ABI(s)."
                    )
                }
            }

            // ----------------------------------------------------------
            // copyPyBridgeNatives:
            //   prebuilt-packages/{abi}/*.so  ->  src/main/jniLibs/{abi}/
            // ----------------------------------------------------------
            copyNativesTask.configure {
                dependsOn(configureTask)
                enabled = usePrebuilt
                doLast {
                    if (!usePrebuilt) return@doLast
                    var total = 0
                    for (abi in abiFilters) {
                        val abiDir = project.file("$prebuiltDir/$abi")
                        if (!abiDir.exists()) {
                            project.logger.lifecycle(
                                "[PyBridge] Skip natives for $abi: directory not found (${abiDir.absolutePath})."
                            )
                            continue
                        }
                        val soFiles = abiDir.listFiles { f -> f.isFile && f.extension == "so" }
                            ?: emptyArray()
                        if (soFiles.isEmpty()) {
                            project.logger.lifecycle("[PyBridge] No .so files found for $abi.")
                            continue
                        }
                        val destDir = project.file("$JNI_LIBS_DIR/$abi")
                        project.copy {
                            from(abiDir)
                            include("*.so")
                            into(destDir)
                        }
                        total += soFiles.size
                        project.logger.lifecycle(
                            "[PyBridge] Copied ${soFiles.size} .so file(s) for $abi -> ${destDir.absolutePath}"
                        )
                    }
                    project.logger.lifecycle("[PyBridge] Native libraries copy complete ($total total).")
                }
            }

            // ----------------------------------------------------------
            // copyPyBridgePackages:
            //   prebuilt-packages/{abi}/{pkg}/  ->  src/main/assets/python-packages/{pkg}/
            // Python sources are arch-independent, so the first ABI that
            // contains the package wins.
            // ----------------------------------------------------------
            copyPackagesTask.configure {
                dependsOn(configureTask)
                enabled = usePrebuilt
                doLast {
                    if (!usePrebuilt) return@doLast
                    if (packages.isEmpty()) {
                        project.logger.lifecycle("[PyBridge] No packages declared, skipping package copy.")
                        return@doLast
                    }
                    for (pkg in packages) {
                        var copied = false
                        for (abi in abiFilters) {
                            val pkgDir = project.file("$prebuiltDir/$abi/$pkg")
                            if (pkgDir.exists()) {
                                val destDir = project.file("$ASSETS_PACKAGES_DIR/$pkg")
                                project.copy {
                                    from(pkgDir)
                                    into(destDir)
                                }
                                project.logger.lifecycle(
                                    "[PyBridge] Copied Python package '$pkg' (from $abi) -> ${destDir.absolutePath}"
                                )
                                copied = true
                                break // Python sources are arch-independent; first ABI wins.
                            }
                        }
                        if (!copied) {
                            project.logger.lifecycle(
                                "[PyBridge] WARNING: package '$pkg' not found under any ABI in $prebuiltDir."
                            )
                        }
                    }
                    project.logger.lifecycle("[PyBridge] Python packages copy complete.")
                }
            }

            // ----------------------------------------------------------
            // copyPyBridgeSkills:
            //   skillsAssetsDir/**/*.bsk  ->  src/main/assets/python-skills/
            // Files already living in the destination are skipped to avoid
            // copying onto themselves (relevant when the default
            // skillsAssetsDir equals the assets skills directory).
            // ----------------------------------------------------------
            copySkillsTask.configure {
                doLast {
                    val skillsSrc = project.file(skillsDir)
                    if (!skillsSrc.exists()) {
                        project.logger.lifecycle(
                            "[PyBridge] Skills source directory not found: ${skillsSrc.absolutePath}, skipping."
                        )
                        return@doLast
                    }
                    val destDir = project.file(ASSETS_SKILLS_DIR)
                    val bskFiles = skillsSrc.walkTopDown()
                        .filter { it.isFile && it.extension == "bsk" }
                        .toList()
                    if (bskFiles.isEmpty()) {
                        project.logger.lifecycle("[PyBridge] No .bsk files found in ${skillsSrc.absolutePath}.")
                        return@doLast
                    }
                    val toCopy = bskFiles.filter { it.parentFile != destDir }
                    if (toCopy.isNotEmpty()) {
                        project.copy {
                            from(*toCopy.toTypedArray())
                            into(destDir)
                        }
                    }
                    project.logger.lifecycle(
                        "[PyBridge] Copied ${toCopy.size} .bsk skill file(s) -> ${destDir.absolutePath}"
                    )
                }
            }

            // ----------------------------------------------------------
            // Wire the copy tasks into the Android build so they run
            // before the APK is assembled. `preBuild` is contributed by
            // the Android Gradle Plugin; use matching+configureEach so
            // this works whether or not AGP is applied.
            // ----------------------------------------------------------
            project.tasks.matching { it.name == "preBuild" }.configureEach {
                dependsOn(copyNativesTask, copyPackagesTask, copySkillsTask)
            }
        }
    }
}
