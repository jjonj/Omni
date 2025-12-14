// OmniSync.Android.PoC/build.gradle.kts (Root build file)
buildscript {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("com.android.application") version "8.3.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
}

// Allprojects and subprojects are typically configured here,
// but for a single module, it's mostly about plugin management
// and top-level settings.
