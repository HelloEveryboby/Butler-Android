import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Jar

// ============================================================
// PyBridge Gradle Plugin - module build script
// ============================================================
// This module produces the `com.pybridge.gradle` Gradle plugin that
// integrates cross-compiled Python packages into an Android build.
// ============================================================

plugins {
    kotlin("jvm") version "1.9.22"
    `java-gradle-plugin`
}

group = "com.pybridge"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
}

dependencies {
    // Gradle plugin API (Project, Task, CopySpec, extensions, ...).
    implementation(gradleApi())
    // Kotlin Gradle plugin, so this plugin can cooperate with Kotlin-based
    // Android projects and reuse Kotlin tooling types if needed.
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22")
}

// Register the plugin id. The same descriptor is also provided manually under
// src/main/resources/META-INF/gradle-plugins/ for explicitness / consumers
// that resolve the descriptor directly. See duplicatesStrategy note below.
gradlePlugin {
    plugins {
        create("pybridge") {
            id = "com.pybridge.gradle"
            implementationClass = "com.pybridge.PyBridgePlugin"
        }
    }
}

// `java-gradle-plugin` auto-generates a plugin descriptor from the
// `gradlePlugin` block above, while a hand-written descriptor lives under
// src/main/resources. Both carry identical content; allow duplicates so
// assembling the jar does not fail on the overlapping resource entry.
tasks.withType<Jar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}
