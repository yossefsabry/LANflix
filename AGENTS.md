# AGENTS

This file guides agentic coding tools working in this repo.
Keep changes consistent with existing Kotlin/Android patterns.

## Project Snapshot
- App name: LANflix (Android, Kotlin, MVVM, Koin, Room, Ktor).
- Modules: single `:app` module configured in `settings.gradle.kts`.
- Server routes live in `app/src/main/java/com/thiyagu/media_server/server/routes`.
- Client + UI code lives under `app/src/main/java/com/thiyagu/media_server`.

## Environment
- JDK 17 required (see `docs/FDROID.md`).
- Android SDK 34 (compileSdk/targetSdk in `app/build.gradle.kts`).
- Use the Gradle wrapper from repo root: `./gradlew`.
- Default namespace/package: `com.thiyagu.media_server`.
- For device runs, `adb` must be available on PATH.

## Build Commands
- Debug APK: `./gradlew assembleDebug`.
- Release APK: `./gradlew assembleRelease`.
- Full build (CI): `./gradlew build` (used in `.github/workflows/gradle-publish.yml`).
- Clean: `./gradlew clean`.
- Install + launch on device/emulator: `./dev.sh` (wraps `installDebug` + `adb` launch).
- Direct install: `./gradlew :app:installDebug`.

## Lint / Formatting
- Lint (Android lint): `./gradlew lint` or `./gradlew :app:lintDebug`.
- No ktlint/detekt/spotless configured in Gradle.
- Format Kotlin with Android Studio default formatter.
- Keep code ASCII unless the file already uses Unicode characters.

## Tests
- Local JVM unit tests: `./gradlew test` or `./gradlew :app:testDebugUnitTest`.
- Single unit test class:
  `./gradlew :app:testDebugUnitTest --tests "com.thiyagu.media_server.server.ClientStatsTrackerTest"`.
- Single unit test method:
  `./gradlew :app:testDebugUnitTest --tests "com.thiyagu.media_server.server.ClientStatsTrackerTest.markSeen_tracksConnectedDevices"`.
- Instrumentation tests (device/emulator): `./gradlew :app:connectedDebugAndroidTest`.
- Single instrumentation test class:
  `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.thiyagu.media_server.ExampleInstrumentedTest`.
- Single instrumentation test method:
  `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.thiyagu.media_server.ExampleInstrumentedTest#useAppContext`.

## Code Style Guidelines

### Kotlin Basics
- Indentation: 4 spaces; no tabs.
- Prefer `val` over `var`; use `var` only when mutation is required.
- Keep functions small and focused; extract helpers for repeated logic.
- Use early returns for invalid inputs or missing data (see `server/routes/StreamingRoutes.kt`).
- Favor `when` for multi-branch value checks.
- Keep null handling explicit with `if (x == null) return` or `?:` fallback.
- Use `internal` for file-local helpers that should not escape the module.
- Avoid unnecessary type inference in public APIs; explicit types are common for `Flow`/`StateFlow`.

### Imports
- Group imports by origin: Android/AndroidX, third-party, then local project.
- Prefer explicit imports; avoid wildcard imports outside of tests.
- Keep import order stable in a file.

### Naming
- Classes/objects: `PascalCase`.
- Functions/properties: `lowerCamelCase`.
- Constants: `UPPER_SNAKE_CASE` (see `ServerManager.DEFAULT_PORT`).
- Test methods use descriptive snake_case for readability.
- File names match the primary class/object name.

### Coroutines & Threading
- Use `viewModelScope` for UI-layer flows.
- For blocking IO, wrap in `withContext(Dispatchers.IO)` (see `MediaRepository`).
- Prefer suspending functions over `runBlocking`; use `runBlocking` only when required by platform APIs.
- Flows typically exposed as `StateFlow` via `stateIn`.

### Error Handling
- Catch exceptions at IO/network boundaries; log with `printStackTrace()` if needed.
- Propagate user-facing errors via `UiState.Error` or `ServerState.Error`.
- In Ktor routes, respond with explicit `HttpStatusCode` and JSON body strings.
- For cache-sensitive endpoints, add `no-store` headers with `call.noStore()`.

### Ktor Server & Networking
- Route registrations live in `server/routes` and are wired in `ServerRoutes.kt`.
- Use `requireAuth(server)` for protected endpoints.
- Use `contentTypeForFile` and `parseRangeHeader` helpers for streaming responses.
- Follow existing patterns for ETag and cache-control on thumbnails.

### Room / Data Layer
- Entities live in `model` with Room annotations (see `MediaFile`).
- DAOs in `data/db` expose `Flow` for reads and suspend functions for writes.
- Repositories in `data/repository` orchestrate IO and diffing.
- Prefer `OnConflictStrategy.REPLACE` for upserts, matching current DAOs.

### Dependency Injection
- Use Koin modules in `di/AppModule.kt`.
- Add new repositories/managers/viewmodels there, using `single` or `viewModel`.
- Prefer constructor injection and keep Android Context usage limited to lower layers.

### UI / Activities
- Activities are under `app/src/main/java/com/thiyagu/media_server`.
- Prefer ViewModel + Repository for data access; keep Activities thin.
- Use ViewBinding where available (enabled in Gradle).

### JSON & Web Responses
- Use `ContentType.Application.Json` and JSON strings via Kotlin triple quotes.
- Keep JSON keys lowercase and consistent with existing endpoints (`authRequired`, `ready`).

## Repository Structure
- `app/src/main/java/...`: main app code (activities, viewmodels, server, data).
- `app/src/test/java/...`: local unit tests (JUnit4).
- `app/src/androidTest/java/...`: instrumentation tests.
- `docs/RELEASING.md`: release tagging and versioning.
- `docs/FDROID.md`: F-Droid build and submission notes.
- `dev.sh`: local device build/install script.

## Cursor / Copilot Rules
- No `.cursor/rules/`, `.cursorrules`, or `.github/copilot-instructions.md` found.
- If rules are added later, update this file to mirror them.

## Agent Tips
- Prefer `./gradlew` over a system Gradle unless a script specifies otherwise.
- Keep edits localized; avoid formatting entire files unless necessary.
- When modifying Ktor routes, verify headers and status codes match existing conventions.
- When adding new DB columns, update Room schema and migrations if needed.
- The current migration strategy uses `fallbackToDestructiveMigration()`.
- Use `ServerState` and `UiState` sealed classes for observable state.
- Prefer `StateFlow` for public state; avoid exposing `MutableStateFlow` outside owners.
- Avoid adding new dependencies without updating `app/build.gradle.kts`.
- Keep HTTP header names and JSON field names consistent with existing API.

## Quick Command Reference
- `./gradlew assembleDebug`
- `./gradlew assembleRelease`
- `./gradlew lint`
- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:testDebugUnitTest --tests "com.thiyagu.media_server.server.ClientStatsTrackerTest"`
- `./gradlew :app:connectedDebugAndroidTest`
- `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.thiyagu.media_server.ExampleInstrumentedTest`
- `./dev.sh`

## Notes on Releases
- Release builds use `./gradlew clean assembleRelease` (see `docs/RELEASING.md`).
- Tags should be annotated (`git tag -a vX.Y -m "LANflix X.Y"`).
- `versionCode` must increase; `versionName` matches tag without `v`.

## When in Doubt
- Search for similar patterns before inventing new ones.
- Keep behavior consistent for server auth, caching, and streaming paths.
- Prefer small, reviewable changes that align with existing architecture.
- Document new build/lint/test steps here if you add them.
