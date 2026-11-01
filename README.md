# CodeClear

CodeClear is an Android app for preserving emergency-alert originals and showing
translations alongside them. It targets Android 11 (API 30) and later smartphones
and tablets, with on-device translation as the default.

## Project status

The app is under development. Reliable recovery of complete alert bodies is the
primary release gate. Android version compatibility, a successful build, or a
working translation engine does not establish emergency-alert capture support.
Capture must be validated for each supported alert source and firmware profile.

Home, searchable history, original/translation details, model management, speech
playback, and settings are implemented. The interface supports English, Korean,
Japanese, French, German, Simplified Chinese, and Traditional Chinese.

## Capture and translation boundaries

- Capture uses reviewed system alert sources through notification access and a
  scoped accessibility service. The user grants these accesses in Android
  Settings. The app does not acknowledge or dismiss system warnings for the user.
- Wi-Fi-only hardware cannot receive cellular emergency broadcasts or use their
  automatic capture path. A cellular device using Wi-Fi, lacking a SIM, or having
  mobile data disabled is not necessarily Wi-Fi-only hardware.
- ML Kit is the default translation engine. Model preparation requires an explicit
  download action; background translation does not silently download models.
- Android system translation is available only when the installed provider exposes
  a supported language pair. Service presence alone does not establish support.
- Labs contains experimental nearby sharing and MyMemory translation. MyMemory
  requires a separate, default-off remote permission and disclosure of provider
  retention and quota limits. There is no silent remote fallback.
- Alert history stays in app-private storage until deletion. App backup and device
  transfer are disabled. Remote permission covers automatic captured-alert
  translation and requested historical/peer-original translations. Nearby sharing
  has its own disclosure and permission for peer exchange.
- Other server providers, user-supplied endpoints and keys, shared server caches,
  and backend deployment are deferred.

## Build and check

Use Android Studio with a JDK 25 installation and Android SDK platform 37. The
checked-in Gradle wrapper and daemon criteria define the build toolchain. Configure
the SDK through Android Studio or an untracked `local.properties` file; do not
commit machine-specific SDK paths or signing keys.

From PowerShell at the repository root, with Java available to the wrapper:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug :app:lintRelease
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebugAndroidTest
```

On other platforms, use `./gradlew` with the same task arguments.

The debug version includes diagnostic capture tooling and has a `-capture-probe`
version suffix. Synthetic ordinary notifications, injected system receiver alerts,
and physical radio reception are distinct validation paths. Passing a fixture test
does not prove physical reception or recovery of every stacked alert.

Instrumented tests require a deliberately prepared emulator or authorized device.
Some test classes change app state, use Bluetooth, or contact the remote provider.
Review the selected fixtures and use isolated synthetic data before running device
tests. Building the test APK does not execute those tests.

## Source map

All handwritten sources are Kotlin and live under each source set's `kotlin/`
directory in the single `app` module. Main packages under
`app/src/main/kotlin/xyz/gaon/codeclear/` are:

| Package | Responsibility |
| --- | --- |
| `mobile` | Product screens, settings, history, runtime ownership, and work coordination |
| `probe` | Alert-source observation, extraction, and diagnostics |
| `translation` | Shared translation contracts, on-device engines, MyMemory transport, and future server response contracts |
| `nearby` | Experimental Bluetooth relay protocol, radio work, and peer inbox |
| `reception` | Radio/network country context and country-code mappings |
| `ui/theme` | Shared theme definitions |

`src/debug/kotlin` contains diagnostic entry points. `src/testDebug/kotlin`
contains JVM tests, and `src/androidTest/kotlin` contains instrumented tests.

Within `mobile`, `MobileController` owns product state and consent decisions.
`AlertCaptureIngestor` applies current source/access decisions and deduplicates
observations before storage. `AlertHistoryReader` composes bounded history views;
the controller owns their cancellation and stale-response checks.
`AlertTranslationQueue` selects and completes translation jobs while preserving
their producing engine and work generation. `HistoryWorker` serializes storage
operations, bounds pending work, and releases storage after active work completes.
`MobileScreen` owns navigation and scroll state; tutorial steps, home/history
feeds, and the detail dialog render through focused components. Alert formatting,
metadata, message panels, and shared settings controls have separate owners.

## Contributing

Follow [AGENTS.md](AGENTS.md). Keep changes focused: edit, format and lint, run the
affected tests, inspect the staged diff, then commit. Kotlin formatting uses
standalone ktlint 1.8.0 and the checked-in [.editorconfig](.editorconfig); Android
lint is a separate required check. Do not treat template tests as feature evidence.

Keep real alert bodies, private device captures, credentials, generated output,
and local configuration out of commits. Internal development records stay in the
ignored `.dev/` directory; the public build instructions must remain usable
without those records.
