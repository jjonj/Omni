plugins {
    kotlin("jvm") version "1.9.22"
    application
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation("com.microsoft.signalr:signalr:8.0.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.slf4j:slf4j-simple:2.0.9")
}

application {
    mainClass.set("com.omni.test.MainKt")
}

sourceSets {
    main {
        kotlin {
            // Add paths to real Android source here to test them directly
            // srcDir("../../OmniSync.Android/app/src/main/java")
        }
    }
}
