# Implementation Plan - Fix Gradle Sync Error (Task 'prepareKotlinBuildScriptModel' not found)

The user is encountering a Gradle sync error where the `prepareKotlinBuildScriptModel` task is not found. Investigation reveals that the root cause is a configuration failure during the project evaluation phase, specifically due to an `AndroidLocationsException` thrown by the Android Gradle Plugin (AGP).

## Root Cause Analysis
The build fails because both `ANDROID_PREFS_ROOT` and `ANDROID_USER_HOME` environment variables are set. AGP 9.3.0 (a very new/preview version) enforces a strict check that only one of these should be set to avoid ambiguity, even if they point to the same directory.

Additionally, the project is using very recent (possibly future) versions of AGP (9.3.0) and Gradle (9.5.0), and the Kotlin plugin is not explicitly applied in the `app` module, which might be causing issues with IDE support for Kotlin DSL.

## Proposed Changes

### 1. Environment Configuration (User Action Required)
The primary fix requires unsetting one of the conflicting environment variables.
> [!IMPORTANT]
> The user should unset either `ANDROID_PREFS_ROOT` or `ANDROID_USER_HOME` in their OS environment variables. It is recommended to keep `ANDROID_USER_HOME`.

### 2. Build Script Improvements
I will make the following changes to the project to improve stability and IDE support:

#### [MODIFY] [libs.versions.toml](file:///C:/Users/Varun/OneDrive/Desktop/app/gradle/libs.versions.toml)
- Downgrade AGP to a stable version (`8.7.0`) and Gradle to a compatible stable version (`8.10.2`) if the current versions are not strictly required for Android 16/36 features.
- Add the Kotlin Gradle plugin to the versions and plugins block.

#### [MODIFY] [gradle-wrapper.properties](file:///C:/Users/Varun/OneDrive/Desktop/app/gradle/wrapper/gradle-wrapper.properties)
- Downgrade Gradle distribution to `8.10.2`.

#### [MODIFY] [build.gradle.kts (root)](file:///C:/Users/Varun/OneDrive/Desktop/app/build.gradle.kts)
- Apply the Kotlin plugin (apply false).

#### [MODIFY] [build.gradle.kts (app)](file:///C:/Users/Varun/OneDrive/Desktop/app/app/build.gradle.kts)
- Apply the Kotlin Android plugin.
- Adjust `compileSdk` to a stable version (e.g., 35) unless API 36 is specifically required.

## Verification Plan

### Automated Tests
- Run `./gradlew :app:tasks` to ensure configuration completes successfully.
- Trigger a Gradle Sync in the IDE (if possible, or ask the user to do so).

### Manual Verification
- Verify that the `AndroidLocationsException` is no longer thrown.
- Confirm that Kotlin DSL support in the IDE is functional (i.e., `prepareKotlinBuildScriptModel` works).
