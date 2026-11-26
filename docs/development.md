# Development

## Environment

| Tool | Version / note |
|---|---|
| JDK | 21 (to run Gradle). The code is compiled as Java 17. |
| Android SDK | compileSdk 37, targetSdk 36, minSdk 29. Install the SDK Platform and Build-Tools with the Android SDK Manager. |
| Gradle | Use the wrapper (`gradlew` / `gradlew.bat`, Gradle 9.8.1). |
| IDE | Android Studio or IntelliJ IDEA (optional). |
| OS | Developed on Windows with PowerShell; Linux and macOS work with `./gradlew`. |

Set `ANDROID_HOME` (or `sdk.dir` in a local, untracked `local.properties`) and `JAVA_HOME`.

## Build and test

```powershell
./gradlew.bat build                 # all unit tests, lint and assemble
./gradlew.bat :usecases:test        # unit tests of one module while working
./gradlew.bat :app:assembleDebug    # debug APK in app/build/outputs/apk/debug/
```

Instrumented tests run on a real phone (`./gradlew.bat connectedDebugAndroidTest`, runner
`app.lightscout.LightScoutTestRunner`). Some of them need the screen unlocked and change system settings; read the
test class before you run it.

The debug app has the id `app.lightscout.debug`. **Do not install a debug build next to a release build on a phone
that is used for real alarms**: both would monitor and ring.

## Modules

| Module | Content |
|---|---|
| `domain` | Pure Java model and rules: alarms, data loss, consumables, entries, statistics, ports. |
| `usecases` | Sync cycle, alarm evaluation, status projection, history loading, entry writing. |
| `nightscout` | Nightscout API v3 client (OkHttp), mappers. |
| `storage` | SQLite cache and SharedPreferences stores; token encryption with the Android Keystore. |
| `monitor` | Foreground service, scheduled checks, watchdog, notifications. |
| `alarm` | Alarm playback, escalation, snooze. |
| `platform` | Android services: logging, connectivity, notification channels, readiness checks. |
| `ui-core` | Theme, shared views, formatters, colours. |
| `feature-*` | Screens: home, entry, onboarding, settings, statistics. |
| `widget` | Home screen widget. |
| `app` | Composition root, activity, navigation. |
| `build-logic` | Gradle convention plugin (SDK levels, Java 17, checks). |
| `tools/alarm-tone`, `tools/colors` | Generators of the alarm sound and the colour scheme. |

## Rules

- **Java 17 only, no Kotlin.**
- **No new dependency** without discussing it first in an issue. The app keeps a small, reviewed set of libraries.
- **Alarms must ring.** Changes to alarms, scheduled checks, the foreground service or sync windows need tests that
  cover the changed rule, and a test on a real phone.
- **No secrets in code, logs or tests.** Never log glucose values, the Nightscout address or the token.
- **Never use a real Nightscout with someone's data for tests.** Unit tests use fakes.
- All user-visible text is in string resources.

## Build types

| Build type | App id | Signing | Use |
|---|---|---|---|
| `debug`, `debugProxy` | `app.lightscout.debug` | debug key | development, device tests |
| `release` | `app.lightscout` | release key, in CI only | stable releases |
| `nightly` | `app.lightscout.nightly` | nightly key, in CI only | nightly pre-releases, a separate app "LightScout Nightly" |

Locally `release` and `nightly` build unsigned. CI signs them from environment variables
(`LIGHTSCOUT_RELEASE_*` / `LIGHTSCOUT_NIGHTLY_*`: `STORE_FILE`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`); keys are
never stored in the repository.

## CI and releases (GitHub Actions)

| Workflow | When | What |
|---|---|---|
| `CI` | every push and pull request, or by hand | `./gradlew build` |
| `Nightly` | 02:00 UTC, or by hand | only if `master` has a commit without a `nightly-<sha>` tag: tests, signed nightly APK, pre-release; keeps the newest 5 |
| `Release` | by hand | tests and an unsigned APK; with *publish* also signs (environment `release`, needs approval), tags `vX.Y.Z` and publishes |

To release: raise `lightscout.versionName` in `gradle.properties`, add a `## [X.Y.Z]` section to `CHANGELOG.md`,
merge to `master`, run *Release* with *publish*, approve the `release` environment.

Repository settings the workflows need (maintainer):

- Environment **`release`** with a required reviewer and the secrets `RELEASE_KEYSTORE_BASE64`,
  `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.
- Repository secrets `NIGHTLY_KEYSTORE_BASE64`, `NIGHTLY_STORE_PASSWORD`, `NIGHTLY_KEY_ALIAS`, `NIGHTLY_KEY_PASSWORD`
  (a separate key; losing it only means reinstalling the nightly app).
- Actions → General → Workflow permissions: read and write (to create tags and releases).

## Dependency updates

Dependabot opens weekly pull requests. Gradle dependency verification is on (`gradle/verification-metadata.xml`), so
a Gradle update fails CI until the metadata is refreshed. On the update branch:

```powershell
./gradlew.bat --write-verification-metadata sha256,pgp help
```

Review the diff of `gradle/verification-metadata.xml` (new versions only, expected publishers), commit it to the
branch, wait for *CI* on the pull request, and merge when it is green.
