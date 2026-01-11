// build.gradle.kts (Project root)

plugins {
    // Versions are usually defined here in the root project.
    // Use THE SAME Kotlin version your project already uses.
    // If you already have these plugins here, keep your versions and just add the compose plugin line.

    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false

    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false

    id("com.google.protobuf") version "0.9.4" apply false
}
