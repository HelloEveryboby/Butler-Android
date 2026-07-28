package com.pybridge

// ============================================================
// PyBridgeExtension
// ============================================================
// Backing object for the `pybridge { ... }` DSL block used in an
// Android app's build.gradle.kts. It declares which cross-compiled
// Python packages must be bundled into the build and where the
// prebuilt artifacts (produced by pybridge-build-packages.sh) live.
//
// Example:
//   pybridge {
//       pythonVersion = "3.12.3"
//       abiFilters("arm64-v8a")
//       packages("Pillow", "lxml", "numpy", "PyMuPDF")
//       usePrebuiltPackages = true
//       prebuiltPackagesDir = "../prebuilt-packages"
//   }
// ============================================================

open class PyBridgeExtension {

    /** Python runtime version targeted by the cross-compiled packages. */
    var pythonVersion: String = "3.12.3"

    /**
     * Target Android ABIs. Each entry must match an ABI that was
     * cross-compiled into [prebuiltPackagesDir] (e.g. "arm64-v8a").
     */
    var abiFilters: List<String> = listOf("arm64-v8a")

    /**
     * Declared Python packages that must be present under
     * `prebuiltPackagesDir/{abi}/{pkg}/`. Both pure-Python and
     * C-extension packages belong here.
     */
    var packages: List<String> = emptyList()

    /**
     * When true, the plugin copies artifacts from [prebuiltPackagesDir]
     * into jniLibs / assets and validates that every declared package
     * exists. Set to false to disable bundling (e.g. for non-Python builds).
     */
    var usePrebuiltPackages: Boolean = true

    /**
     * Directory containing the cross-compiled `.so` files and Python
     * source trees, as produced by the cross-compile script.
     * Layout: `{prebuiltPackagesDir}/{abi}/*.so` and
     *         `{prebuiltPackagesDir}/{abi}/{pkg}/...`
     */
    var prebuiltPackagesDir: String = "prebuilt-packages"

    /**
     * Directory containing `.bsk` skill files (output of the skill
     * converter / smart_skill_builder). These are copied into the
     * Android `assets/python-skills/` directory.
     */
    var skillsAssetsDir: String = "src/main/assets/python-skills"

    /** DSL helper: `packages("Pillow", "lxml", "numpy")`. */
    fun packages(vararg pkgs: String) {
        packages = pkgs.toList()
    }

    /** DSL helper: `abiFilters("arm64-v8a", "x86_64")`. */
    fun abiFilters(vararg abis: String) {
        abiFilters = abis.toList()
    }
}
