# BitChord Workspace Rules

Guidelines synthesized from `CONTRIBUTING.md` and `CODE_OF_CONDUCT.md`.

## 0. Development Scope
- **Android App Only**: Develop only the Android mobile app (`app/`). Ignore `tv/`, `wearos/`, `web/`, `desktopApp/`, and `desktopUi/`.

## 1. Architecture & Code Quality
- **Idiomatic Kotlin & Jetpack Compose**: Follow standard Kotlin and Compose paradigms.
- **Architectural Boundaries**: Preserve strict separation of concerns:
  - UI layer (`ui/`, Compose screens, viewmodels)
  - Domain logic (`domain/`, models, use cases)
  - Playback services (`playback/`, audio engine, service lifecycles)
  - Data repositories (`data/`, storage, caching, network)
- **Minimal Diffs**: Keep changes surgical and focused on the immediate task. Do not mix refactors, cosmetic tweaks, or mass reformatting with functional changes.
- **Dependency Discipline & GPLv3 Compliance**:
  - The project is licensed under **GPLv3**. All code and dependencies must be strictly GPLv3-compatible.
  - Avoid adding external dependencies unless strictly necessary. Evaluate necessity, binary size impact, and license before adding.

## 2. Audio Engine & Telemetry Rules (Critical)
- **Hardware Telemetry Rule**: Application telemetry must **never** be presented as proof of physical hardware behavior unless verified on physical hardware.
- **Clarity of Audio Stages**: Always distinguish explicitly between:
  1. Source metadata
  2. Decoder format
  3. Internal DSP format
  4. AudioTrack output
  5. AudioFlinger / HAL state
  6. Advertised device capability
  7. External physical endpoint behavior

## 3. Toolchain & Environment
- **JDK**: Java 17 (Eclipse Temurin 17 recommended).
- **Android SDK**: `compileSdk = 37`, `targetSdk = 36`, `minSdk = 26`. Compiling against 37 is an InnerTubeX AAR requirement, not a behaviour choice — runtime behaviour follows `targetSdk`.
- **C/C++ NDK & CMake**: CMake 3.22.1+ and Android NDK for native audio DSP under `app/src/main/cpp`.
- **Backend (Optional)**: Go 1.22+ for party server (`backend/`).

## 4. Build & Test Commands
Run from repository root:
- **Android app scope**: Prefix build and test tasks with `:app:`. Root aggregate tasks may build or test ignored modules such as `tv/` and `wearos/`.
- **Build (Dev Debug)**:
  - Windows: `.\gradlew.bat :app:assembleDevDebug`
  - Linux/macOS: `./gradlew :app:assembleDevDebug`
- **Unit Tests**:
  - Windows: `.\gradlew.bat :app:testDevDebugUnitTest`
  - Linux/macOS: `./gradlew :app:testDevDebugUnitTest`
- **Single Test**:
  - Windows: `.\gradlew.bat :app:testDevDebugUnitTest --tests "<FullClassName>"`
- **Test Integrity**: Write unit tests for new logic, fixes, and edge cases. Never introduce new test failures. Document known pre-existing failures if encountered.

### Which build to install

Three packages come out of `app/`, from two flavors (`dev`, `prod`) and three build types
(`debug`, `release`, `benchmark`). Pick by what you are trying to learn.

| package | built by | use for |
|---|---|---|
| `com.dev.bitchord` | `:app:installDevDebug` | Feature and UI checks, `logcat`, `run-as`. Debuggable, so it is the only one you can inspect app data on. |
| `com.music.bitchord` | `:app:installProdRelease` | The shipped configuration — R8, resource shrinking, release-only branches. |
| `com.dev.bitchord.benchmark` | `:app:installDevBenchmark` | **Every metric**: cold start, frame timing, memory, CPU, battery. |

- **A number measured on a debug APK is not a result.** The benchmark type is
  `initWith(release)`, signed with the debug key, so the code is optimised the way it
  ships; a debug build is interpreted and verified at runtime and its timings say nothing
  about the shipped app. Re-measure anything that came from `*-debug.apk`.
- **Launch by the right package id.** `am start -n com.music.bitchord/.MainActivity` runs
  the *release* app already on the phone, not the branch you just installed. For the dev
  build: `am start -n com.dev.bitchord/com.music.bitchord.LauncherDefault`.
- **`run-as` only works on `com.dev.bitchord`.** Neither release nor benchmark is
  debuggable.
- **Always `adb install -r --user 0`.** Installing to any other user leaves the app the
  launcher actually starts untouched.
- **Build one ABI only.** This phone is `arm64-v8a`, so pass
  `-PbitchordAbi=arm64-v8a` to every dev and benchmark assemble/install task. Without it
  Gradle packages four APKs (`arm64-v8a`, `armeabi-v7a`, `x86_64`, universal). Release
  builds for distribution are the exception — those need every ABI.
  (`-Pandroid.injected.build.abi` does **not** work here: the splits are declared in
  `app/build.gradle.kts`, and that injected property is only honoured by the IDE.)
- **One Gradle build at a time.** Two builds sharing the caches race on the artifact
  transforms and fail with "Module was compiled with an incompatible version of Kotlin …
  expected version is 2.1.0" on a `shared_release.kotlin_module`. The retry then succeeds,
  so it looks like a stale-cache bug and costs a whole wasted compile. Wait for the build
  in flight before starting another.
- Installing one package never replaces another: all three can sit on the device at once,
  so a wrong id launches a stale app with no error to tell you.

## 5. Git & PR Workflow
- Base new work on active development/version branch rather than assuming `main`.
- Branch naming: descriptive prefix (e.g., `fix/streaming-buffer`, `feat/...`, `docs/...`).
- **Atomic Commits**: Keep commits strictly atomic. Split features logically across separate commits (e.g. 1. `feat(settings)`: preferences/UI, 2. `feat(...)`: core logic/service implementation, 3. `test(...)`: unit test coverage) rather than one bulk commit.
- **PR Format**: Follow the established 3-section structure: `## Summary`, `## Changes`, and `## Verification` (with optional `## Caveats & Scope`). Keep descriptions concise without fluffy intros.
- **Technical Honesty**: Clearly document known constraints or edge cases (e.g. duration mismatches or intro silence offsets) rather than claiming absolute gapless/desync-free behavior.
- **Upstream Catch-Up (Android Only)**: Fork tracks `kushagrasinghx/BitChord`. When merging an upstream release into `dev`, take Android app parts only — skip `tv/`, `wearos/`, `web/`, `desktopApp/`, `desktopUi/`; keep `shared/`, `sharedUi/` and `app/src/main/cpp` because the Android app builds them. Dry-run the merge in a throwaway worktree first and resolve conflicts there. Expect clashes in `build.gradle.kts` (version), `AppSettings.kt`, `SettingsSheet.kt`, `AudioCache.kt`, `MiniPlayer.kt`, `GlassNavBar.kt`, `PlaybackService.kt`; the `AppSettings` both-added setter needs an extra closing brace, and the two alignment systems (`alignedPosition` official time map vs `StreamAudioAligner` offset) must not be merged blindly. Compile the resolved tree before committing.

## 6. Conduct & Collaboration
- Communicate constructively, respectfully, and professionally.
- Focus discussions on technical merits and project health.
- Accept constructive feedback gracefully.

## 7. Release Workflow
- **Version Bumping**: Prior to cutting or tagging releases, always bump `extra["bitchordVersionCode"]` (integer) and `extra["bitchordVersionName"]` (e.g. `"1.8.2"`, without leading `"v"`) in `build.gradle.kts`.
- **Release Verification**: Ensure the updated version code and name match release notes and tag name before generating release artifacts.
