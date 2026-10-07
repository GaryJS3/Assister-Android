# Repository Guidelines

## Project Structure & Module Organization

Assister is a Kotlin Android application using Jetpack Compose and Material 3. The single `app/` module contains:

- `src/main/java/net/thegary/assister/`: application code, including `MainActivity.kt`.
- `src/main/java/net/thegary/assister/ui/theme/`: Compose colors, typography, and theme.
- `src/main/res/`: strings, icons, themes, and backup configuration.
- `src/test/java/`: local JVM unit tests.
- `src/androidTest/java/`: tests requiring an Android device or emulator.

Module configuration lives in `app/build.gradle.kts`; dependency versions and aliases live in `gradle/libs.versions.toml`.

## Build, Test, and Development Commands

Run these commands from the repository root in PowerShell:

- `.\gradlew.bat :app:assembleDebug`: build the debug APK.
- `.\gradlew.bat :app:testDebugUnitTest`: run local JUnit tests.
- `.\gradlew.bat :app:connectedDebugAndroidTest`: run instrumented tests on a connected device or emulator.
- `.\gradlew.bat :app:lintDebug`: run Android Lint.
- `.\gradlew.bat :app:installDebug`: install the debug app; launch Assister on the device afterward.

Use Android Studio for interactive development and Compose previews. Install Android SDK 37; the minimum supported device API is 26. Gradle daemon configuration requests JDK 25, while Java source compatibility is 11.

## Coding Style & Naming Conventions

Follow existing Kotlin style: four-space indentation, `PascalCase` classes and composable functions, and `camelCase` ordinary functions and properties. Keep packages under `net.thegary.assister` and use lowercase underscore-separated Android resource names. Extract user-facing strings into `res/values/strings.xml`.

Use Android Studio formatting; no external formatter is configured. Prefer C# for separate tooling, Kotlin for Android. Avoid Python or YAML unnecessarily.

## Testing Guidelines

Local tests use JUnit 4. Instrumented tests use AndroidJUnit4; Espresso and Compose UI testing dependencies are available. Name test classes `*Test` and test methods descriptively, such as `addition_isCorrect`. Add regression tests for changed behavior. No coverage threshold is configured. Run unit tests and lint before review; run instrumented tests for device-dependent or UI changes.

## Commit & Pull Request Guidelines

Git history is unavailable here. Use focused commits with imperative subjects, such as `Add settings screen`. PRs should describe changed behavior, link issues, report validation, and include screenshots for UI changes.

## Security & Configuration

Keep SDK paths in `local.properties`. Exclude credentials, signing keys, machine configuration, and build outputs. Manage dependencies through the version catalog.

## Architecture & Project Plan

Read [progress.md](progress.md) before work; update implementation items and separate validation evidence after changes. Use `README.md` for architecture and `docs/assister-android-project.md` for requirements. Preserve Kotlin/Compose and `net.thegary.assister`; isolate protocol/network/state from UI. Consult the current [canonical protocol](https://github.com/GaryJS3/Assister/blob/main/docs/rich-client-protocol.md) before wire changes. Prioritize live typed chat and reconnect recovery before voice or assistant services.
