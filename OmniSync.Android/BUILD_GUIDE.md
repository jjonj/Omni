# OmniSync Android Client Build Guide

This document outlines the steps required to successfully build the OmniSync Android client. It incorporates solutions for common issues encountered during the build process, including dependency version conflicts and Android Manifest lint errors.

## 1. Prerequisites

Before you begin, ensure you have the following installed:

*   **Java Development Kit (JDK):** Version 8 or higher. Android Studio typically bundles a suitable JDK, which is recommended for consistency.
*   **Android SDK:** Installed via Android Studio. Ensure you know the path to your SDK installation (e.g., `C:\Users\<YourUser>\AppData\Local\Android\Sdk`).
*   **Android Studio:** While not strictly required for CLI builds, it's essential for managing SDKs and for initial project setup.
*   **Gradle:** You can either install Gradle manually or rely on the Gradle Wrapper (`gradlew.bat`/`gradlew`) provided within the project, which is the recommended approach.

## 2. Project Structure and Initial Configuration

Navigate to the `OmniSync.Android` directory, which is the root of the Android project.

### 2.1 `local.properties`

This file is located at `OmniSync.Android/local.properties`. It tells Gradle where your Android SDK is located. If it doesn't exist, create it.
Ensure the `sdk.dir` property points to your Android SDK installation path. Use forward slashes (`/`) even on Windows.

**Example `local.properties` content:**
```properties
sdk.dir=C:/Users/<YourUser>/AppData/Local/Android/Sdk
# Replace <YourUser> with your actual Windows username.
```

### 2.2 `gradle.properties`

This file is located at `OmniSync.Android/gradle.properties`. Ensure it contains the following line, which enables AndroidX:

```properties
android.useAndroidX=true
```

### 2.3 Gradle Wrapper (If Missing)

If `gradlew` or `gradlew.bat` are missing from your `OmniSync.Android` directory, you can generate them. First, download a Gradle distribution (e.g., Gradle 8.8 from `https://gradle.org/releases/`). Extract it, then run the following command from within the `OmniSync.Android` directory (adjust `gradle.bat` to your downloaded Gradle's `bin` path):

```bash
# Example if Gradle's bin directory is in your PATH, or you provide the full path
gradle wrapper --gradle-version 8.8
```

### 2.4 `AndroidManifest.xml` Adjustments

Several adjustments were made to `OmniSync.Android/app/src/main/AndroidManifest.xml` to resolve lint errors related to Android 14 (API 34) requirements for foreground services and accessibility services.

*   **`BIND_ACCESSIBILITY_SERVICE` Permission:**
    The `android.permission.BIND_ACCESSIBILITY_SERVICE` is a system-only permission that should not be requested via `<uses-permission>`. Instead, it should be applied as an `android:permission` attribute to the `<service>` tag for `OmniAccessibilityService`.

    **Before (Incorrect):**
    ```xml
    <uses-permission android:name="android.permission.BIND_ACCESSIBILITY_SERVICE" />
    ```
    **After (Correct):**
    Remove the `<uses-permission>` tag from the top level and ensure the `<service>` declaration for `OmniAccessibilityService` looks like this:
    ```xml
            <service android:name=".service.OmniAccessibilityService"
                android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
                android:exported="true">
                <!-- ... other tags ... -->
            </service>
    ```

*   **`ForegroundServiceType` Permissions:**
    The `ForegroundService` previously declared `foregroundServiceType="connectedDevice|location"`. Each type requires specific permissions.
    *   `connectedDevice` requires `android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE` *and* an additional network-related permission (e.g., `CHANGE_NETWORK_STATE`).
    *   `location` requires `android.permission.FOREGROUND_SERVICE_LOCATION` and `ACCESS_COARSE_LOCATION` or `ACCESS_FINE_LOCATION`.

    To resolve this, we've:
    1.  Added `android.permission.CHANGE_NETWORK_STATE` (if not already present).
    2.  Removed the `location` type from `foregroundServiceType`, as the application does not have location-based features.

    **Ensure your permissions in `AndroidManifest.xml` include:**
    ```xml
        <uses-permission android:name="android.permission.INTERNET" />
        <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
        <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
        <uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
        <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
        <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    ```
    **And your `ForegroundService` declaration is:**
    ```xml
            <service android:name=".service.ForegroundService"
                android:foregroundServiceType="connectedDevice"
                android:exported="false"/>
    ```

## 3. Resolving Dependency Conflicts (Kotlin/Compose Versions)

A common issue is a mismatch between Kotlin, Compose Compiler, and other related library versions. The following versions have been found to be compatible:

### 3.1 `OmniSync.Android/build.gradle.kts` (Root Project)

Ensure the `plugins` block at the top of this file has the following versions:

```kotlin
plugins {
    id("com.android.application") version "8.3.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" apply false
}
```

### 3.2 `OmniSync.Android/app/build.gradle.kts` (App Module)

Ensure the `composeOptions` block and Kotlin Coroutines dependencies are set as follows:

```kotlin
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.12" // Compatible with Kotlin 2.0.0
    }

    // ... other dependencies ...

    dependencies {
        // ... existing dependencies ...

        // Update Compose BOM to a compatible version
        implementation(platform("androidx.compose:compose-bom:2024.04.01"))
        androidTestImplementation(platform("androidx.compose:compose-bom:2024.04.01"))

        // Kotlin Coroutines for async operations (update to compatible versions)
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-rx3:1.8.1")
    }
```

## 4. Building the Project

1.  Open a terminal or command prompt.
2.  Navigate to the `OmniSync.Android` directory:
    ```bash
    cd D:\SSDProjects\Omni\OmniSync.Android
    ```
    (Adjust the path as necessary for your system.)
3.  Run the Gradle build command. It's often helpful to clean previous builds first:
    ```bash
    .\gradlew.bat clean build
    ```
    (Use `.\gradlew clean build` on Windows or `./gradlew clean build` on Linux/macOS.)

## 5. Locating the Output APK

Upon successful compilation, the generated APK files will be located in the following directories relative to `OmniSync.Android`:

*   **Debug APK:** `app/build/outputs/apk/debug/app-debug.apk`
*   **Release APK:** `app/build/outputs/apk/release/app-release.apk`

## 6. Troubleshooting

*   **`java.nio.file.AccessDeniedException`:** This usually indicates a file locking issue (e.g., by antivirus software or another process holding files open). Try restarting your system or temporarily disabling antivirus software.
*   **"Plugin was not found" errors:** Ensure all plugin IDs and their versions in your `build.gradle.kts` files (both root and app module) are correct and can be resolved by your configured Gradle repositories. Refer to Section 3 for the known working versions.
*   **Kotlin/Compose Compilation Errors:** Ensure all Kotlin and Compose related dependencies are aligned to compatible versions as detailed in Section 3.
*   **Lint Errors:** Carefully read the lint error messages. They often provide clear explanations and suggested fixes, as demonstrated by the `AndroidManifest.xml` issues in Section 2.4.

If you encounter persistent issues, consider running Gradle with more detailed logging:
```bash
.\gradlew.bat clean build --stacktrace --info --debug
```
This can provide more insights into the cause of the failure.
