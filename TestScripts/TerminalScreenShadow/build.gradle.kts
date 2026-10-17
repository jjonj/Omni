plugins {
    kotlin("jvm") version "1.9.22"
    application
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("com.omni.test.MainKt")
}

sourceSets {
    main {
        kotlin {
            // Real Android source: the Simple-mode terminal emulator (no Android dependencies).
            srcDir("../../OmniSync.Android/app/src/main/java/com/omni/sync/logic/terminal")
        }
    }
}
