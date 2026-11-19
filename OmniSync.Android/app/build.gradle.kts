import java.security.MessageDigest
import java.time.Instant

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val updateVersionCode = (System.currentTimeMillis() / 1_000L).toInt()
val updateVersionName = "dev.$updateVersionCode"

android {
    namespace = "com.omni.sync"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.omni.sync"
        minSdk = 24
        targetSdk = 34
        versionCode = updateVersionCode
        versionName = updateVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.media3.common.util.UnstableApi"
        )
    }
    buildFeatures {
        compose = true
    }
    // FUTO swipe's AAR needs a tiny app-owned ITrie adapter for its English lexicon.
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.12"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    sourceSets {
        // Bundle the exact shared React editor from the sibling OmniDesign project.
        getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/omnidesignAssets"))
        getByName("test").resources.srcDir(rootProject.file("../Shared"))
    }
}

fun registerUpdateMetadata(variant: String) {
    val variantName = variant.replaceFirstChar { it.uppercase() }
    val apkFile = layout.buildDirectory.file("outputs/apk/$variant/app-$variant.apk")
    val metadataFile = layout.buildDirectory.file("outputs/apk/$variant/app-$variant.update.json")
    val metadataTask = tasks.register("write${variantName}UpdateMetadata") {
        inputs.file(apkFile)
        inputs.property("versionCode", updateVersionCode)
        outputs.file(metadataFile)
        doLast {
            val apk = apkFile.get().asFile
            check(apk.isFile) { "Cannot publish Android update because $apk was not created." }
            val sha256 = MessageDigest.getInstance("SHA-256")
                .digest(apk.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            metadataFile.get().asFile.writeText(
                """{
  "versionCode": $updateVersionCode,
  "versionName": "$updateVersionName",
  "sha256": "$sha256",
  "size": ${apk.length()},
  "builtAtUtc": "${Instant.now()}"
}
"""
            )
        }
    }
    tasks.matching { it.name == "assemble$variantName" }.configureEach {
        finalizedBy(metadataTask)
    }
}

// APK assets must match current shared editor sources; WebView never loads a remote page.
val buildOmniDesignAssets = tasks.register<Exec>("buildOmniDesignAssets") {
    workingDir = rootProject.file("../OmniDesign/web")
    commandLine("cmd", "/c", "npm", "run", "build")
    inputs.dir(rootProject.file("../OmniDesign/web/src"))
    inputs.file(rootProject.file("../OmniDesign/web/index.html"))
    inputs.file(rootProject.file("../OmniDesign/web/package-lock.json"))
    outputs.dir(rootProject.file("../OmniDesign/web/dist"))
}
val prepareOmniDesignAssets = tasks.register<Copy>("prepareOmniDesignAssets") {
    dependsOn(buildOmniDesignAssets)
    from(rootProject.file("../OmniDesign/web/dist"))
    into(layout.buildDirectory.dir("generated/omnidesignAssets/omnidesign"))
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(prepareOmniDesignAssets) }

registerUpdateMetadata("debug")
registerUpdateMetadata("release")

dependencies {
    implementation(files("libs/futo-swipe-release.aar"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.foundation:foundation-layout")
    implementation("androidx.webkit:webkit:1.11.0")

    // Additional dependencies for RxJava3, Compose ViewModel, and Material Icons
    implementation("io.reactivex.rxjava3:rxjava:3.1.5")
    implementation("io.reactivex.rxjava3:rxandroid:3.0.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("androidx.compose.material:material-icons-extended")
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // SignalR Client
    implementation("com.microsoft.signalr:signalr:7.0.0")

    // Gson for manual JSON deserialization
    implementation("com.google.code.gson:gson:2.10.1")

    // Retrofit for API calls (if needed for other components, though SignalR is primary)
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")

    // Kotlin Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-rx3:1.8.1")

    // Timber for logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Media3 (ExoPlayer)
    val media3_version = "1.2.0"
    implementation("androidx.media3:media3-exoplayer:$media3_version")
    implementation("androidx.media3:media3-ui:$media3_version")
    implementation("androidx.media3:media3-common:$media3_version")

    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.6.0")
}
